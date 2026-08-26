# Agent.md — complete project context

> **Read this first in any new session.** This is the single source of truth for *what this
> project is, where it stands, and what will bite you.* `CLAUDE.md` is a thin pointer to
> this file.
>
> The phased plan and its exit criteria live in [`plan.md`](plan.md). Keep the two separate:
> **Agent.md = state and context. plan.md = what to do next.**
>
> Original design conversation: `../Order & Inventory Platform.pdf` (257 pages, outside the
> repo). Text-extractable with `pdftotext -layout` from Git Bash.

---

## 0. Update protocol — read before you finish any task

Every task that changes architecture, status, or a gotcha **must** update this file in the
same commit. Specifically:

1. Tick the box in `plan.md` and record the outcome in **§10 Change log** here (newest
   first, dated).
2. Update **§5 Implementation status** — it must never describe code that no longer exists.
3. If you hit a trap that cost you time, add it to **§8 Traps and gotchas**. That section is
   the highest-value part of this file.
4. If a decision was made, add it to **§7 Locked decisions** with the *why*, not just the what.
5. **Update [`docs/INTERVIEW-GUIDE.md`](docs/INTERVIEW-GUIDE.md).** Move anything just built
   out of its "NOT built yet" list, add the new hard problem in the
   problem → what breaks → what I did shape, and refresh the test counts and verified
   numbers with **real measurements only**. That file is what turns this work into offers,
   and an overstated one is worse than none — Karthik will repeat it in a room with someone
   who asks a second question.

Do not let this file drift. A stale Agent.md is worse than no Agent.md, because the next
session will trust it.

---

## 1. What this is

A **distributed order-fulfillment platform** built as event-driven Spring Boot
microservices, deployed to **GKE** with MySQL and Kafka on a Compute Engine VM.

- Repo directory: `InventoryPlatformManagement`
- Intended presentation name: **"Fault-Tolerant Order Fulfillment Platform"**
  (names an engineering problem, not a CRUD domain)

**This is a portfolio project, not production software.** The owner (Karthik) currently
works in mainframe technology and is targeting **Java backend SDE-1** and **mainframe
modernization** roles.

That changes the optimization target. The goal is **defensible interview talking points**,
not feature count or delivery speed. Concretely:

- Prefer solving one distributed-systems problem correctly (idempotency, transactional
  outbox, optimistic locking) over adding another CRUD endpoint.
- Every technology must answer "what problem does this solve?" Never add tech for the
  résumé alone — a technology-demo project reads worse than a focused one.
- **Never claim a capability that isn't implemented and tested.** Interviewers ask about
  everything on a résumé.

---

## 2. Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot **4.1.0** |
| Cloud libs | Spring Cloud **2025.1.2** |
| Persistence | MySQL + Spring Data JPA |
| Messaging | Apache Kafka **(KRaft, wired end-to-end)** |
| Discovery | Eureka — **local only**, dropped on GKE (see §7) |
| Config | Spring Cloud Config, git backend |
| Gateway | Spring Cloud Gateway **MVC** (`spring-cloud-starter-gateway-server-webmvc`) |
| Resilience | Resilience4j — timeout, retry, circuit breaker, fallback on the payment call |
| Build | Maven (wrapper per module; there is **no parent aggregator pom**) |
| Boilerplate | Lombok |
| Container | Docker Desktop 29.7.2. **Seven multi-stage Dockerfiles**; `docker compose up` runs the whole platform |
| Orchestration | GKE Standard, zonal, Spot nodes *(not yet created)* |
| Secrets | GCP Secret Manager via CSI driver *(not yet created)* |

The Gateway is the **MVC/Servlet** variant, not WebFlux/Netty. The source PDF assumed
WebFlux; the actual pom does not. Don't reintroduce reactive types on that assumption.

Every module is an independent Maven project with its own wrapper. There is no root
aggregator pom, so "build everything" means looping over modules.

---

## 3. Services and ports

| Service | Port | Role | Config client? | Deployed to GKE? |
|---|---|---|---|---|
| `discovery-service` | 8761 | Eureka server (`spring.application.name: discovery-server`) | no, by design | **no** |
| `config-service` | 8888 | Spring Cloud Config Server | n/a — it *is* the server | yes |
| `api-gateway-service` | 8080 | Single entry point; the only public surface | yes | yes, public |
| `order-service` | 8081 | Order lifecycle, `order_db` | yes | yes |
| `inventory-service` | 8082 | Stock + reservations, `inventory_db` | yes | yes |
| `notification-service` | 8083 | Consumes both result topics, mock email. **No database** | yes | yes |
| `payment-service` | 8084 | Mocked, synchronous, idempotent by orderId. **No database** | yes | yes |
| MySQL | 3306 | **both** schemas on one instance | — | on the data VM |
| Kafka | 9092 in-network / **29092 on the host** | `order.placed`, `inventory.reserved`, `inventory.failed`, `order.confirmed`, `order.cancelled` | — | on the data VM |

Note `discovery-service`'s application name is `discovery-server`, which does **not** match
its directory name. That is deliberate but easy to trip over.

---

## 4. Repo layout

```
InventoryPlatformManagement/          <- git root
├── Agent.md                          <- this file: context and state
├── plan.md                           <- the phased plan (canonical)
├── docs/
│   └── INTERVIEW-GUIDE.md         <- how to explain this project; keep in step
├── CLAUDE.md                         <- thin pointer to this file
├── README.md                         <- still a stub (Phase 11)
├── .gitignore
├── .gitmodules
├── docker-compose.yml             <- Kafka + 2x MySQL. Verified 2026-08-26
├── .run/                             <- SHARED IntelliJ run configs (committed on purpose)
│   └── config-service.run.xml
├── config-repo/                      <- GIT SUBMODULE (see §6)
│   ├── api-gateway-service.yaml
│   ├── inventory-service.yaml
│   ├── notification-service.yaml
│   └── order-service.yaml
├── config-service/
├── discovery-service/
├── api-gateway-service/
├── order-service/
├── inventory-service/
├── notification-service/
└── payment-service/
```

Planned but not yet created: `deploy/k8s/`, `deploy/gcp/`, `docs/decisions/`.

Java packages are `com.demo.<service_name>` with **underscores**
(e.g. `com.demo.inventory_service`), not the `com.karthik.*` used in the source PDF.
Entities live in a `models` package, not `entity`.

---

## 5. Implementation status

*Update this section with every change.*

### `inventory-service` — Phases 1 and 3 complete
- `models/Product` — `id`, `sku` (unique), `name`
- `models/Inventory` — `id`, `productId`, `warehouseId`, `availableQuantity`,
  `reservedQuantity`, `@Version version`
- **`models/Reservation`** — `orderId` (String/UUID), `productId`, `warehouseId`, `quantity`,
  `status`, `createdAt`, `updatedAt`, with a unique constraint on
  `(order_id, product_id, warehouse_id)`. That constraint **is** the idempotency key.
