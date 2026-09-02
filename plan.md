# plan.md — Fault-Tolerant Order Fulfillment Platform

**This file is the single canonical plan.** It supersedes the old `docs/ROADMAP.md`
(folded in here on 2026-08-25). Project context, current state and gotchas live in
[`Agent.md`](Agent.md); this file is *only* the phased plan and its exit criteria.

- Source design conversation: `../Order & Inventory Platform.pdf` (257 pages, outside the repo)
- Stack: Java 21 · Spring Boot 4.1.0 · Spring Cloud 2025.1.2 · MySQL · Kafka · Docker · GKE

**Guiding rule: each phase must leave a running system.** Do not start phase N+1 until
phase N's exit criteria pass. Tick the boxes as they land, and record the outcome in
`Agent.md` §Change log in the same commit.

---

## How this plan is shaped

Three parts, deliberately sequential:

- **Part A — Local (Phases 0-11).** Build and prove the whole platform on this machine
  with Docker Compose. No cloud.
- **Part B — GCP (Phases 12-18).** Containerise once, then lift the finished platform
  into GKE, with MySQL + Kafka on a single Compute Engine VM and secrets in Secret Manager.
- **Part C — Optional (Phase 19).** The mainframe-modernization differentiator.

You chose *build everything locally first* over deploying a thin slice early. The
trade-off you accepted: no live demo URL until Phase 16, and Part B is one larger push
rather than several small ones. To limit that risk, Phase 9 is a real containerisation
phase — by its exit the platform already runs entirely from images, so Part B is about
GCP plumbing, not about learning Docker under deadline.

---

## Decisions locked

| Decision | Choice | Rationale |
|---|---|---|
| Event class sharing | **Duplicated per service** | Services stay independently deployable. A shared event jar couples deployments — a known anti-pattern interviewers probe. |
| `payment-service` | **In scope, mocked** | A pure-Kafka design has *no* synchronous inter-service call, so Resilience4j would be decoration. Payment gives the circuit breaker a real target and makes Saga compensation meaningful. |
| Phases 1 + 2 | Built in parallel | Inventory hardening and the Order domain are independent; both must land before Kafka. |
| Config backend | Git backend, **remote URI** | A `file:` path to the submodule works only in the original working copy — in any clone, a submodule's `.git` is a redirect file the backend rejects. Reading the remote is also how Config Server is used in production and needs no change on GKE. Changed 2026-08-25 after the clone test failed. |
| `config-repo` | Real git submodule, for editing only | Preserves the git-backed-config story. Since Config Server reads the remote, the submodule is now a convenience, not a startup requirement. |
| Cloud sequencing | **All local first** | Chosen 2026-08-25. Trade-off noted above. |
| MySQL on GCP | **Compute Engine VM**, not Cloud SQL | Cheaper, and you wanted to manage it. Cloud SQL's cheapest tier costs more than the rest of this deployment combined. |
| Kafka on GCP | **Same VM as MySQL** | Confirmed feasible on an `e2-medium` (4 GB) with tuned heaps. Cheapest option, keeps stateful workloads off Kubernetes, and reuses the Phase 3 Compose file almost verbatim. Single broker = no real HA; say so honestly in interviews. |
| Eureka on GKE | **Dropped** | Kubernetes Service DNS already does discovery. Eureka stays for local runs; a `k8s` profile switches the gateway to DNS routes. Saves a pod and is the answer a Kubernetes-literate interviewer wants. |
| Config Server on GKE | **Kept** | Git-backed config with an audit trail is a genuine capability, and it becomes the place Secret Manager values land. |
| Secrets on GKE | **Secret Manager via the CSI driver → env vars** | Framework-version-independent. A `spring-cloud-gcp-starter-secretmanager` property source would be more elegant but has no confirmed Boot 4.1 release — do not assume one exists. |
| Public access | Static IP + HTTP first, TLS optional | A GCLB forwarding rule is roughly $18/mo, more than every VM combined. Phase 16 starts on the cheap path and documents the upgrade. |

---

# Part A — Local

## Phase 0 — Unblock and tidy ✅ *(done 2026-08-21, committed 2026-08-25)*

- [x] Config Server URI → `file:${CONFIG_REPO_PATH:../config-repo}`
- [x] `.run/config-service.run.xml` pins the working directory for IntelliJ
- [x] Rename `config-repo/api-gateway.yaml` → `api-gateway-service.yaml`
- [x] `api-gateway-service` and `notification-service` made real config clients
- [x] Root `.gitignore`; untrack `.idea/`
- [x] `config-repo` registered as a real submodule
- [x] Verified: all four clients boot on Config-Server-supplied ports and register in Eureka

**Exit:** met. `http://localhost:8888/order-service/default` returns populated
`propertySources`, and all four services show `UP` at `http://localhost:8761`.

## Phase 0.5 — Commit the backlog ✅ *(done 2026-08-25)*

Phase 0's work had been sitting **entirely uncommitted** since 2026-08-21 — one careless
`git checkout` from being lost.

- [x] Created `https://github.com/Ishita2803/order-platform-config-repo` (public) and
      pushed `config-repo`'s `master`. The submodule URL had been a dead link.
- [x] Squashed `config-repo`'s 9 commits into one before the first push, because
      `password: "root"` was in the earlier commits and scrubbing the working file does not
      remove it from history. Tree hash unchanged (`1a75e83`) — content provably identical.
      Passwords are now `${MYSQL_PASSWORD:root}` placeholders.
- [x] Bumped the gitlink in the parent repo
- [x] Committed Phase 0 in the parent repo and pushed (`0d5c315`)
- [x] Verified a fresh `git clone --recurse-submodules` yields a populated `config-repo/`
- [x] **Fixed the bug that verification exposed** — see below

**Unplanned but necessary.** The clone test failed its second half: `config-service` would
not start from a clone, dying with
`IllegalStateException: No .git directory at file:../config-repo`. Spring Cloud Config's git
backend needs `.git` to be a real directory, and in a clone a submodule's `.git` is a
redirect *file*. The original working copy hid this because its `config-repo/.git` is still
a real directory from the pre-submodule layout, so the platform "worked on this machine"
and nowhere else — and Phase 15 would have hit the same wall inside GKE.

