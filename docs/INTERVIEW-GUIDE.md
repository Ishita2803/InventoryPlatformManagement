# Interview guide — Fault-Tolerant Order Fulfillment Platform

## Part D — Impulse: a modernized supply-chain domain on top of the platform below

Everything in §1-§9 below describes the Phase 1-11 order/inventory demo, and it is all
still fully built, tested, and true — it's the foundation the domain below is built on,
not something superseded by it.

**The pitch.** "Impulse" models a mainframe-style supply-chain system, modernized onto
this platform: vendors list products, admin prices them for sale, warehouses hold stock
by region, customers place orders that resolve fulfillment against real stock, shortfalls
auto-backorder from the vendor, carriers price shipping by weight, and every sale is
invoiced and reported on. The differentiator over the Phase 1-11 demo isn't more
CRUD — it's that every one of those actors (vendor, customer, carrier, admin) is a real
login with role-enforced routes, and every flow reuses the Saga/outbox/idempotency
machinery already proven below rather than reinventing it per domain.

**The two order flows, and why there are two.** A **sales order** (Phase D7) resolves
synchronously: search the customer's own region's warehouse first, then every other
warehouse in registration order, greedily reserving whatever's available, never
rejecting — a fully-stocked order ships in full, a partially-stocked one ships what it
can and auto-backorders the rest through the exact same purchase-order mechanism admin
uses for manual stocking (Phase D6). That's a deliberate divergence from the Phase 1-11
Saga: "never reject, tell the customer what actually shipped" is a promise about *this
response*, so the fulfillment search has to happen before the response is built, not
later via Kafka. A **direct order** (Phase D9) is the opposite case: it buys straight
from the vendor and never touches inventory-service's reservation machinery at all —
proof that removing a dependency removes the whole category of failure that depending on
it required handling (no fulfillment search, no release-on-failure compensation, because
there's nothing on inventory-service's side to compensate for).

**Auth and tracing are real, not simulated.** `auth-service` issues JWTs over
bcrypt-hashed passwords; the gateway enforces per-route, per-role authorization from a
verified token, not a client-supplied header. Every service exports real distributed
traces to GCP Cloud Trace via an in-cluster OpenTelemetry Collector — a single sales
order shows up as one connected waterfall across the gateway, order-service,
inventory-service, and every Kafka hop between them, verified live in the Cloud Trace
console, not just log correlation.

**Two things worth naming as design decisions, not oversights, if asked "why not
microservices for everything":** purchase orders live in `order-service` (not a separate
service) because they share the exact outbox/idempotent-consumer shape sales orders
already use — same aggregate type, same actor category as far as the machinery cares.
Vendor, customer, and carrier are three separate services, not because their APIs are
big, but because each is a genuinely distinct actor with its own login and lifecycle —
merging them to save a pod would couple three unrelated bounded contexts.

**The storefront and admin console are real screens over real APIs, not a demo shell.**
`customer.html`'s cart persists in `localStorage` (scoped per logged-in customer, cleared
on checkout) so it survives a reload — unlike the JWT, which still lives in
`sessionStorage` and is deliberately wiped every page load. "My Orders" calls a new,
gateway-gated `GET /api/orders/mine` scoped server-side by the caller's own business id
(not a client-supplied filter), and a billing screen renders a real invoice fetched from
a new `GET /api/payments/invoices/{orderId}` — the first time a generated invoice is
ever read back rather than only emailed. Admin gained real user management
(`GET/PUT /auth/users`, `POST /auth/users/{username}/password`) — edit a user's
role/business id, disable a login, or set a new password directly, all against
`auth-service`, not a static directory. See plan.md's phase for this work for exactly
what's still a stated simplification (no self-service password reset, invoice ownership
not cross-checked against the caller's JWT).

See `plan.md`'s Phase D1–D11 entries and `learn/22`–`learn/32` for the full build story,
including every real bug found and fixed along the way (a data-sync gap between two
`Product` tables, a schema-migration limitation, a reconciliation sweep that would have
mis-cancelled a healthy order). §8 below reflects Impulse's auth/tracing capabilities
where relevant; its own detailed data model and failure-mode deep-dive follow the same
"never claim more than is built" discipline as the rest of this file.

> **What this file is for.** Everything you need to explain this project confidently, from a
> 30-second pitch to a whiteboard deep-dive, plus the awkward questions and honest answers.
>
> **Read the rules first:**
> 1. **Never claim anything in the "Not built yet" section.** Interviewers probe everything.
>    One "I said Saga but haven't actually done compensation" ends the credibility of the
>    whole conversation.
> 2. **Volunteering a weakness is a strength.** Saying "two order-service instances could
>    both drain the same outbox row; it's harmless because consumers are idempotent, and
>    `SKIP LOCKED` is the clean fix" reads as senior. Being caught not knowing reads as
>    junior.
> 3. Everything below is **true as of Phases 0–11.5**. Status is tracked in
>    [`plan.md`](../plan.md); implementation detail in [`Agent.md`](../Agent.md).

**Last updated:** 2026-08-26, after Phase 11.5. (Part D's own section above is kept in
step separately — most recently 2026-09-04, after the user-management/storefront-
completeness phase.)

---

## 1. The pitch

### 30 seconds

> "It's an event-driven order fulfillment platform — Spring Boot microservices talking over
> Kafka. You place an order, it's accepted immediately as PENDING, and inventory reserves
> stock asynchronously and reports back. The interesting part isn't the CRUD; it's the
> failure handling — making the consumer idempotent so a redelivered event can't
> double-reserve stock, optimistic locking so concurrent orders can't oversell, and a
> dead-letter topic so one bad message can't stall the queue."