- `models/ReservationStatus` — `RESERVED` / `RELEASED` / `CONFIRMED`; the latter two terminal
- Repositories: `ProductRepository.findBySku`,
  `InventoryRepository.findByProductIdAndWarehouseId`, `ReservationRepository`
  (`findByOrderIdAndProductIdAndWarehouseId`, `findByOrderId`, `findByOrderIdAndStatus`)
- DTOs: `ProductRequest`, `InventoryRequest`, `InventoryResponse`,
  `ReserveInventoryRequest` (now carries `orderId`), `OrderReferenceRequest`
- **Two service beans, deliberately** — `InventoryService` (public API + bounded
  optimistic-lock retry, *not* transactional) delegating to `InventoryTxService`
  (`@Transactional` units). Retry must wrap the whole transaction, and a same-bean call
  would bypass the Spring proxy and silently run with no transaction at all.
- Reserve is **idempotent**: an existing reservation for the same
  (orderId, productId, warehouseId) is a no-op that reports current stock.
  Release and confirm are **order-scoped** — they act on every line the order holds.
- `GlobalExceptionHandler`: `InventoryNotFound` / `ProductNotFound` → 404;
  `InsufficientInventory` / `DuplicateSku` / `ReservationConflict` /
  `ObjectOptimisticLockingFailure` → 409; `MethodArgumentNotValid` → 400 with per-field
  errors; `IllegalArgument` → 400
- Endpoints under `/api`: `POST /products`, `POST /inventory`, `GET /inventory`,
  `POST /inventory/reserve`, `POST /inventory/release`, `POST /inventory/confirm`
- **23 tests, all green.** Business rules (Mockito), retry policy (Mockito), HTTP contract
  (`@WebMvcTest`), and real concurrency against H2 (`@DataJpaTest`).
- **Phase 3:** `events/` holds `OrderPlacedEvent` (consumed), `InventoryReservedEvent` and
  `InventoryFailedEvent` (produced), plus `KafkaTopics` — all duplicated from order-service.
  `kafka/OrderPlacedListener` reserves every line, and on failure **releases whatever it
  already reserved** before publishing `InventoryFailed`. `config/KafkaConfig` declares the
  two result topics and the `StringJsonMessageConverter`.
- **27 tests** (23 unit + 4 integration under `./mvnw verify`).

### `order-service` — Phases 2 and 3 complete
- `models/Order` — surrogate `id`, plus **`orderId` (String UUID, unique)** as the public
  cross-service identifier. Table is **`orders`**, not `order` (see §8). `customerId`,
  `status`, `totalAmount` (`BigDecimal(19,2)`), `items`, `createdAt`, `updatedAt`.
  `addItem()` maintains both sides of the association; `transitionTo()` enforces the
  lifecycle.
- `models/OrderItem` — `productId`, **`warehouseId`**, `quantity`, `unitPrice`, `lineTotal()`.
  `warehouseId` lives here because inventory keys reservations on
  (orderId, productId, warehouseId); without it the Phase 3 `OrderPlaced` event could not
  reserve anything.
- `models/OrderStatus` — `PENDING` → `INVENTORY_RESERVED` → `CONFIRMED`, with
  `INVENTORY_FAILED` and `CANCELLED`. **Legal transitions are encoded** in
  `canTransitionTo` / `allowedNextStates` / `isTerminal`, so a late or duplicated Kafka
  event cannot revive a terminal order.
- `OrderRepository` — `findByOrderId`, `findAllBy(Pageable)`, both with
  `@EntityGraph("items")` to avoid an N+1 on the response
- DTOs: `CreateOrderRequest`, `OrderItemRequest`, `OrderResponse`, `OrderItemResponse`
  (records). **`OrderResponse` deliberately omits the surrogate `id`.**
- `OrderMapper` — hand-written; mints the UUID **server-side** and sums money in
  `BigDecimal`
- `OrderService` — `createOrder` (PENDING), `getOrder`, `listOrders(Pageable)`,
  `transitionOrder`. Makes **no** call to inventory: checking stock synchronously would
  reintroduce exactly the coupling the event-driven design removes.
- `OrderController` — `POST /api/orders` (201), `GET /api/orders/{orderId}`,
  `GET /api/orders?page=&size=` (paged, size capped at 100)
- `GlobalExceptionHandler` — `OrderNotFound` → 404,
  `InvalidOrderStateTransition` → 409, `@Valid` → 400 with nested field paths
- **Phase 3:** `events/` holds `OrderPlacedEvent` (produced), `InventoryReservedEvent` and
  `InventoryFailedEvent` (consumed), plus `KafkaTopics`. `kafka/OrderEventPublisher` sends
  `OrderPlaced` keyed by `orderId`; `kafka/InventoryResultListener` applies the answer and
  tolerates redelivery. **`OrderService` no longer holds the transaction** — `OrderTxService`
  does, so the event is published only after commit.
- **32 tests** (28 unit + 4 integration under `./mvnw verify`), plus verified end-to-end
  against a real broker and real MySQL.
- Kafka and Resilience4j are on the classpath but still unused — Phases 3 and 8.

### `notification-service` — Phase 6 complete
- `events/` — `InventoryReservedEvent`, `InventoryFailedEvent`, `KafkaTopics` (third copy)
- `notification/` — `Notification` record, `NotificationSender` interface,
  `LoggingNotificationSender`
- `kafka/InventoryEventListener` — one listener per result topic, own consumer group
- `config/KafkaConfig` — converter, string template, error handler, DLT topics only
- **No JPA, no MySQL, no Lombok on the classpath.** The first two make "knows nothing about
  other services' data" a build-time guarantee rather than a convention.
- Does **not** deduplicate; a redelivery sends a second email, and a test asserts it.
- 6 tests (3 unit + 3 IT)

### `api-gateway-service` — Phase 7 complete
- Routes in **config**, not code: `/api/orders/**` → order-service,
  `/api/products/**` + `/api/inventory/**` → inventory-service
- **Two route sets by profile** — `lb://` (Eureka) by default,
  Service DNS under `k8s`, both served by Config Server
- `filter/CorrelationIdFilter` — honour-or-generate `X-Correlation-Id`, MDC, forwarded
  downstream through an `HttpServletRequestWrapper`, echoed to the caller, request logged
- `exception/GatewayExceptionHandler` — unreachable downstream → **503 JSON**, not 500/HTML
- Property namespace is `spring.cloud.gateway.server.webmvc.*` (MVC gateway, not reactive)
- 6 tests (1 context + 5 IT against a stub HTTP server)

### `discovery-service`, `config-service` — working
See §8 for the Config Server's working-directory constraint.

### Not started
Kafka wiring, Saga, Outbox, idempotency, DLT, `payment-service`, Resilience4j wiring,
Docker, Kubernetes, GCP, structured logging, **any real tests** (only the generated
`contextLoads`), CI, OpenAPI.