Fixed by pointing `config-service` at the **remote** repository via
`${CONFIG_REPO_URI:https://github.com/Ishita2803/order-platform-config-repo.git}` with
`clone-on-start: true`.

**Exit:** met. `git status` clean; from a throwaway clone, `config-service` reports `UP` and
serves populated `propertySources` for all four services on ports 8081/8082/8083/8080.

## Phase 1 — Inventory hardening ✅ *(done 2026-08-25)*

The source conversation is explicit that reservation must be correct **before** Kafka arrives.

- [x] **`Reservation` entity** — `orderId` (String/UUID), `productId`, `warehouseId`,
      `quantity`, `status` (`RESERVED` / `RELEASED` / `CONFIRMED`), unique constraint on
      `(order_id, product_id, warehouse_id)`. Reserve and release are now *order-scoped*.
- [x] Optimistic-lock retry around reserve (4 attempts, jittered backoff), and
      `ObjectOptimisticLockingFailureException` maps to **409**, not a 500 stack trace
- [x] `MethodArgumentNotValidException` → **400** with per-field errors
- [x] Bare `RuntimeException`s replaced with `ProductNotFoundException` and
      `DuplicateSkuException`; added `ReservationConflictException`
- [x] `confirmByOrderId` added so `CONFIRMED` is a real state rather than a dead enum value
- [x] 23 tests: sufficient stock succeeds · insufficient stock rejected · release restores
      stock · negative quantity rejected · unknown product/inventory errors cleanly ·
      redelivery reserves exactly once · **concurrent reservations do not oversell**

**Beyond the original scope, and worth knowing:**
- The service was split into `InventoryService` (retry, non-transactional) and
  `InventoryTxService` (`@Transactional`). Retry must wrap the whole transaction, and
  Spring's proxying means a same-bean call would have run with **no transaction at all**.
- H2 was added as a test-scoped dependency, plus `src/test/resources/application.yaml`,
  so the suite no longer needs Config Server and MySQL running. Phase 10 still adds
  Testcontainers for genuine MySQL coverage.

**Exit:** met. All 23 tests green. The concurrency test was additionally **mutation-checked**
— removing `@Version` makes it fail with a classic lost update (10 threads all report
success while only 2 units are deducted), proving the test can actually detect overselling
rather than passing vacuously.

## Phase 2 — Order domain ✅ *(done 2026-08-25)*

> **Constraint inherited from Phase 1:** `Reservation.orderId` is a **String UUID**, because
> a cross-service identifier travelling in Kafka events must not be another service's
> auto-increment surrogate key. `Order` must therefore expose a UUID business identifier,
> whatever it uses as its own primary key.

- [x] `Order`, `OrderItem`, `OrderStatus` (`PENDING`, `INVENTORY_RESERVED`,
      `INVENTORY_FAILED`, `CONFIRMED`, `CANCELLED`), with **legal transitions encoded on the
      enum** so a replayed event cannot revive a terminal order
- [x] Repository, service, DTOs, mapper, `GlobalExceptionHandler`, validation
- [x] `POST /api/orders`, `GET /api/orders/{orderId}`, `GET /api/orders` (paged, capped at 100)
- [x] 25 tests: lifecycle rules, service behaviour, JPA mapping against a real database,
      and the HTTP contract
- [x] **No Kafka in this phase**

**Deliberately deferred:** no `@Version` on `Order`. Concurrent status updates only become
possible once Kafka consumers exist, so that machinery lands in Phase 4 *with* tests rather
than sitting here untested.

**Exit:** met, and verified against **real MySQL** rather than only H2. `POST /api/orders`
→ 201; the row is present in `order_db.orders` with `status=PENDING` and
`total_amount=26.25`; both `order_item` rows carry the correct foreign key; `GET` by id →
200; a negative line quantity → 400 naming `items[0].quantity`; an unknown id → 404.

## Phase 3 — Kafka and the first async flow ✅ *(done 2026-08-26)*

- [x] `docker-compose.yml` — Kafka in **KRaft mode** (no ZooKeeper) plus two *separate*
      MySQL instances, parameterised so the same file runs on the GCP data VM.
      **Verified 2026-08-26** once Docker was installed — and it did not work first time;
      see the Phase 3 addendum below.
- [x] Topics: `order.placed`, `inventory.reserved`, `inventory.failed`, declared as
      `NewTopic` beans rather than left to broker auto-creation
- [x] Event **records**, separate from the JPA entities, duplicated per service, each
      carrying `eventId` (unused until Phase 4, but present so no migration is needed then)
- [x] Order publishes `OrderPlaced` via `KafkaTemplate`, keyed by `orderId`
- [x] Inventory consumes, reserves, publishes `InventoryReserved` / `InventoryFailed`
- [x] Order consumes the result and advances the order

**Deviation from the original plan, deliberate.** This phase was written as
"→ `CONFIRMED` / `INVENTORY_FAILED`", but `CONFIRMED` means *paid and shipped*, and payment
does not exist until Phase 8. A reserved order therefore stops at `INVENTORY_RESERVED`,
which is what the Phase 2 state machine allows: `PENDING → INVENTORY_RESERVED → CONFIRMED`.
Jumping straight to `CONFIRMED` here would mean confirming orders nobody has paid for.

**Also landed:** the order service was split into `OrderService` (publishes) and
`OrderTxService` (`@Transactional`), so the event is published strictly *after* commit —
publishing inside the transaction would let inventory reserve stock for an order that then
rolls back. The remaining dual-write window is documented in the code and closed in Phase 5.

**Addendum, 2026-08-26 — the Compose file was wrong, and running it proved it.** Three
defects that no amount of review had caught:

1. **Kafka would not start at all.** `KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092` is rejected —
   *"advertised.listeners cannot use the nonroutable meta-address 0.0.0.0"*. Fixed by
   omitting the host: `PLAINTEXT://:9092`.