### 2 minutes

Add the *why*:

> "The naive version is order-service calling inventory-service over REST. That couples them:
> if inventory is down, you can't take orders at all, and you've made your revenue path
> depend on your warehouse system's uptime. So orders are accepted and persisted first, then
> an `OrderPlaced` event goes on Kafka. Inventory consumes it, reserves, and publishes
> `InventoryReserved` or `InventoryFailed`. Order-service consumes that and moves the order on.
>
> Getting the event out is its own problem: the order goes to MySQL and the event goes to
> Kafka, two systems with no shared transaction, so a crash between them loses the event.
> That's the dual-write problem, and I solved it with a transactional outbox — the event is
> written to an outbox table in the same commit as the order, and a poller drains it to Kafka
> afterwards.
>
> That buys decoupling but costs you exactly-once. Kafka is at-least-once, so the same event
> *will* arrive twice. I handle that two ways: a `processed_event` table keyed by event ID,
> written in the same transaction as the work, and a unique constraint on
> (orderId, productId, warehouseId) so even a regenerated event ID can't double-reserve.
>
> Concurrency is separate: two different orders hitting the same stock row. That's an
> `@Version` column with bounded retry — and I proved it works by deleting the annotation and
> watching the test fail with a classic lost update."

### The whiteboard version

Draw this. It's the whole system in six boxes:

```
   Client
     │  POST /api/orders
     ▼
┌──────────────┐  1. save order + outbox row  ┌──────────┐
│    order     │─────ONE TRANSACTION─────────►│ order_db │
│   service    │                              └──────────┘
└──────┬───────┘
       │ 2. OutboxPublisher drains it (key = orderId)
       ▼
  ┌─────────────────── Kafka ───────────────────┐
  │  order.placed                               │
  │  inventory.reserved                         │
  │  inventory.failed                           │
  │  *.DLT   ← poison messages land here        │
  └────┬────────────────────────────▲───────────┘
       │ 3. consume                 │ 4. publish result
       ▼                            │
┌──────────────┐   reserve stock  ┌─┴────────────┐
│  inventory   │─────────────────►│ inventory_db │
│   service    │                  └──────────────┘
└──────────────┘
       │
       │ 5. order-service consumes the result,
       ▼   moves order PENDING → INVENTORY_RESERVED / INVENTORY_FAILED
```

Notification-service also subscribes to those two result topics, under its **own consumer
group**, so it receives every event too and emails the customer.

Everything enters through the **gateway on :8080** — it routes by path, stamps a correlation
id on every request, and turns a dead downstream into a 503 rather than a stack trace.

Then say: *"Config Server and Eureka sit alongside for configuration and discovery. Payment
is planned but not built yet."* — honest, and it pre-empts the
question.

---

## 2. Every component, and why it exists

| Component | Port | Status | What it does | Why it exists |
|---|---|---|---|---|
| `order-service` | 8081 | **Built** | Owns the order lifecycle and the outbox, `order_db` | The write side. Accepts orders without depending on inventory — or even Kafka — being up. |
| `inventory-service` | 8082 | **Built** | Owns stock and reservations, `inventory_db` | The constrained resource. Everything hard in this project lives here. |
| `config-service` | 8888 | **Built** | Spring Cloud Config Server | Configuration versioned in git with an audit trail, not baked into images. |
| `discovery-service` | 8761 | **Built** | Eureka server | Service discovery locally. **Dropped on GKE** — Kubernetes DNS already does this. |
| Kafka | 9092 | **Running** | 3 topics + DLTs, KRaft mode | The decoupling. No ZooKeeper — KRaft is the modern setup. |
| MySQL | 3306 | **Running** | `order_db`, `inventory_db` | A database per service. Currently one instance, two schemas — see honesty section. |
| `api-gateway-service` | 8080 | **Built** | Single entry point; routes, correlation ids, error translation | One public surface instead of five. Routes live in **config**, switchable to Kubernetes DNS by profile. |
| `notification-service` | 8083 | **Built** | Consumes both result topics, sends a mock email | Proves the events are genuinely reusable: adding a whole new consumer required changing neither producer. Has **no database at all**. |
| `payment-service` | 8084 | **Built** | Mocked provider, idempotent by orderId | The only **synchronous** call in the platform — which is the only reason a circuit breaker is not decoration here. |

### Why each *technology* is there

Be ready for "why did you use X?" on every one. The wrong answer is "it's popular."

- **Kafka** — decouples order acceptance from stock availability, and gives durable replay.
  A queue you can rewind is different from a queue you can only drain.
- **MySQL per service** — each service owns its data. No shared database, so no cross-service
  join sneaking in and welding the two together.
- **Spring Cloud Config** — config has its own lifecycle and audit trail. It's read from a
  *remote* git repo, so a pod in Kubernetes gets the same config as a laptop.
- **Eureka** — client-side discovery locally. Honest answer for GKE: *"it's redundant there,
  so I drop it. Kubernetes Services already do discovery. Keeping it would be cargo-culting."*
- **Optimistic locking (`@Version`)** — reads massively outnumber conflicts on a stock row,
  so pessimistic `SELECT ... FOR UPDATE` would serialise everything for a rare collision.
- **Lombok** — boilerplate only. No behaviour.

---

## 3. The two flows

### Happy path

