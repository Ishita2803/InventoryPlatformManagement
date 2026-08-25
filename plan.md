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
| Config backend | Git backend, relative URI | No machine-specific absolute paths in version control. |
| `config-repo` | Real git submodule | Preserves the git-backed-config story. |
| Cloud sequencing | **All local first** | Chosen 2026-08-25. Trade-off noted above. |
| MySQL on GCP | **Compute Engine VM**, not Cloud SQL | Cheaper, and you wanted to manage it. Cloud SQL's cheapest tier costs more than the rest of this deployment combined. |
| Kafka on GCP | **Same VM as MySQL** | Confirmed feasible on an `e2-medium` (4 GB) with tuned heaps. Cheapest option, keeps stateful workloads off Kubernetes, and reuses the Phase 3 Compose file almost verbatim. Single broker = no real HA; say so honestly in interviews. |
| Eureka on GKE | **Dropped** | Kubernetes Service DNS already does discovery. Eureka stays for local runs; a `k8s` profile switches the gateway to DNS routes. Saves a pod and is the answer a Kubernetes-literate interviewer wants. |
| Config Server on GKE | **Kept** | Git-backed config with an audit trail is a genuine capability, and it becomes the place Secret Manager values land. |
| Secrets on GKE | **Secret Manager via the CSI driver → env vars** | Framework-version-independent. A `spring-cloud-gcp-starter-secretmanager` property source would be more elegant but has no confirmed Boot 4.1 release — do not assume one exists. |
| Public access | Static IP + HTTP first, TLS optional | A GCLB forwarding rule is roughly $18/mo, more than every VM combined. Phase 16 starts on the cheap path and documents the upgrade. |

---

# Part A — Local

## Phase 0 — Unblock and tidy ✅ *(done 2026-08-21, not yet committed)*

- [x] Config Server URI → `file:${CONFIG_REPO_PATH:../config-repo}`
- [x] `.run/config-service.run.xml` pins the working directory for IntelliJ
- [x] Rename `config-repo/api-gateway.yaml` → `api-gateway-service.yaml`
- [x] `api-gateway-service` and `notification-service` made real config clients
- [x] Root `.gitignore`; untrack `.idea/`
- [x] `config-repo` registered as a real submodule
- [x] Verified: all four clients boot on Config-Server-supplied ports and register in Eureka

**Exit:** met. `http://localhost:8888/order-service/default` returns populated
`propertySources`, and all four services show `UP` at `http://localhost:8761`.

## Phase 0.5 — Commit the backlog *(do this first — nothing below is safe until it lands)*

Phase 0's work is **entirely uncommitted**. `git status` shows `CLAUDE.md`, `.gitignore`,
`.run/` and `docs/` untracked plus six modified files. One careless `git checkout` loses
all of it.

- [ ] Create `https://github.com/Karthik0770/order-platform-config-repo` on GitHub and
      push `config-repo`'s `master`. **The submodule URL is currently a dead link** —
      anyone cloning gets an empty `config-repo/` and every config client fails to start.
- [ ] Commit `config-repo`'s own history, then bump the gitlink in the parent repo
- [ ] Commit Phase 0 in the parent repo: `.gitignore`, `.run/`, `Agent.md`, `plan.md`,
      the `.idea/` deletions, and the six modified files
- [ ] Push the parent repo; verify a fresh `git clone --recurse-submodules` into a temp
      directory yields a populated `config-repo/`

**Exit:** a clean `git status`, and a throwaway clone starts `config-service` successfully.

## Phase 1 — Inventory hardening *(parallel with Phase 2)*

The source conversation is explicit that reservation must be correct **before** Kafka arrives.

- [ ] **`Reservation` entity** — `orderId`, `productId`, `warehouseId`, `quantity`,
      `status` (`RESERVED` / `RELEASED` / `CONFIRMED`), unique constraint on
      `(orderId, productId, warehouseId)`. Reserve and release become *order-scoped*
      rather than quantity-only.
      *This is the highest-leverage change in the project.* Without `orderId` there is no
      way to make the Kafka consumer idempotent, and no way to release everything
      belonging to one order during Saga compensation.