### Verified working as of 2026-08-21
All four config clients boot on ports they know *only* from Config Server, report
`{"status":"UP"}` on `/actuator/health`, and register in Eureka as `ORDER-SERVICE`,
`INVENTORY-SERVICE`, `NOTIFICATION-SERVICE`, `API-GATEWAY-SERVICE`.

---

## 6. `config-repo` is a git submodule

`config-repo` is a **separate git repository**, registered as a submodule of this one.

- Submodule URL: `https://github.com/Ishita2803/order-platform-config-repo.git`
- Tracked branch: **`master`**, not `main`

Rationale: configuration has its own lifecycle and audit trail, and Config Server reads it
through a real git backend — the whole point of using git rather than the `native`
filesystem backend.

### Config Server reads the *remote*, not your local checkout

Since 2026-08-25 `config-service` points at the **remote URL**, not `file:../config-repo`.
See §8.3 for why the local path could never work outside this machine.

Two consequences, both good:

- **A plain `git clone` is now enough to run the platform.** `--recurse-submodules` is
  convenient (it gives you the YAMLs to edit) but no longer required for startup, because
  Config Server clones the config repository itself at boot.
- **Config Server needs network at startup.** `clone-on-start: true` makes an unreachable
  repo a loud boot failure rather than a confusing 500 on the first client request.
  Override the URI with `CONFIG_REPO_URI` for an offline or air-gapped run.

### Changing configuration

```bash
cd config-repo
# edit yaml
git add . && git commit -m "..." && git push    # push is what makes it live
cd ..
git add config-repo && git commit -m "Bump config-repo"   # moves the gitlink
```

**Committing is no longer enough — you must push.** Config Server reads the remote, so an
unpushed commit has no effect. The gitlink bump in the parent repo is bookkeeping: it
records which config revision this code was tested against, but Config Server ignores it
and always serves the tip of `master`.

---

## 7. Locked decisions

| Decision | Choice | Why |
|---|---|---|
| Kafka event classes | **Duplicated per service** | Keeps services independently deployable. A shared event jar couples deployments and is a known anti-pattern interviewers probe. |
| `payment-service` | **In scope, mocked** | A pure-Kafka design has *no* synchronous inter-service call, so Resilience4j would be pure decoration. Payment gives the circuit breaker a real target and makes Saga compensation meaningful. |
| Phases 1 + 2 | Built in parallel | Inventory hardening and the Order domain are independent, and both must land before Kafka. |
| Config backend | Git backend, relative URI | No machine-specific absolute paths in version control. |
| `config-repo` | Real submodule | Preserves the git-backed-config story; a bare gitlink left clones broken. |
| Cloud sequencing | **All local first**, then GCP | Chosen 2026-08-25 over deploying a thin slice early. Cost: no live demo URL until Phase 16, and Part B is one large push. Mitigated by making Phase 9 a real containerisation phase. |
| MySQL on GCP | **Compute Engine VM**, not Cloud SQL | Cheaper, and the owner wanted to manage it. Cloud SQL's cheapest tier costs more than the rest of the deployment combined. |
| Kafka on GCP | **Same VM as MySQL** | Cheapest option that still teaches something. Fits an `e2-medium` (4 GB) with tuned heaps, keeps stateful workloads off Kubernetes, and reuses the Compose file. Single broker = no real HA; be honest about that. |
| Eureka on GKE | **Dropped** | Kubernetes Service DNS already does discovery, so Eureka on GKE is redundant infrastructure. Kept for local runs; a `k8s` profile switches the gateway to DNS routes. Saves a pod and is the answer a Kubernetes-literate interviewer wants. |
| Config Server on GKE | **Kept** | Git-backed config with an audit trail is a genuine capability, and it is where Secret Manager values land. |
| Secrets on GKE | **Secret Manager via CSI driver → env vars** | Framework-version-independent. `spring-cloud-gcp-starter-secretmanager` would be more elegant but has **no confirmed Spring Boot 4.1 release** — do not assume one exists. |
| Public access | Static IP + HTTP first; TLS optional | A GCLB forwarding rule is roughly $18/mo, more than both VMs. Start cheap, document the upgrade. |
| GKE shape | Zonal cluster, 2 × `e2-medium` **Spot** | Cost. The cluster management fee is covered by the GKE free-tier credit; Spot nodes cut node cost by ~70 % in exchange for preemption. |

---

## 8. Traps and gotchas

Ordered roughly by how much time each one costs when forgotten.

1. **`default-label: master`** in `config-service` is required. Spring Cloud Config defaults
   to `main`; `config-repo` uses `master`. Removing it breaks everything with
   *"No such label: main"*.
   Related: **the two repositories use different default branches.** The parent repo is on
   **`main`**; `config-repo` is on **`master`**. Confirm which repo you are in before any
   branch or push operation.
2. **Config filenames must equal `spring.application.name`.** The gateway registers as
   `api-gateway-service`, so the file must be `api-gateway-service.yaml`. A mismatch fails
   **silently**, returning 200 with an *empty* `propertySources` — not an error.
3. **Never point Config Server at `file:../config-repo`.** It works in this working copy
   and fails in every clone. Spring Cloud Config's git backend requires `.git` to be a real
   **directory**; a submodule's `.git` in a fresh clone is a redirect *file*
   (`gitdir: ../.git/modules/config-repo`), which the backend rejects outright:

   ```
   java.lang.IllegalStateException: No .git directory at file:../config-repo
   ```

   This working copy only survives it because `config-repo/.git` is still a real directory,
   left over from the pre-submodule nested-repo layout. Fixed 2026-08-25 by pointing
   `config-service` at the **remote** `https://github.com/Ishita2803/order-platform-config-repo.git`.
   Because the URI is no longer relative, **the old working-directory constraint is gone** —
   `.run/config-service.run.xml` still pins it, but nothing depends on that any more.
4. **Config Server reads pushed state only.** Now that the URI is remote, a config change
   must be committed **and pushed** before Config Server sees it — committing alone is no
   longer enough. See §6.
5. **`orderId` is a `String` (UUID), never a `Long`.** It is a cross-service identifier that
   travels inside Kafka events, so it must not be order-service's auto-increment surrogate
   key. Phase 2's `Order` entity must therefore expose a UUID business identifier, whatever
   it uses as its own primary key. Decided 2026-08-25 when `Reservation` was built.
6. **`ORDER` is a reserved word in SQL**, so `Order` is mapped to `@Table(name = "orders")`.
   Letting Hibernate derive the name emits `create table order (...)`, which MySQL rejects
   with a syntax error pointing at the wrong token entirely. `OrderPersistenceTest` is what
   would catch a regression here.
7. **Declaring any `KafkaTemplate` bean switches off Boot's.** The auto-configuration is
   `@ConditionalOnMissingBean(KafkaTemplate.class)` — a **raw-type** condition, so a
   `KafkaTemplate<String, String>` bean removes the `KafkaTemplate<String, Object>` one and
   every injection point for it fails. Both services therefore declare **both** templates
   explicitly. Two related traps: Lombok does not copy `@Qualifier` onto generated
   constructors (write the constructor by hand), and Boot's bean is named `kafkaTemplate`,
   so by-name fallback silently picks it.