```
Client        order-service        Kafka         inventory-service
  │                 │                │                  │
  ├─POST /orders───►│                │                  │
  │                 ├─save order              │         │
  │                 │  + outbox row  │                  │
  │                 │  (ONE COMMIT)  │                  │
  │◄──201 PENDING───┤                │                  │
  │                 │  ...poller...  │                  │
  │                 ├─OrderPlaced───►│                  │
  │                 │                ├─────────────────►│
  │                 │                │                  ├─ processed_event insert
  │                 │                │                  ├─ check ALL lines
  │                 │                │                  ├─ apply ALL lines
  │                 │                │                  │  (ONE COMMIT)
  │                 │                │◄─InventoryReserved┤
  │                 │◄───────────────┤                  │
  │                 ├─ processed_event + status change   │
  │                 │  (ONE COMMIT)                      │
  │                 │  → INVENTORY_RESERVED              │
```

**Say this:** *"Three things. The response returns 201 PENDING — the client isn't waiting for
inventory. The order and its outbox row commit together, so the request path never touches
Kafka. And both consumers write their idempotency record in the same transaction as their
work."*

### Failure path — out of stock

Identical until inventory checks. Then:

```
inventory-service:
  ├─ processed_event insert
  ├─ check ALL lines  → line 2 is short
  ├─ return FAILED    ← nothing was applied, so nothing to undo
  │  (COMMIT: only the processed_event row)
  └─ publish InventoryFailed
       │
order-service → order becomes INVENTORY_FAILED
```

**The key line to say:** *"Because I validate every line before applying any, a partial order
reserves nothing at all. There's no compensation needed for the in-flight case. Earlier I
reserved line-by-line and released on failure, which left a window where stock was held for
an order already doomed to fail."*

### Failure path — poison message

```
bad JSON → inventory-service listener
    → conversion fails
    → marked NOT retryable (it won't parse the second time either)
    → straight to order.placed.DLT with failure headers
    → the next message is processed normally  ← the point
```

---

## 4. The five hard problems

This is the heart of the interview. For each: **the problem → what breaks → what I did.**

### 4.1 Idempotent consumers

**Problem.** Kafka is at-least-once. A consumer that commits its offset after processing can
crash in between, and the same event is redelivered. Reserve twice, and stock silently
vanishes.

**What I did.** Two independent defences:

1. **`processed_event` table**, `eventId` as primary key, **written in the same transaction as
   the work**. This ordering is everything:
   - written *before* the work → crash loses the work, redelivery skips it. **Lost update.**
   - written *after* the work → crash repeats the work. **Duplicate.**
   - written *together* → both or neither. **Exactly-once effect.**
2. **Unique constraint on `(orderId, productId, warehouseId)`** in the reservation table.

**Why both?** *"The event table handles a redelivered event. The constraint handles the case
where the publisher regenerates the event ID — a retry from an outbox, say — where the event
ID is different but the business intent is identical. And it's enforced by the database, so
two concurrent consumers can't both pass an application-level 'have I seen this?' check."*

> **Killer detail to mention:** exactly-*once delivery* is impossible; exactly-once **effect**
> is what you actually engineer. Saying this distinguishes you immediately.

### 4.2 Overselling under concurrency

**Problem.** Two orders for the last 5 units arrive simultaneously. Both read `available=5`,
both write `available=0`. You've sold 10 units of 5 stock.

**What I did.** `@Version` on the `Inventory` entity. Hibernate emits
`UPDATE ... WHERE id=? AND version=?`; the loser updates 0 rows and gets
`ObjectOptimisticLockingFailureException`. A **bounded** retry (4 attempts, exponential
backoff **with jitter**) re-reads and tries again. Exhausted retries → HTTP 409, not 500 —
contention is a normal outcome, not a server fault.

**Why bounded?** *"Unbounded retry under sustained contention is a livelock. It presents as a
hung request and takes the thread pool with it."*

**Why jitter?** *"Without it, contending threads back off by identical amounts and collide
again in lockstep."*

> **The detail that wins this question:** *"I verified the test can actually fail. I removed
> `@Version` and re-ran it — all 10 threads reported success, implying 20 units reserved,
> while only 2 were actually deducted. Classic lost update. A concurrency test you've never
> seen go red isn't evidence of anything; plenty pass because the threads quietly serialised."*

**Why not pessimistic locking?** *"`SELECT FOR UPDATE` serialises every reservation on that
row even though collisions are rare. Optimistic locking pays only when there's an actual
conflict. If contention were high — flash-sale on one SKU — pessimistic would win, and I'd
switch."*

### 4.3 The dual-write problem, and the transactional outbox

**Problem.** `createOrder` has to do two things: commit the order to MySQL, and publish to
Kafka. They are separate systems, so they cannot share a transaction. Whichever order you
pick is wrong:

- **commit then publish** → a crash in between leaves an order stuck PENDING that no consumer
  ever hears about. It sits there forever.
- **publish then commit** → worse. Inventory reserves stock for an order that then rolled
  back and does not exist.

There is no ordering of two writes to two systems that is safe.

**What I did.** Stop writing to two systems. The event is written to an `outbox_event` table
**in the same transaction as the order** — one database, one commit, atomic by construction.
A scheduled `OutboxPublisher` drains pending rows to Kafka afterwards and marks them
published.

**The guarantee changes shape.** Not "maybe published" but "will be published, eventually, at
least once". At-least-once is acceptable here *precisely because* the consumers were made
idempotent in Phase 4 — the outbox and idempotent consumers are two halves of one design, not
two independent features. That connection is worth stating; it shows the pieces were chosen
together rather than collected.