- [ ] Optimistic-lock retry around reserve; map `ObjectOptimisticLockingFailureException`
      to **409**, not a 500 stack trace
- [ ] Handle `MethodArgumentNotValidException` → **400** with field errors
- [ ] Replace the bare `RuntimeException`s in `InventoryService.createProduct`,
      `addInventory` and `getInventory` with typed exceptions the handler already knows
- [ ] Unit tests (JUnit 5 + Mockito): sufficient stock succeeds · insufficient stock
      rejected · release restores stock · negative quantity rejected · unknown
      product/inventory errors cleanly · **concurrent reservations do not oversell**

**Exit:** all tests green, and the concurrency test demonstrably prevents overselling.

## Phase 2 — Order domain *(parallel with Phase 1)*

- [ ] `Order`, `OrderItem`, `OrderStatus` (`PENDING`, `INVENTORY_RESERVED`,
      `INVENTORY_FAILED`, `CONFIRMED`, `CANCELLED`)
- [ ] Repository, service, DTOs, mapper, `GlobalExceptionHandler`, validation
- [ ] `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders`
- [ ] Unit tests
- [ ] **No Kafka in this phase**

**Exit:** `POST /api/orders` persists an order in `order_db` with status `PENDING`.

## Phase 3 — Kafka and the first async flow

- [ ] `docker-compose.yml` — Kafka in **KRaft mode** (no ZooKeeper) plus both MySQL
      schemas. Write it so the same file can later run on the GCP data VM.
- [ ] Topics: `order.placed`, `inventory.reserved`, `inventory.failed`
- [ ] Event **records**, defined separately from JPA entities, duplicated per service,
      each carrying `eventId` for later idempotency
- [ ] Order publishes `OrderPlaced` via `KafkaTemplate` (plain publish for now)
- [ ] Inventory consumes, reserves, publishes `InventoryReserved` / `InventoryFailed`
- [ ] Order consumes the result → `CONFIRMED` / `INVENTORY_FAILED`

**Exit:** one `POST /api/orders` drives the full async round-trip; final status verified in MySQL.

## Phase 4 — Saga correctness

- [ ] `processed_events` table + idempotent consumer (straightforward now that
      reservations are keyed by `orderId`)
- [ ] Bounded retry + `DeadLetterPublishingRecoverer` → `order.placed.DLT`
- [ ] Compensating release when a downstream step fails

**Exit:** replaying the same `OrderPlaced` twice reserves stock exactly once; a poisoned
message lands in the DLT instead of looping forever.

## Phase 5 — Transactional Outbox (Order Service)

- [ ] `outbox_event` table written in the **same transaction** as the order
- [ ] Scheduled publisher drains the outbox to Kafka and marks rows published
- [ ] Retry on publish failure

**Exit:** killing Kafka mid-`POST /api/orders` still yields a consistent order plus a
pending outbox row that publishes once Kafka returns.

## Phase 6 — Notification Service

- [ ] Consume `inventory.reserved` / `inventory.failed`; log a mock email
- [ ] Deliberately no knowledge of order-database internals

**Exit:** a placed order produces a notification log line without Notification touching MySQL.

## Phase 7 — API Gateway

- [ ] Routes for `/api/orders/**` and `/api/inventory/**`
- [ ] **Write routes profile-switched from day one:** `lb://ORDER-SERVICE` under the
      default (Eureka) profile, `http://order-service:8081` under the `k8s` profile.
      Doing this now avoids reworking the gateway in Phase 15.
- [ ] Correlation-ID filter, request logging, global error handling

**Exit:** every client call goes through `:8080`; no direct service ports needed.

## Phase 8 — Payment Service + Resilience4j

