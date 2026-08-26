# Benchmark results

Every performance number quoted in the README and the interview guide comes from the runs
below. The raw output is reproduced verbatim so the claims can be checked rather than
believed. Nothing here is estimated, extrapolated or rounded in the project's favour.

**Measured:** 2026-08-26, Phase 11.

## Environment

This matters more than usual, because the headline finding *is* an environment effect.

| | |
|---|---|
| Host | Windows 11, 12 logical CPUs, 16 GB RAM |
| Runtime | Docker Desktop (WSL 2 backend), disk image on the `F:` drive |
| Stack | all 10 Compose services on one machine — 7 JVMs, Kafka, 2× MySQL 8.0 |
| Per-container limits | 512 MB / 768 MB (`mem_limit`), no CPU pin |
| Load generator | `bench.py`, running on the same host and competing for the same CPUs |

Seven JVMs, a broker and two databases on one laptop, with the load generator alongside
them. These are **development-environment numbers, not production numbers**. Their value is
in the *relative* comparisons and the bottleneck they expose, not the absolute figures.

## Reproducing

```bash
docker compose up -d               # wait for all 10 to report healthy
python docs/benchmark/bench.py --orders 200 --concurrency 20 --products 20
```

## Summary of the four runs

| # | Orders | Conc. | Products | `innodb_flush_log_at_trx_commit` | Accept/sec | POST p50 | E2E p50 | Settled in window |
|---|--------|-------|----------|----------------------------------|-----------|----------|---------|-------------------|
| 1 | 200 | 20 | 1 | `1` (durable) | 8.7 | 2203 ms | 68280 ms | 157 / 200 |
| 2 | 200 | 20 | 20 | `1` (durable) | 8.1 | 2271 ms | 70038 ms | 168 / 200 |
| 3 | 200 | 20 | 20 | `2` (relaxed) | **119.4** | **189 ms** | **6183 ms** | **200 / 200** |
| 4 | 1000 | 50 | 50 | `2` (relaxed) | 108.5 | 251 ms | 27932 ms | **1000 / 1000** |

*Accept* is `POST /api/orders` returning `201`. *E2E* is POST until the order reaches a
terminal status — the full asynchronous saga: outbox poll → Kafka → reserve → payment →
settle.

## How the bottleneck was found

The interesting part is not the final number; it is that the **first hypothesis was wrong**,
and the data said so.

### Hypothesis 1: contention on the stock row — refuted

Run 1 put all 200 orders against a single product, so every reservation contended for the
same `inventory` row and the optimistic-lock retry serialised them. An obvious suspect for a
p50 of 68 seconds.

Run 2 spread the identical load across 20 SKUs, removing that contention entirely. The result
was **8.1 orders/sec against 8.7** — no improvement, in fact marginally worse (run-to-run
noise). Row contention was not the limiter, and every further minute spent tuning the retry
strategy would have been wasted. This is why `--products` is a flag on the benchmark rather
than a constant: it separates "how fast is the platform" from "how fast can one row be
updated".

### Narrowing it down

With the load spread out, each stage was measured in isolation on an idle system:

```
gateway -> order POST: 0.872s 0.756s 0.556s 0.469s 0.767s
direct  order POST:    0.515s 0.950s 0.607s              (bypassing the gateway, :8081)
payment POST:          0.005s 0.005s 0.005s
```

Two things fell out immediately. The gateway costs essentially nothing — direct calls to
`:8081` are the same speed, so routing and the correlation-ID filter are not the problem. And
payment answers in **5 ms**, so the synchronous HTTP call inside the saga is not the problem
either.

That leaves the accept path itself: ~600 ms, on an idle system, to insert an order, its
items, and one outbox row. Far too slow for three inserts in a single transaction.

### Hypothesis 2: commit latency — confirmed

Measured inside the database container, with no Java, Spring or Kafka anywhere near it:

```
innodb_flush_log_at_trx_commit = 1
sync_binlog                    = 1

20 autocommit inserts: 3810.49 ms total | 190.52 ms per commit
```

**190 ms to commit a single-column insert.** MySQL is configured for full durability — an
`fsync` of the redo log *and* the binlog on every commit — and on Docker Desktop's virtualised
disk each of those costs tens of milliseconds.