**Details worth having ready:**

- The publisher **blocks on the broker's acknowledgement** before marking a row published.
  Fire-and-forget would mark rows published for sends that later failed — losing exactly what
  the outbox exists to protect.
- `fixedDelay`, not `fixedRate`: with fixedRate a slow drain overlaps itself and two threads
  publish the same rows.
- Rows drain **oldest-first, in capped batches**, so one backlog cannot stall a poll.
- After the attempt budget is spent a row goes to `FAILED` and is skipped — same reasoning as
  the DLT. Without a terminal state, one undeliverable row is retried on every poll forever
  and delays everything behind it.

**Known limitation, say it before they ask:** with more than one order-service instance, two
pollers could pick up the same row and publish it twice. Harmless — the consumers are
idempotent — but the clean fix is `SELECT ... FOR UPDATE SKIP LOCKED` so each poller claims a
disjoint batch.

> **This phase is a good "tell me about a bug you found" story.** Two real defects surfaced,
> both because the tests asserted on *content* rather than on delivery:
>
> 1. Outbox payloads are already-serialized JSON held as `String`, and the default
>    `JsonSerializer` re-encoded them into a quoted, escaped JSON string. Inventory could
>    never have parsed it. A test that only checked "a message arrived" would have passed.
> 2. Fixing that by adding a second `KafkaTemplate<String, String>` bean silently switched
>    off Boot's auto-configured one — the condition is
>    `@ConditionalOnMissingBean(KafkaTemplate.class)`, a **raw-type** check that ignores
>    generics. Everything wanting `KafkaTemplate<String, Object>` stopped resolving. Both
>    templates are now declared explicitly.
>
> A third, from first running the Docker Compose file: the Kafka named volume was mounted,
> looked right, and held **nothing**, because without `KAFKA_LOG_DIRS` the broker writes to
> `/tmp`. Persistence that silently isn't persistence, invisible until a container is
> recreated. Good answer to *"tell me about something that looked correct and wasn't."*

### 4.4 Poison messages and the DLT

**Problem.** One malformed payload at the head of a partition, retried forever, and every
good message behind it stops. This is how event-driven systems quietly die.

**What I did.** `DefaultErrorHandler` with 3 retries, exponential backoff with jitter, then
`DeadLetterPublishingRecoverer` → `<topic>.DLT`, carrying headers with the original topic and
the exception. **Conversion failures are registered as not-retryable** — a payload that won't
parse won't parse the second time, so retrying just delays everything behind it.

> **What I test that most people don't:** not only that the poison message reaches the DLT,
> but that **a valid message queued behind it on the same partition is still processed**. A
> DLT that works while the partition stalls anyway would pass the obvious test and still be
> broken.

### 4.5 Saga, compensation, and the circuit breaker

**Problem.** You cannot have an ACID transaction across order, inventory and payment.

**What I did.** A **choreography-based saga** — no orchestrator, each service reacts to
events. The full path:

```
OrderPlaced → reserve stock → InventoryReserved
                                    ↓
                          order-service calls PAYMENT (synchronous)
                                    ↓
        approved ────────────────┐  └──── declined / timeout / circuit open
             ↓                   │                    ↓
      order CONFIRMED            │             order CANCELLED
      → OrderConfirmed           │             → OrderCancelled
      → inventory CONFIRMS       │             → inventory RELEASES   ← compensation
        (stock ships; does NOT   │
         return to available)    │
```

**Why payment is synchronous when everything else is not.** This is the question to be ready
for. You cannot ship an order and find out later whether it was paid for — payment is the one
step that genuinely wants an answer now. And it is *because* there is a synchronous call that
a circuit breaker is worth having: in a pure-Kafka design the broker already absorbs a slow
consumer, so a breaker would be protecting nothing.

**Around that call:** an HTTP read timeout (2s), 3 attempts with exponential backoff, a
circuit breaker opening at 50% failures over at least 5 calls, and a fallback.

- **A read timeout, not Resilience4j's `TimeLimiter`** — TimeLimiter needs a
  `CompletableFuture` to cancel, and a blocking call has nothing to cancel.
- **A decline is not a failure.** It returns 200 with `DECLINED`, so it is not retried and
  does not count towards the breaker. Retrying the bank's "no" is pointless and would
  eventually trip the breaker on a service that is working perfectly.
- **The fallback fails closed** — cancel, do not confirm. Approving on failure ships goods
  for free; leaving it pending holds stock forever. Cancelling is the option that is safe to
  be wrong about.
- **Payment is idempotent by orderId**, which is what makes the retry safe. A retry in front
  of a non-idempotent charge is a double-charge waiting to happen.

**Compensation goes through the outbox, not a REST call.** order-service could have called
`POST /api/inventory/release` directly, but a cancellation during an inventory restart would
then be lost and the stock leaked forever. Published as `OrderCancelled`, it waits in the
topic.

**Choreography vs orchestration, if asked:**
> *"Choreography, because there are three participants and no complex branching. With five or
> six steps and conditional paths I'd switch to orchestration, because with choreography the
> business process becomes an emergent property of scattered listeners and nobody can see it
> in one place."*

> **The demo that lands.** Kill the payment service. Place orders. The first is cancelled
> after the timeout; the breaker opens on the second, so the third fails *instantly* without
> a network call at all; stock is released every time. Restart payment and watch
> `OPEN → HALF_OPEN → CLOSED` in `/actuator/circuitbreakers` as trial calls succeed.

---

## 5. The data model

**order-service** (`order_db`)