2. **The Kafka volume held nothing.** Without `KAFKA_LOG_DIRS` the broker writes to
   `/tmp/kraft-combined-logs`, so the named volume looked like persistence and was not.
   This is the dangerous one — it fails silently, and only on container recreation.
3. **MySQL reported unhealthy while starting normally.** A cold data-directory init measured
   ~85s; `start_period` was 30s. Raised to 150s.

Also parameterised the host ports, since a Windows MySQL service already owns 3306.

**Exit:** met, verified against a **real broker and real MySQL**, not only in tests.
`POST /api/orders` (qty 3, stock 10) → `PENDING` → `INVENTORY_RESERVED`, with a `RESERVED`
row in `inventory_db.reservation` and stock at 7 available / 3 reserved
(`version=1`, so exactly one update). A second order for qty 999 → `INVENTORY_FAILED`
with **no** reservation row and stock untouched.

Automated proof: 8 integration tests against an embedded broker (4 per service), covering
publish, both result paths, partial-order compensation, and duplicate delivery.

## Phase 4 — Saga correctness ✅ *(done 2026-08-26)*

- [x] `processed_event` table in **both** services, `eventId` as the primary key, written
      **in the same transaction as the work it describes**. That is the whole mechanism:
      written before, a crash loses the work; written after, a crash repeats it; written
      together, at-least-once delivery becomes exactly-once *effect*.
- [x] Bounded retry (3 attempts, exponential backoff **with jitter**) plus
      `DeadLetterPublishingRecoverer` → `<topic>.DLT`. Unparseable payloads are marked
      **not retryable** and go straight to the DLT rather than burning six seconds first.
- [x] Compensation: no longer needed for a partly-fulfillable order — see below

**The design changed, for the better.** `reserveOrder` now checks *every* line before
applying *any*, all in one transaction. So a short line means nothing was reserved at all,
and there is nothing to compensate for. Phase 3 reserved line-by-line and released on
failure, which left a window where stock was held for an order already doomed to fail. The
order-scoped `releaseInventory` remains — Phase 8 needs it when *payment* fails, which is a
genuinely downstream failure rather than an in-flight one.

Idempotency is now belt **and** braces: the `processed_event` row keyed by `eventId`, and
the unique constraint on (orderId, productId, warehouseId) underneath it. The second
survives the publisher regenerating an `eventId`; the first is what a general consumer needs
when its work has no such natural key.

**Exit:** met. Replaying the same `OrderPlaced` reserves stock exactly once and leaves
exactly one `processed_event` row. A malformed payload lands in `order.placed.DLT` with the
failure reason in its headers, and — separately asserted — **a valid message queued behind
a poison one is still processed**, which is the failure this phase actually exists to
prevent.

## Phase 5 — Transactional Outbox (Order Service) ✅ *(done 2026-08-26)*

- [x] `outbox_event` table written in the **same transaction** as the order. `createOrder`
      no longer touches Kafka at all — one database, one commit, atomic by construction.
- [x] Scheduled publisher (`fixedDelay`, not `fixedRate`) drains oldest-first in capped
      batches and marks rows `PUBLISHED`, blocking on the broker's ack so a row is never
      marked published for a send that later failed
- [x] Retry on failure, recording attempt count and last error; once the budget is spent the
      row becomes `FAILED` and is skipped, so one undeliverable event cannot be retried
      forever ahead of everything queued behind it

**Two real bugs this phase surfaced**, both caught by tests asserting on *content* rather
than merely on delivery:

1. **Double-encoded payloads.** The outbox payload is already-serialized JSON held as a
   `String`, and the default `JsonSerializer` re-encoded it into a quoted, escaped JSON
   string. Inventory-service could never have deserialized it. Fixed with a separate
   string-valued template.
2. **A vanishing bean.** Adding that second template silently switched off Boot's
   auto-configured `KafkaTemplate`, because the condition is
   `@ConditionalOnMissingBean(KafkaTemplate.class)` — raw type, generics ignored. Every
   injection point wanting `KafkaTemplate<String, Object>` stopped resolving. Both templates
   are now declared explicitly.

**Exit:** met, and tested with **no broker at all** rather than by killing one mid-request.
With `bootstrap-servers` pointed at a dead port: `createOrder` returns `PENDING` without
throwing or hanging, the order is durably committed, and its event waits `PENDING` in the
outbox. A failed drain records the attempt and leaves the row pending; once the budget is
spent the row is quarantined as `FAILED` and further drains ignore it.

## Phase 6 — Notification Service ✅ *(done 2026-08-26)*

- [x] Consumes `inventory.reserved` / `inventory.failed` and sends a mock email
- [x] **No knowledge of order-database internals — enforced by the classpath.** This service
      has no JPA and no MySQL dependency at all, so it *cannot* read another service's tables
      even by accident. Verified: zero Hikari/Hibernate/JDBC lines in its startup log.
- [x] `NotificationSender` interface with a logging implementation, so tests assert on what
      was sent rather than scraping log output, and a real provider is a new class later
- [x] Its **own consumer group**, so it and order-service both receive every event. A shared
      group id would make them compete, and each event would be either notified or applied,
      never both.
- [x] Bounded retry + DLT, same shape as the other consumers

**Deliberate limitation, tested rather than hidden:** this service **does not deduplicate**.
Kafka is at-least-once, so a redelivered event sends a second email — and there is a test
asserting exactly that, so nobody later assumes otherwise. The other two services dedupe with
a `processed_event` table; this one has no database, and an in-memory set would be worse than
nothing (per-instance, lost on restart, while looking like a solution). The real fix belongs
at the provider, which accepts an idempotency key.

**Exit:** met, verified end-to-end on the Compose stack with all four services running. One
`POST /api/orders` for qty 3 produced an `ORDER_CONFIRMED` mock email naming the order; a
second for qty 999 produced an `ORDER_FAILED` email carrying the real reason from the event
("Available=7, requested=999") plus "You have not been charged". Both orders reached their
correct status in `order_db` at the same time, which is what proves the two consumer groups
each received every event.

