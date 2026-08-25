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

1. Tick the box in `plan.md` and record the outcome in **§9 Change log** here (newest first,
   dated).
2. Update **§5 Implementation status** — it must never describe code that no longer exists.
3. If you hit a trap that cost you time, add it to **§8 Traps and gotchas**. That section is
   the highest-value part of this file.
4. If a decision was made, add it to **§7 Locked decisions** with the *why*, not just the what.

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
| Messaging | Apache Kafka *(not yet wired)* |
| Discovery | Eureka — **local only**, dropped on GKE (see §7) |
| Config | Spring Cloud Config, git backend |
| Gateway | Spring Cloud Gateway **MVC** (`spring-cloud-starter-gateway-server-webmvc`) |
| Resilience | Resilience4j *(dependency present, unused)* |
| Build | Maven (wrapper per module; there is **no parent aggregator pom**) |
| Boilerplate | Lombok |
| Container | Docker, multi-stage *(not yet written)* |
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
| `notification-service` | 8083 | Consumes events, mock email | yes | yes |
| `payment-service` | tbd | Mocked, synchronous — Resilience4j's target | planned | yes |
| MySQL | 3306 | **both** schemas on one instance | — | on the data VM |
| Kafka | 9092 | not yet running | — | on the data VM |

Note `discovery-service`'s application name is `discovery-server`, which does **not** match
its directory name. That is deliberate but easy to trip over.

---

## 4. Repo layout

```
InventoryPlatformManagement/          <- git root
├── Agent.md                          <- this file: context and state
├── plan.md                           <- the phased plan (canonical)
├── CLAUDE.md                         <- thin pointer to this file
├── README.md                         <- still a stub (Phase 11)
├── .gitignore
├── .gitmodules
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
└── notification-service/
```

Planned but not yet created: `payment-service/`, `deploy/k8s/`, `deploy/gcp/`,
`docs/decisions/`, `docker-compose.yml`.

Java packages are `com.demo.<service_name>` with **underscores**
(e.g. `com.demo.inventory_service`), not the `com.karthik.*` used in the source PDF.
Entities live in a `models` package, not `entity`.

---

## 5. Implementation status

*Update this section with every change.*

### `inventory-service` — furthest along
- `models/Product` — `id`, `sku` (unique), `name`
- `models/Inventory` — `id`, `productId`, `warehouseId`, `availableQuantity`,
  `reservedQuantity`, `@Version version`
- `ProductRepository.findBySku`, `InventoryRepository.findByProductIdAndWarehouseId`
- DTOs: `ProductRequest`, `InventoryRequest`, `InventoryResponse`, `ReserveInventoryRequest`
- `InventoryService`: `createProduct`, `addInventory`, `getInventory`, `reserveInventory`,
  `releaseInventory` — all `@Transactional`
- `GlobalExceptionHandler`: `InventoryNotFoundException` → 404,
  `InsufficientInventoryException` → 409
- Endpoints under `/api`: `POST /products`, `POST /inventory`, `GET /inventory`,
  `POST /inventory/reserve`, `POST /inventory/release`
- **Known rough edges:** `createProduct` / `addInventory` / `getInventory` still throw bare
  `RuntimeException`, so those paths return 500 rather than a typed status.
  `releaseInventory` throws `IllegalArgumentException`, also unmapped.

### `order-service` — **skeleton only**
Application class with `@EnableDiscoveryClient` plus a config-client `application.yaml`.
**No entity, repository, service, controller or DTO exists.** This is the largest gap.
The pom already carries JPA, MySQL, Validation, Kafka and Resilience4j — all unused.

### `notification-service`, `api-gateway-service` — skeletons
Application classes only. Both are config clients. The gateway has **no routes** yet.

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

- Submodule URL: `https://github.com/Karthik0770/order-platform-config-repo.git`
- Tracked branch: **`master`**, not `main`

Rationale: configuration has its own lifecycle and audit trail, and Config Server reads it
through a real git backend — which is the whole point of using git rather than the `native`
filesystem backend.