```
orders                          order_item
──────                          ──────────
id            BIGINT PK  ◄──┐   id             BIGINT PK
order_id      VARCHAR UQ    └───order_id_fk    BIGINT FK
customer_id   VARCHAR          product_id     BIGINT
status        VARCHAR          warehouse_id   VARCHAR
total_amount  DECIMAL(19,2)    quantity       INT
created_at / updated_at        unit_price     DECIMAL(19,2)

processed_event
───────────────
event_id      VARCHAR PK   ← the idempotency key
event_type    VARCHAR
processed_at  TIMESTAMP
```

**inventory-service** (`inventory_db`)

```
product              inventory                      reservation
───────              ─────────                      ───────────
id      BIGINT PK    id                 BIGINT PK   id            BIGINT PK
sku     VARCHAR UQ   product_id         BIGINT      order_id      VARCHAR  ┐
name    VARCHAR      warehouse_id       VARCHAR     product_id    BIGINT   ├ UNIQUE
                     available_quantity INT         warehouse_id  VARCHAR  ┘
                     reserved_quantity  INT         quantity      INT
                     version            BIGINT ←──  status        VARCHAR
                                        @Version    created_at / updated_at
processed_event  (same shape as above)
```

**Three modelling decisions to be able to defend:**

1. **`orderId` is a UUID string, not the surrogate `id`.** It's a cross-service identifier
   travelling in Kafka events. Keying inventory's reservations on order-service's
   auto-increment number would couple inventory to another service's sequence and break the
   moment order data is migrated or resharded. The surrogate `id` never leaves the service —
   it isn't even in the API response.
2. **`warehouseId` lives on `order_item`.** Inventory keys reservations on
   (orderId, productId, warehouseId), so without it the event couldn't reserve anything.
3. **Money is `BigDecimal(19,2)`, never `double`.** Floating point accumulates rounding error;
   an order total off by a cent surfaces in production reconciliation, not in tests.
4. **The table is `orders`, not `order`.** `ORDER` is a reserved SQL word — Hibernate's
   derived name produces `create table order (...)`, which MySQL rejects with a syntax error
   pointing at the wrong token.

---

## 6. Numbers and facts worth memorising

| Fact | Value |
|---|---|
| Tests | **117** across the five application modules — 62 order, 38 inventory, 6 notification, 6 gateway, 5 payment (119 in CI, +2 infrastructure smoke tests) |
| Test split | 58 unit/slice, **43 integration** (embedded Kafka, real MySQL via Testcontainers, stub HTTP servers) |
| Optimistic-lock retry | 4 attempts, exponential backoff with jitter |
| Kafka consumer retry | 3 attempts, then DLT |
| Payment call | read timeout 2s, 3 attempts, breaker opens at 50% over ≥5 calls |
| Outbox publish retry | 10 attempts (3 in tests), then quarantined as `FAILED` |
| Verified E2E (happy) | qty 3 vs stock 10 → `INVENTORY_RESERVED`, stock 7/3, `version=1` |
| Verified E2E (failure) | qty 999 → `INVENTORY_FAILED`, **no** reservation row, stock untouched |
| Mutation check | `@Version` removed → 10 threads "succeed", only 2 units deducted |
| Stack | Java 21, Spring Boot 4.1, Spring Cloud 2025.1.2, Kafka KRaft, MySQL 8, Docker |
| Containers | 10 (7 services + Kafka + 2 MySQL); images 538–655 MB; cold build 8.3 min |

**`version=1` is worth quoting** — it proves exactly one optimistic-locked update happened,
not two.

---

## 7. Questions you will get, and answers

**"Why events instead of a REST call to inventory?"**
> Coupling and availability. A synchronous call means you can't accept orders when inventory
> is down — you've tied revenue to warehouse-system uptime. Async means orders are accepted
> and reconciled when inventory recovers. The cost is eventual consistency: the customer sees
> PENDING briefly, which is a business decision, not a technical accident.

**"Why duplicate the event classes instead of a shared module?"**
> A shared events jar couples deployments — change the contract and every service must be
> released in lockstep, which defeats the point of separate services. Duplication costs a
> synchronised edit; sharing costs independent deployability.
>
> *Follow-up detail:* it forced a related decision — the producer must **not** write Java type
> headers, because `__TypeId__` would name a class the consumer can't load and every message
> would fail to deserialize. The wire contract is plain JSON, and the consumer takes the
> target type from the listener method signature.

**"How do you guarantee exactly-once?"**
> You don't — exactly-once *delivery* is impossible. You engineer exactly-once **effect**:
> at-least-once delivery plus idempotent consumers. [Then §4.1.]

**"What if the consumer crashes halfway through?"**
> The database transaction rolls back and the offset was never committed, so Kafka redelivers.
> The work is retried from a clean state. That's why the `processed_event` row must be in the
> same transaction — otherwise the crash leaves the two out of step.

**"Why one partition? Doesn't that limit throughput?"**
> Yes, and it's deliberate for now. Ordering is only guaranteed *within* a partition, and
> events are keyed by `orderId`, so scaling to N partitions keeps per-order ordering intact —
> the keying is already correct, only the partition count changes. I haven't measured
> throughput yet, so I'd rather not guess at a number.

**"What happens if events arrive out of order?"**
> Within one order they can't — same key, same partition. Across orders it doesn't matter,
> they're independent. And the order state machine refuses illegal transitions, so a stale
> event can't drag a terminal order back to life. Terminal states accept nothing.