## Phase 7 — API Gateway ✅ *(done 2026-08-26)*

- [x] Routes for `/api/orders/**` → order-service and `/api/products/**`, `/api/inventory/**`
      → inventory-service
- [x] **Profile-switched from day one.** `api-gateway-service.yaml` uses `lb://` through
      Eureka; `api-gateway-service-k8s.yaml` uses Kubernetes Service DNS and disables the
      Eureka client. Both verified being served by Config Server. Phase 15 changes a profile,
      not code.
- [x] `CorrelationIdFilter` — honours an incoming `X-Correlation-Id` or generates one, puts
      it in the MDC, **forwards it downstream** via a request wrapper, returns it to the
      caller, and logs method/path/status/duration
- [x] `GatewayExceptionHandler` — an unreachable downstream is **503 with JSON**, carrying
      the correlation id, rather than a 500 or Spring's HTML error page
- [x] 6 tests (1 context + 5 integration against a real stub HTTP server)

**Note the property namespace:** `spring.cloud.gateway.server.webmvc.routes`. This is the
MVC gateway; every example using `spring.cloud.gateway.routes` targets the reactive one and
silently does nothing here.

**Exit:** met. The complete flow ran through `:8080` alone with all six services up —
`POST /api/products`, `POST /api/inventory`, `POST /api/orders`, polling
`GET /api/orders/{id}` to `INVENTORY_RESERVED`, and `GET /api/inventory` showing 6/4. A
validation error still surfaced as a 400 with field errors; an unrouted path returned 404;
and with inventory-service killed, the gateway returned **503 JSON** with a quotable
correlation id.

**Honest limitation:** the correlation id is generated and *forwarded* (proved in the IT
against a stub that inspects the received header), but the downstream services do not yet
**log** it — they have no correlation filter of their own — so the trail currently stops at
the gateway. Phase 9's structured logging with `traceId` completes it.

## Phase 8 — Payment Service + Resilience4j ✅ *(done 2026-08-26)*

> **Design note:** in a pure-Kafka design there is no synchronous inter-service call, so a
> circuit breaker would be decoration. The mocked Payment Service exists to give
> Resilience4j a genuine target — and to make the Saga a real Saga.

- [x] `payment-service` (mocked), called **synchronously** from order-service after reserve.
      **Idempotent by orderId**, which is what makes putting a retry in front of it safe.
      Switchable at runtime (APPROVE / DECLINE / SLOW) so the failure paths can be shown live.
- [x] Resilience4j: HTTP read timeout, retry with exponential backoff, circuit breaker,
      fallback. 5 integration tests against a stub that can be told to misbehave.
- [x] Payment failure → order `CANCELLED` → `OrderCancelled` event → inventory releases.
      Payment success → `CONFIRMED` → `OrderConfirmed` → inventory confirms (stock ships and
      does **not** return to available).
- [x] Compensation travels **through the outbox**, not a direct REST call, so a cancellation
      during an inventory-service restart is not lost.

**A read timeout, not a TimeLimiter.** Resilience4j's TimeLimiter needs a
`CompletableFuture` to cancel; there is nothing to cancel in a blocking call. Using it here
would be cargo cult.

**Two real bugs this phase found — both invisible to a passing unit test:**

1. **The fallback silently disabled the retry.** It was declared on `@CircuitBreaker`, which
   Resilience4j nests *inside* `@Retry`. The first failure hit the fallback, which returned
   normally, so Retry saw a success and never retried. The configuration looked perfect.
   Only a test counting requests *at the server* caught it. The fallback now sits on the
   outermost annotation.
2. **The outbox `payload` column was TINYTEXT (255 bytes).** `@Lob` on a String with no
   length makes Hibernate pick MySQL's smallest text tier. Latent since Phase 5 —
   `OrderPlaced` payloads were ~200 characters and fit by luck — and it surfaced the moment
   a cancellation carried an exception message. H2 does not reproduce the mapping, so no
   existing test could have caught it. Now `mediumtext`.

**Exit:** met. With payment stopped: three orders each returned `CANCELLED`, the breaker went
`CLOSED → OPEN` after the second, and stock was released rather than leaked (reserved
unchanged, because each order reserved and released the same quantity). Restarting payment
showed the full recovery: `OPEN → HALF_OPEN → CLOSED` after two successful trial calls, with
those orders reaching `CONFIRMED`. Also verified: an approved payment ships the stock
(reserved drops, available does **not** rise), and a declined payment returns it.

**The documented Phase 4 gap manifested for real, which is worth knowing.** Three orders from
before the TINYTEXT fix are permanently stuck at `INVENTORY_RESERVED` holding 6 units: the
`processed_event` row committed in the first transaction, the settlement transaction then
failed, and redelivery correctly skipped the event as already handled. Payment is idempotent
so resuming would be safe — what is missing is the thing that resumes it. **A reconciliation
job over orders sitting in `INVENTORY_RESERVED` past a threshold is now a required item, not
a nice-to-have.**

## Phase 9 — Containerise everything ✅ *(done 2026-08-26)*

- [x] Multi-stage `Dockerfile` per service (7): JDK 21 builder → **JRE 21** runtime,
      non-root user (uid 10001), layered jar extraction
- [x] `-XX:MaxRAMPercentage=75` **plus real `mem_limit`s**, because the flag bounds nothing
      without a limit — verified: 768m container → 576 MB heap, 512m → 384 MB
- [x] Every hard-coded `localhost` replaced by an env var with a localhost default. The last
      ones were `spring.config.import`, now `configserver:${CONFIG_SERVER_URL:...}`
- [x] `docker compose up` brings the **entire platform** up from images — 10 containers
- [x] Actuator health wired into Compose healthchecks, with `depends_on: service_healthy`
      expressing startup order instead of sleeps
- [x] `.dockerignore` per service

**Image sizes:** 538–655 MB. Layer ordering is deliberate — dependencies copied before
application code, so a source-only change rebuilds just the top layer. A BuildKit cache mount
shares one Maven repository across all seven builds, so Spring Boot is resolved once rather
than seven times. Full cold build: **8.3 minutes**.