**Cloning this repo requires:**
```bash
git clone --recurse-submodules <url>
# or, after a plain clone:
git submodule update --init --recursive
```
Without that, `config-repo/` is empty and **every config client fails to start.**

**Changing configuration is a two-commit operation:**
```bash
cd config-repo
# edit yaml
git add . && git commit -m "..." && git push
cd ..
git add config-repo && git commit -m "Bump config-repo"   # moves the gitlink
```
Config Server reads **committed** state. An uncommitted yaml edit has no effect — this
will waste an hour if you forget it.

> ⚠ **The GitHub repository at that URL does not exist yet.** `config-repo`'s history has
> been squashed to a single clean commit that is ready to push, and `gh` is now installed,
> but the repository still has to be created — `gh auth login` is interactive and needs a
> human. Until that happens the submodule URL is a dead link for anyone else cloning.
> `plan.md` Phase 0.5.

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
3. **Config Server working directory.** `config-service` resolves
   `file:${CONFIG_REPO_PATH:../config-repo}` **relative to the process working directory**,
   which must be `config-service/`.
   - CLI: `cd config-service && ./mvnw spring-boot:run` — correct automatically.
   - IntelliJ: use the committed `.run/config-service.run.xml`, which pins
     `WORKING_DIRECTORY` to `$PROJECT_DIR$/config-service`.

   This matters because the project has **one** IntelliJ module rooted at the project
   directory, so IntelliJ's default working directory is the *project root*, from which
   `../config-repo` resolves outside the repo entirely. That is exactly the bug that broke
   the platform before Phase 0. Override `CONFIG_REPO_PATH` for Docker rather than editing
   the yaml.
4. **Config Server reads committed state only.** See §6. An edited-but-uncommitted yaml
   silently has no effect.
5. **Reservations have no `orderId`.** `reserveInventory(productId, warehouseId, quantity)`
   is quantity-only. The moment Kafka delivers `OrderPlaced` twice — and at-least-once is
   the default — this double-reserves, and there is no way to release everything belonging
   to one order. **Fix this before Kafka** (`plan.md` Phase 1). Afterwards it is not an
   entity addition, it is a consumer rewrite.
6. **`@Version` on `Inventory` has no landing pad.** `ObjectOptimisticLockingFailureException`
   is unhandled and unretried, so a lost race returns 500 instead of 409.
   `MethodArgumentNotValidException` is also unhandled, so `@Valid` messages never reach
   clients cleanly. Both are Phase 1 work.
7. **DB passwords are `${MYSQL_PASSWORD:root}` placeholders — keep them that way.**
   `config-repo/order-service.yaml` and `inventory-service.yaml` previously carried
   `password: "root"` in plaintext. Fixed 2026-08-25, and `config-repo`'s history was
   squashed to one commit before its first push, so the literal credential never reached
   GitHub at all. The `:root` default means local runs still need no env var. **Do not
   reintroduce a literal password** — `config-repo` is public, and history is forever once
   pushed. Phase 14 replaces the default with Secret Manager.
8. **`java` on PATH is Java 8.** Use JDK 21: `JAVA_HOME=C:\Users\Karthik\.jdks\ms-21.0.12`.
   `mvn` is not on PATH at all — use each module's `./mvnw`.
9. **Both DBs share `localhost:3306`.** The design called for 3306/3307. Fine locally;
   Compose and the GCP data VM will split the schemas properly. Be honest about this.
10. **Eureka registration lags roughly 40 s after boot** (client replication interval). An
    empty `/eureka/apps` immediately after startup is normal, not a failure.
11. Maven needs network on first run — don't pass `-o`.
12. `.idea/` is intentionally untracked; `.run/` is intentionally tracked.
13. **The local toolchain for Part B does not exist yet.** No `gcloud`, `kubectl`, `docker`,
    `helm` or `terraform` on this machine. Phase 12 installs them.

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