> **Design note:** in a pure-Kafka design there is no synchronous inter-service call, so a
> circuit breaker would be decoration. The mocked Payment Service exists to give
> Resilience4j a genuine target — and to make the Saga a real Saga.

- [ ] `payment-service` (mocked), called **synchronously** from Order after reserve
- [ ] Resilience4j: timeout, retry, circuit breaker, fallback
- [ ] Payment failure → inventory release + order `CANCELLED`

**Exit:** with Payment stopped, the circuit opens, the fallback fires, and inventory is
released rather than leaked.

## Phase 9 — Containerise everything

This phase is the bridge to Part B. Get it right and Part B is plumbing.

- [ ] Multi-stage `Dockerfile` per service (JDK 21 build layer → JRE 21 runtime layer),
      non-root user, layered jar extraction for fast rebuilds
- [ ] `-XX:MaxRAMPercentage` set explicitly — the GKE nodes will be small and the JVM's
      default heap sizing will not fit them
- [ ] Every `localhost` hard-coding replaced by an env var with a localhost default
      (Config Server URL, Eureka URL, JDBC URL, Kafka bootstrap servers). **Nothing in
      Part B works until this is done.**
- [ ] `docker compose up` brings the entire platform up from images alone
- [ ] Actuator health and readiness wired into Compose healthchecks
- [ ] `.dockerignore` per service

**Exit:** `docker compose up` on a machine with no JDK installed runs the full happy path
and the full failure path.

## Phase 10 — Testing and CI

- [ ] Testcontainers integration tests (MySQL + Kafka), including the outbox path
- [ ] GitHub Actions: compile → unit → integration → jar → image build

**Exit:** a green CI run on a pull request, with images built but not yet pushed anywhere.

## Phase 11 — Documentation

- [ ] README with architecture diagram, happy-path flow, **failure-path flow**
- [ ] ADRs under `docs/decisions/` (start with: duplicated events, mocked payment,
      Eureka dropped on GKE, MySQL on a VM over Cloud SQL)
- [ ] OpenAPI/Swagger per service
- [ ] Benchmark: push synthetic order volume through Kafka and **measure** throughput,
      latency and retry rate. Quantify from real numbers — never invent them.

**Exit:** someone who has never seen the repo can understand and run it from the README alone.

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

## Phase 12 — GCP foundation and local toolchain

Nothing here touches the application. Do it in one sitting.

- [ ] **Install the local toolchain** — none of it is on this machine today:
      Google Cloud CLI, `kubectl` (`gcloud components install kubectl`), Docker Desktop.
      Also put JDK 21 on `PATH`; `java` currently resolves to Java 8.
- [ ] Create the GCP account and enable billing (free-trial credit applies if unused)
- [ ] **Set a budget with alert thresholds at 50 / 90 / 100 % before creating any
      resource.** This is the guardrail that stops a forgotten cluster becoming a
      surprise bill.
- [ ] Create project `order-platform`, set it as default, pick one region (`us-central1`)
      and stay in it — cross-region traffic is billed
- [ ] Enable APIs: `container`, `compute`, `artifactregistry`, `secretmanager`,
      `cloudbuild`, `iam`
- [ ] Create the Artifact Registry Docker repository
- [ ] Push one Phase 9 image to Artifact Registry as a smoke test
- [ ] Capture every command in `deploy/gcp/00-bootstrap.sh` — reproducible, and it doubles
      as documentation

**Exit:** `gcloud auth list` and `gcloud config list` are correct, the budget alert exists,
and one image is visible in Artifact Registry.

## Phase 13 — The data VM (MySQL + Kafka)

One `e2-medium` VM runs both, via the Phase 3 Compose file.

- [ ] Create a VPC (or use default) plus firewall rules. **MySQL 3306 and Kafka 9092 must
      be reachable only from the GKE node subnet — never from `0.0.0.0/0`.**
- [ ] Create the VM: `e2-medium`, Debian 12, 20 GB balanced PD, **no external IP** (reach
      it over IAP for SSH), Docker installed by startup script