**Kafka needs two listeners, and this is the subtle part.** A client connects to a bootstrap
address, is handed back the *advertised* address, and reconnects to that. One advertised
address therefore cannot serve both audiences: containers must be told `kafka:9092`
(meaningless on the host) and host processes `localhost:29092` (meaningless in the network).
Two listeners, two advertised addresses, one broker. **The host port moved 9092 → 29092.**

**Also added:** `.env.example` (committed) plus a gitignored `.env`, because a Windows MySQL
service owns 3306 and the resulting bind failure — *"Only one usage of each socket address"* —
does not obviously point at MySQL.

**Exit:** met. `docker compose up` runs the full happy path (order → `CONFIRMED`, stock
shipped) and both failure paths (out of stock → `INVENTORY_FAILED`; payment declined →
`CANCELLED` with stock released) entirely from images, through the gateway, with no JDK
involved at runtime — `javac` is absent from the runtime images and every service runs as
`appuser`.

**Two things worth knowing for next time.** A container killed mid-initialisation leaves MySQL
with a corrupt data directory (*"Cannot create redo log files"*) that no restart recovers —
the volume must be deleted. And after recreating a service, the gateway's first request can
404 until its Eureka registry cache refreshes; that is registry propagation, not a routing
bug.

## Phase 10 — Testing and CI ✅ *(done 2026-08-26)*

- [x] Testcontainers integration tests against **real MySQL 8.0** — the same image the
      platform runs on — in both database-backed services
- [x] GitHub Actions: a 7-way matrix running `verify` per service, then a job that builds
      every container image and boots the backing services

**These tests exist because of a specific bug.** Phase 8's `TINYTEXT` defect was invisible to
100% of the H2 suite, because H2 does not reproduce MySQL's type mapping. `OutboxMySqlIT` now
asserts directly that `outbox_event.payload` is a real text type and that an eight-line
order — payload well over 255 bytes — round-trips intact. `InventoryMySqlIT` does the same
for the things inventory's correctness rests on: the unique constraint that *is* the
idempotency guarantee, and the `@Version` column that prevents overselling.

**`@ServiceConnection`** wires the container's JDBC URL into the context, so there is no
`@DynamicPropertySource` plumbing and no way for the test to point at the wrong database.

**Two traps worth knowing.** Testcontainers **2.x renamed every module** —
`org.testcontainers:mysql` no longer exists, it is `testcontainers-mysql`, and every tutorial
online still shows the old coordinates. And the MySQL container timed out after 390s until
the data directory was moved to **tmpfs**: on-disk init takes 85–235s here and Testcontainers
kept connecting during the entrypoint's temporary server — the same trap the Compose
healthcheck hit in Phase 7. With tmpfs the suite runs in ~26s.

**CI notes.** The workflow runs `verify`, not `test`: `test` alone silently skips every `*IT`
in this project and the build would go green while proving far less than it looks. Images are
built but **not pushed** — there is no registry until Phase 17.

**Exit:** met. All five application modules green — **101 tests** (51 order, 33 inventory,
6 notification, 6 gateway, 5 payment), of which 43 are integration tests against a real
broker, real MySQL, or a real HTTP server. CI reports **103 across all seven modules**, the
extra two being context-load smoke tests in `config-service` and `discovery-service`.

**Verified on real runners 2026-08-26** (run `32993087529`): **all eight jobs green** —
seven matrix builds plus the image build — with 103 tests, 0 failures, 0 errors, 0 skipped.
The Testcontainers MySQL tests passed without needing the tmpfs tuning that was essential
locally.

**Running it for the first time found two real bugs, neither visible in the files.**

1. **Every `mvnw` was committed mode `100644`.** All seven jobs died identically with
   `./mvnw: Permission denied`, exit 126. Windows has no executable bit, so git recorded the
   wrappers as non-executable and the Linux runners refused them. Fixed with
   `git update-index --chmod=+x`.
2. **The BuildKit cache mount covered all of `/root/.m2`** — including
   `/root/.m2/wrapper/dists`, where the Maven Wrapper installs Maven itself. `docker compose
   build` builds in parallel, so seven `mvnw` processes raced to install into one shared
   mount; one partially populated the directory and the next one's `mv` onto a non-empty
   target failed, leaving no Maven on PATH. Now caching only `/root/.m2/repository` with
   `sharing=locked`.

Both were invisible locally because this machine had state the runners do not: an ignored
executable bit, and a build cache already warmed by Phase 9. The second means Phase 9's
"8.3 minute cold build" was not as cold as it was labelled.

## Phase 11 — Documentation ✅ *(done 2026-08-26)*

- [x] README with architecture diagram, happy-path flow, **failure-path flow**
- [x] ADRs under `docs/decisions/` — seven of them, each recording the rejected alternatives
- [x] OpenAPI specification — `docs/openapi.yaml`
- [x] Benchmark: real measured throughput, latency and outcome distribution

**On the OpenAPI spec: it is hand-written, deliberately.** springdoc-openapi has no Spring
Boot 4 release — the latest is 2.8.6, targeting Boot 3 — so annotation-driven generation is
not available without pinning the whole platform back a major version. The spec is maintained
by hand, validated as parsable with every `$ref` resolving, and its status codes were checked
against the actual `@ExceptionHandler` methods rather than assumed.

**The benchmark is the part worth reading.** `docs/benchmark/bench.py` drives synthetic load
and `docs/benchmark/RESULTS.md` records four runs verbatim. The headline is not the numbers
but the investigation: the obvious hypothesis — contention on a single stock row — was
**tested and refuted** (spreading the same load across 20 SKUs changed nothing: 8.1 vs 8.7
orders/sec). Measuring each stage separately showed the gateway cost nothing and payment
answered in 5 ms, which left the accept path at ~600 ms for three inserts. The cause was
`fsync`: **190 ms per commit** on Docker Desktop's virtual disk, measured inside the database
container with no Java involved, and roughly five commits per order across single-threaded
consumers. Relaxing one durability setting and changing nothing else made the platform
**14.7× faster** — 8.1 → 119.4 orders/sec, end-to-end p50 70 s → 6.2 s. Durable settings were
restored afterwards and verified.