8. **Anything already serialized must not go through `JsonSerializer`.** Outbox payloads and
   dead-lettered records are already JSON strings; re-encoding them yields a quoted, escaped
   JSON *string* the consumer cannot parse. Use the `stringKafkaTemplate`. Build it from the
   auto-configured `ProducerFactory`'s own config, or it silently loses the timeout settings.
9. **`ExponentialBackOffWithMaxRetries` no longer exists.** Spring Framework 7 folded it
   into `ExponentialBackOff`, which now has `setMaxAttempts(long)` and built-in
   `setJitter(long)`. Every Spring Kafka retry/DLT tutorial online still imports the old
   class from `org.springframework.util.backoff`, and it will not resolve.
10. **Kafka in Docker: never write `0.0.0.0` in a listener.** Use `PLAINTEXT://:9092`.
    With `0.0.0.0` the broker refuses to start — *"advertised.listeners cannot use the
    nonroutable meta-address 0.0.0.0"* — because when `advertised.listeners` is absent Kafka
    derives it from `listeners`.
11. **Set `KAFKA_LOG_DIRS` or the named volume is decoration.** The broker otherwise writes
    to `/tmp/kraft-combined-logs`; the volume mounts, stays empty, and the data is lost on
    recreate. Verify with `docker exec kafka grep log.dirs /opt/kafka/config/server.properties`.
12. **A MySQL healthcheck must force TCP.** `mysqladmin ping -h localhost` uses the unix
    **socket**, and the entrypoint's init runs a temporary server on `port: 0` — socket
    only, no TCP — before restarting the real one. The socket check passes, Docker reports
    healthy, and a client connecting in that window gets "Communications link failure".
    Use `--protocol=TCP -h 127.0.0.1 -P 3306`.
13. **Gateway properties are `spring.cloud.gateway.server.webmvc.*`.** This project uses the
    MVC/Servlet gateway. Every tutorial using `spring.cloud.gateway.routes` is for the
    reactive one, and configuring that here fails silently — no routes, no error.
14. **Kafka in Compose needs TWO listeners.** A client reconnects to the *advertised*
    address, so containers need `kafka:9092` and host processes need `localhost:29092`. One
    advertised address cannot serve both. **The host port is 29092, not 9092.**
15. **`extract --layers --launcher` produces no jar.** The entrypoint is
    `java org.springframework.boot.loader.launch.JarLauncher` from the extracted directory,
    not `java -jar app.jar`.
16. **`MaxRAMPercentage` does nothing without a container memory limit.** The JVM otherwise
    sees the whole host. Note `free` inside a container still reports host RAM; the JVM
    reads the cgroup limit, so trust `-XX:+PrintFlagsFinal`, not `free`.
17. **A MySQL container killed mid-init leaves a corrupt data directory.** *"Cannot create
    redo log files because data files are corrupt"* — no restart recovers it, the volume
    must be deleted.
18. **After recreating a service, the gateway can 404 briefly** until its Eureka registry
    cache refreshes. Registry propagation, not a routing bug.
19. **Put a Resilience4j `fallbackMethod` on the OUTERMOST annotation.** The aspects nest
    as `Retry(CircuitBreaker(call))`, so a fallback on `@CircuitBreaker` fires on the first
    failure and returns normally — Retry then sees a success and never retries. The retry is
    silently dead while the configuration still looks correct. Only a test that counts
    requests arriving at the server catches this.
20. **`@Lob` on a String needs an explicit `length`.** Without one Hibernate picks MySQL's
    smallest text tier — `TINYTEXT`, 255 bytes — and inserts fail with *"Data truncation:
    Data too long"*. Use `@Column(length = 1_000_000)` for `LONGTEXT`/`MEDIUMTEXT`. H2 does
    not reproduce this, so unit tests cannot catch it; `ddl-auto: update` will not widen an
    existing column either, so the table must be dropped or altered by hand.
21. **MySQL's cold init takes ~85s on this machine**, so a `start_period` below that makes a
    perfectly healthy container report `unhealthy` while it is merely initialising.
22. **The Windows MySQL service owns port 3306**, so Compose cannot bind it. Host ports are
    overridable: `ORDER_DB_PORT=3316 docker compose up -d`.
23. **Never let the Kafka producer stamp Java type headers.** Event classes are duplicated
   per service, so `spring.json.add.type.headers` must stay `false`. Left on, the producer
   writes `__TypeId__: com.demo.order_service.events.OrderPlacedEvent`, and the consumer —
   which only has `com.demo.inventory_service.events.OrderPlacedEvent` — fails to
   deserialize every single message. Consumers use `StringDeserializer` plus a
   `StringJsonMessageConverter` bean, which takes the target type from the
   `@KafkaListener` method parameter instead.
24. **`*IT` classes do not run under `./mvnw test`.** Surefire only picks up `*Test`,
   `Test*`, `*Tests`, `*TestCase`. The integration tests are named `*IT` and run under
   **`./mvnw verify`** via failsafe. A green `test` run therefore proves *less* than it
   looks — check which plugin actually executed.
25. **Tests must set `spring.kafka.admin.auto-create: false`.** Otherwise every
   `@SpringBootTest` spends ~45 s watching `KafkaAdmin` retry the `NewTopic` beans against
   a broker that is not running. It is not a failure, just a silent 10x slowdown.
26. **Running Kafka on Windows without Docker:** the `bin/windows/*.bat` scripts die with
    *"The input line is too long"* — the expanded classpath exceeds cmd's 8191-char limit
    under any deep path. Bypass them and let the JVM expand the wildcard itself:
    `java -cp "<kafka>/libs/*" kafka.Kafka <config>` (and `kafka.tools.StorageTool` to
    format KRaft storage first). Also avoid passing `-Dlog4j.configuration=` through
    PowerShell, which mangles it.
27. **Spring Boot 4 moved the test-slice annotations.** They are no longer under
   `org.springframework.boot.test.autoconfigure.*`:
   - `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`
   - `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`

   Every tutorial online still shows the Boot 3 packages, so the import will look right and
   fail to resolve. `@MockitoBean` (not `@MockBean`) is likewise the current spelling.
28. **DB passwords are `${MYSQL_PASSWORD:root}` placeholders — keep them that way.**
   `config-repo/order-service.yaml` and `inventory-service.yaml` previously carried
   `password: "root"` in plaintext. Fixed 2026-08-25, and `config-repo`'s history was
   squashed to one commit before its first push, so the literal credential never reached
   GitHub at all. The `:root` default means local runs still need no env var. **Do not
   reintroduce a literal password** — `config-repo` is public, and history is forever once
   pushed. Phase 14 replaces the default with Secret Manager.