That one number explains everything else. A single order requires roughly five committed
transactions spread across the two services: write order + outbox, mark the outbox row sent,
reserve inventory + record the processed event, settle the order + write the next outbox row,
mark that one sent. At ~190 ms each that is **~1 second of pure fsync per order**, and the
Kafka consumers are single-threaded, so it is a *serial* second per order. 200 orders ≈ 200
seconds of unavoidable disk waiting, against an observed p50 of 68 s and a 120 s window that
32–43 orders failed to make. The model fits the measurements.

### The controlled test

Both settings are dynamic, so they were changed with no restart and nothing else touched:

```sql
SET GLOBAL innodb_flush_log_at_trx_commit = 2;   -- flush to OS, fsync once per second
SET GLOBAL sync_binlog = 0;
```

```
20 autocommit inserts: 726.31 ms total | 36.32 ms per commit    (was 190.52 ms)
```

The identical load then produced run 3:

| Metric | Durable (run 2) | Relaxed (run 3) | Change |
|---|---|---|---|
| Accept throughput | 8.1 /sec | 119.4 /sec | **14.7× faster** |
| POST p50 | 2271 ms | 189 ms | **12× faster** |
| POST p99 | 3450 ms | 450 ms | 7.7× faster |
| End-to-end p50 | 70038 ms | 6183 ms | **11× faster** |
| Settled within 120 s | 168 / 200 | 200 / 200 | all of them |

One storage setting, nothing else. The application code, the JVM settings, the Kafka
configuration and the retry policy were untouched between the two runs.

**The durable settings were restored immediately afterwards** and verified
(`@@innodb_flush_log_at_trx_commit = 1`, `@@sync_binlog = 1`). Relaxing them is defensible for
a benchmark on a laptop; it is a genuine durability trade-off — up to a second of committed
transactions can be lost on host failure — and is not a change that should be made to a real
order database without deciding, deliberately, that the risk is acceptable.

## Scale check: 1000 orders

Run 4 raised the load fivefold to see where the pipeline saturates.

```
accepted 1000 orders in 9.22s  (108.5 orders/sec)

--- POST /api/orders latency (ms) ---
  n=1000  min=19  p50=251  p95=1513  p99=2766  max=2777  mean=458

--- end-to-end: POST until terminal status (ms) ---
  n=1000  min=5237  p50=27932  p95=34482  p99=34811  max=35186  mean=26417

--- outcomes ---
  CONFIRMED          1000
```

Two things worth noting.

**Accept throughput held flat** — 108.5/sec at 1000 orders against 119.4/sec at 200. The
write path is already saturated at roughly 110 orders/sec on this hardware and degrades
gracefully rather than collapsing; the cost shows up as queueing in the tail (p95 1513 ms,
p99 2766 ms) while p50 stays at 251 ms.

**Settlement is the narrower pipe.** The whole run drained in about 35 seconds of wall clock,
so the saga sustained roughly **29 orders/sec end to end** — about a quarter of the accept
rate. That is expected and is the system behaving as designed: acceptance is one local
transaction, settlement is five transactions and two network hops. It is also why the outbox
exists. The backlog is durable; a producer running 4× faster than the consumer builds a
queue, it does not lose orders.

**Correctness held at scale.** 1000 of 1000 orders reached `CONFIRMED`, and the final stock
figures reconciled exactly — no oversell, no double-reservation, no order stuck mid-saga. The
idempotency and optimistic-locking machinery was under real concurrent load here, not just
under unit tests.

## The other ceiling: partition count

```
Topic: order.placed         PartitionCount: 1
Topic: inventory.reserved   PartitionCount: 1
Topic: inventory.failed     PartitionCount: 1
Topic: order.confirmed      PartitionCount: 1
Topic: order.cancelled      PartitionCount: 1
```

Every topic has **one partition**, so each consumer group is capped at one consumer doing
useful work. Adding a second `inventory-service` instance today would not increase
settlement throughput at all — the second instance would sit idle.

This is a deliberate simplicity choice for a single-machine development stack, not an
oversight, but it is the first thing that would have to change to scale out. Partitioning by
`orderId` would preserve per-order ordering while allowing parallel consumers, because every
event for one order carries the same key and would land on the same partition. Ordering
*between* different orders is not something this system needs.

