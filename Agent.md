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
| Orchestration | GKE Standard, zonal, Spot nodes — `order-platform-cluster` running, Workload Identity enabled |
| Secrets | GCP Secret Manager, two secrets + scoped-access GSA created *(CSI driver mount + KSA↔GSA binding still open — no workload deployed yet)* |

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
| `auth-service` (Part D) | 8085 | Login, JWT issuance, bcrypt hashing. `auth_db` | yes | yes |
| `vendor-service` (Part D) | 8086 | Vendor onboarding, vendor's product catalog. `vendor_db` | yes | yes |
| `customer-service` (Part D) | 8087 | Customer onboarding, addresses, end users. `customer_db` | yes | yes |
| `carrier-service` (Part D) | 8088 | Carrier onboarding, weight-tier surcharges. `carrier_db` | yes | yes |
| `otel-collector` (Part D) | 4317 (grpc) / 4318 (http) | Receives OTLP, exports to Cloud Trace via `googlecloud` exporter | n/a | yes |
| MySQL | 3306 | **three** schemas on one instance (`order_db`, `auth_db`, `customer_db`) + a second instance with **three** schemas (`inventory_db`, `vendor_db`, `carrier_db`, 3307) | — | on the data VM |
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
├── README.md                         <- full: architecture, flows, quickstart, measured perf
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