At 1000 orders: ~110 accepted/sec, ~29 fully settled/sec, **1000/1000 confirmed with zero
oversell** and stock reconciling exactly — the idempotency and optimistic-locking machinery
under real concurrent load rather than unit tests.

Also documented honestly: every topic has **one partition**, so consumers cannot scale out
today; a second `inventory-service` instance would sit idle.

**Exit:** met. `README.md` takes someone from `git clone` to a confirmed order, and to a
deliberately triggered compensation, without reading anything else.

---

## Phase 11.5 — Reconciliation ✅ *(done 2026-08-26)*

The gap flagged since Phase 4 and proven real in Phase 8. Closed on **both** sides, because
investigating it turned up a second, different leak.

- [x] `OrderReconciliationService` — sweeps orders stuck at `INVENTORY_RESERVED` past a
      threshold and re-drives settlement
- [x] `SettlementRecoveryService` — drains `order.confirmed.DLT` / `order.cancelled.DLT` and
      re-applies them
- [x] `@Version` on `Order`, deferred since Phase 2, now that a second writer genuinely exists
- [x] 16 tests (11 order, 5 inventory), mutation-verified

**Two different failures, one shape.** Both strand stock while every component behaves exactly
as designed:

1. *The marker outlives the work.* `processed_event` commits, settlement then fails,
   redelivery correctly skips. No consumer-side retry can help — skipping is right.
2. *The dead-letter topic is write-only.* Anything that exhausts its retries lands in a `.DLT`
   that **nothing ever reads**. Excellent at stopping a poison message blocking a partition;
   a guaranteed leak of whatever lands there.

**The second one was found by looking at the live database, not by reasoning.** 1608 orders
all read `CONFIRMED`, yet 9 units were still reserved. Tracing it: `order.confirmed.DLT` held
exactly 9 records, and their headers gave the cause —
`ReservationConflictException: ... after 4 attempts due to concurrent modification`. Benchmark
run 1's 200 orders against a single product exhausted the optimistic-lock retry budget for 9
confirmations. **My order-side sweeper does not catch this**: order-service settled perfectly;
the drift is entirely inventory-side.

**Verified against the real data**, not only in tests. One sweep recovered all nine —
`examined=9 confirmed=9 released=0 failed=0` — leaving `orders CONFIRMED` = 1608 and
`reservations CONFIRMED` = 1608, with zero stock held. Consumer-group lag 0, so the records are
not reprocessed.

**A deliberate policy difference.** The listener cancels when payment is unreachable, because a
customer is waiting. The sweeper does **not** — a five-minute outage would otherwise cancel an
entire backlog. It waits, with a much longer ceiling after which it frees the stock anyway.
A mutation test pins this: making the sweeper cancel on outage fails exactly one test.

**Still open, and honestly the better fix:** confirm and release use read-modify-write with an
optimistic lock, when they are pure relative adjustments (`reserved -= n`) that one atomic
`UPDATE` would apply with no contention failure mode at all. Reconciliation makes the symptom
recoverable; it does not make it stop happening.

**Exit:** met. See [ADR-0008](docs/decisions/0008-reconcile-state-not-messages.md).

---

# Part B — GCP

Cost target: **as cheap as possible.** Estimates below; every phase notes its cost impact.
The single biggest lever is Phase 18's teardown script — an idle cluster and VM cost money
whether or not anyone is looking at them.

| Item | Shape | ~USD/month if left running |
|---|---|---|
| GKE zonal cluster (management fee) | 1 zonal cluster | **~$0** — covered by the GKE free-tier credit |
| GKE nodes | 2 × `e2-medium` **Spot** | ~$15 |
| Data VM (MySQL + Kafka) | 1 × `e2-medium` standard + 20 GB balanced PD | ~$27 |
| Static external IP | 1, in use | ~$3 |
| Artifact Registry | under 1 GB | ~$0 |
| Secret Manager | a handful of secrets | ~$0 |
| **Total, always-on** | | **~$45** |
| **Total, torn down between demos** | ~10 h/month | **~$3** |

These are order-of-magnitude figures for `us-central1` and they drift. Verify against the
pricing calculator before committing, and set the Phase 12 budget alert *first*.

## Phase 12 — GCP foundation and local toolchain ✅ *(done 2026-08-30)*

Nothing here touches the application. Done in one sitting.

- [x] **Install the local toolchain** — Google Cloud CLI installed and authenticated;
      Docker Desktop already present since Phase 9.
- [x] Create the GCP account and enable billing
- [x] **Set a budget with alert thresholds at 50 / 90 / 100 % before creating any
      resource.**
- [x] Create project (named `inventorymanagement-507107`, not `order-platform` as
      originally planned — the intended name was taken), set as default, region
      `us-central1` / zone `us-central1-a`
- [x] Enable APIs: `container`, `compute`, `artifactregistry`, `secretmanager`,
      `cloudbuild`, `iam`
- [x] Create the Artifact Registry Docker repository (`order-platform-repo`, `us-central1`)
- [x] Push one Phase 9 image (`order-service`) to Artifact Registry as a smoke test
- [x] Capture every command in `deploy/gcp/00-bootstrap.sh`

**Note:** local images build under the `order-platform/` Compose namespace (e.g.
`order-platform/order-service:latest`), not the bare service name — the first tag attempt
failed with "No such image: order-service:latest" until this was found via `docker images`.

**Exit:** met. `gcloud config list` shows project `inventorymanagement-507107`, region
`us-central1`, zone `us-central1-a`; the budget alert exists; `order-service` is visible in
Artifact Registry under `order-platform-repo`.

## Phase 13 — The data VM (MySQL + Kafka) ✅ *(infrastructure done 2026-08-30; exit criterion deferred to Phase 15)*

One `e2-medium` VM runs both, via the Phase 3 Compose file.