**"How do you replay from the DLT?"**
> Today: manually — inspect the message and its failure headers, fix the cause, republish to
> the original topic. There's no automated replay tool, and I'd want one before this was
> anything real.

**"How do you decide what to test with a container versus in memory?"**
> By asking what the fast test structurally cannot see. H2 is fine for logic and even for
> optimistic locking, because that is Hibernate emitting `UPDATE ... WHERE version = ?` and
> behaves identically. It is useless for anything database-specific — and I learned that the
> hard way: `@Lob` on a String silently became `TINYTEXT` on MySQL, 255 bytes, and every
> payload over that failed at insert. It survived three phases because the payloads happened
> to fit, and no H2 test could ever have caught it.
>
> So the container tests are targeted, not duplicated: column types, the unique constraint
> the idempotency guarantee depends on, the `@Version` column. Everything else stays on H2
> and runs in milliseconds.

**"Have you measured it?"**
> Yes, and the interesting part is that my first hypothesis was wrong.
>
> End-to-end p50 was 68 seconds, which is terrible. The obvious suspect was contention on the
> stock row — all the test orders hit one product, so the optimistic-lock retry would be
> serialising them. I tested it by spreading the identical load across 20 SKUs. It made no
> difference: 8.1 orders/sec against 8.7. So I would have spent the afternoon tuning a retry
> strategy that was not the problem.
>
> So I measured each stage separately. The gateway cost nothing — calling order-service
> directly was the same speed. Payment answered in 5 milliseconds. That left the accept path
> at about 600 milliseconds on an idle system, for what is three inserts in one transaction.
>
> Then I measured inside the database container, with no Java involved at all: 20 autocommit
> inserts took 3.8 seconds. **190 milliseconds per commit.** MySQL was configured for full
> durability — fsync of the redo log and the binlog on every commit — on Docker Desktop's
> virtual disk. A single order needs about five commits across the two services, and the
> Kafka consumers are single-threaded, so that is a serial second of pure disk waiting per
> order. That one number explained every other number.
>
> The controlled test was to relax `innodb_flush_log_at_trx_commit` to 2 and `sync_binlog` to
> 0 — both dynamic, no restart, nothing else touched. Throughput went from 8.1 to 119.4
> orders per second. **14.7×**, from one storage setting. Then I put it back, because that is
> a real durability trade-off and not something I would make silently on an order database.

**"What did the load test tell you about correctness?"**
> More than about performance, actually. A thousand orders at concurrency fifty: all thousand
> reached CONFIRMED, and the final stock reconciled exactly. No oversell, nothing stuck. That
> is the optimistic locking and the idempotency table under genuine concurrent load rather
> than under a unit test that simulates it.
>
> It also showed the shape of the system clearly: about 110 orders/sec accepted but only
> about 29 settled end to end. Acceptance is one local transaction; settlement is five
> transactions and two network hops. The producer runs four times faster than the consumer —
> and that is fine, precisely because the outbox makes the backlog durable. It queues, it
> does not lose orders.

**"Tell me about a bug you found that nobody reported."**
> The system told me everything was fine and it wasn't.
>
> After benchmarking I looked at the live databases. Order-service said all 1608 orders were
> CONFIRMED. Inventory still had 9 units reserved. Those two facts cannot both be right — a
> confirmed order has shipped its stock, it does not still hold it.
>
> The outbox showed all 1608 `order.confirmed` events published, so nothing was lost on the way
> out. Then I checked the dead-letter topic: `order.confirmed.DLT` had exactly 9 records. The
> headers carried the cause — `ReservationConflictException: after 4 attempts due to concurrent
> modification`. Benchmark run 1 fired 200 orders at a single product, so every confirmation
> fought for the same inventory row, and 9 of them exhausted the optimistic-lock retry budget.
> The error handler retried three more times, gave up, and dead-lettered them.
>
> Everything behaved exactly as designed. The bug was architectural: **we had dead-letter
> topics that nothing ever read.** They were write-only. Brilliant at stopping one poison
> message from blocking a partition, and a guaranteed leak of anything that landed there.
>
> What I find useful about it is that it was invisible from inside the message flow. No error,
> no alert, no failed request. You only see it by comparing state across the two services and
> asking whether those numbers can both be true.

**"So how did you fix it?"**
> Two scheduled jobs, because there were two different leaks.
>
> One sweeps order-service for orders stuck at INVENTORY_RESERVED and re-drives settlement.
> That covers the older gap where the `processed_event` row commits and settlement then fails —
> redelivery correctly skips it, so no consumer-side retry can ever help.
>
> The other drains the dead-letter topics and re-applies them. Both are safe to run repeatedly:
> payment is idempotent by orderId, and confirm and release both filter on `status = RESERVED`,
> so a replayed settlement matches no rows.
>
> One design decision I'd defend: the sweeper does **not** cancel an order when payment is
> unreachable, even though the live listener does. For a live order that's right — a customer
> is waiting. In a sweep it would let a five-minute provider outage cancel an entire backlog,
> turning a temporary failure into permanently lost business. So it waits, with a much longer
> ceiling after which it frees the stock anyway. There's a mutation test on that: make the
> sweeper cancel on outage and exactly one test fails.
>
> And I ran it against the real data rather than only the tests. One sweep: nine recovered,
> 1608 orders confirmed against 1608 reservations confirmed, zero stock held.
>
> The honest part is that it's a safety net, not a fix. The root cause is that confirm and
> release use read-modify-write with an optimistic lock when they're pure relative adjustments —
> `reserved -= n` — which a single atomic UPDATE would apply with no contention failure mode at
> all. That's the better fix and I haven't done it.

