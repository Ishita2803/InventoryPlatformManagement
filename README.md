# Order & Inventory Platform

An event-driven microservice platform built around a single hard problem: **placing an order
has to update two databases owned by two services, and there is no distributed transaction
available.**

The interesting parts are the failure paths — what happens when the payment provider is down,
when a Kafka message is delivered twice, when two orders race for the last unit of stock, and
when a service crashes between writing to its database and publishing an event. Those cases
are implemented and tested, not hand-waved.

Java 21 · Spring Boot 4.1 · Spring Cloud 2025.1 · Kafka (KRaft) · MySQL 8 · Docker Compose

---

## Contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [The happy path](#the-happy-path)
- [The failure paths](#the-failure-paths)
- [The five problems worth talking about](#the-five-problems-worth-talking-about)
- [Running it](#running-it)
- [API](#api)
- [Testing](#testing)
- [Performance](#performance)
- [Design decisions](#design-decisions)
- [Live demo](#live-demo)
- [Deployed to GCP](#deployed-to-gcp)
- [What is not built](#what-is-not-built)

---

## What it does

A customer places an order. The platform then, asynchronously:

1. accepts and persists the order as `PENDING`, returning immediately;
2. reserves the stock in a different service with a different database;
3. takes payment;
4. confirms the order and converts the reservation into a permanent deduction — or, if any
   step fails, **compensates** by releasing the stock and cancelling the order.

No step blocks the customer's HTTP request beyond the first, and no step can leave stock
reserved for an order that was never confirmed.

## Architecture

```
                          ┌──────────────────┐
      HTTP :8080 ───────► │   api-gateway    │  correlation-ID filter, 503 fallback
                          └────────┬─────────┘
                                   │ lb://  (Eureka)
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
      ┌────────────────────┐              ┌────────────────────┐
      │   order-service    │              │ inventory-service  │
      │       :8081        │              │       :8082        │
      │                    │              │                    │
      │  order             │              │  product           │
      │  order_item        │              │  inventory (@Version)
      │  outbox_event      │              │  reservation       │
      │  processed_event   │              │  processed_event   │
      └────────┬───────────┘              └─────────┬──────────┘
               │  order_db                          │  inventory_db
               │  (MySQL :3316)                     │  (MySQL :3306)
               │                                    │
               │        ┌──────────────────┐        │
               └───────►│      Kafka       │◄───────┘
                        │     (KRaft)      │
                        └────────┬─────────┘
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │  notification-service  │  :8083  (consumer only, no database)
                    └────────────────────────┘

               order-service ──HTTP──► payment-service :8084
                                       (Resilience4j: retry + circuit breaker)

      Supporting: discovery-service :8761 (Eureka) · config-service :8888 (Config Server,
      git-backed, reads a separate public config repository)
```

**Topics:** `order.placed`, `inventory.reserved`, `inventory.failed`, `order.confirmed`,
`order.cancelled` — plus a `.DLT` dead-letter topic per consumer group.

## The happy path

```
CLIENT          ORDER-SERVICE            KAFKA          INVENTORY-SERVICE      PAYMENT
  │                   │                    │                    │                │
  ├─ POST /api/orders►│                    │                    │                │
  │                   │                                         │                │
  │              ┌────┴──── ONE TRANSACTION ────┐                │                │
  │              │  INSERT order (PENDING)      │                │                │
  │              │  INSERT order_item × n       │                │                │
  │              │  INSERT outbox_event         │                │                │
  │              └────┬─────────────────────────┘                │                │
  │◄─ 201 PENDING ────┤                    │                    │                │
  │                   │                    │                    │                │
  │      OutboxPublisher polls ───────────►│                    │                │
  │                   │   order.placed     ├───────────────────►│                │
  │                   │                    │              ┌─────┴──── ONE TXN ───┐│
  │                   │                    │              │ processed_event      ││
  │                   │                    │              │ reservation          ││
  │                   │                    │              │ inventory: avail−n,  ││
  │                   │                    │              │   reserved+n  @Version│
  │                   │                    │              └─────┬────────────────┘│
  │                   │                    │◄─ inventory.reserved                 │
  │                   │◄───────────────────┤                    │                │
  │                   │                                         │                │
  │                   ├──── POST /api/payments ─────────────────────────────────►│
  │                   │◄─── APPROVED ───────────────────────────────────────────┤│
  │                   │                                         │                │
  │              ┌────┴──── ONE TRANSACTION ────┐                │                │
  │              │  processed_event             │                │                │
  │              │  order → CONFIRMED           │                │                │
  │              │  INSERT outbox_event         │                │                │
  │              └────┬─────────────────────────┘                │                │
  │                   ├─── order.confirmed ───►├───────────────►│  reservation →  │
  │                   │                        │                │  CONFIRMED,     │
  │                   │                        │                │  reserved−n     │
  │                   │                        └──► notification-service
```

The customer's request ends at `201 PENDING`. Everything after that is asynchronous, and the
client polls `GET /api/orders/{orderId}` or waits for a notification.

## The failure paths

**Out of stock.** Inventory publishes `inventory.failed` instead of `inventory.reserved`; the
order moves `PENDING → INVENTORY_FAILED`. Nothing to compensate, because nothing was reserved.

**Payment declines, or the payment service is down.**

```
  order-service                                     inventory-service
       │
       │  POST /api/payments ──► DECLINED
       │  (or: connection refused, retried 3×, circuit opens, fallback returns unavailable)
       │
  ┌────┴──── ONE TRANSACTION ────┐
  │  order → CANCELLED           │
  │  INSERT outbox_event         │  ── order.cancelled ──►  reservation → RELEASED
  └──────────────────────────────┘                          inventory: avail+n, reserved−n
```

The compensation is the point. There is no rollback across services, so the stock is given
back by a *new* forward action that undoes the previous one.

**A duplicate message arrives.** Every consumer inserts the event's ID into its own
`processed_event` table inside the same transaction as its business write. The table has a
unique constraint, so the second delivery of the same event fails the insert and the whole
transaction rolls back — the business change happens exactly once even though delivery is
at-least-once.

**The service crashes after committing but before publishing.** It cannot: the event is
written to `outbox_event` in the *same* transaction as the business change. Either both are
committed or neither is. A separate poller publishes committed rows and marks them sent, so a
crash between commit and publish just means the row is published after restart.

**Work that silently never finished.** Idempotency guarantees work is not done *twice*, and
says nothing about work that was never *completed*. If a consumer's `processed_event` row
commits and the settlement then fails, redelivery correctly skips the event and the order
stalls for ever holding stock. Separately, anything that exhausts its retries lands in a
dead-letter topic that nothing reads. Two scheduled jobs close both: one sweeps orders stalled
past a threshold and re-drives settlement, the other replays dead-lettered settlements. Both
are safe to run repeatedly — see
[ADR-0008](docs/decisions/0008-reconcile-state-not-messages.md).

**Two orders race for the last unit.** The `inventory` row carries a `@Version` column.
The loser's `UPDATE ... WHERE version = ?` matches no rows, Hibernate throws, and the
operation is retried — outside the transaction, with bounded jittered backoff — against
freshly read state. One order gets the unit; the other is told there is no stock. Neither
oversells.

## The five problems worth talking about

| Problem | Solution | Where |
|---|---|---|
| Write to the DB *and* publish an event atomically | Transactional outbox + poller | `order-service/outbox/` |
| Kafka delivers at least once | `processed_event` unique constraint, in-transaction | both services |
| No distributed transaction across services | Choreographed saga with explicit compensation | `order.cancelled` handler |
| Concurrent stock updates | `@Version` optimistic lock + retry outside the transaction | `InventoryService` / `InventoryTxService` |
| A downstream dependency is down | Resilience4j retry → circuit breaker → fallback | `PaymentClient` |
| Work that silently never finished | State-based reconciliation + dead-letter replay | `OrderReconciliationService`, `SettlementRecoveryService` |

## Running it

**Prerequisites:** Docker Desktop, and about 6 GB of free RAM. Nothing else — no local JDK,
Maven, MySQL or Kafka is required.

```bash
git clone --recurse-submodules https://github.com/Ishita2803/InventoryPlatformManagement.git
cd InventoryPlatformManagement
cp .env.example .env          # defaults work as-is for local use
docker compose up -d
```

The first start builds seven images and initialises two databases; allow several minutes.
Wait until all ten containers report healthy:

```bash
docker compose ps
```

Then place an order:

```bash
# Create a product and give it stock
curl -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-1","name":"Widget"}'

curl -X POST http://localhost:8080/api/inventory \
  -H 'Content-Type: application/json' \
  -d '{"productId":1,"warehouseId":"WH-1","quantity":100}'

# Place the order — returns immediately with PENDING
curl -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","items":[
        {"productId":1,"warehouseId":"WH-1","quantity":2,"unitPrice":9.99}]}'

# Poll until it reaches CONFIRMED
curl http://localhost:8080/api/orders/{orderId}
```

### Watching a failure

`payment-service` has a runtime switch, which is how the compensation path is demonstrated
without editing code or killing containers:

```bash
curl -X POST 'http://localhost:8084/api/payments/behaviour?mode=DECLINE'
# place an order -> it will reach CANCELLED and the stock will be returned
curl -X POST 'http://localhost:8084/api/payments/behaviour?mode=APPROVE'
```

To see the circuit breaker instead, stop the service entirely:

```bash
docker compose stop payment-service
```

### Ports

| Service | Port | Purpose |
|---|---|---|
| api-gateway-service | 8080 | the only entry point clients use |
| order-service | 8081 | orders, outbox, saga orchestration |
| inventory-service | 8082 | products, stock, reservations |
| notification-service | 8083 | consumes terminal events; no database |
| payment-service | 8084 | mocked provider with a controllable behaviour switch |
| discovery-service | 8761 | Eureka |
| config-service | 8888 | Config Server, git-backed |
| order-mysql | 3316 | `order_db` |
| inventory-mysql | 3306 | `inventory_db` |

`order-mysql` is on 3316 because a Windows MySQL service commonly owns 3306.

## API

The full specification is [`docs/openapi.yaml`](docs/openapi.yaml) — hand-written, because
springdoc has no Spring Boot 4 release yet.

Everything below goes through the gateway on `:8080`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/orders` | Place an order. Returns `201` with status `PENDING`. |
| `GET` | `/api/orders/{orderId}` | Current status of one order. |
| `GET` | `/api/orders?page=&size=` | List orders, newest first. |
| `POST` | `/api/products` | Register a product. |
| `POST` | `/api/inventory` | Add stock for a product in a warehouse. |
| `GET` | `/api/inventory?productId=&warehouseId=` | Current available and reserved quantities. |
| `POST` | `/api/inventory/reserve` | Reserve stock directly (used by tests and tooling). |
| `POST` | `/api/inventory/release` | Release an order's reservations. |
| `POST` | `/api/inventory/confirm` | Convert an order's reservations into a deduction. |

`payment-service` is deliberately **not** routed through the gateway — it is an internal
dependency, reachable on `:8084` only for demonstrating failure modes.

Order status transitions are enforced in the domain model, not just checked at call sites:

```
PENDING ──► INVENTORY_RESERVED ──► CONFIRMED
   │                  │
   │                  └──────────► CANCELLED
   ├──► INVENTORY_FAILED
   └──► CANCELLED
```

`CONFIRMED`, `INVENTORY_FAILED` and `CANCELLED` are terminal.

## Testing

**120 tests** across the five application modules — 62 order, 38 inventory, 6 notification,
9 gateway, 5 payment. **51 of them are integration tests** against an embedded Kafka broker, a
real MySQL 8 container, or a real HTTP server.

CI reports **122**, because `config-service` and `discovery-service` each contribute a
context-load smoke test on top.

```bash
cd order-service && ./mvnw verify     # note: verify, not test
```

`verify`, not `test`, because `test` runs only Surefire and would silently skip every `*IT`
class — the build would go green while proving considerably less than it appears to.

What the integration tests actually pin down:

- an event published twice changes the database once;
- two concurrent reservations for the last unit produce exactly one winner;
- a payment outage produces a cancelled order **and** returned stock;
- `outbox_event.payload` is a real text column on MySQL, and a large order round-trips
  intact — a regression test for a bug that survived three phases because H2 could not
  reproduce MySQL's type mapping;
- the reservation unique constraint that idempotency depends on exists in the schema MySQL
  actually generates.

CI runs a seven-way matrix on every push, then builds all container images.

## Performance

Measured, not estimated. Full method, raw output and analysis in
[`docs/benchmark/RESULTS.md`](docs/benchmark/RESULTS.md).

On a single laptop running all ten containers plus the load generator:

| | |
|---|---|
| Accept throughput | ~110 orders/sec |
| `POST /api/orders` p50 | 189 ms |
| End-to-end (POST → `CONFIRMED`) p50 | 6.2 s |
| 1000 concurrent orders | 1000/1000 confirmed, zero oversell, stock reconciled exactly |

The benchmark's most useful finding was a negative one. The obvious suspect for slow
throughput — contention on a single stock row — was **tested and refuted**: spreading the same
load across 20 SKUs changed nothing. The real limit was `fsync` latency on Docker Desktop's
virtual disk, at 190 ms per commit, with roughly five commits per order. Relaxing one MySQL
durability setting, and changing nothing else, made the system **14.7× faster**.

## Design decisions

Recorded as ADRs in [`docs/decisions/`](docs/decisions/), each with the alternatives that were
rejected and why:

| | |
|---|---|
| [ADR-0001](docs/decisions/0001-transactional-outbox.md) | Transactional outbox instead of publishing inside the transaction |
| [ADR-0002](docs/decisions/0002-choreography-over-orchestration.md) | Choreographed saga rather than a central orchestrator |
| [ADR-0003](docs/decisions/0003-optimistic-locking.md) | Optimistic locking rather than `SELECT ... FOR UPDATE` |
| [ADR-0004](docs/decisions/0004-idempotency-via-processed-event.md) | Consumer-side idempotency table |
| [ADR-0005](docs/decisions/0005-per-service-event-classes.md) | Duplicated event classes instead of a shared library |
| [ADR-0006](docs/decisions/0006-mock-payment-service.md) | A real HTTP payment service, mocked internally |
| [ADR-0007](docs/decisions/0007-gateway-mvc-over-webflux.md) | Spring Cloud Gateway MVC rather than WebFlux |
| [ADR-0008](docs/decisions/0008-reconcile-state-not-messages.md) | Reconcile persisted state, because replaying messages cannot fix everything |

Longer narrative context lives in [`Agent.md`](Agent.md) (full project state, including a
numbered list of every trap hit along the way) and [`plan.md`](plan.md) (the phased plan).
[`docs/INTERVIEW-GUIDE.md`](docs/INTERVIEW-GUIDE.md) explains the whole system end to end.

## Live demo

`/demo.html`, served by `api-gateway-service` itself — no build step, no framework, calls
the real API on the same origin. Create a product, add stock, place an order, and watch it
resolve to a real terminal state, or deliberately over-order to trigger `INVENTORY_FAILED`
live. Also has a static architecture panel for narrating the six services and both failure
paths without a terminal open.

## Deployed to GCP

The whole platform also runs on GKE — a zonal cluster with Spot `e2-medium` nodes, MySQL and
Kafka on a single Compute Engine VM instead of Cloud SQL, secrets read from Secret Manager,
and CI/CD to `main` authenticated with Workload Identity Federation (no service-account key
file exists anywhere). Only the gateway is public.

**It is torn down between demos, not left running.** `deploy/gcp/down.sh` scales the GKE
node pool to zero and stops (never deletes) the data VM, which leaves nothing billable but
disks and the reserved static IP. `deploy/gcp/up.sh` reverses it — starts the VM, resizes the
pool back up, waits for MySQL/Kafka to accept connections, then waits for all six
`Deployment`s to report `Ready` — and restores a working public URL from cold in a few
minutes, both scripts run for real, not just written and assumed to work.

| | Always on | Torn down between demos |
|---|---|---|
| Approx. cost | ~$45/month | ~$3/month (disks + reserved IP only) |

```bash
./deploy/gcp/down.sh   # end of a session
./deploy/gcp/up.sh     # before a demo/interview
```

Full cost breakdown and the GCP-specific traps (Workload Identity, the GKE-managed CSI
driver's RBAC gap, `externalTrafficPolicy`, node CPU headroom) are in `plan.md` Part B and
`Agent.md` §8; a narrative walkthrough of each phase is in `learn/13` through `learn/19`.

## What is not built

Listed deliberately, because a portfolio project that claims everything is worth nothing.

- **Confirm and release still use optimistic locking where an atomic `UPDATE` would do.**
  They are pure relative adjustments (`reserved -= n`), so a single conditional UPDATE would
  have no contention failure mode at all. Today they read-modify-write with a bounded retry,
  and under heavy single-row contention that budget can be exhausted — which is exactly what
  dead-lettered 9 confirmations during benchmarking. Reconciliation recovers them, but not
  having to is better.
- **No authentication or authorisation.** Every endpoint is open.
- **TLS on the public gateway.** Skipped by deliberate choice for a portfolio demo reached by
  a raw IP, not an oversight — see `plan.md` Phase 16.
- **GitOps.** `deploy/k8s/*.yaml` is not the sole source of truth for what's running — CI does
  `kubectl set image` directly against the cluster. Argo CD/Flux reconciling the manifest is
  the more complete answer, deliberately deferred to keep one moving part instead of three.
- **One partition per topic**, so consumers cannot currently scale out. Partitioning by
  `orderId` would preserve per-order ordering while allowing parallelism.
- **No distributed tracing.** Correlation IDs are generated and propagated at the gateway,
  but nothing collects them.
- **Payments are mocked.** A real provider brings 3-D Secure, webhooks, settlement delays and
  refunds — none of which are modelled.