## What these numbers do and do not support

**Supported by the measurements above:**

- Accepting an order is one local transaction and returns without waiting for Kafka,
  inventory or payment — p50 189 ms under load once storage is not the constraint.
- The platform sustains ~110 accepted orders/sec and ~29 fully-settled orders/sec on a single
  developer machine running all ten containers.
- 1000 concurrent orders settled with zero oversell and zero lost orders.
- Throughput in the default configuration is bound by storage `fsync` latency, demonstrated
  by a 14.7× improvement from changing one durability setting and nothing else.

**Not supported, and not claimed:**

- Nothing here says anything about performance on real hardware, on GKE, or against a managed
  database. Different disks, different network, different numbers.
- No comparison against a synchronous implementation of the same flows was run, so "the
  asynchronous design is faster" is not a claim this file backs.
- The 120-second settle window in runs 1 and 2 was a *timeout*, not a failure: those orders
  were still progressing. They are reported as "did not settle within the window" rather than
  as errors, which is why runs 1 and 2 report 157/200 and 168/200 rather than losses.

## Appendix: raw output

### Run 1 — 200 orders, concurrency 20, one product, durable

```
seeded product 2 with 250 units
firing 200 orders at concurrency 20...

accepted 200 orders in 23.07s  (8.7 orders/sec)

--- POST /api/orders latency (ms) ---
  n=200  min=1054  p50=2203  p95=2969  p99=3573  max=3573  mean=2219

--- end-to-end: POST until terminal status (ms) ---
  n=157  min=19579  p50=68280  p95=117956  p99=122741  max=122889  mean=67517

--- outcomes ---
  CONFIRMED          157
  STILL PENDING      43   (did not settle within 120s)

--- final stock ---
  {"productId": 2, "warehouseId": "WH-1", "availableQuantity": 50, "reservedQuantity": 56}
```

### Run 2 — 200 orders, concurrency 20, 20 products, durable

```
seeded 20 product(s), 60 units each
firing 200 orders at concurrency 20...

accepted 200 orders in 24.73s  (8.1 orders/sec)

--- POST /api/orders latency (ms) ---
  n=200  min=301  p50=2271  p95=3157  p99=3450  max=3590  mean=2324

--- end-to-end: POST until terminal status (ms) ---
  n=168  min=22310  p50=70038  p95=120121  p99=122717  max=123330  mean=70486

--- outcomes ---
  CONFIRMED          168
  STILL PENDING      32   (did not settle within 120s)

--- final stock (first product) ---
  {"productId": 3, "warehouseId": "WH-1", "availableQuantity": 50, "reservedQuantity": 1}
```

### Run 3 — 200 orders, concurrency 20, 20 products, relaxed fsync

```
seeded 20 product(s), 60 units each
firing 200 orders at concurrency 20...

accepted 200 orders in 1.67s  (119.4 orders/sec)

--- POST /api/orders latency (ms) ---
  n=200  min=13  p50=189  p95=441  p99=450  max=452  mean=166

--- end-to-end: POST until terminal status (ms) ---
  n=200  min=1720  p50=6183  p95=7799  p99=7929  max=7999  mean=6002

--- outcomes ---
  CONFIRMED          200

--- final stock (first product) ---
  {"productId": 24, "warehouseId": "WH-1", "availableQuantity": 50, "reservedQuantity": 0}
```

### Run 4 — 1000 orders, concurrency 50, 50 products, relaxed fsync

```
seeded 50 product(s), 70 units each
firing 1000 orders at concurrency 50...

accepted 1000 orders in 9.22s  (108.5 orders/sec)

--- POST /api/orders latency (ms) ---
  n=1000  min=19  p50=251  p95=1513  p99=2766  max=2777  mean=458

--- end-to-end: POST until terminal status (ms) ---
  n=1000  min=5237  p50=27932  p95=34482  p99=34811  max=35186  mean=26417

--- outcomes ---
  CONFIRMED          1000

--- final stock (first product) ---
  {"productId": 44, "warehouseId": "WH-1", "availableQuantity": 50, "reservedQuantity": 0}
```