29. **`java` on PATH is Java 8.** Use JDK 21: `JAVA_HOME=C:\Users\Karthik\.jdks\ms-21.0.12`.
   `mvn` is not on PATH at all — use each module's `./mvnw`.
30. **Both DBs share `localhost:3306`** when running against the host MySQL. Compose splits
    them into genuinely separate instances (verified 2026-08-26). The design called for
    3306/3307. Fine locally;
   Compose and the GCP data VM will split the schemas properly. Be honest about this.
31. **Eureka registration lags roughly 40 s after boot** (client replication interval). An
    empty `/eureka/apps` immediately after startup is normal, not a failure.
32. Maven needs network on first run — don't pass `-o`.
33. `.idea/` is intentionally untracked; `.run/` is intentionally tracked.
34. **Part B toolchain is half-installed.** Docker Desktop 29.7.2 is present and working;
    `gcloud`, `kubectl`, `helm` and `terraform` are not. Phase 12 installs those.
    Note Docker's CLI is only on the **machine** PATH — a shell started before the install
    will not see it. Use the full path
    `C:\Program Files\Docker\Docker
esourcesin\docker.exe` or start a new shell.

---

## 9. Startup order and verification (local)

```
1. config-service       :8888   <- must be first; clients hard-fail without it
2. discovery-service    :8761
3. order-service        :8081   \
4. inventory-service    :8082    | any order
5. notification-service :8083    |
6. api-gateway-service  :8080   /
```

All four clients use **non-optional** `spring.config.import`, so a missing Config Server is
a loud startup failure by design, not a silent fallback to stale local defaults.

```bash
curl http://localhost:8888/order-service/default   # 200 + populated propertySources
curl http://localhost:8761/eureka/apps             # registered services (allow ~40s)
curl http://localhost:8083/actuator/health         # {"status":"UP"}
```

A 200 with **empty** `propertySources` means the filename doesn't match
`spring.application.name` — not that the server is broken. See §8.2.

---

## 10. Change log

Newest first. Add an entry for every meaningful change.

### 2026-08-26 — Phase 9 complete: the whole platform runs from containers
- **Seven multi-stage Dockerfiles.** JDK builder → JRE runtime, non-root uid 10001, layered
  jar extraction with `-Djarmode=tools ... extract --layers --launcher`. Note that produces
  an exploded directory, **not** an `app.jar`, so the entrypoint is
  `org.springframework.boot.loader.launch.JarLauncher`.
- A BuildKit cache mount on `/root/.m2` is shared across all seven builds, so Spring Boot is
  resolved once, not seven times. Cold build 8.3 min; images 538–655 MB.
- **`MaxRAMPercentage` needed `mem_limit` to mean anything.** Without a container limit the
  JVM sees the whole host and the flag is decoration — exactly the kind of thing this project
  criticises elsewhere. With limits: 768m → 576 MB heap, 512m → 384 MB, verified.
- **Kafka now has two listeners.** `kafka:9092` for containers, `localhost:29092` for the
  host. One advertised address cannot serve both, because the client reconnects to whatever
  address the broker hands back. **The host port changed from 9092 to 29092.**
- Last hard-coded `localhost` removed: `spring.config.import` is now
  `configserver:${CONFIG_SERVER_URL:http://localhost:8888}`.
- `depends_on: condition: service_healthy` replaces sleeps — which only works because the
  MySQL healthcheck was fixed in Phase 7 to check TCP rather than the socket.
- Added `.env.example` (committed) and a gitignored `.env`.
- **Verified from images alone:** happy path plus both failure paths through the gateway,
  `javac` absent from runtime images, all services running as `appuser`.

### 2026-08-26 — Phase 8 complete: payment-service and Resilience4j
- New `payment-service` module (port 8084): mocked, **idempotent by orderId**, switchable at
  runtime between APPROVE / DECLINE / SLOW so the failure paths are demonstrable live.
  5 tests, including concurrent retries producing exactly one payment.
- `PaymentClient` in order-service: HTTP read timeout, retry with backoff, circuit breaker,
  fallback. 5 integration tests against a stub that can be told to misbehave.
- **The Saga now closes.** Approved → `CONFIRMED` → `OrderConfirmed` → inventory confirms
  (stock ships, does not return to available). Declined or unavailable → `CANCELLED` →
  `OrderCancelled` → inventory releases. Both settlement events go **through the outbox**, so
  compensation survives inventory-service being down.
- **Two real bugs, neither visible to a passing unit test:**
  1. **The fallback silently disabled the retry.** Declared on `@CircuitBreaker`, which
     Resilience4j nests *inside* `@Retry`, so the first failure hit the fallback, returned
     normally, and Retry saw success. Only a test counting requests at the server found it.
  2. **`outbox_event.payload` was TINYTEXT (255 bytes).** `@Lob` on a String with no length
     makes Hibernate choose MySQL's smallest text tier. Latent since Phase 5 — OrderPlaced
     payloads were ~200 chars and fit by luck. H2 does not reproduce the mapping.
- 94 tests across five modules.
- **The Phase 4 gap happened for real.** Three pre-fix orders are permanently stuck at
  `INVENTORY_RESERVED` holding 6 units: `processed_event` committed, the settlement
  transaction then failed, and redelivery correctly skipped the event. A reconciliation job
  over stale `INVENTORY_RESERVED` orders is now a **required** item, not a nice-to-have.

### 2026-08-26 — Phase 7 complete: the API Gateway
- Routes `/api/orders/**` to order-service and `/api/products/**`, `/api/inventory/**` to
  inventory-service. **6 tests** (1 context + 5 IT against a real stub HTTP server).
- **Routes are profile-switched, in config, from day one.** `api-gateway-service.yaml` uses
  `lb://` via Eureka; `api-gateway-service-k8s.yaml` uses Service DNS with the Eureka client
  disabled. Both confirmed being served by Config Server. Phase 15 flips a profile rather
  than editing Java.
- `CorrelationIdFilter` honours an incoming `X-Correlation-Id` or mints one, puts it in the
  MDC, **wraps the request so it is forwarded downstream**, returns it to the caller, and
  logs method/path/status/duration.
- `GatewayExceptionHandler`: an unreachable downstream is **503 with JSON** carrying the
  correlation id — not a 500, and not Spring's HTML error page.
- **Found a real healthcheck bug while running the full stack.** Both order- and
  inventory-service failed at startup with "Communications link failure" even though Compose
  reported MySQL `healthy`. Cause: `mysqladmin ping -h localhost` uses the **unix socket**,
  and the entrypoint's data-directory init runs a *temporary* server on `port: 0` — socket
  only, no TCP listener — before shutting it down and starting the real one. The healthcheck
  passed against the temporary server. Fixed with `--protocol=TCP -h 127.0.0.1 -P 3306`, so
  healthy now means reachable by the port clients actually use. This would have made Phase
  9's `depends_on: condition: service_healthy` unreliable in a way that looked like a flake.
- **Limitation, recorded not hidden:** the correlation id is forwarded (proved in the IT
  against a stub that inspects the header) but the downstream services do not yet *log* it,
  so the trail stops at the gateway until Phase 9.

