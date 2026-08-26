# ADR-0008 — Reconcile state, because replaying messages cannot fix everything

**Status:** Accepted · **Date:** 2026-08-26 · **Implemented:** Phase 11.5

## Context

Two failures in this project stranded stock permanently. Neither was caused by a bug in the
messaging layer — both happened while every component behaved exactly as designed.

**Failure 1 — the idempotency marker outlives the work.** A consumer writes its
`processed_event` row and moves the order to `INVENTORY_RESERVED`; that transaction commits.
The settlement that should follow then fails. Kafka redelivers, the consumer correctly sees
the event in `processed_event`, and skips it. The order never advances and never will. Three
orders were lost this way in Phase 8, holding six units.

**Failure 2 — the dead-letter topic is write-only.** Benchmark run 1 pushed 200 orders at a
single product, so every confirmation contended for the same `inventory` row. Nine exhausted
the four-attempt optimistic-lock retry budget, the listener's error handler retried three more
times, and the records went to `order.confirmed.DLT`. Nine reservations stayed `RESERVED`
against orders that order-service had already marked `CONFIRMED`.

The common shape is important: **in both cases the message layer had already reached its
correct terminal decision.** Redelivering achieves nothing in the first case (the consumer is
right to skip) and had already been tried and abandoned in the second.

## Decision

Add reconciliation that works from **persisted state**, not from the message stream, on both
sides of the boundary.

- **`OrderReconciliationService`** (order-service) sweeps orders sitting in
  `INVENTORY_RESERVED` past a threshold and re-drives settlement. It never touches
  `processed_event` — it re-runs the *settlement* transaction, which was never marked done.
- **`SettlementRecoveryService`** (inventory-service) drains `order.confirmed.DLT` and
  `order.cancelled.DLT` on a timer and re-applies them.

Both are safe to run repeatedly, which is the property that makes them possible at all:
payment is idempotent by `orderId`; `settleOrder` refuses to act on a terminal order; and
`confirmByOrderId` / `releaseByOrderId` both filter on `status = RESERVED`, so a replayed
settlement matches no rows.

## Consequences

**What this buys.** The two failure modes that actually occurred are now recoverable without
human intervention, and the recovery is observable — each sweep returns a report distinguishing
*confirmed*, *cancelled*, *waiting* and *skipped*, because "processed 12 orders" hides whether
the system is recovering healthily or drowning.

Running it against the live database resolved the real inconsistency: nine stranded
reservations confirmed, nine units released, and `orders CONFIRMED` in one database finally
equal to `reservations CONFIRMED` in the other — 1608 each.

**What it costs.**

- *A second writer to the order row.* This forced `@Version` onto `Order`, deferred since
  Phase 2 on the explicit grounds that concurrent updates were not yet possible. They are now.
- *Two more scheduled jobs*, each of which must be prevented from killing its own schedule by
  throwing — both wrap their sweep in a catch-all.
- *Thresholds are a judgement call.* Too tight and the sweeper races the Kafka listener over
  live orders; too loose and stock sits held for longer than it should.
- *It is a safety net, not a fix.* The root cause of failure 2 is that confirm/release use
  read-modify-write with an optimistic lock, when they are pure relative adjustments
  (`reserved -= n`) that a single atomic `UPDATE` could apply with no contention failure mode
  at all. That is the better fix and it is not done. Reconciliation makes the symptom
  recoverable rather than making it not happen.

## Alternatives rejected

**Just increase the retry budget.** Would reduce failure 2's frequency and not eliminate it —
under enough contention any bounded budget is exhausted eventually, and an unbounded one turns
contention into an outage. It does nothing at all for failure 1, where retrying is precisely
what the consumer correctly refuses to do.

**A `@KafkaListener` on the dead-letter topics.** Much less code than a scheduled drain, and
wrong. It consumes each record milliseconds after it arrives — while whatever caused the
failure is still happening — fails again immediately, and produces a hot retry loop against
an already-contended row. Draining on a timer gives the transient condition time to pass,
which for lock contention is the entire fix.

**Have inventory-service sweep its own stale `RESERVED` reservations.** Appealing, because it
needs no cross-service anything. Rejected because inventory **cannot decide what to do**: a
reservation stuck at `RESERVED` should be confirmed if its order was paid and released if it
was cancelled, and inventory does not know which. Guessing "confirm" loses stock that should
have returned; guessing "release" returns stock that was already shipped, which oversells
later. The dead letter is valuable precisely because it *carries the decision* — that is why
replaying it works when sweeping state does not.

**Have order-service resume stuck `PENDING` orders too.** The reconciler reports these and
deliberately does not act. Resuming means guessing whether inventory holds stock, and being
wrong in either direction is a correctness bug. Inventory owns that answer, and asking for it
would mean a synchronous call from order-service into inventory-service — reintroducing
exactly the coupling [ADR-0002](0002-choreography-over-orchestration.md) spends real effort
avoiding. Reported for a human, not resolved automatically.

**Cancel stuck orders when payment is unreachable.** This is what the live listener does, and
it is right there — a customer is waiting. Applying the same rule in a *sweep* would let a
five-minute provider outage cancel an entire backlog at once, converting a temporary failure
into permanently lost business. The reconciler therefore treats `UNAVAILABLE` as "try again
next pass", with a much longer ceiling after which it gives up and frees the stock anyway.