`deploy/gcp/` holds the GCP-facing scripts: `00-bootstrap.sh` (Phase 12, one-time),
`docker-compose.data-vm.yml` (Phase 13, the data VM's Kafka + 2× MySQL), and `up.sh`/`down.sh`
(Phase 18, run before/after every session — see §7 and §10). `deploy/k8s/` holds the per-service
`Deployment`/`Service` manifests (Phase 15).

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
14. **Testcontainers 2.x renamed every module artifact.** `org.testcontainers:mysql` does
    not exist at 2.0.5 — it is `testcontainers-mysql`, likewise
    `testcontainers-junit-jupiter`. The `testcontainers-bom` must also be imported: the Boot
    parent defines `testcontainers.version` but does not manage the artifacts. Every
    tutorial online still shows the 1.x coordinates.
15. **Give a MySQL Testcontainer a tmpfs data directory.** On-disk init takes 85-235s here
    and Testcontainers connects during the entrypoint's temporary server, failing with
    "Communications link failure" after a long timeout.
    `.withTmpFs(Map.of("/var/lib/mysql", "rw"))` cuts it to seconds; tests need no
    durability.
16. **Kafka in Compose needs TWO listeners.** A client reconnects to the *advertised*
    address, so containers need `kafka:9092` and host processes need `localhost:29092`. One
    advertised address cannot serve both. **The host port is 29092, not 9092.**
17. **`extract --layers --launcher` produces no jar.** The entrypoint is
    `java org.springframework.boot.loader.launch.JarLauncher` from the extracted directory,
    not `java -jar app.jar`.
18. **`MaxRAMPercentage` does nothing without a container memory limit.** The JVM otherwise
    sees the whole host. Note `free` inside a container still reports host RAM; the JVM
    reads the cgroup limit, so trust `-XX:+PrintFlagsFinal`, not `free`.
19. **A MySQL container killed mid-init leaves a corrupt data directory.** *"Cannot create
    redo log files because data files are corrupt"* — no restart recovers it, the volume
    must be deleted.
20. **After recreating a service, the gateway can 404 briefly** until its Eureka registry
    cache refreshes. Registry propagation, not a routing bug.
21. **Put a Resilience4j `fallbackMethod` on the OUTERMOST annotation.** The aspects nest
    as `Retry(CircuitBreaker(call))`, so a fallback on `@CircuitBreaker` fires on the first
    failure and returns normally — Retry then sees a success and never retries. The retry is
    silently dead while the configuration still looks correct. Only a test that counts
    requests arriving at the server catches this.
22. **`@Lob` on a String needs an explicit `length`.** Without one Hibernate picks MySQL's
    smallest text tier — `TINYTEXT`, 255 bytes — and inserts fail with *"Data truncation:
    Data too long"*. Use `@Column(length = 1_000_000)` for `LONGTEXT`/`MEDIUMTEXT`. H2 does
    not reproduce this, so unit tests cannot catch it; `ddl-auto: update` will not widen an
    existing column either, so the table must be dropped or altered by hand.
23. **MySQL's cold init takes ~85s on this machine**, so a `start_period` below that makes a
    perfectly healthy container report `unhealthy` while it is merely initialising.
24. **The Windows MySQL service owns port 3306**, so Compose cannot bind it. Host ports are
    overridable: `ORDER_DB_PORT=3316 docker compose up -d`.
25. **Never let the Kafka producer stamp Java type headers.** Event classes are duplicated
   per service, so `spring.json.add.type.headers` must stay `false`. Left on, the producer
   writes `__TypeId__: com.demo.order_service.events.OrderPlacedEvent`, and the consumer —
   which only has `com.demo.inventory_service.events.OrderPlacedEvent` — fails to
   deserialize every single message. Consumers use `StringDeserializer` plus a
   `StringJsonMessageConverter` bean, which takes the target type from the
   `@KafkaListener` method parameter instead.
26. **`*IT` classes do not run under `./mvnw test`.** Surefire only picks up `*Test`,
   `Test*`, `*Tests`, `*TestCase`. The integration tests are named `*IT` and run under
   **`./mvnw verify`** via failsafe. A green `test` run therefore proves *less* than it
   looks — check which plugin actually executed.
27. **Tests must set `spring.kafka.admin.auto-create: false`.** Otherwise every
   `@SpringBootTest` spends ~45 s watching `KafkaAdmin` retry the `NewTopic` beans against
   a broker that is not running. It is not a failure, just a silent 10x slowdown.
28. **Running Kafka on Windows without Docker:** the `bin/windows/*.bat` scripts die with
    *"The input line is too long"* — the expanded classpath exceeds cmd's 8191-char limit
    under any deep path. Bypass them and let the JVM expand the wildcard itself:
    `java -cp "<kafka>/libs/*" kafka.Kafka <config>` (and `kafka.tools.StorageTool` to
    format KRaft storage first). Also avoid passing `-Dlog4j.configuration=` through
    PowerShell, which mangles it.
29. **Spring Boot 4 moved the test-slice annotations.** They are no longer under
   `org.springframework.boot.test.autoconfigure.*`:
   - `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`
   - `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`

   Every tutorial online still shows the Boot 3 packages, so the import will look right and
   fail to resolve. `@MockitoBean` (not `@MockBean`) is likewise the current spelling.
30. **DB passwords are `${MYSQL_PASSWORD:root}` placeholders — keep them that way.**
   `config-repo/order-service.yaml` and `inventory-service.yaml` previously carried
   `password: "root"` in plaintext. Fixed 2026-08-25, and `config-repo`'s history was
   squashed to one commit before its first push, so the literal credential never reached
   GitHub at all. The `:root` default means local runs still need no env var. **Do not
   reintroduce a literal password** — `config-repo` is public, and history is forever once
   pushed. Phase 14 replaces the default with Secret Manager.
31. **`java` on PATH is Java 8.** Use JDK 21: `JAVA_HOME=C:\Users\Karthik\.jdks\ms-21.0.12`.
   `mvn` is not on PATH at all — use each module's `./mvnw`.
32. **Both DBs share `localhost:3306`** when running against the host MySQL. Compose splits
    them into genuinely separate instances (verified 2026-08-26). The design called for
    3306/3307. Fine locally;
   Compose and the GCP data VM will split the schemas properly. Be honest about this.
33. **Eureka registration lags roughly 40 s after boot** (client replication interval). An
    empty `/eureka/apps` immediately after startup is normal, not a failure.
34. Maven needs network on first run — don't pass `-o`.
35. `.idea/` is intentionally untracked; `.run/` is intentionally tracked.
36. **Part B toolchain is half-installed.** Docker Desktop 29.7.2 is present and working;
    `gcloud`, `kubectl`, `helm` and `terraform` are not. Phase 12 installs those.
    Note Docker's CLI is only on the **machine** PATH — a shell started before the install
    will not see it. Use the full path
    `C:\Program Files\Docker\Docker
esourcesin\docker.exe` or start a new shell.

---

37. **A commit costs ~190 ms on Docker Desktop's virtual disk.** With
    `innodb_flush_log_at_trx_commit=1` and `sync_binlog=1`, every commit fsyncs twice. This
    is *the* throughput bottleneck of the local stack — not the code, not Kafka, not the
    optimistic lock. Measured directly with 20 autocommit inserts inside the container. Both
    variables are dynamic, so a benchmark can relax them with `SET GLOBAL` and no restart;
    restore them afterwards, and never relax them on anything real without deciding the
    durability loss is acceptable.
38. **`docker compose ps` does not show containers stuck in `Created`.** If an earlier `up`
    was interrupted while waiting on a `depends_on: service_healthy` condition, Compose may
    have created a container without ever starting it. It simply does not appear in the
    default listing, so the stack looks like it has fewer services rather than broken ones.
    Use `docker compose ps -a`.
39. **Kafka has one partition per topic.** Consumer groups therefore cannot scale beyond one
    active consumer, and adding a second service instance changes throughput by nothing.
    Fine for a single-machine stack; the first thing to change when scaling out. Partition by
    `orderId` to keep per-order ordering.

40. **Commit `mvnw` as executable or CI cannot run it.** Windows has no executable bit, so a
    wrapper added from Windows lands in git as mode `100644` and every Linux runner fails with
    `./mvnw: Permission denied` and exit code **126**. It cannot reproduce locally, because
    Windows ignores the bit. Fix: `git update-index --chmod=+x <service>/mvnw`, then verify
    with `git ls-files -s '*/mvnw'` — the mode must read `100755`.
41. **Never point a BuildKit cache mount at all of `/root/.m2`.** That path contains
    `wrapper/dists`, where the Maven Wrapper installs Maven itself, and `docker compose build`
    builds in parallel. Several `mvnw` processes then race to install into one shared mount
    and `mv` fails with *"unable to remove target: Directory not empty"*, leaving `mvnw` to
    exit **127**. Cache `/root/.m2/repository` with `sharing=locked` instead: the repository
    is the part worth sharing, and the wrapper dist installs harmlessly per service. Warm
    local caches hide this completely.
42. **GitHub Actions push runs register 2–6 minutes late on this repo.** Do not conclude that
    a push failed to trigger CI, and do not manually dispatch while waiting — combined with
    `cancel-in-progress`, a late push run carrying an *older* commit will cancel a dispatch
    run carrying the fix, which looks exactly like the fix failing.

43. **A dead-letter topic that nothing reads is a stock leak.** DLTs stop a poison message
    blocking a partition, and then silently keep whatever lands in them for ever. This project
    ran with write-only DLTs from Phase 4 to Phase 11.5 and lost 9 units of stock to it.
    Something must drain them. Use a **scheduled drain, not a `@KafkaListener` on the DLT** —
    a listener re-consumes milliseconds after the failure, while the cause is still present,
    and hot-loops.
44. **Idempotency cannot detect unfinished work.** `processed_event` guarantees work is not
    done twice and says nothing about work that was never completed. If the marker ever
    commits without the work, it becomes a lie that suppresses every future attempt. The only
    cure is reconciliation over **state**, because the message layer has already reached its
    correct terminal decision.
45. **A bounded optimistic-lock retry drops work under sustained single-row contention.**
    200 orders against one product exhausted a 4-attempt budget on 9 confirmations. Raising
    the budget only moves the threshold. For operations that are pure relative adjustments —
    `reserved -= n` — a single atomic `UPDATE` has no contention failure mode at all and is
    the right tool; optimistic locking is for check-then-act, like `reserve`.
46. **Cross-service drift is invisible from inside the message flow.** No error, no alert, no
    failed request — order-service said CONFIRMED and inventory said RESERVED, and both were
    internally consistent. It surfaces only by comparing state across services and asking
    whether both numbers can be true. Worth doing deliberately after any load test.
47. **`KafkaProperties` relocated in Spring Boot 4** — no longer in
    `org.springframework.boot.autoconfigure.kafka`. If one value is needed, read the property
    with `@Value("${spring.kafka.bootstrap-servers}")` rather than chasing the new package.
48. **Locally built images live under the `order-platform/` namespace, not the bare service
    name.** `docker compose build` tags them `order-platform/order-service:latest`, so
    `docker tag order-service:latest ...` fails with *"No such image"*. Check `docker images`
    for the real local tag before pushing to Artifact Registry.
49. **A Compose service with no `restart:` policy does not come back when its VM restarts.**
    Stopping a GCE VM cleanly stops every container on it (`Exited (0)`, not a crash), and
    Docker only revives a stopped container on the daemon's next boot if that container's own
    restart policy says to. The default is to do nothing. `deploy/gcp/docker-compose.data-vm.yml`
    ran for two days with no policy set before a real `down.sh`/`up.sh` cycle exposed it —
    "the VM is running" and "the workload on it is running" are different claims, and only
    stopping and starting the VM for real distinguishes them. Fixed with
    `restart: unless-stopped` on all three services.
50. **GKE node-pool autoscaling fights a resize to zero.** If `minNodeCount` is 1 (or any
    nonzero value), `gcloud container clusters resize --num-nodes 0` gets silently undone —
    the autoscaler's whole job is to maintain at least the minimum. No error; the node count
    just doesn't drop. Disable autoscaling first (`--no-enable-autoscaling`), resize, then
    re-enable it on the way back up.
51. **The SSH user from OS Login does not own files created by a different account.** A `scp`
    straight to `~/data-vm/docker-compose.yml` failed with "permission denied" because the
    file was created (Phase 13) by a different Google account than the one OS Login mapped
    this session to. `scp` to `/tmp`, then `sudo cp` into place — the standard workaround, not
    specific to this project.
52. **`management.otlp.tracing.*` is deprecated at *error* level as of Spring Boot 4.0** and
    silently does not bind — no warning at startup, the property is just ignored, and tracing
    config that looks correct has zero effect. Confirmed by reading
    `spring-boot-micrometer-tracing-opentelemetry`'s own `spring-configuration-metadata.json`
    after real requests through a fully-wired `otel-collector` produced zero spans in Cloud
    Trace. The real property is `management.opentelemetry.tracing.export.otlp.endpoint`
    (default transport HTTP on port 4318, full `/v1/traces` path — not gRPC on 4317). The
    metrics-export equivalent (`management.otlp.metrics.export.enabled`) is *not* deprecated;
    only the tracing namespace moved.
53. **XML comments cannot contain a literal `--` anywhere in the body**, not just at the
    closing `-->`. A pom.xml comment written in this project's usual prose style ("only
    needs X -- deliberately not Y") fails the whole POM to parse with a cryptic
    `Non-parseable POM` / `ModelParseException` pointing at an unrelated later line. Use a
    comma or semicolon instead of `--` inside any XML comment.
54. **CI's `kubectl set image` never applies `deploy/k8s/configmap.yaml`.** The pipeline
    only patches each Deployment's image tag; a new key added to the shared ConfigMap
    (Phase D7's `INVENTORY_BASE_URL`) sits in the repo doing nothing until someone runs
    `kubectl apply -f deploy/k8s/configmap.yaml` by hand, **and** the consuming
    Deployment is restarted (`kubectl rollout restart`) — env vars from `envFrom:
    configMapRef` are only read at pod start, never live-reloaded. Symptom: a client falls
    back to its `@Value` default (`http://localhost:8082`) and fails with `Connection
    refused`, which looks like a code bug, not a stale ConfigMap.
55. **`ddl-auto: update` never relaxes an existing column's constraint.** Phase D7 dropped
    `nullable = false` from `OrderItem.productId`/`warehouseId` (a sales-order's
    backordered row has neither, until it ships) — Hibernate happily added the new
    `sku_number` column but left the two existing ones `NOT NULL` in the real database,
    because `update` only adds what's missing, it never alters what's already there. A
    fresh database (a new clone's local Compose, or a wiped MySQL) never hits this: the
    table is created from the entity's *current* constraints the first time. Only an
    already-populated database drifts from what the entity now says — found by a live
    500 on the very first partial-fulfillment order, fixed with a manual `ALTER TABLE
    order_item MODIFY COLUMN warehouse_id VARCHAR(64) NULL` (and the same for
    `product_id`) against the real order_db.

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

### 2026-09-03 — Phase D7 complete: sales-order fulfillment search, partial ship, auto-backorder
- **`SalesOrderService`** (new, `order-service`): resolves a sales order's fulfillment
  **synchronously**, at creation time — a deliberate divergence from the legacy demo
  flow's async `OrderPlaced`/`InventoryReserved` Saga. "Never reject, return the order
  with whatever it can currently ship" means the caller needs the real `shipQuantity` in
  *this* response, not eventually via Kafka. `OrderService.createOrder` routes to it only
  when a request's items carry a `skuNumber`; the legacy productId/warehouseId flow is
  completely untouched, including all 62 of its existing tests.
- **`inventory-service`'s fulfillment search** (`InventoryTxService.fulfillSalesOrderLine`,
  exposed via `POST /api/inventory/fulfillment`, internal-only): the requested region's
  own warehouse first, then every other warehouse in registration order, greedily
  reserving whatever's available from each until the requested quantity is met or
  warehouses run out. Reuses the existing optimistic-lock retry and `Reservation`
  idempotency machinery (Phase 1/4) rather than inventing new concurrency handling — the
  only new code is *which* warehouses to try and in what order.
- **One requested (sku, quantity) line can persist as several `OrderItem` rows**: one per
  warehouse it actually shipped from, plus a `warehouseId = null` row for whatever
  couldn't be filled. `OrderItem.productId`/`warehouseId` had to become nullable for the
  first time since Phase 2 — see Agent.md trap #55 for the real `ddl-auto: update` gap
  that caused live on the very first partial order.
- **Shortfall auto-backorders** through the exact D6 purchase-order mechanism, now with a
  second `PurchaseOrderPurpose` (`BACKORDER` instead of `STOCKING`) — same vendor
  resolution, same outbox event, same mock-vendor fulfillment, placed against the same
  warehouse the search would have preferred.
- **Compensation on a late failure**: the inventory-service call happens inside
  `SalesOrderService.create`'s transaction; if persisting the order itself then fails
  (e.g. a DB error), the reservation inventory-service already made would be stranded.
  Caught with a try/catch that calls the existing `/api/inventory/release` endpoint —
  the same compensation Saga cancellation already relies on — before rethrowing.
- **A second real bug found and fixed before it could bite in production**:
  `OrderReconciliationService`'s stuck-order sweep queries every order sitting in
  `INVENTORY_RESERVED` past a timeout and either settles or cancels it. A D7 sales order
  reaches `INVENTORY_RESERVED` synchronously and then just *waits* there for Phase D8's
  billing (not built yet) — indistinguishable from a genuinely stalled legacy Saga to
  that query. Fixed by excluding `deliveryRegion IS NOT NULL` orders from
  `findStuckOrderIds`, so the sweep only ever acts on the legacy async flow it was built
  for.
- **Verified live, three scenarios against the real deployed system**: (1) a
  well-within-stock sales order ships in full, no backorder; (2) a request exceeding a
  warehouse's stock ships the available portion and auto-creates+auto-fulfills a
  `BACKORDER` purchase order for the rest within ~2 seconds, confirmed by re-querying
  stock (`availableQuantity` rose by exactly the backordered amount); (3) the legacy
  demo order flow, unchanged, still starts at `PENDING` and is unaffected by any of the
  above. New unit tests: 6 in `InventoryTxServiceTest` (full/partial/zero-stock/no-
  warehouse/unknown-sku/unknown-catalog-item), 7 in the new `SalesOrderServiceTest`
  (backorder decision logic, mixed-item rejection, missing-region rejection, release
  compensation) — full suites still green (order-service 69 tests, inventory-service 40).

### 2026-09-03 — Phase D6 complete: purchase orders, folded into order-service (not a new pod)
- **`PurchaseOrder`** added to `order-service` as a new aggregate — purchaseOrderId,
  vendorId, skuNumber, quantity, warehouseId, **purpose** (`STOCKING`/`BACKORDER`/`DIRECT`,
  only `STOCKING` used this phase), **status** (`PENDING`/`FULFILLED`, no `REJECTED` — the
  mock vendor always succeeds). Reuses the exact transactional-outbox shape Phase 4 built
  for sales orders, rather than standing up a separate `purchase-order-service` — same
  actor/lifecycle coupling reasoning that kept vendor/customer/carrier as separate
  services in the other direction.
- **`POST /api/purchase-orders`** (ADMIN only) resolves the sku's owning vendor via a sync
  call to vendor-service (new `VendorServiceClient` in order-service, same shape as
  inventory-service's D5 client), saves the PO, writes a `PurchaseOrderPlaced` outbox event.
- **`PurchaseOrderPlacedListener`** is the mock vendor — no real vendor system exists to
  call, so it consumes `PurchaseOrderPlaced` and immediately marks the PO fulfilled
  (idempotent via the existing `ProcessedEvent` table), writing `PurchaseOrderFulfilled`.
- **`inventory-service`'s `PurchaseOrderFulfilledListener`** consumes the fulfilled event,
  resolves `skuNumber → Product.id`, and calls the existing (Phase 1) `addInventory` —
  purely additive, the Phase 1 machinery itself is untouched.
- **`GET /api/purchase-orders`** (ADMIN + VENDOR) — ADMIN sees every PO, VENDOR sees only
  their own (`vendorId` from the JWT's business-id claim, never a client-supplied param).
- **Real integration bug found and fixed before it ever ran live**: inventory-service's own
  Phase-1 `Product` (id/sku/name) is a separate table from vendor-service's much richer
  `Product`, and nothing synced them — the fulfillment listener's `sku → Product.id`
  lookup would have thrown `ProductNotFoundException` on every purchase order for a sku
  nobody had priced *since this fix shipped*. Fixed by having `CatalogService.setSalePrice`
  (D5) also upsert inventory-service's own `Product` row for a never-seen sku, using
  `productName` fetched from vendor-service. **Caught the gap this fix does NOT close**
  live: a sku priced *before* this fix deployed (from D5's own verification) still had no
  Product row, so its first D6 purchase order genuinely failed with
  `ProductNotFoundException`, retried 3 times, and landed in `purchase.order.fulfilled.DLT`
  — exactly the DLT machinery Phase 9 built doing its job. Re-setting that sku's price
  (which now upserts the missing row) and placing a second purchase order fulfilled
  cleanly. This is expected, pre-existing-data behavior, not a flaw in the fix — new skus
  priced from here on never hit it.
- Gateway: `/api/purchase-orders/**` folded into order-service's existing route predicate
  (not a new route entry) — `POST` ADMIN-only, `GET` ADMIN+VENDOR.
- **Verified live**: admin placed a stocking PO against a real vendor sku; mock vendor
  fulfilled it in under 2 seconds; inventory-service's stock for that sku/warehouse
  increased by exactly the ordered quantity (`GET /api/inventory?productId=&warehouseId=`
  confirmed the real row); a vendor's own JWT saw only its own POs (empty list for an
  unrelated seeded vendor) and got 403 trying to create one. Full `inventory-service` and
  `order-service` test suites (`./mvnw verify`, Testcontainers MySQL/Kafka ITs included)
  re-run and green after the new code.

### 2026-09-02 — Phase D5 complete: warehouses & catalog pricing, added to inventory-service
- **`Warehouse`** (warehouseId, location, **region**) added to `inventory-service` —
  registered separately from `Inventory.warehouseId`, which has been a bare, unvalidated
  `String` since Phase 1. Adding this table does NOT retrofit a foreign key onto existing
  stock rows; Part A/B tests and demo data keep working unchanged. `region` is the field
  Phase D7's fulfillment search will match against a customer address's own region
  (Phase D3).
- **`CatalogItem`** (sku → **salePrice**, `vendorId`/`unitWeight` denormalized from
  vendor-service) — deliberately a different price from vendor-service's own
  `Product.costPrice`. What we pay the vendor and what we charge the customer are two
  different actors' decisions, so they live in two different services.
- **New `VendorServiceClient`** in `inventory-service` — the synchronous, internal,
  never-through-the-gateway call `CatalogService` makes when admin sets a sale price,
  fetching the sku's owning vendor and unit weight. Same shape as order-service's
  `PaymentClient`; no circuit breaker, since this call happens on price-setting (rare),
  not on every order the way payment's call does.
- Both mutation endpoints (`POST /api/inventory/warehouses`, `POST /api/inventory/catalog`)
  gated ADMIN-only at the gateway; every existing `/api/inventory/**`/`/api/products/**`
  route is untouched.
- **Verified live**: registered a warehouse with a region; set a sale price for a real
  vendor sku (auto-fetched vendor id + unit weight); re-setting the same sku updates the
  existing row rather than duplicating it; a sku unknown to any vendor returns
  `404 VENDOR_SKU_NOT_FOUND`. Full existing `inventory-service` test suite (Testcontainers
  MySQL ITs, Kafka ITs, concurrency tests) re-run and still green after the new entities
  and exception handlers were added to the existing `GlobalExceptionHandler`.

### 2026-09-02 — Phase D4 complete: carrier-service, and building the D8 lookup before D8 needs it
- **New `carrier-service`**: `Carrier` — **admin-supplied** `carrierCode`
  ("BLUEDART", "DTDC"), deliberately not server-minted like `Vendor.vendorId`/
  `Customer.customerNo`, because a carrier code is a real-world mnemonic the business
  already has — and `WeightTier` (upper limit → additional cost, ordered ascending).
- **`WeightTierService.surchargeFor(carrierCode, weightKg)`** written and unit-tested
  now, even though nothing calls it yet — the lookup Phase D8's invoicing needs:
  first tier the weight doesn't exceed, heaviest tier as a ceiling above that, zero for
  an unconfigured carrier. Same "prove the mechanism before the phase that depends on
  it" discipline as wiring tracing into every service in D1 before anything needed it.
- `GET /api/carrier/carriers/{code}` is the first Part D route with a deliberately
  *broad* role set (any authenticated role, not narrowly scoped) — a customer needs to
  see a carrier's weight tiers to choose a carrier at order time.
- `carrier_db` is a third schema on the existing `inventory-mysql` instance.
- **Verified live**: onboarded a carrier, added 3 real weight tiers, confirmed
  `surchargeFor` picks the right tier at an exact boundary and the heaviest tier above
  the top, confirmed a `CUSTOMER` token can read but not mutate, and cross-carrier
  tier mutation is blocked the same way cross-vendor product mutation was in D2.

### 2026-09-02 — Phase D3 complete: customer-service, a real access-control gap found and fixed
- **New `customer-service`**: `Customer` (server-minted `customerNo`, default
  billing/shipping addresses), `CustomerAddress` (every address beyond the two
  defaults), `EndUser` (one customer, many end users — "Vijay Sales" has end users
  "Vijay Sales Mumbai", "Vijay Sales Pune", each with their own shipping address).
  `Address` is a reused `@Embeddable` (line, city, **region**) — region is the zone
  code Phase D7's fulfillment search matches against a warehouse's own region
  (Phase D5), not decoration.
- Same onboarding shape as D2: `POST /api/customer/onboard` (ADMIN-only) creates the
  Customer row and calls auth-service's `/auth/credentials`. Every address/end-user
  operation scoped to the caller's own `customerNo` from `X-User-Business-Id`.
- **A real access-control gap, found before closing the phase, not after**:
  `GET /api/customer/end-users/by-end-user-id/{id}` (built for internal,
  service-to-service lookups — the same shape as vendor-service's product-by-sku
  route) had no ownership check and was reachable through the *public* gateway by any
  authenticated `CUSTOMER`, letting them read another customer's end-user name and
  shipping address by guessing an id. Fixed with a more-specific gateway route-role
  entry (`GET /api/customer/end-users/by-end-user-id/**` → `ADMIN` only, declared
  before the broader `CUSTOMER` pattern in `JwtAuthFilter`'s `LinkedHashMap` so it's
  checked first). Internal callers never go through the gateway, so this doesn't
  affect the route's actual intended use in Phase D7.
- **`customer_db`** is a third schema on the existing `order-mysql` instance (now
  hosting `order_db`, `auth_db`, `customer_db` — `inventory-mysql` hosts
  `inventory_db`, `vendor_db`).
- **Verified live**: onboarded two customers through the public gateway; second
  customer's own address/end-user lists correctly empty; second customer's attempt to
  delete the first's address returns `404` (deliberately, not `403` — same
  don't-confirm-existence reasoning as auth-service's login error); admin's customer
  list shows both.

### 2026-09-02 — Phase D2 complete: vendor-service, verified live end to end
- **New `vendor-service`**: `Vendor` (server-minted UUID `vendorId`, same reasoning as
  `Order.orderId` since Phase 2 — a cross-service identifier must not be an
  auto-increment surrogate) and `Product` (vendor's own catalog: sku, description,
  unitWeight, costPrice). Deliberately a *different* `Product` from inventory-service's
  existing one — inventory-service has no business knowing what we pay a vendor.
- **`POST /api/vendor/onboard`** (ADMIN-only) creates the vendor and calls
  auth-service's `/auth/credentials` to provision the login in the same request —
  an accepted, documented dual-write (two services, two transactions).
- **Ownership enforced twice**: the gateway's route-role map restricts
  `POST`/`PUT`/`DELETE` `/api/vendor/products/**` to `VENDOR` only (both `VENDOR` and
  `ADMIN` may `GET`), and `ProductService.requireOwned` independently checks the
  product actually belongs to the calling vendor's `businessId`.
- **Gateway's `JwtAuthFilter` upgraded**: exact-path matching → method-aware
  `AntPathMatcher` prefix matching (`ROUTE_ROLES` keys are now `"METHOD /pattern/**"`).
  Needed the moment a real route had a path variable and GET needed a different
  allowed-role set than the mutations on the same path.
- **`vendor_db`** is a second schema on the *existing* `inventory-mysql` instance (not
  `order-mysql`, to spread the two new Part D schemas across both existing MySQL
  containers rather than piling both onto one).
- **Verified live, not just unit-tested**: onboarded two real vendors through the public
  gateway; the second vendor's attempt to edit the first's product returned a real
  `403 FORBIDDEN` naming the product and vendor; the second vendor's own product list
  was correctly empty; admin's list correctly showed both vendors' catalogs combined;
  a `CUSTOMER`-role token got `403` on the onboarding route; no token at all got `401`.
- CI built and deployed `vendor-service` on the first real pipeline run (the Deployment
  manifest was applied manually *first*, since `kubectl set image` — what CI's deploy
  step does — requires the Deployment to already exist; it cannot create one).

### 2026-09-03 — Phase D1 complete: tracing confirmed live, rolled out to every service
- **The "tracing export still open" item from the entry below is resolved — it was a
  false alarm, not a bug.** `/actuator/conditions` and `/actuator/beans` (temporarily
  exposed on `auth-service`) showed every relevant bean (`webMvcObservationFilter`,
  `otelSdkTracerProvider`, `otlpHttpSpanExporter`) already present and correctly wired.
  Adding a `debug` exporter to `otel-collector`'s own pipeline showed real spans arriving
  within seconds of a request. Querying the Cloud Trace API directly then confirmed real
  traces for both `auth-service` and `api-gateway-service`, correct trace/span IDs,
  correct `service.name` and GCP resource labels. The earlier empty results were from
  checking before a redeploy's config had propagated, not a broken pipeline.
- **Extended the same pattern to every remaining service** — `order-service`,
  `inventory-service`, `notification-service`, `payment-service`, `config-service` (the
  last configured in its own local `application.yaml`, since it can't fetch config from
  itself). Same dependency (`spring-boot-starter-opentelemetry`), same properties
  (`management.opentelemetry.tracing.export.otlp.endpoint`, not the deprecated
  `management.otlp.tracing.*`). All five compile clean; CI built and deployed all of
  them successfully.
- Removed the temporary `debug` exporter and the temporarily-exposed
  `conditions`/`beans`/`env` actuator endpoints on `auth-service` now that the real
  `googlecloud` exporter is confirmed working — back to `health,info` only.
- **Phase D1 is now fully done.** `plan.md` and the D1 learn file (`learn/22-...`)
  updated to reflect this rather than left as a known-partial phase.

### 2026-09-02/03 — Phase D1 started: auth-service + gateway JWT verified live; tracing export still open
- **Part D begins** — the "Impulse" supply chain modernization system this whole project
  was scaffolding for. Full design in the approved plan (service boundaries, key flows,
  auth/tracing design, phased delivery D1-D11) — see `plan.md`'s new Part D section for
  the summary as it's built.
- **`auth-service` (new)**: `Credential` (username, bcrypt hash, role, businessId),
  `POST /auth/login` → HS256 JWT, `POST /auth/credentials` (never routed through the
  gateway — internal-only, same boundary as `payment-service`), `GET /auth/me` as a
  protected smoke-test route. 4 demo users seeded on startup, one per role.
- **Gateway `JwtAuthFilter`** — verifies the token with a secret shared via a plain k8s
  `Secret` (`impulse-secrets`), forwards decoded claims as `X-User-*` headers. Opt-in: an
  explicit map of exact paths to required roles, defaulting to "not gated" for anything
  not listed — every pre-Part-D route is untouched.
- **`otel-collector` deployed in-cluster** (contrib distribution, `googlecloud` exporter,
  reusing the existing `order-platform-workload` Workload Identity GSA, newly granted
  `roles/cloudtrace.agent`) — chosen over Google's Java-native Cloud Trace exporter
  because that library is deprecated and scheduled for archival after 2026-09-30
  (confirmed by reading its own source, which logs the deprecation warning on class
  load).
- **Verified live**: all 4 seeded roles log in and get a correctly-claimed JWT; `/auth/me`
  correctly returns 401/401/200 for no-token/garbage-token/valid-token respectively,
  against the real public gateway.
- **Not yet verified: tracing.** Spans are not reaching Cloud Trace despite the exporter
  demonstrably initializing. See §8 for the deprecated-property trap found along the way;
  root cause of the missing spans themselves is still open. **Phase D1 is not done** —
  carried forward as the first task before D2, per this project's own rule.
- **Three real bugs, all caught by checking live state rather than assuming a manifest
  apply or a config value did what it looked like it should:**
  1. `kubectl apply` on `deploy/k8s/api-gateway-service.yaml` silently reverted a manual
     `kubectl set image` update, because the checked-in file still named the old tag —
     the "manifest reflects last manually-applied state" trap, this time on the image
     field.
  2. The gateway's k8s manifest had no `JWT_SECRET` env var at all (only auth-service's
     did) — crashed on `jwtAuthFilter` bean creation with an unresolved placeholder,
     caught immediately from the crash-looping pod's own logs.
  3. `management.otlp.tracing.*` is deprecated at **error** level as of Spring Boot 4.0 —
     confirmed via the auto-config module's own `spring-configuration-metadata.json`.
- **Capacity**: node pool `max-nodes` bumped 4 → 6 — auth-service + otel-collector pushed
  the cluster back into the exact scheduling crunch Phase 15 first hit (`Insufficient
  cpu`/`memory` on all nodes). Same fix as before.

### 2026-09-02 — Phase 21: correlation-id log tracing across the whole event lifecycle
- **The request/message trail, previously stopping at the gateway (documented gap since
  Phase 7), now reaches every service.** New `CorrelationIdFilter` in `order-service` and
  `payment-service` (mirroring the gateway's), plus MDC handling in every `@KafkaListener`
  across `order-service`, `inventory-service`, and `notification-service`. Chose log
  correlation over standing up ELK or Micrometer Tracing: GKE already ships every pod's
  stdout to Cloud Logging via the `fluentbit-gke` addon (Phase 15), so the only missing
  piece was getting one id to actually reach every log line — no new infrastructure needed,
  which matters on a cluster Phase 15 already found has very little spare capacity.
- **The hard part is that most of this platform talks over Kafka, not HTTP**, so the
  gateway's existing header-forwarding trick doesn't reach past the first hop by itself.
  Fixed by treating the correlation id as data that travels with the event, not just the
  request: `OutboxEvent` gained a nullable `correlation_id` column, captured from MDC at
  write time (`OutboxWriter`); `OutboxPublisher` attaches it as a Kafka record header
  (`X-Correlation-Id`) when it finally drains the row to the broker, however much later that
  is. Every downstream `@KafkaListener` (`InventoryResultListener`, `OrderPlacedListener`,
  `OrderSettlementListener`, `InventoryEventListener`) takes it as an optional `@Header`
  method parameter, puts it in its own MDC for the duration of processing, and — where it
  publishes a further event (`OrderPlacedListener`) — attaches it to the outgoing record the
  same way. `PaymentClient`'s one synchronous call forwards it as a plain HTTP header, so
  `payment-service`'s own logs land in the same trail rather than being an island.
- **`logging.pattern.level` added to `order-service.yaml`, `inventory-service.yaml`,
  `notification-service.yaml`, and `payment-service.yaml`** in `config-repo`, matching the
  gateway's existing format — without this, MDC holds the value but no log line ever prints
  it, which would have looked like the feature worked while doing nothing.
- **Deliberately not distributed tracing.** No spans, no latency waterfall, no automatic
  per-hop timing — this is "one Cloud Logging query returns every service's line for one
  request," not "see where the time went." Honest gap, recorded rather than implied away:
  work with no live request behind it (a reconciliation sweep) has no correlation id to
  carry, since there is no MDC context to capture one from.
- Existing test suites re-run after the change: all four touched modules compile clean;
  `./mvnw verify` passes everywhere except two Testcontainers-backed MySQL ITs that failed
  only because local Docker Desktop wasn't running at the time (confirmed via `docker info`)
  — unrelated to this change, and CI (which has Docker) is what actually gates this.
  `InventoryEventListenerTest`'s two direct-invocation call sites updated for the listener
  methods' new `@Header` parameter.

### 2026-09-02 — Phase 20: a static demo page, served by the gateway itself
- **`api-gateway-service/src/main/resources/static/demo.html`** — single static file, no
  build step, no framework. Served automatically at `/demo.html` by Spring Boot's default
  static-resource handler; the gateway's declared routes only match `/api/orders/**` and
  `/api/products/**,/api/inventory/**`, so there's no collision to route around. Calls
  `/api/**` on the same origin the page is served from, so no CORS configuration was needed.
- Two sections: a static architecture panel (the 6 services, happy-path and both
  failure-path flow diagrams) and a live demo — create a product, add stock, place a real
  order, poll it to a terminal state, or deliberately over-order to trigger
  `INVENTORY_FAILED` against the live cluster.
- Shipped through the existing Phase 17 pipeline like any other change to this service —
  commit, push to `main`, CI builds and rolls it out. No new infrastructure, no new
  deployment path.

### 2026-09-02 — Phase 18 complete: cost control and teardown, both scripts run live
- **`deploy/gcp/down.sh` and `up.sh`** — scale/stop, never recreate/delete. `down.sh`
  disables node-pool autoscaling (its `minNodeCount: 1` would otherwise fight a resize to
  zero — GKE's autoscaler exists specifically to undo that), resizes `default-pool` to 0
  nodes, and stops `order-platform-data-vm`. `up.sh` starts the VM, resizes the pool back to
  4 (the count Phase 15 found necessary for all 6 pods to schedule without waiting on the
  autoscaler), re-enables autoscaling (min 1 / max 4), waits for the data VM's MySQL to
  accept a TCP connection, then waits on all 6 deployments' `rollout status`.
- **Both scripts run for real against the live infrastructure**, not merely written and
  assumed correct. `down.sh`: confirmed via `gcloud compute instances list` (VM
  `TERMINATED`) and `gcloud container clusters describe --format="value(currentNodeCount)"`
  (empty). `up.sh`: confirmed via a live `curl http://35.208.57.189/api/orders` returning
  real order data with `200` after a full cold start.
- **Real bug found only by running `up.sh` for real, not by reading it.** The first live
  run left every service crash-looping on `Communications link failure` well past the
  script's 90s MySQL-wait budget. SSHing into the data VM (`gcloud compute ssh ...
  --tunnel-through-iap`) found all three containers (`kafka`, `order-mysql`,
  `inventory-mysql`) sitting `Exited (0)` — **none had a `restart` policy**, so stopping the
  VM stopped them for good; nothing about starting the VM ever re-runs `docker compose up`.
  Fixed by adding `restart: unless-stopped` to all three services in
  `deploy/gcp/docker-compose.data-vm.yml` and pushing the corrected file to the VM (via
  `scp` to `/tmp` then `sudo cp` — OS Login's SSH user doesn't own the directory the
  original file lived in). `docker compose up -d` recreated the containers to pick up the
  new policy; the named volumes were untouched, so no data was lost. See §8 for the trap
  entry.
- **`inventory-service` needed one manual `kubectl delete pod`** after the data VM
  recovered — its most recent crash-loop attempt (against the still-dead database) had
  already pushed Kubernetes' exponential backoff several minutes out. Deleting the pod
  forces an immediate retry instead of waiting out the backoff, which is what a person
  present for a real bring-up would do. The other five pods (already mid-backoff on shorter
  delays) recovered on their own once the VM was healthy.
- **Budget alert: verified the trigger condition, not delivery — recorded as two different
  claims.** Enabled the (previously-unused) Billing Budget API to confirm Phase 12's budget
  (`Bill Alert`, ₹5650/month, 50/90/100% thresholds) exists via `gcloud billing budgets
  list`. Created a throwaway budget (`Phase18-Teardown-Test-DELETE-ME`, ₹1/month) to force
  an already-crossed threshold, since GCP evaluates a budget against the month's accrued
  spend, not from zero. **What this does not prove:** GCP sends the alert email on its own
  periodic schedule with no API to force or poll delivery, and confirming the email needs
  the billing account's inbox (`ishitabhargava28@gmail.com`), outside this session's reach.
  Recorded honestly in `plan.md` rather than asserted as fully verified.
- **README gained a "Deployed to GCP" section** — cost table, the `up.sh`/`down.sh`
  commands, and pointers to `plan.md`/`Agent.md`/`learn/` for the full detail. Corrected a
  stale "no Kubernetes or cloud deployment yet" line in "What is not built" left over from
  before Part B started, and added the two real remaining gaps (TLS, GitOps) in its place.
- **Phase 18 done — Part B (Phases 12-18) is now fully complete**, every phase with a
  running, live-verified exit criterion. Only Phase 19 (`legacy-adapter`, optional) remains
  in `plan.md`.

### 2026-09-02 — Phase 17 complete: CI/CD to GKE via Workload Identity Federation
- **New `deploy` job** in `.github/workflows/ci.yml`, gated on `build` + `images` passing and
  the trigger being a real push to `main` (`if: github.event_name == 'push' && github.ref ==
  'refs/heads/main'`) — a PR never touches GCP. Matrix over the 6 deployed services
  (`discovery-service` correctly absent, matching Phase 15). Each: builds its image, tags
  with the **git SHA** (never `latest`), pushes to Artifact Registry, then
  `kubectl set image` + `kubectl rollout status --timeout=300s` so a bad rollout fails the
  CI job loudly instead of leaving a silently-broken deployment discovered later.
- **Auth is Workload Identity Federation, no key file, ever.** GCP-side setup, mostly done in
  the console: a Workload Identity Pool (`github-actions-pool`) with an OIDC provider
  (`github-provider`, issuer `https://token.actions.githubusercontent.com`) whose attribute
  condition restricts token exchange to exactly this repo
  (`assertion.repository == 'Ishita2803/InventoryPlatformManagement'`); a dedicated service
  account `github-actions-deployer` with `artifactregistry.writer` + `container.developer`;
  one `add-iam-policy-binding` granting the pool's `principalSet` `roles/iam.workloadIdentityUser`
  on that service account. **One console gotcha, worth knowing:** the provider creation
  failed with *"The attribute condition must reference one of the provider's claims"* until
  the Attribute Mapping table (`google.subject = assertion.sub`,
  `attribute.repository = assertion.repository`) was confirmed saved *before* setting the
  condition — the console validates the condition against already-declared mapped claims,
  and doesn't clearly say so in the error.
- **Verified with two real CI runs, not just "the YAML looks right."** First run: 4/6
  services deployed; `order-service` and `inventory-service` failed at
  `kubectl rollout status` with `timed out waiting for the condition`. Checked the cluster
  directly rather than trusting the CI failure alone — both pods showed **0 restarts** (never
  crash-looping) and were `1/1 Running` a few minutes later, past the 180s the CI step had
  waited. Root cause: the matrix deploys all 6 services roughly concurrently, and Phase 15
  had already established these `e2-medium` nodes have very little spare CPU headroom — 6
  simultaneous JVM cold starts measurably slow each one down. Fixed by raising the timeout to
  300s; the second run deployed 6/6 successfully, confirmed via `kubectl get pods` (all
  `1/1 Running`, 0 restarts) and a live `curl` through the public gateway returning real data.
- **Rollback verified live, not just documented.** `kubectl rollout undo deployment/payment-service`
  reverted the image to the previous git-SHA tag; `kubectl rollout status` confirmed the
  rollback completed; rolled forward again (`kubectl rollout undo` a second time) to restore
  the latest build, confirmed via the deployment's image field and a live gateway health
  check. **One real wrinkle surfaced by actually running it:** `kubectl` warns that
  `rollout undo` doesn't update the `kubectl.kubernetes.io/last-applied-configuration`
  annotation that `kubectl apply` maintains — a future manual `kubectl apply -f
  deploy/k8s/<service>.yaml` may not behave the way its committed image tag suggests. Same
  underlying trade-off documented in the previous Phase 16 entry (committed manifests reflect
  the last *manually applied* state, not necessarily live state), now with a second concrete
  case.
- **Phase 17 done.** Both exit-criterion halves — "a push to `main` lands in GKE with no
  manual step" and "a rollback is one command" — verified against the real pipeline and the
  real cluster, not asserted from reading the workflow file.

### 2026-09-02 — Phase 16: rate-limiting shipped, and a real bug only the live LB could expose
- **`RateLimitFilter`** (`api-gateway-service/filter/`): per-client-IP, fixed-window,
  in-memory (`ConcurrentHashMap<String, AtomicInteger>`), ordered right after
  `CorrelationIdFilter` so a rejected request still carries a correlation id. Config via
  `gateway.rate-limit.{requests-per-window,window-seconds}` in `config-repo`, default
  50/second. A `@Scheduled` sweep evicts windows untouched for 10× their own lifetime so a
  public endpoint hit by many distinct client IPs doesn't grow the map unbounded. Chose this
  over Spring Cloud Gateway's built-in `RequestRateLimiter` because that needs Redis, and
  there is no Redis anywhere in this stack — adding a stateful dependency solely to
  rate-limit one lightly-loaded gateway would be disproportionate. 3 new integration tests;
  9/9 gateway tests green.
- **Real bug, and a real lesson about what unit tests can't reach.** The filter passed every
  unit test, then let a 100-request concurrent burst through as 100×200 against the live LB.
  Root cause: the Service's `externalTrafficPolicy` defaults to `Cluster`, under which
  kube-proxy **SNATs every request to the receiving node's own IP** before forwarding to the
  pod — `request.getRemoteAddr()` never saw the real client, only one of 4 node IPs, silently
  fragmenting one client's burst across up to 4 separate rate-limit counters, each comfortably
  under the limit alone. No unit test could have caught this: none of them go through
  kube-proxy. Fixed with `externalTrafficPolicy: Local`, which preserves the real client IP
  end-to-end. Confirmed two ways: the GCP target pool's health check now reports exactly the
  one node hosting the pod as `HEALTHY` (the other three correctly `UNHEALTHY`, since `Local`
  only routes to nodes with a currently-`Ready` local pod — a non-cost here with one replica);
  and a second live burst test produced 92×200 + 7×429, matching both the HTTP status codes
  and the pod's own "Rate limit exceeded" log count.
- Built and pushed `api-gateway-service:f0c3e94` to Artifact Registry with this code; deployed
  and confirmed the running pod picked up `config-service`'s new `gateway.rate-limit.*`
  values (`requests-per-window: 50`, not the code's fallback default) via
  `wget -qO- http://config-service:8888/api-gateway-service/default` from inside the cluster.
- **Still open in Phase 16:** optional TLS.

### 2026-09-02 — Phase 16 started: gateway public via a static IP, LoadBalancer over NodePort
- **Reserved a Standard-tier static external IP** (`35.208.57.189`, `us-central1`) via the
  console, and changed `api-gateway-service`'s k8s `Service` from implicit `ClusterIP` to
  `type: LoadBalancer` with `loadBalancerIP: "35.208.57.189"`, external port `80` → pod
  `8080` (so the exit criterion's `curl http://<ip>/api/orders` needs no port suffix).
- **Chose `LoadBalancer` over raw `NodePort` + firewall, deliberately.** `plan.md` offered
  both as "cheap path" options. NodePort means pointing the static IP at one specific node's
  external interface; Phase 15 had just proven (deliberately, via a simulated preemption)
  that Spot nodes get destroyed and recreated. A `LoadBalancer` Service balances across every
  node in the pool and keeps working regardless of which node comes or goes — the node-level
  fragility NodePort would have was already demonstrated, not hypothetical.
- **Real bug: the reserved IP's network tier didn't match the LoadBalancer's default.**
  `SyncLoadBalancerFailed: requested IP "..." belongs to the Standard network tier; expected
  Premium` — GKE provisions `LoadBalancer` Services as Premium tier unless told otherwise. Fix
  is the annotation `cloud.google.com/network-tier: "Standard"`. **First attempt used the
  wrong key**, `networking.gke.io/network-tier` — it applied with no error and appeared in
  `kubectl get svc -o yaml` exactly like a working annotation, but silently had zero effect
  on provisioning. Cost real time because a silently-accepted-but-ignored annotation gives no
  signal that anything is wrong; only re-checking the *provisioning* error (not the applied
  manifest) revealed it was still failing for the same reason.
- **Verified from outside the cluster, not just `kubectl port-forward`:**
  `curl http://35.208.57.189/api/orders` returns real order data; a direct request to
  `order-service`'s port `8081` on the same IP times out — only the gateway is reachable.
  Actuator's `env`/`heapdump` were already unexposed since Phase 7
  (`management.endpoints.web.exposure.include: health,info,gateway`), so no code change was
  needed for that checklist item.
- **Still open:** rate-limiting the gateway (planned: an in-memory Bucket4j filter, not
  Spring Cloud Gateway's Redis-backed `RequestRateLimiter` — no Redis exists in this stack)
  and optional TLS. Also worth doing: re-run the `curl` check from an actual phone on mobile
  data, since everything verified so far was from this machine.

### 2026-09-02 — Phase 15: last two checklist items closed (one was already done, one newly verified)
- **`MaxRAMPercentage` was already correctly set — the earlier "still open" note in this file
  and in `plan.md` was wrong.** It was written on the assumption that no `JAVA_OPTS` value
  existed anywhere, having checked only the k8s `ConfigMap`. Every service's Dockerfile has
  carried `ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"` since Phase 9,
  and that persists into the container regardless of a k8s manifest's `command` override.
  Confirmed on the **actual running PID 1** via `/proc/1/cmdline` in both
  `inventory-service` (which overrides `command`) and `payment-service` (which doesn't) — not
  a fresh `java -version` invocation, which would have shown the JVM's unrelated 25% default
  and given a false negative.
- **Verified Spot-preemption survival for real, not simulated with a cordon/drain.** Deleted
  the GCE instance backing the node running `inventory-service`
  (`gcloud compute instances delete gke-...-8dhp`). Node went `NotReady` within ~60s, was
  removed from the cluster shortly after (the old pod object was garbage-collected with it),
  GKE's managed instance group recreated a replacement node with the same name within ~25s,
  and the Deployment's ReplicaSet scheduled a new pod onto it with **no manual
  intervention** — it reached `1/1 Ready` unassisted, including re-resolving the
  `config-service` dependency from a cold start (the same race documented in the previous
  entry, self-healing the same way it did the first time).
- **Phase 15 exit criterion verified and the phase is complete.**
  `kubectl port-forward svc/api-gateway-service 8080:8080` against the real data VM: created a
  product and 10 units of inventory, then an order placed **before** the inventory existed for
  it correctly settled at `INVENTORY_FAILED` (a real failure path, produced incidentally by a
  test-data ordering mistake rather than staged). A second order for qty 2 reached
  `CONFIRMED` in ~3s, with stock correctly settled — `available` 10→8, `reserved` back to 0,
  i.e. shipped, not merely held. **Phase 15 done.**

### 2026-09-02 — Phase 15: all six pods running; CSI driver workaround; node pool bumped to 4
- **`deploy/k8s/` manifests deployed and all pods `Running`/`Ready`**: config-service,
  order-service, inventory-service, payment-service, notification-service,
  api-gateway-service. `discovery-service` correctly not deployed; the gateway logs confirm
  `k8s` profile active and `config-service` in-cluster is reachable at
  `http://config-service:8888`.
- **The Secret Manager CSI driver's `secretObjects` sync does not work on GKE Standard.**
  That field is supposed to mirror a mounted secret into a native Kubernetes `Secret` so
  `env.valueFrom.secretKeyRef` can reference it. It never completes — the driver's own
  service account (`system:serviceaccount:kube-system:secrets-store-csi-driver-gke`) lacks
  cluster-scope RBAC to list/watch `Secret` objects, confirmed via `"secrets is forbidden
  ... at the cluster scope"` in the driver's logs and `FailedToCreateSecret` on the pod. This
  is a gap in the GKE-managed component, not fixable by editing our own RBAC. **Worked
  around**: `order-service` and `inventory-service` no longer use `secretKeyRef` at all;
  their container `command`/`args` `cat` the mounted secret file directly
  (`/mnt/secrets-store/{order,inventory}/mysql-*-password`) and `export MYSQL_PASSWORD`
  before `exec`-ing the JAR. The volume mount itself works fine — only the
  mount-to-native-Secret sync is broken.
- **Also fixed:** the CSI driver name in the `SecretProviderClass` volume spec was wrong —
  GKE's managed driver registers as `secrets-store-gke.csi.k8s.io`, not the upstream
  `secrets-store.csi.k8s.io`. Using the upstream name meant the volume never mounted.
- **`api-gateway-service` and `notification-service` stuck `Pending` for 6+ hours** —
  `FailedScheduling: Insufficient cpu` on two of three nodes, `Insufficient memory` on the
  third, and the autoscaler logged `NotTriggerScaleUp: max node group size reached`. Root
  cause: **`e2-medium` nodes only expose ~940m of their 2000m CPU as allocatable** — GKE's
  own addons (`csi-secrets-store-gke`, `fluentbit-gke` logging, GMP monitoring
  (`collector`/`gmp-operator`/`kube-state-metrics`), `konnectivity-agent`,
  `node-local-dns`, `netd`, `pdcsi-node`, `gke-metadata-server`) were consuming 800–930m of
  that on two of the three nodes, leaving no room for even one more 50m app pod — this was
  not fixable by trimming our own five services' CPU requests further (already at 350m
  total across all five). Trimmed CPU requests anyway as a first attempt (order/inventory
  250m→100m, the other three 100m→50m) — did not resolve it alone.
  **Fixed** by bumping the node pool's `--max-nodes` from 3 to 4
  (`gcloud container clusters update ... --node-pool default-pool --enable-autoscaling
  --min-nodes 1 --max-nodes 4`); the autoscaler added a 4th Spot `e2-medium` within a couple
  of minutes and both pods scheduled onto it immediately. **Cost impact:** an extra Spot
  `e2-medium` node when the pool is scaled up, roughly +$5/mo over the Phase-12 estimate if
  ever left running at 4 nodes continuously — Phase 18's teardown still scales to zero.
- **Investigated, then left alone as already-correct:** `inventory-service` showed 7
  restarts. Root cause was the documented, deliberate design already recorded in
  `order-service.yaml`'s header comment — no Kubernetes equivalent of Compose's
  `depends_on: service_healthy`, so a pod starting before `config-service` is reachable
  fails fast (`ResourceAccessException: Connection refused` fetching
  `http://config-service:8888/...`) and Kubernetes restarts it with backoff until it
  succeeds. Confirmed this is exactly what happened: `config-service`'s pod first became
  `Running` at `19:40:43`; `inventory-service`'s crash loop ended and it has run clean since
  `19:42:36`. Considered adding an init container to gate startup on `config-service`'s
  health endpoint and explicitly decided against it — reverses a decision already made and
  documented, and the existing behavior (slower boot, but correct once it succeeds) was an
  accepted trade-off, not an oversight.
- **Still open in Phase 15:** JVM `-XX:MaxRAMPercentage` is not yet set against the pod's
  resource limit (no `JAVA_OPTS` value is defined anywhere — the `$JAVA_OPTS` reference in
  the `command`/`args` workaround above currently expands to nothing), and Spot-preemption
  survival is unverified. Manifest changes from this session are staged but **not yet
  committed** as of this entry.

### 2026-09-01 — Phase 15 started: cluster created, Phase 13's deferred exit criterion verified
- **`order-platform-cluster`** created: GKE Standard, zonal (`us-central1-a`), default
  network/subnet, Spot `e2-medium` node pool, autoscaling 1-3.
- **Workload Identity is not on by default** for a new Standard cluster — corrected after
  first assuming otherwise. `workloadIdentityConfig` came back `{}` (empty) right after
  creation. Fixed with `gcloud container clusters update --workload-pool=<project>.svc.id.goog`
  followed by `gcloud container node-pools update --workload-metadata=GKE_METADATA`; the
  second command **recreates every node**, so do it before anything is deployed, not after.
- **The cluster's pod range doesn't fall inside the data-VM firewall rules.** GKE Standard is
  VPC-native by default: nodes get IPs from the subnet's primary range (`10.128.0.0/20` here,
  which the Phase 13 firewall rules already allowed), but pods get a separate secondary range
  GKE auto-picked (`10.83.128.0/17`). A pod's traffic to the data VM keeps the pod's own
  source IP rather than getting SNAT'd to the node IP — `ip-masq-agent`'s default
  non-masquerade list includes all of RFC1918, and both the pod range and the VM are RFC1918
  — so the existing rules silently didn't cover pods. Fixed by adding the pod CIDR to all
  three data-VM firewall rules (`allow-kafka-internal`, `allow-mysql-internal`,
  `allow-mysql-inventory-internal`). **Anyone recreating the cluster with a different pod
  range must redo this** — it's not automatic.
- **Verified Phase 13's deferred exit criterion** from throwaway pods
  (`kubectl run --rm --attach`): `mysql:8.0` reached both `order_db` (3306) and
  `inventory_db` (3307) on `10.128.0.2`; `apache/kafka:3.9.0`'s console producer/consumer
  round-tripped a message through `10.128.0.2:9092`.
- Still open in Phase 15: per-service `Deployment`/`Service` manifests, the `k8s` Spring
  profile that drops `discovery-service`, in-cluster `config-service`, and the
  KSA↔GSA Workload Identity binding + Secret Manager CSI mount deferred from Phase 14.

### 2026-09-01 — Phase 14 partial: Secret Manager set up, cluster-dependent steps deferred
- Created secrets `mysql-order-password` and `mysql-inventory-password` in Secret Manager,
  and a dedicated GSA `order-platform-workload` with **per-secret** `secretAccessor` grants
  (via each secret's own Permissions tab) rather than a project-wide role.
- `config-repo/order-service.yaml` and `inventory-service.yaml`: removed the
  `${MYSQL_USER:root}` / `${MYSQL_PASSWORD:root}` defaults — a missing credential now fails
  loudly instead of silently trying `root`. Local `docker-compose.yml` updated to pass
  `MYSQL_USER`/`MYSQL_PASSWORD` explicitly for `order-service` and `inventory-service`,
  since it had been relying on the default just removed.
- **Deferred to Phase 15, not skipped:** enabling Workload Identity and installing the
  Secret Manager CSI driver both require a running GKE cluster, which doesn't exist until
  Phase 15. Same split as Phase 13's exit criterion.
- **Known, accepted gap — do not claim this is fixed:** the data VM's real credentials
  (`MYSQL_ROOT_PASSWORD`, `ORDER_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`) are all the same
  weak value (`root123`), set directly in the VM's `.env` (never committed to git).
  Karthik chose to defer rotating this rather than block on it. The two Secret Manager
  secrets created above currently hold this same value. Revisit before claiming Phase 14
  is fully done.

### 2026-08-31 — Phase 13 complete: the data VM, exit criterion deferred to Phase 15
- **VM `order-platform-data-vm`** created: `e2-medium`, Debian 12 **x86** (not arm64 — the
  default `e2-medium` image family is x86; worth checking before assuming an image matches
  the machine type), 20 GB balanced PD, no external IP, network tag `data-vm`. Internal IP
  reserved as static: `10.128.0.2`.
- **Cloud NAT added** (`data-vm-nat` gateway + `order-platform-router`) — not in the original
  plan. A VM with no external IP has no outbound internet either, so `apt install docker` and
  the startup script failed silently until NAT gave it a path out. No external IP blocks
  inbound only if something also provides outbound; it doesn't come for free.
- **Firewall rules** open TCP 3306/3307/9092, scoped to the default subnet range as a
  stand-in for "the future GKE node subnet" — that subnet doesn't exist until Phase 15, so
  this is provisional and should be tightened once it does.
- **`deploy/gcp/docker-compose.data-vm.yml`** — Kafka 3.9 in KRaft mode advertising
  `10.128.0.2:9092` (never `localhost`, which would hand off-VM clients an unreachable
  address), plus two separate MySQL 8 instances (`order-mysql`:3306, `inventory-mysql`:3307),
  each with a non-root `MYSQL_USER` scoped to its own database. Heaps tuned for the 4 GB
  `e2-medium`: Kafka 768M, MySQL 384M × 2. `.env.example` committed; the real `.env` (with
  actual passwords) is not.
- **Deployed and running** — `docker compose up -d` confirmed healthy on the VM.
- **Exit criterion deliberately left unverified**: "from a throwaway pod in GKE, `mysql`
  connects to both schemas and Kafka round-trips a message" cannot be checked without a GKE
  cluster, which doesn't exist until Phase 15. Rather than fake this with an in-place
  `docker exec` check (which wouldn't test the same network path a real pod uses), the
  verification is deferred and added as the **first task in Phase 15**, before any real
  workload deploys.

### 2026-08-30 — Phase 12 complete: GCP foundation and local toolchain
- **gcloud CLI installed and authenticated**; Docker Desktop was already present since
  Phase 9. `kubectl`/`helm`/`terraform` still not installed — deferred to the phase that
  needs them (15).
- **GCP project created as `inventorymanagement-507107`**, not `order-platform` as
  `plan.md` originally named it — the intended name was already taken. Region
  `us-central1`, zone `us-central1-a`, set as the active gcloud config.
- Billing enabled and a **budget alert (50/90/100%)** created in the console before any
  resource existed.
- APIs enabled: `container`, `compute`, `artifactregistry`, `secretmanager`, `cloudbuild`,
  `iam` (the project also carries a long list of unrelated APIs — BigQuery, DNS, Pub/Sub,
  etc. — that came bundled with the project template; harmless, not part of this design).
- **Artifact Registry Docker repo `order-platform-repo`** created in `us-central1`.
  `order-service`'s Phase 9 image pushed as the smoke test and confirmed visible.
- **`deploy/gcp/00-bootstrap.sh`** captures the reproducible parts (config, API enables,
  repo creation, Docker auth) — not the budget alert, which has no clean gcloud CLI surface
  and was done by hand in the console.
- **Trap found:** local images build under the `order-platform/` Compose namespace, not
  the bare service name. `docker tag order-service:latest ...` fails with "No such image" —
  the actual local tag is `order-platform/order-service:latest`, found via `docker images`.
- Karthik is running Part B's remaining phases (13-18) himself from here; this session's
  role is guidance and the bootstrap script only, not executing gcloud/kubectl directly.

### 2026-08-26 — Phase 11.5 complete: reconciliation, on both sides
- **`OrderReconciliationService`** (order-service) sweeps orders stuck at
  `INVENTORY_RESERVED` past a threshold and re-drives settlement. It never touches
  `processed_event` — it re-runs the *settlement* transaction, which was never marked done.
  Closes the gap flagged since Phase 4 and proven real in Phase 8.
- **`OrderSettlementService`** extracted from `InventoryResultListener.settle(...)`. The
  listener and the sweeper must behave identically; two copies would have drifted silently.
  Two entry points differing only in policy: `settleCancellingOnOutage` (listener — a customer
  is waiting) and `settle` (sweeper — an outage must not cancel a backlog).
- **`@Version` added to `Order`**, deferred since Phase 2 on the explicit grounds that
  concurrent updates were not yet possible. The sweeper is a second writer on a timer, so they
  are now.
- **A SECOND leak, found by looking at the live database rather than by reasoning.** All 1608
  orders read `CONFIRMED`, yet 9 units were still reserved. The outbox showed all 1608
  `order.confirmed` events PUBLISHED. `order.confirmed.DLT` held exactly 9 records, and their
  headers gave the cause: `ReservationConflictException: ... after 4 attempts due to concurrent
  modification`. Benchmark run 1's 200 orders against a single product exhausted the
  optimistic-lock retry budget on 9 confirmations; the error handler retried 3× and gave up.
  **Nothing in this system ever read a dead-letter topic** — they were write-only.
- **`SettlementRecoveryService`** (inventory-service) drains `order.confirmed.DLT` and
  `order.cancelled.DLT` on a timer and re-applies them. Safe because `confirmByOrderId` and
  `releaseByOrderId` both filter `WHERE status = RESERVED`, so a replay matches no rows.
  A scheduled drain, deliberately not a `@KafkaListener` on the DLT — a listener would
  re-consume milliseconds after the failure, while the contention causing it is still there.
  Required `@EnableScheduling` on `InventoryServiceApplication` (it had none).
- **Verified against the real data, not only tests.** One sweep:
  `examined=9 confirmed=9 released=0 failed=0`. After it, `orders CONFIRMED` = 1608 and
  `reservations CONFIRMED` = 1608, `SUM(reserved_quantity)` = 0, recovery consumer-group
  lag 0.
- **Mutation-tested.** Neutering the sweep fails 7 of 11 order-side tests; making it cancel on
  a payment outage fails exactly 1 — `outageDoesNotCancel`, the test written for that policy.
- **Test counts:** order 51 -> 62, inventory 33 -> 38. Five app modules **117**; CI **119**.
- **Known and deliberately not fixed:** confirm/release are pure relative adjustments
  (`reserved -= n`) and could be one atomic conditional `UPDATE` with no contention failure
  mode at all. That is the better fix. Reconciliation makes the symptom recoverable rather
  than making it stop happening. Recorded in ADR-0008 and the README's "not built" list.
- **`KafkaProperties` moved in Boot 4** out of `org.springframework.boot.autoconfigure.kafka`.
  `SettlementRecoveryService` reads `${spring.kafka.bootstrap-servers}` via `@Value` instead.

### 2026-08-26 — CI actually run for the first time; two real bugs fixed
- The `workflow` OAuth scope was refreshed, Phases 10 and 11 pushed, and the pipeline ran for
  the first time. **Final state: all eight jobs green** (run `32993087529`, commit `30c81a7`)
  — 103 tests, 0 failures, 0 errors, 0 skipped.
- **Bug 1 — every `mvnw` was mode `100644`.** All seven matrix jobs failed identically with
  `./mvnw: Permission denied`, exit code 126. Windows has no executable bit, so git recorded
  the wrappers non-executable. Fixed with `git update-index --chmod=+x <svc>/mvnw` for all
  seven, which sets the mode in the index without needing filesystem support.
- **Bug 2 — the BuildKit cache mount raced under parallel builds.**
  `--mount=type=cache,target=/root/.m2` covers `/root/.m2/wrapper/dists`, where the Maven
  Wrapper installs Maven. `docker compose build` runs services in parallel, so seven `mvnw`
  processes tried to install into one shared mount; `mv` onto a non-empty target failed and
  `mvnw` exited 127. Now `--mount=type=cache,target=/root/.m2/repository,sharing=locked` —
  the repository is the part worth sharing, and the wrapper dist installs per-service.
- **Neither reproduced locally**, and neither is visible by reading the files. Windows ignores
  the executable bit entirely, and the local build cache was warm from Phase 9, so the wrapper
  skipped its install. **Phase 9's "8.3 minute cold build" was therefore not measured from a
  genuinely cold cache.**
- **Test count clarified:** 101 across the five application modules (51 order, 33 inventory,
  6 notification, 6 gateway, 5 payment); **103 in CI**, the extra two being context-load smoke
  tests in `config-service` and `discovery-service`. Docs now state both.
- **Operational note on GitHub Actions here:** push-triggered runs register **2–6 minutes
  late**. Manually dispatching while waiting caused two runs to collide — `cancel-in-progress`
  correctly killed the newer dispatch in favour of a late-arriving push run carrying an
  *older* commit, so a fix appeared to fail when it had simply never been tested. Push, then
  wait; do not dispatch on top.

### 2026-08-26 — Phase 11 complete: documentation, ADRs, OpenAPI, and a real benchmark
- **`README.md` written** (was a stub): architecture diagram, happy-path and failure-path
  sequence diagrams, quickstart from `git clone` to a confirmed order, how to trigger
  compensation deliberately, port table, API table, measured performance, and an explicit
  "what is not built" list.
- **Seven ADRs** in `docs/decisions/`, each stating the rejected alternatives and the costs:
  outbox, choreography, optimistic locking, `processed_event` idempotency, per-service event
  classes, the mock payment service, gateway MVC.
- **`docs/openapi.yaml` hand-written.** springdoc has no Boot 4 release (latest 2.8.6), so
  generation is unavailable. Validated as parsable with all `$ref`s resolving; status codes
  checked against the real `@ExceptionHandler` methods, which turned up two responses the
  first draft had missed (`DUPLICATE_SKU` 409, `PRODUCT_NOT_FOUND` 404).
- **Benchmark run for real** — `docs/benchmark/bench.py`, four runs recorded verbatim in
  `docs/benchmark/RESULTS.md`.
  - Hypothesis "throughput is limited by contention on the stock row" was **tested and
    refuted**: 200 orders across 20 SKUs gave 8.1 orders/sec against 8.7 for a single SKU.
  - Stage-by-stage measurement: gateway overhead ~nil (direct :8081 was the same speed),
    payment 5 ms. That left ~600 ms for the accept path on an idle system.
  - **Root cause: 190 ms per commit**, measured with 20 autocommit inserts *inside* the DB
    container. `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` on Docker Desktop's
    virtual disk. ~5 commits per order × single-threaded consumers explains the 68 s p50.
  - Controlled test: `SET GLOBAL innodb_flush_log_at_trx_commit=2; SET GLOBAL sync_binlog=0`
    (both dynamic, no restart, nothing else touched) → **14.7× throughput**, 8.1 → 119.4
    orders/sec, POST p50 2271 → 189 ms, e2e p50 70038 → 6183 ms, 200/200 settled.
    **Durable settings restored and verified afterwards.**
  - 1000 orders / concurrency 50: 108.5 accepted/sec, ~29 settled/sec, **1000/1000 CONFIRMED,
    zero oversell**, stock reconciled exactly.
  - All five topics have **PartitionCount: 1** — consumers cannot scale out; a second
    inventory instance would idle. Documented as the next scaling change.
- **Two containers were found stuck in `Created`** at the start of this phase: an earlier
  `docker compose up` had been interrupted while waiting on MySQL's cold init, so Compose
  created `order-service` and `inventory-service` but never started them. `docker compose ps`
  hides these — only `ps -a` shows them.

### 2026-08-26 — Phase 10 complete: Testcontainers and CI
- **MySQL Testcontainers tests in both database-backed services**, written specifically to
  catch the class of bug that got through in Phase 8. `OutboxMySqlIT` asserts the
  `outbox_event.payload` column type directly and round-trips an eight-line order whose
  payload exceeds the old 255-byte limit; `InventoryMySqlIT` verifies the reservation unique
  constraint and the `@Version` column exist in the schema MySQL actually generates.
- `@ServiceConnection` wires the container into the context — no `@DynamicPropertySource`.
- **Testcontainers 2.x renamed every module artifact.** `org.testcontainers:mysql` does not
  exist at 2.0.5; it is `testcontainers-mysql`. Same for `testcontainers-junit-jupiter`.
  Needs the `testcontainers-bom` imported too — the Boot parent sets the version property
  but does not manage the artifacts.
- **The container timed out at 390s until the data directory moved to tmpfs.** On-disk MySQL
  init takes 85–235s here and Testcontainers connects during the entrypoint's temporary
  server, giving "Communications link failure" — the same trap as the Phase 7 healthcheck.
  With tmpfs the suite runs in ~26s.
- GitHub Actions: 7-way matrix running `verify` per service (not `test`, which would skip
  every `*IT`), plus an image-build job. Images are built, not pushed — no registry until
  Phase 17.
- **101 tests** across five modules: 51 order, 33 inventory, 6 notification, 6 gateway,
  5 payment. 43 of them are integration tests.

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