### 2026-08-26 — Phase 6 complete: notification-service
- Consumes `inventory.reserved` / `inventory.failed` and emits a mock email. **6 tests**
  (3 unit + 3 integration against an embedded broker).
- **The "no database" constraint is enforced by the build, not by discipline.** The module
  has no JPA and no MySQL dependency, so it cannot query another service's tables even by
  mistake. Confirmed at runtime too: zero Hikari/Hibernate/JDBC lines in its log.
- Also has **no Lombok** — plain constructors and `LoggerFactory` here, unlike the others.
- `NotificationSender` interface + `LoggingNotificationSender`, so tests assert on the
  notification rather than on log text, and a real provider is a new implementation later.
- **Its own consumer group**, so order-service and notification-service both see every
  event. Sharing a group id would make them compete for messages.
- `KafkaConfig` declares **only** the DLT topics, not the two it consumes — inventory-service
  owns those, and a consumer that redeclares its source topics becomes a second owner of the
  schema. It also declares a single `KafkaTemplate<String, String>`, which switches off
  Boot's auto-configured one; harmless *here* because nothing publishes domain objects,
  which is exactly the trap that broke the other two services in Phase 5.
- **Duplicate notifications are possible and deliberately not prevented**, with a test
  pinning that behaviour. No database means nowhere to record what was sent; an in-memory
  set would be per-instance and lost on restart while looking like a fix.
- Verified end-to-end on the Compose stack with all four services: confirmation and failure
  emails both produced, the failure one carrying the reason from the event.
- Raised the MySQL `start_period` to 300s. Cold init measured ~85s on an idle machine but
  over 235s on a busy one; it self-corrects, but would break `depends_on: service_healthy`.

### 2026-08-26 — Docker installed; the Compose file finally ran, and was wrong
- Docker Desktop 29.7.2 is installed and working. The blocker was the **Virtual Machine
  Platform** Windows feature being off — the CPU had virtualization enabled all along, and
  WSL2 with Ubuntu was already present.
- **Running `docker-compose.yml` for the first time found three defects.** It had been
  reviewed and looked fine; none of these are visible by reading it:
  1. **Kafka would not start.** `KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092` →
     *"advertised.listeners cannot use the nonroutable meta-address 0.0.0.0"*. Fixed by
     omitting the host entirely: `PLAINTEXT://:9092`.
  2. **The Kafka named volume held nothing.** Without `KAFKA_LOG_DIRS` the broker writes to
     `/tmp/kraft-combined-logs`. The volume existed, was mounted, and was empty — persistence
     that silently is not persistence, and only discovered on container recreation.
  3. **MySQL reported `unhealthy` while starting perfectly normally.** A cold data-directory
     init measured ~85s here; `start_period` was 30s. Raised to 150s.
- Host ports are now overridable (`KAFKA_PORT`, `ORDER_DB_PORT`, `INVENTORY_DB_PORT`) because
  the Windows MySQL service already owns 3306.
- **Verified properly, not just "containers are up":** all three healthy in ~50s; each MySQL
  has only its own schema, with the 384 MB buffer pool applied; Kafka round-trips a message
  and `advertised.listeners`/`log.dirs` are confirmed in the generated `server.properties`.
- **Then ran the whole platform against it.** config-, inventory- and order-service pointed
  at the containerised infrastructure purely through the env overrides added in Phase 3
  (`ORDER_DB_URL`, `INVENTORY_DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`) — no code or config change.
  `POST /api/orders` → `INVENTORY_RESERVED`; in the containerised databases: `outbox_event`
  `PUBLISHED` with `attempts=0`, one `RESERVED` reservation, one `OrderPlaced`
  `processed_event`, stock 6/4 at `version=1`. Torn down with `docker compose down -v`.

### 2026-08-26 — Phase 5 complete: the dual-write window is closed
- **`outbox_event` written in the same transaction as the order.** `createOrder` no longer
  talks to Kafka, so there is no longer a moment where the order exists and its event does
  not. `OutboxPublisher` drains the table on a schedule and can retry indefinitely, because
  the consumers have been idempotent since Phase 4 — the outbox and idempotent consumers are
  two halves of one design, not two features.
- `OrderEventPublisher` deleted; `OrderService.createOrder` is now a one-line delegate.
- **Found a real serialization bug.** Outbox payloads are already-serialized JSON strings and
  the default `JsonSerializer` re-encoded them into quoted, escaped JSON — inventory could
  never have parsed it. Fixed with a string-valued template. Caught only because the test
  asserted on payload fields rather than on delivery.
- **Found a second, nastier one.** Adding that template switched off Boot's auto-configured
  `KafkaTemplate` entirely: the condition is `@ConditionalOnMissingBean(KafkaTemplate.class)`,
  a **raw-type** check that ignores generics. Both templates are now declared explicitly.
  Related: Lombok does not copy `@Qualifier` onto generated constructors, and Boot's bean is
  literally named `kafkaTemplate`, so by-name fallback would have injected the wrong one —
  `OutboxPublisher` has a hand-written constructor for exactly this reason.
- Deriving the string template from the auto-configured `ProducerFactory`, rather than
  building its config from scratch, made it inherit the timeout settings; the broker-down
  test went from **242s to 6s**.
- 71 tests green (42 order-service, 29 inventory-service).

### 2026-08-26 — Phase 4 complete: exactly-once effect, and a dead-letter topic
- **`processed_event` table in both services**, `eventId` as primary key, written **in the
  same transaction as the work it describes**. Before the work, a crash loses it; after, a
  crash repeats it; together, at-least-once delivery becomes exactly-once *effect*.
- **`reserveOrder` replaced the per-line reserve for the Kafka path.** It checks every line
  before applying any, in one transaction, so a short line means nothing was reserved and
  there is nothing to compensate for. Phase 3 reserved line-by-line then released on
  failure, which left a window where stock was held for an order already doomed.
- Idempotency is now belt and braces: the `processed_event` row keyed by `eventId`, and the
  unique constraint on (orderId, productId, warehouseId) underneath it. The second survives
  the publisher regenerating an `eventId`.
- **DLT wired in both services** — 3 retries, exponential backoff with jitter, then
  `<topic>.DLT`. Unparseable payloads are registered as **not retryable** so they go straight
  to the DLT instead of burning six seconds first.
- A **vacuous assertion was found and fixed**: the partial-order test asserted
  `allSatisfy(RELEASED)` on what is now an empty list, so it passed while proving nothing.
  It now asserts explicit emptiness.
- 63 tests green (34 order-service, 29 inventory-service). Verified: a valid message queued
  behind a poison one still gets processed — the failure this phase exists to prevent.

### 2026-08-26 — Phase 3 complete: the first async flow
- `POST /api/orders` now drives a real round trip. Order publishes `OrderPlaced`; inventory
  reserves and answers `InventoryReserved` / `InventoryFailed`; order applies the result.