**"Where does this break at 10× scale?"**
> Three places. The `processed_event` tables grow unbounded — they need a retention job. The
> single Kafka partition caps consumer parallelism. And the outbox poller in Phase 5 becomes a
> bottleneck if it's a single scheduled thread. None are hard to fix; none are done.

**"Why is Eureka there if you're deploying to Kubernetes?"**
> It shouldn't be, and it isn't — I drop it on GKE. Kubernetes Services already do discovery,
> so running Eureka there would be redundant infrastructure. It stays for local runs where
> there's no cluster.

**"Why put routes in config rather than code?"**
> So the same jar runs locally and in Kubernetes. Locally the gateway resolves `lb://order-service`
> through Eureka; under the `k8s` profile the identical route points at `http://order-service:8081`,
> Kubernetes Service DNS, with the Eureka client switched off. If discovery were hard-coded,
> moving to the cluster would mean editing and redeploying the gateway rather than selecting
> a profile.

**"What does the correlation id actually buy you?"**
> Without it a failed request is unrelated log lines in five services with no way to tell
> they belong together. The gateway honours an incoming `X-Correlation-Id` or mints one and
> forwards it downstream over HTTP; every other hop is Kafka, so the id travels as a message
> header instead — attached when an outbox row is written, read back off the header by each
> consumer, and put in that consumer's own MDC before it does anything else. The synchronous
> payment call gets it as an HTTP header too. GKE already ships every pod's stdout to Cloud
> Logging (the `fluentbit-gke` addon), so one log query for that id returns the whole
> request's story: gateway, order-service, inventory-service, notification-service, and
> payment-service, in order.
>
> *Be honest about the limit:* this is log correlation, not distributed tracing — no spans,
> no waterfall, no automatic latency breakdown per hop, and reconciliation-driven work (no
> live request behind it) has no id to carry. Micrometer Tracing with a real backend is the
> next step if that ever becomes worth the infrastructure.

**"Walk me through your Dockerfile."**
> Multi-stage. A JDK builds, a JRE runs — the runtime image has no compiler, no Maven, no
> source, which is smaller and removes tooling from the attack surface of the thing exposed
> to traffic. Non-root user. The pom is copied on its own layer before the source, so a code
> change does not re-resolve dependencies, and the fat jar is split into layers so
> dependencies and application code cache independently.
>
> *The detail worth adding:* `-XX:MaxRAMPercentage` is useless without a container memory
> limit — the JVM sees the whole host and the flag bounds nothing. I set both, and verified
> a 768 MB container yields a 576 MB heap.

**"Why does your Kafka have two listeners?"**
> Because a client connects to a bootstrap address and is then handed back the *advertised*
> address to reconnect to. Containers need `kafka:9092`, which is meaningless on the host;
> host processes need `localhost:29092`, which is meaningless inside the network. One
> advertised address cannot serve both audiences, so there are two listeners on one broker.

**"What would you do differently?"**
> Build the outbox before the plain publish rather than after — I knowingly shipped a
> dual-write window I then had to document. And I'd verify the Docker Compose file earlier
> instead of writing infrastructure I couldn't run.

---

## 8. NOT built yet — never claim these

| Not built | Phase |
|---|---|
| README, ADRs, OpenAPI, **any throughput benchmark** | 11 |
| TLS | 16 |

**One thing that is genuinely broken right now, and say so if the saga comes up:** three
orders are permanently stuck at `INVENTORY_RESERVED` holding stock. The `processed_event` row
commits in one transaction and the settlement in another; when the second failed, redelivery
correctly skipped the event as already handled and nothing resumed it. Payment is idempotent
so resuming is safe — the missing piece is a reconciliation job over orders stale in
`INVENTORY_RESERVED`. *"I know exactly why it happens and what fixes it"* is a much better
answer than not having noticed.

**Also be honest about these:**

- **Both schemas are on one MySQL instance** when running against the host MySQL. The
  Compose stack does split them into two genuinely separate instances, verified — neither
  can see the other's schema. Say which one you mean.
- **Single Kafka broker.** No replication, no HA. `acks=all` is set, which matters only once
  there's more than one broker.
- **Auth is real but minimal.** `auth-service` issues HS256 JWTs over bcrypt-hashed
  passwords, and the gateway enforces per-route/per-role authorization (Phase D1). Admin
  can now edit a user's role/business id, disable a login, or set a new password
  directly (the user-management phase) — but there is still no refresh-token flow and no
  *self-service* password reset (no reset token, no email link; admin sets the new
  password synchronously). Expiry is short by design. This is a demo of real auth
  *mechanics*, not a production identity system — say that plainly if asked "is this how
  you'd do auth for real."
- **The billing screen reads payment-service's in-memory invoice map, not a database.**
  `GET /api/payments/invoices/{orderId}` (user-management phase) is the first time a
  generated invoice is read back rather than only emailed — but payment-service still has
  no database, so a restart loses every invoice, same limitation `PaymentService.pay`
  already had for charges. Also: ownership isn't cross-checked against the caller's JWT
  on this route, the same already-stated "client-supplied identifier" simplification
  Phase D10 documents for `customerId`.
- **Unit price comes from the client** on the original Phase 1-11 `order-service`/
  `inventory-service` demo endpoints. The Part D catalog (`CatalogItem.salePrice`, set
  admin-side in `inventory-service`) fixes this for the Impulse domain, but the older
  endpoints still take price from the request — say which API you mean if asked.