- [x] Create a VPC (or use default) plus firewall rules. **MySQL 3306 and Kafka 9092 must
      be reachable only from the GKE node subnet — never from `0.0.0.0/0`.** *(Scoped to the
      default subnet range as a stand-in for the future GKE node subnet, since that subnet
      doesn't exist until Phase 15.)*
- [x] Create the VM: `e2-medium`, Debian 12, 20 GB balanced PD, **no external IP** (reach
      it over IAP for SSH), Docker installed by startup script
- [x] Deploy MySQL 8 + single-broker Kafka (KRaft) with Compose, both on named volumes so
      a VM restart doesn't lose data
- [x] **Tune heaps for 4 GB total:** MySQL `innodb_buffer_pool_size` around 768 MB, Kafka
      heap around 768 MB. The defaults assume a dedicated machine and will OOM here.
      *(384M per MySQL instance × 2 + Kafka 768M heap = the 4 GB budget.)*
- [x] Advertise Kafka on the VM's **internal** IP — a broker advertising `localhost`
      accepts the connection and then hands clients an unreachable address, which presents
      as a mysterious timeout
- [x] Create `order_db` and `inventory_db`, with a non-root application user per schema
- [x] Reserve the VM's internal IP so it survives a restart

**Exit: met, verified 2026-09-01 in Phase 15.** From a throwaway pod (`mysql:8.0` and
`apache/kafka:3.9.0` images, `kubectl run --rm --attach`), `mysql` connected to both
`order_db` and `inventory_db` over the VM's internal IP, and a Kafka console
producer/consumer round-tripped a message through `10.128.0.2:9092`. Getting there required
widening `allow-kafka-internal`, `allow-mysql-internal`, and `allow-mysql-inventory-internal`
to also allow `10.83.128.0/17` — the cluster's pod secondary range, which sits outside the
`10.128.0.0/20` range those rules originally allowed. Pod-to-VM traffic keeps the pod's own
source IP (both are RFC1918, so `ip-masq-agent`'s default rules do not masquerade it), which
is why the node range alone wasn't enough.

## Phase 14 — Secret Manager 🟡 *(secrets + IAM + config fix done 2026-09-01; cluster-dependent steps deferred to Phase 15)*

This phase deletes a real defect: `config-repo/order-service.yaml` and
`inventory-service.yaml` used to contain `password: "${MYSQL_PASSWORD:root}"` — a plaintext
password default, committed in git.

- [x] Create secrets `mysql-order-password` and `mysql-inventory-password`
- [x] Create a Google service account for the workloads (`order-platform-workload`); grant
      `roles/secretmanager.secretAccessor` scoped to those two secrets individually (via
      each secret's own Permissions tab), not project-wide
- [ ] **Deferred to Phase 15 — no cluster exists yet.** Enable **Workload Identity** on the
      cluster and bind the Kubernetes service account to the Google one, so no JSON key
      file ever exists
- [ ] **Deferred to Phase 15 — same reason.** Install the **Secret Manager CSI driver**;
      mount the secrets and expose them as env vars
- [x] Change the `config-repo` yamls to `${MYSQL_USER}` / `${MYSQL_PASSWORD}` — no default,
      so a missing credential fails loudly instead of silently trying `root`. Local Compose
      now passes `MYSQL_USER`/`MYSQL_PASSWORD` explicitly rather than relying on the removed
      default.
- [ ] **Not done — declined for now.** The VM's actual credentials (`MYSQL_ROOT_PASSWORD`,
      `ORDER_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`) are all the same weak value (`root123`),
      set directly in the VM's `.env`, never committed to git. This is a live defect (one
      leaked value compromises root plus both app users) but Karthik chose to defer
      rotating it rather than block on it. **Do not claim this is fixed.**
- [ ] *Optional, only if a Boot 4.1-compatible release exists:*
      `spring-cloud-gcp-starter-secretmanager` for `sm://` property references. Do not
      assume one exists — the CSI-driver path above carries no version risk.

**Exit (deferred to Phase 15):** a pod reads its DB password from Secret Manager, `git grep -i password` in
`config-repo` finds only placeholders, and the old credential is revoked.

## Phase 15 — GKE cluster and manifests ✅ *(done 2026-09-02)*

- [x] **First, verify Phase 13's deferred exit criterion:** from a throwaway pod, confirm
      `mysql` connects to both schemas on the data VM and a Kafka console producer/consumer
      round-trips a message over its internal IP, before deploying anything real.
- [x] Create a **zonal** cluster (regional multiplies control-plane cost and node count)
      with a Spot node pool, autoscaling 1-3 — `order-platform-cluster`, `us-central1-a`,
      default network/subnet, `e2-medium` Spot nodes
- [x] Enabled Workload Identity on the cluster and the node pool (`gcloud container
      clusters update --workload-pool=...` then `node-pools update
      --workload-metadata=GKE_METADATA`, which recreates every node) — deferred from Phase 14
      since it needs a cluster to exist first
- [x] `deploy/k8s/` — per service: `Deployment`, `Service`, liveness/readiness probes on
      Actuator, resource requests **and** limits, `ConfigMap` for non-secret env. Node pool
      max bumped 3→4 (`e2-medium` Spot) — the original 3-node cap left no allocatable CPU
      for two pods once GKE's own addons (logging, monitoring, CSI driver, etc.) are
      accounted for; see Agent.md §10, 2026-09-02.
- [x] **Do not deploy `discovery-service`.** Added a `k8s` Spring profile that switches the
      gateway to Service-DNS routes and disables the Eureka client — confirmed live via the
      gateway's boot log (`The following 1 profile is active: "k8s"`).
- [x] Deploy `config-service` in-cluster; clients point at `http://config-service:8888` —
      confirmed via client boot logs (`Fetching config from server at :
      http://config-service:8888`).
- [x] Set each JVM's `MaxRAMPercentage` against the **pod limit**, not the node size.
      Already satisfied since Phase 9 — every Dockerfile bakes in
      `ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`, which k8s
      inherits regardless of the manifest's `command` override. Confirmed on the live PID 1
      process (`/proc/1/cmdline`), not just a fresh `java -version` check — corrects an
      earlier, wrong "not yet done" note in this file and in Agent.md.
- [x] Verify pods survive a Spot preemption — that is the trade-off you're buying.
      Simulated by deleting the GCE instance backing the node hosting
      `inventory-service` (`gcloud compute instances delete`). Node went `NotReady` in
      ~60s and was removed from the cluster; GKE's managed instance group recreated a
      replacement (same name) within ~25s; the Deployment's ReplicaSet scheduled a new pod
      onto it with no manual intervention, and it reached `1/1 Ready` unassisted — including
      re-resolving its `config-service` dependency from a cold start.

**Exit: met, 2026-09-02.** All six pods `Running`/`Ready`. Verified via
`kubectl port-forward svc/api-gateway-service 8080:8080` against the real data VM: a product +
inventory (qty 10) created, then an order placed **before** inventory existed for it correctly
reached `INVENTORY_FAILED`; a second order for qty 2 reached `CONFIRMED` in ~3s with stock
correctly settled (`available` 10→8, `reserved` back to 0 — shipped, not merely held).

## Phase 16 — Public internet access 🟡 *(gateway public and verified 2026-09-02; rate-limiting and TLS still open)*

- [x] Reserve a static external IP — `35.208.57.189`, **Standard** network tier (cheaper than
      Premium), reserved via the console (`VPC network → IP addresses`), `us-central1`.
- [x] **Cheap path first:** chose `Service type=LoadBalancer` over raw `NodePort` +
      firewall — Phase 15 had just proven Spot nodes get recreated (sometimes with churned
      identity), and a NodePort setup means pointing the static IP at one specific node,
      which breaks the moment that node is preempted. A `LoadBalancer` Service balances
      across all nodes and follows pods automatically regardless of which node comes and
      goes, at the cost of a forwarding-rule fee while it's up.
- [x] Only the gateway is public. `order-service`'s port `8081` confirmed unreachable from
      outside the cluster (connection times out); every other service, and the data VM,
      remain cluster-internal by construction — nothing else has a `LoadBalancer` Service.
- [x] Sanity-checked Actuator `env` / `heapdump` — already not exposed. The gateway's
      `management.endpoints.web.exposure.include` in `config-repo` was already
      `health,info,gateway` since Phase 7; no code change was needed here.
- [ ] *Optional TLS:* not done.
- [ ] Rate-limit the gateway — not done. Plan: a lightweight in-memory token-bucket filter
      (e.g. Bucket4j) rather than Spring Cloud Gateway's Redis-backed `RequestRateLimiter` —
      there's no Redis anywhere in this stack, and adding one solely for rate-limiting would
      be disproportionate to what it buys.

**Exit: met for the core criterion, 2026-09-02.** `curl http://35.208.57.189/api/orders`
returns real order data from outside the cluster (verified from this machine, not yet from a
phone on mobile data — worth a quick check, but the network path proven is the one that
matters: an external client, not `kubectl port-forward`). A direct request to
`order-service:8081` from outside times out. Rate-limiting and TLS remain open and are not
required for this exit criterion as written, but should close before calling Phase 16 fully
done.

**A real gotcha, worth knowing for next time.** The first `LoadBalancer` apply failed
repeatedly with `SyncLoadBalancerFailed: requested IP "..." belongs to the Standard network
tier; expected Premium` — GKE defaults new `LoadBalancer` Services to Premium tier, and a
Standard-tier reserved IP must be told explicitly to match via
`cloud.google.com/network-tier: "Standard"` on the Service. The first attempt at this used
`networking.gke.io/network-tier`, which is silently accepted (no error, shows up in
`kubectl get svc ... -o yaml`) but has **no effect on provisioning** — a wrong annotation key
that looks like it worked is worse than one that errors immediately.

## Phase 17 — CI/CD to GKE

- [ ] Extend the Phase 10 workflow: build → push to Artifact Registry → `kubectl apply`
- [ ] Authenticate with **Workload Identity Federation**, not a long-lived service-account
      key. Never commit a key file.
- [ ] Tag images with the git SHA, never `latest` — `latest` makes rollbacks guesswork
- [ ] Document the rollback: `kubectl rollout undo`

**Exit:** a push to `main` lands in GKE with no manual step, and a rollback is one command.

## Phase 18 — Cost control and teardown

The phase that decides whether this project costs $3/month or $45/month.

- [ ] `deploy/gcp/up.sh` and `down.sh` — recreate/delete the cluster, stop/start the VM
- [ ] Scale the node pool to zero when idle; **stop** (not delete) the data VM so its disks
      and data survive
- [ ] Verify the budget alert actually fires, using a throwaway $1 threshold
- [ ] README section: exact cost, what is running, and how to bring it up for an interview

**Exit:** `down.sh` leaves nothing billable but the disks, and `up.sh` restores a working
public URL from cold.

---

# Part C — Optional

## Phase 19 — `legacy-adapter`

Batch/flat-file order ingest that parses legacy-style **fixed-width** records into
`OrderPlaced` events consumed by the modern services. Directly relevant to mainframe
modernization roles and almost absent from comparable portfolio projects — this is the
phase most likely to differentiate the project for the roles being targeted.

- [ ] Fixed-width record layout plus a parser with copybook-style field definitions
- [ ] Spring Batch or a scheduled reader; reject-file handling for malformed records
- [ ] Emits the same `OrderPlaced` event as the REST path, so both paths converge

---

## Resume discipline

Claim a capability **only after it is implemented and tested.** Interviewers ask about
everything on a résumé. Until Phases 4-5 land, Saga / Outbox / Idempotency / DLQ do not go
on it. Until Part B lands, neither do GKE / Secret Manager / CI-CD-to-Kubernetes.

Frame bullets as problems solved, not technologies used:

> Decoupled order and inventory processing using Kafka-based asynchronous events, enabling
> inventory failures to be retried independently without blocking order ingestion.

not

> Implemented Kafka for communication between microservices.