- **Event records are duplicated per service** and **type headers are disabled on the
  producer**. Those two decisions are linked: with duplicated classes the producer's
  `__TypeId__` would name a class the consumer cannot load, so the wire format is plain
  JSON and the target type comes from the `@KafkaListener` method signature via
  `StringJsonMessageConverter`.
- Events are **keyed by `orderId`** so everything about one order stays on one partition
  and therefore stays ordered.
- **Order service split** into `OrderService` (publishes) and `OrderTxService`
  (`@Transactional`), so publishing happens strictly after commit. The remaining dual-write
  window is documented in `OrderService.createOrder` and closed by Phase 5's outbox.
- Inventory **compensates partial orders**: if line 3 has no stock, lines 1 and 2 are
  released before `InventoryFailed` goes out, so nothing is leaked.
- Duplicate `InventoryReserved` is tolerated: the listener swallows the lifecycle guard's
  exception rather than rethrowing, which would retry forever and stall the partition. This
  is duplicate *tolerance*, not idempotency — Phase 4 adds `processed_events`.
- **Deviation:** a reserved order stops at `INVENTORY_RESERVED`, not `CONFIRMED`. `CONFIRMED`
  means paid, and payment arrives in Phase 8. See `plan.md` Phase 3.
- Added `maven-failsafe-plugin` to both services: `*IT` classes are integration tests and
  run under `./mvnw verify`, not `./mvnw test`.
- **Verified against a real broker and real MySQL**, not only tests — see `plan.md` Phase 3
  for the observed values. Kafka 3.9 was downloaded, run standalone, used, and removed;
  nothing was left installed.
- `docker-compose.yml` added but **never executed** (no Docker on this machine).

### 2026-08-25 — Moved to the `Ishita2803` GitHub account
- Both repositories now live under **`github.com/Ishita2803/`**, and all commits are authored
  by `Ishita Bhargava <68944355+Ishita2803@users.noreply.github.com>`. History in both repos
  was rewritten so authorship is consistent throughout rather than split across two accounts.
- The GitHub **noreply** address is used deliberately: `config-repo` is public and git history
  is permanent, so a personal address committed once is exposed forever.
- Identity is set **repo-locally**, not globally. `C:\Users\Karthik\.gitconfig` still says
  `Karthik0770 <iyerkarthik07@gmail.com>`, so every other project on this machine is
  unaffected. **A fresh clone will not inherit this** — re-run the two
  `git config --local user.*` commands after cloning, or commits silently revert to the
  global identity.
- **Authoring and pushing are two different identities, and both had to change.** Setting
  `user.email` only changes who the commit *says* wrote it; the push still used Windows
  Credential Manager's cached `Karthik0770` token and was rejected with
  `Permission to Ishita2803/... denied to Karthik0770`. Fixed by pointing
  `credential.https://github.com.helper` at the GitHub CLI **in each repo's local config**:

  ```
  git config --local --add 'credential.https://github.com.helper' ''
  git config --local --add 'credential.https://github.com.helper' '!"C:/Program Files/GitHub CLI/gh.exe" auth git-credential'
  ```

  The empty first value clears the inherited `manager` helper for this URL only. Doing this
  globally, or via `gh auth setup-git`, would silently make **every** repo on the machine
  push as whichever account `gh` last logged into. `gh` holds both accounts; `gh auth switch`
  moves between them.
- Updated in the same pass: `.gitmodules`, `CONFIG_REPO_URI` in `config-service`, and the URL
  references in this file and `plan.md`.
- The old repositories under `Karthik0770` were **left in place**, not deleted. If only one
  copy should be discoverable, they need removing or making private by hand.
- Known cosmetic wrinkle: the parent repo's pre-migration commits still record gitlinks
  pointing at `config-repo` SHAs that no longer exist, because both histories were rewritten
  independently. Checking out an old parent commit and running
  `git submodule update` would fail. Current `HEAD` is correct, which is what matters.

### 2026-08-25 — Phase 2 complete: the Order domain
- `Order` / `OrderItem` / `OrderStatus` with repository, mapper, service, controller,
  validation and a per-service exception handler. **25 tests, all green.**
- **`orderId` is a server-minted UUID**, separate from the surrogate `id`, matching the
  constraint Phase 1 imposed. `OrderResponse` never exposes the surrogate key, so this
  service stays free to change it.
- **Legal state transitions are encoded on the enum** (`canTransitionTo`, `isTerminal`)
  rather than left to each caller. Terminal states accept nothing, which is what stops a
  late or duplicated Kafka event in Phase 3 from reviving a cancelled order.
- `OrderItem` carries `warehouseId`, because inventory keys reservations on
  (orderId, productId, warehouseId). Without it the Phase 3 `OrderPlaced` event would not
  contain enough information to reserve anything.
- `createOrder` makes **no call to inventory-service**. Checking stock synchronously would
  make accepting an order depend on another service being up — the exact coupling the
  event-driven design exists to remove.
- Money is `BigDecimal(19,2)` throughout, never `double`.
- `GET /api/orders` is paged with the size capped at 100. An unbounded `findAll` is fine on
  a demo and a way to exhaust heap on a real table.
- **Verified end-to-end against real MySQL**, not just H2: `POST /api/orders` → 201, row
  present in `order_db.orders` with `status=PENDING` and `total_amount=26.25`, both
  `order_item` rows carrying the correct foreign key; `GET` by id → 200; negative line
  quantity → 400 naming `items[0].quantity`; unknown id → 404.
- Deliberately **deferred**: no `@Version` on `Order`. Concurrent status updates only become
  possible once Kafka consumers exist, so the optimistic-locking machinery lands in Phase 4
  with tests, rather than sitting here untested.

### 2026-08-25 — Phase 1 complete: order-scoped reservations, and proof they hold
- **Added the `Reservation` entity**, the highest-leverage change in the project. Reserve and
  release are now keyed by `orderId` instead of being quantity-only, which is what makes an
  idempotent Kafka consumer and Saga compensation possible at all.
- Idempotency is enforced by a **database unique constraint** on
  `(order_id, product_id, warehouse_id)`, not by an application-level "does it exist?" check
  — two concurrent consumers can both pass such a check.
- **Split the service into two beans.** `InventoryService` holds a bounded
  (4-attempt, jittered-backoff) optimistic-lock retry; `InventoryTxService` holds the
  `@Transactional` units. The retry has to wrap the whole transaction, and Spring's proxy
  means a same-bean call would have silently run with no transaction at all.
- Added `confirmByOrderId` so `CONFIRMED` is a real state, not a dead enum value: it drops
  reserved stock **without** returning it to available, which is the difference between a
  shipment and a cancellation.
- Replaced the bare `RuntimeException`s with `ProductNotFoundException` and
  `DuplicateSkuException`; added `ReservationConflictException`. Exhausted retries and
  optimistic-lock failures are now **409, not 500** — contention is a normal outcome, not a
  server fault. `@Valid` failures are 400 with per-field messages.