- **Distributed tracing is real, not just log correlation** — Micrometer Tracing +
  an in-cluster OpenTelemetry Collector export every request's trace to GCP Cloud Trace
  (Phase D1), so a single flow across the gateway, Kafka, and every downstream service
  shows up as one connected waterfall, verified live. Metrics beyond Actuator health are
  still not built.

If asked "is this production-ready?" — **no, and say so**: minimal auth (no refresh tokens,
no password reset), no HA, partial observability (tracing yes, metrics no), no load
testing. It's a correctness demonstrator.

---

## 9. Résumé bullets that are defensible *today*

Frame as problems solved, not technologies used.

> **Built an event-driven order platform (Java 21, Spring Boot 4, Kafka, MySQL)** that
> decouples order intake from stock reservation, so inventory outages delay fulfilment
> instead of blocking order acceptance.

> **Made Kafka consumers idempotent** using a processed-event ledger written in the same
> transaction as the work, plus a database uniqueness constraint — verified by replaying
> duplicate events and asserting stock moves exactly once.

> **Eliminated overselling under concurrency** with JPA optimistic locking and bounded
> jittered retry; validated with a 10-thread contention test, and confirmed the test's own
> validity by mutation-testing the lock away and watching it fail.

> **Added bounded retry and dead-letter routing** so malformed messages are quarantined with
> failure metadata rather than blocking their partition — tested by asserting a valid message
> queued behind a poison one is still processed.

> **Closed the dual-write gap between the database and the broker** with a transactional
> outbox — the event is committed alongside the order and drained to Kafka out of band —
> verified by pointing the service at a dead broker and asserting the order still commits
> with its event queued for retry.

> **Made a distributed Saga compensate correctly under failure** — a declined, timed-out or
> circuit-broken payment cancels the order and releases the reserved stock via an event, so
> inventory is never leaked; demonstrated by killing the payment service and observing the
> breaker open, the fallback fire, and stock return.

> **Containerised the platform** with multi-stage builds producing JRE-only, non-root
> images and layered jars for fast rebuilds; `docker compose up` brings all ten containers up
> in dependency order using healthchecks rather than sleeps.

> **Caught a class of bug the fast tests structurally could not see** by adding
> Testcontainers integration tests on the same MySQL image the platform runs on — after an
> H2-invisible column-type defect reached three phases before surfacing.

> **Profiled the platform end to end and found the bottleneck was storage `fsync`, not the
> application** — after testing and *refuting* the obvious hypothesis. Demonstrated a
> **14.7× throughput improvement** (8.1 → 119.4 orders/sec) from one durability setting, with
> 1000 concurrent orders settling with zero oversell.

> **Deployed to GKE with cost as a first-class constraint** — Spot node pool, MySQL and Kafka
> on a single Compute Engine VM instead of Cloud SQL, secrets mounted from Secret Manager via
> the CSI driver, and a teardown path that scales to near-zero between demos.

> **Built a CI/CD pipeline to GKE authenticated with Workload Identity Federation** — no
> service-account key file exists anywhere. Every push to `main` builds, tags with the git
> SHA, and rolls out via `kubectl set image` + `kubectl rollout status`, so a bad image fails
> the pipeline instead of leaving a silently broken deployment; a rollback is one command,
> verified live.

> **Rate-limited a newly public endpoint without adding infrastructure to satisfy a
> library** — a hand-rolled, in-memory per-client-IP limiter instead of Spring Cloud
> Gateway's Redis-backed one, since no Redis exists anywhere else in the stack. Found and
> fixed a real bug live burst-testing found and unit tests could not: Kubernetes'
> default `externalTrafficPolicy` masks the real client IP behind node-level SNAT.

> **Automated teardown between demos as a cost control, not an afterthought** —
> `deploy/gcp/down.sh`/`up.sh` scale the GKE node pool to zero and stop the data VM (never
> delete either, so no state is lost), cutting cost from ~$45/month always-on to ~$3/month.
> Running the bring-up script for real surfaced a genuine bug it was written to catch:
> the data VM's MySQL/Kafka containers had no `restart` policy, so stopping the VM silently
> lost the whole stack on the next start — found by checking the live containers over SSH,
> not by assuming the script worked because it exited 0.

> **Correlated one request's story across five services and a message broker using nothing
> but a header and GKE's log pipeline** — no ELK, no new infrastructure. The gateway's
> correlation id now rides every Kafka message as a header (attached when the outbox writes
> the row, read back off the header by each consumer into its own MDC), and one Cloud
> Logging query returns every service's log line for that request, in order. Deliberately
> not distributed tracing — no spans, no latency waterfall — chosen because the actual gap
> was "can't find the story," not "can't see per-hop latency," and the cheaper fix solved the
> real problem without adding a stateful service to a cluster that was already tight on
> spare capacity.

**Do not yet write:** TLS on the public endpoint, GitOps (the deployed manifest is not yet the
single source of truth — `kubectl set image` updates the cluster directly), distributed
tracing (spans/waterfalls — what exists is log correlation, a different and smaller claim).

---

## 10. Keeping this file honest

**This file must be updated in the same commit as every phase**, alongside `plan.md` and
`Agent.md` — see `Agent.md` §0. Specifically:

- Move items out of §8 as they're built, and add them to §2 and §9.
- Add each new hard problem to §4 in the same problem → breaks → did shape.
- Refresh the test counts and verified numbers in §6 — **quote real measurements only**.

A guide that overstates the project is worse than no guide, because you'll repeat it in a
room with someone who asks a second question.