- [ ] Deploy MySQL 8 + single-broker Kafka (KRaft) with Compose, both on named volumes so
      a VM restart doesn't lose data
- [ ] **Tune heaps for 4 GB total:** MySQL `innodb_buffer_pool_size` around 768 MB, Kafka
      heap around 768 MB. The defaults assume a dedicated machine and will OOM here.
- [ ] Advertise Kafka on the VM's **internal** IP — a broker advertising `localhost`
      accepts the connection and then hands clients an unreachable address, which presents
      as a mysterious timeout
- [ ] Create `order_db` and `inventory_db`, with a non-root application user per schema
- [ ] Reserve the VM's internal IP so it survives a restart

**Exit:** from a throwaway pod in GKE, `mysql` connects to both schemas and a Kafka console
producer/consumer round-trips a message.

## Phase 14 — Secret Manager

This phase deletes a real defect: `config-repo/order-service.yaml` and
`inventory-service.yaml` currently contain `password: "root"` **committed in plaintext**.

- [ ] Create secrets `mysql-order-password` and `mysql-inventory-password`
- [ ] Create a Google service account for the workloads; grant
      `roles/secretmanager.secretAccessor` scoped to those secrets, not project-wide
- [ ] Enable **Workload Identity** on the cluster and bind the Kubernetes service account
      to the Google one, so no JSON key file ever exists
- [ ] Install the **Secret Manager CSI driver**; mount the secrets and expose them as env vars
- [ ] Change the `config-repo` yamls to `password: ${MYSQL_PASSWORD}` — placeholders only
- [ ] Rotate the `root` password that was in git, and confirm it is no longer used anywhere
- [ ] *Optional, only if a Boot 4.1-compatible release exists:*
      `spring-cloud-gcp-starter-secretmanager` for `sm://` property references. Do not
      assume one exists — the CSI-driver path above carries no version risk.

**Exit:** a pod reads its DB password from Secret Manager, `git grep -i password` in
`config-repo` finds only placeholders, and the old credential is revoked.

## Phase 15 — GKE cluster and manifests

- [ ] Create a **zonal** cluster (regional multiplies control-plane cost and node count)
      with a Spot node pool of 2 × `e2-medium`, autoscaling 1-3
- [ ] `deploy/k8s/` — per service: `Deployment`, `Service`, liveness/readiness probes on
      Actuator, resource requests **and** limits, `ConfigMap` for non-secret env
- [ ] **Do not deploy `discovery-service`.** Add a `k8s` Spring profile that switches the
      gateway to Service-DNS routes and disables the Eureka client.
- [ ] Deploy `config-service` in-cluster; clients point at `http://config-service:8888`
- [ ] Set each JVM's `MaxRAMPercentage` against the **pod limit**, not the node size
- [ ] Verify pods survive a Spot preemption — that is the trade-off you're buying

**Exit:** all pods `Running` and `Ready`; a `kubectl port-forward` to the gateway runs the
full happy path and the full failure path against the data VM.

## Phase 16 — Public internet access

- [ ] Reserve a static external IP
- [ ] **Cheap path first:** expose the gateway via `NodePort` plus a firewall rule, or a
      single L4 `Service type=LoadBalancer`. Confirm the real cost of whichever you pick
      before leaving it up — a GCLB forwarding rule alone is around $18/mo, more than both
      VMs together.
- [ ] Only the gateway is public. Order, Inventory, Notification, Payment, Config and the
      data VM stay cluster-internal.
- [ ] Sanity-check that Actuator `env` / `heapdump` endpoints are **not** publicly exposed
- [ ] *Optional TLS:* a `nip.io` hostname over the static IP plus cert-manager with Let's
      Encrypt (free), or a GCE Ingress with a Google-managed certificate if you buy a domain
- [ ] Rate-limit the gateway — it is now on the public internet

**Exit:** `curl http://<static-ip>/api/orders` works from your phone on mobile data, and a
direct request to a service port from outside the cluster is refused.

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