- **23 tests, all green**, including a real-concurrency test on H2 (Mockito cannot lose a
  race, so it cannot prove this property).
- **Verified the concurrency test can actually fail.** Temporarily removed `@Version` and
  re-ran it: all 10 threads reported success, implying 20 units reserved, while only **2**
  were actually deducted — a textbook lost update. The test caught it and failed; `@Version`
  was then restored and the suite re-run green. A concurrency test that has never been seen
  to fail is not evidence of anything.
- Added H2 as a **test-scoped** dependency, and `src/test/resources/application.yaml` which
  shadows the main config so tests no longer require Config Server and MySQL to be running.
  `contextLoads` genuinely passes now rather than being aspirational.

### 2026-08-25 — Phase 0.5 complete: the platform now actually runs from a clone
- **Found and fixed a bug that made the project unusable for anyone who cloned it.**
  Verifying Phase 0.5's exit criterion against a throwaway
  `git clone --recurse-submodules` showed `config-service` failing with
  `IllegalStateException: No .git directory at file:../config-repo`. Spring Cloud Config's
  git backend requires `.git` to be a real directory; in a clone, a submodule's `.git` is a
  redirect *file*. The local working copy hid this because its `config-repo/.git` is still a
  real directory left over from the pre-submodule layout.
  - This also invalidated a claim the 2026-08-21 entry made below: that verification
    "confirmed JGit follows the submodule's `.git`-file redirect." It never did — the test
    ran against a working copy where no redirect existed.
  - It would additionally have broken Phase 15, where the GKE pod gets a clone.
- **Fix:** `config-service` now reads the **remote** repository
  (`CONFIG_REPO_URI`, defaulting to the GitHub URL) with `clone-on-start: true`. This is how
  Config Server is used in production, works from any clone, and needs no change in GKE.
  Side effect: the working-directory constraint that `.run/config-service.run.xml` existed
  to enforce is now irrelevant.
- Created `github.com/Ishita2803/order-platform-config-repo` (public) and pushed
  `config-repo`'s squashed `master`.
- **Verified from a fresh clone:** `/actuator/health` → `UP`; all four of order (8081),
  inventory (8082), notification (8083) and api-gateway (8080) return 200 with populated
  `propertySources`, sourced from the GitHub URL at revision `5829919`; the dead
  `api-gateway` name still correctly returns empty `propertySources`.

### 2026-08-25 — Phase 0.5: Phase 0's work committed at last
- **Committed Phase 0.** It had been sitting entirely uncommitted since 2026-08-21 — the
  config-server fix, `.run/`, `.gitignore`, the `.idea/` untracking and the new docs were
  all one careless `git checkout` away from being lost.
- **Removed a credential before it could ever be published.** `config-repo`'s
  `order-service.yaml` and `inventory-service.yaml` carried `password: "root"`, and the
  repo was about to be pushed public so the submodule would resolve for anyone cloning.
  Replaced with `${MYSQL_PASSWORD:root}` — the default keeps local runs byte-for-byte
  identical — then **squashed `config-repo`'s 9 commits into one**, because scrubbing the
  working file leaves the credential in history. Verified the resulting tree hash was
  unchanged (`1a75e83`), so content was provably untouched and only history collapsed.
  The 8 discarded commits were `initial commit` plus five near-identical
  "update order service config" messages — no audit value lost.
- Installed the GitHub CLI (2.98.0) to create the `config-repo` GitHub repository.
- **Corrected a factual error in the new docs:** `plan.md` Phase 17 said CI/CD triggers on a
  push to `master`. The parent repo's default branch is **`main`**; only `config-repo` uses
  `master`. Added the mismatch to §8 as a trap in its own right.

### 2026-08-25 — Planning for GCP; docs restructured
- Extracted the full 257-page source PDF with `pdftotext -layout` and confirmed the
  conversation **ends at the inventory reserve/release implementation**, with no cloud or
  deployment content. The prior `docs/ROADMAP.md` was a faithful distillation of it; the
  entire GCP layer is new scope, not something recovered from the PDF.
- **Restructured the docs to remove duplicate sources of truth.** There were previously two
  files each claiming to be authoritative (`CLAUDE.md` for context, `docs/ROADMAP.md` for
  the plan). Now: `Agent.md` owns context and state, `plan.md` owns the plan, and `CLAUDE.md`
  is a pointer to both. `docs/ROADMAP.md` was folded into `plan.md` and deleted.
- Added Part B (Phases 12-18) covering GCP foundation, the MySQL + Kafka data VM, Secret
  Manager, the GKE cluster, public access, CI/CD and cost teardown.
- Decisions taken: local-first sequencing · Kafka co-located with MySQL on one `e2-medium`
  VM · Eureka dropped on GKE but Config Server kept · Secret Manager through the CSI driver
  rather than a Spring Cloud GCP starter · zonal cluster with Spot nodes for cost.
- Added Phase 0.5 to `plan.md`: **all of Phase 0's work is still uncommitted**, and the
  `config-repo` submodule URL points at a GitHub repository that does not exist.
- Recorded a cost model: roughly $45/month always-on, roughly $3/month if torn down
  between demos. Teardown scripts are therefore a planned deliverable, not an afterthought.

### 2026-08-21 — Phase 0: unblock and tidy
- **Fixed the platform-wide startup blocker.** `config-service` pointed at
  `file:F:/SpringProjects/OrderService/config-repo`, which does not exist — the project had
  been moved into `InventoryPlatformManagement/` and the absolute path went stale. Since
  clients use non-optional `spring.config.import`, `order-service` and `inventory-service`
  could not start at all. Replaced with `file:${CONFIG_REPO_PATH:../config-repo}`.
- Added `.run/config-service.run.xml` pinning the working directory, so the relative path
  resolves correctly from IntelliJ as well as the CLI.
- Renamed `config-repo/api-gateway.yaml` → `api-gateway-service.yaml`. The old name never
  matched `spring.application.name`, so Config Server returned empty `propertySources` and
  the file was dead weight.
- Converted `api-gateway-service` and `notification-service` into real config clients.
- **Registered `config-repo` as a proper git submodule.** It had been recorded as a bare
  gitlink (mode `160000`) with no `.gitmodules`, so anyone cloning from GitHub got an empty
  `config-repo/` and no way to populate it.
- Added a root `.gitignore`; untracked `.idea/`.
- **Verified end-to-end** (JDK 21, MySQL on 3306, launched via `mvnw` per module):
  - `GET /order-service/default` → 200, `version` = the new config-repo commit, path
    resolved inside `InventoryPlatformManagement/config-repo` — confirming both that the
    relative URI works and that JGit follows the submodule's `.git`-file redirect.
  - All four clients → 200 with correct ports (8081/8082/8083/8080).
  - The old `api-gateway` name → 200 but **empty** `propertySources`, confirming that file
    had been dead.
  - All four services boot, report `UP`, and register in Eureka.
