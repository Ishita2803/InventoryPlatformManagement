# ADR-0001 — Publish events through a transactional outbox

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; implemented in Phase 5)

## Context

`order-service` must do two things when an order is placed: persist the order to MySQL, and
publish an `order.placed` event to Kafka. These are two different systems. There is no
transaction that spans both.

Whatever order they are done in, there is a window where the process can die:

```
INSERT order; COMMIT;  ✗ crash ✗  publish        → order exists, nobody hears about it.
                                                    Stock is never reserved. Order is
                                                    stuck at PENDING for ever.

publish;  ✗ crash ✗  INSERT order; COMMIT;       → inventory reserves stock for an order
                                                    that does not exist. Stock is held by
                                                    nothing, and never released.
```

Both outcomes are silent. Neither throws an error anyone sees. The second is worse, because
it loses real stock.

## Decision

Write the event to an `outbox_event` table **in the same transaction as the business change**.
A separate scheduled poller reads committed rows, publishes them to Kafka, and marks them
`SENT`.

```java
@Transactional
public OrderResponse createOrder(CreateOrderRequest request) {
    Order order = orderRepository.save(...);   //  ─┐
    outboxWriter.write("order.placed", ...);   //   ├─ one transaction, one commit
    return OrderResponse.from(order);          //  ─┘
}
```

The atomicity problem disappears because there is now only **one** system being written to.
Either both rows commit or neither does.

## Consequences

**What this buys.** No lost events and no phantom events, under any crash timing. If the
process dies after commit but before the poller runs, the row is simply published after
restart. The outbox is also a durable audit log of everything the service intended to say.

**What it costs.**

- *Extra latency.* An event is published on the next poll rather than instantly. With a
  1-second poll interval, that is up to a second of added end-to-end latency. Acceptable
  here — the whole flow is already asynchronous and the client polls for status.
- *At-least-once delivery, not exactly-once.* The poller can publish a row and crash before
  marking it `SENT`, so it republishes on restart. This is deliberate: duplicates are made
  harmless by consumer-side idempotency (see [ADR-0004](0004-idempotency-via-processed-event.md)).
  Losing an event is unrecoverable; delivering it twice is a solved problem.
- *A table to maintain.* Sent rows accumulate and need pruning. Not yet implemented.

## Alternatives rejected

**Publish inside the transaction, before commit.** Does not work. Kafka has no idea about the
database transaction, so a rollback after a successful publish leaves the event out in the
world with no order behind it. It converts a crash window into a *rollback* window, which is
strictly more likely.

**`@TransactionalEventListener(phase = AFTER_COMMIT)`.** Genuinely tempting, and much less
code. But the listener runs in memory after the commit — if the process dies in the
microseconds between commit and listener, the event is gone with no record that it was ever
owed. It narrows the window without closing it, which is the worst kind of fix: it makes the
bug rare enough to reach production and hard enough to reproduce that nobody believes it.

**Change Data Capture (Debezium reading the binlog).** This is the better answer at scale — no
polling, lower latency, and the outbox table needs no status column. Rejected because it adds
Kafka Connect and a binlog reader to the deployment, and the point of this project is to
demonstrate that the *problem* is understood. The outbox table makes the mechanism visible in
the code; Debezium makes it visible in the infrastructure. Both are correct, and the migration
path from the table to CDC is short.

**Kafka transactions with `exactly_once_v2`.** Solves publishing atomicity between Kafka
topics. It does not span Kafka and MySQL, which is the actual problem here.
