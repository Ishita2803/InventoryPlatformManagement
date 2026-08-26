# ADR-0004 — Consumer-side idempotency via a `processed_event` table

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; implemented in Phase 6)

## Context

Kafka gives at-least-once delivery, and the outbox publisher
([ADR-0001](0001-transactional-outbox.md)) deliberately keeps it that way. So the same event
*will* arrive twice. Guaranteed sources of duplicates:

- the outbox poller publishes a row, then crashes before marking it `SENT`;
- a consumer processes a record, then crashes before its offset is committed;
- a consumer group rebalances and redelivers uncommitted records;
- the error handler retries a record after a transient failure.

If `inventory.reserved` is processed twice, the order is confirmed twice and the stock is
deducted twice. Every event handler in the system is a state mutation, so every one of them
needs protecting.

## Decision

Each consuming service owns a `processed_event` table with a unique constraint on the event
ID. The consumer inserts the ID **in the same transaction as the business change**:

```java
@Transactional
public void handle(InventoryReservedEvent event) {
    processedEventRepository.save(new ProcessedEvent(event.eventId()));  // unique constraint
    order.setStatus(CONFIRMED);
    outboxWriter.write("order.confirmed", ...);
}
```

On the second delivery the insert violates the constraint, the exception rolls the whole
transaction back, and the business change does not happen. Delivery stays at-least-once;
the **effect** becomes exactly-once.

The crucial property is that the marker and the business change share one commit. If the
marker were written separately — before or after — a crash between them would either skip a
real event or reprocess one.

## Consequences

**What this buys.** Every handler in the system is safe to retry, which in turn makes the
outbox's at-least-once guarantee acceptable and the error handler's retries safe. It is the
mechanism the rest of the reliability design leans on.

**What it costs.**

- *A table per service that grows for ever* unless pruned. Not yet implemented.
- *An extra insert per event.* Measurably cheap, and dwarfed by the commit itself.
- *Duplicate detection is per-service.* Each service keeps its own table, which is correct —
  a shared one would couple them and become a single point of failure — but means the same
  event ID is stored in several places.

**A real failure this caused, and the gap it exposed.** During Phase 8 three orders became
permanently stuck at `INVENTORY_RESERVED`, holding 6 units of stock. The cause: the
`processed_event` insert committed, but the settlement work that should have shared that
transaction failed afterwards. On redelivery the event was correctly recognised as already
processed and skipped — so the order never advanced, and never would.

This is the honest limitation of the pattern. Marking an event processed is a promise that
the work was done, and if the work is ever *not* in that same transaction, the marker becomes
a lie that suppresses all future attempts. It is why a **reconciliation job** for stale
reservations is recorded as a required item rather than a nice-to-have: idempotency prevents
double work, but it cannot detect work that silently never finished.

## Alternatives rejected

**Rely on Kafka's `enable.idempotence` producer setting.** It prevents duplicates caused by
*producer retries* within a session. It does nothing about consumer redelivery, rebalances or
outbox republication, which are the actual sources here.

**Kafka transactions with `read_process_write` and `exactly_once_v2`.** Genuinely provides
exactly-once *within* Kafka — consume, produce, commit offsets atomically. Rejected because
the business write goes to MySQL, and Kafka's transaction cannot include it. The gap between
"offsets and output topic committed" and "MySQL committed" is exactly the gap that needs
closing, and this leaves it open.

**Make every handler naturally idempotent.** The ideal solution where it is achievable —
setting a status to `CONFIRMED` twice is harmless. Rejected because it is not achievable for
the operations that matter: `availableQuantity -= n` is not idempotent, and neither is
inserting a reservation row. Partial natural idempotency is worse than none, because it
invites the assumption that the rest is covered too.

**Deduplicate on `(topic, partition, offset)` instead of an event ID.** Ties correctness to
Kafka's physical layout. Republishing the same logical event from the outbox produces a new
offset, so the duplicate would sail straight through. A business-level event ID, generated
once when the outbox row is written, survives republication — which is the case that has to
work.
