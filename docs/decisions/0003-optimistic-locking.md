# ADR-0003 — Optimistic locking for stock, with retry outside the transaction

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; implemented in Phase 4)

## Context

Two orders arrive at the same moment for the last unit of a product. Both read
`availableQuantity = 1`, both decide there is enough, both write `availableQuantity = 0`.
One unit has been sold twice. The lost-update problem, and for an inventory system it is the
single most damaging bug available — it produces a promise to a customer that cannot be kept.

Read-check-write on a shared row needs concurrency control. The question is which kind.

## Decision

Optimistic locking via a JPA `@Version` column, with the **retry placed outside the
transaction**.

```java
@Entity
public class Inventory {
    @Version
    private Long version;
}
```

Hibernate then emits:

```sql
UPDATE inventory SET available_quantity = ?, version = 4
 WHERE id = ? AND version = 3
```

If another transaction already moved the version to 4, this matches zero rows, Hibernate
raises `OptimisticLockingFailureException`, and the transaction rolls back. Nothing is
oversold — the second writer is simply told to try again.

### Why the retry is where it is

This is the part that is easy to get wrong, and it is why there are two beans rather than one:

```java
// InventoryService — retry lives here, NO @Transactional
public ReserveOutcome reserve(...) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return inventoryTxService.reserveOnce(...);   // fresh transaction each time
        } catch (OptimisticLockingFailureException e) {
            sleepWithJitter(attempt);
        }
    }
}

// InventoryTxService — @Transactional lives here
@Transactional
public ReserveOutcome reserveOnce(...) { ... }
```

Retrying *inside* the transaction cannot work. Once the optimistic lock fails the transaction
is already marked rollback-only, and the persistence context still holds the stale entity —
so every retry re-reads the same stale version from the first-level cache and fails
identically. The retry must start a **new** transaction, which means crossing a proxy
boundary, which means a second bean. A self-call would bypass the Spring proxy entirely and
silently run with no new transaction at all.

Backoff is bounded and **jittered**. Without jitter, two threads that collide back off by the
same amount and collide again on the same schedule.

## Consequences

**What this buys.** No locks are held while the transaction runs, so throughput under low
contention is essentially unaffected — the common case pays nothing. Deadlock is impossible,
because no row is ever locked while waiting for another. And the failure mode is safe: under
extreme contention a request gives up with an error, rather than overselling.

**What it costs.** Under *sustained* high contention on one row, retries waste work and
latency grows. That is the correct trade for stock, where the row is usually contended only
briefly, but it would be the wrong choice for a hot counter.

**Verification.** The concurrency test was itself verified by mutation: removing `@Version`
makes it fail. A concurrency test that still passes with the protection removed is proving
nothing, and that check is the only reason to trust it. The benchmark later confirmed the
behaviour at scale — 1000 concurrent orders, zero oversell, stock reconciling exactly.

**A measured surprise.** Row contention was assumed to be the platform's throughput
bottleneck. It was tested — the same load spread across 20 SKUs instead of 1 — and the
difference was nil (8.1 vs 8.7 orders/sec). The real limit was storage `fsync` latency. See
[`docs/benchmark/RESULTS.md`](../benchmark/RESULTS.md). The lock is not the constraint here.

## Alternatives rejected

**Pessimistic locking — `SELECT ... FOR UPDATE`.** Correct, and simpler to reason about: no
retry logic, no version column, no two-bean split. Rejected because it holds a database lock
for the duration of the transaction. Every concurrent order for the same product serialises
behind it, and lock ordering across multi-item orders introduces genuine deadlock risk. It
would be the right call if contention were the normal case rather than the exception.

**A single atomic conditional UPDATE** — `UPDATE inventory SET available = available - ?
WHERE id = ? AND available >= ?`, then check the affected row count. Genuinely the fastest
option and free of retries. Rejected because it bypasses JPA entirely and splits the domain
logic between Java and SQL; with several fields to maintain (`availableQuantity`,
`reservedQuantity`) and a reservation row to insert in the same transaction, the saving
disappears. Worth reaching for if a single product ever becomes a genuine hot spot.

**Application-level locking (`synchronized`, a distributed lock).** `synchronized` is simply
wrong the moment there is more than one instance — and horizontal scaling is the point of the
architecture. A distributed lock via Redis or ZooKeeper would work but adds infrastructure to
solve a problem the database already solves correctly.
