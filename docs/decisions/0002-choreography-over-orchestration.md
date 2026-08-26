# ADR-0002 — Choreographed saga rather than a central orchestrator

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; implemented in Phase 6)

## Context

Placing an order spans three services and cannot be a single transaction:

1. `order-service` creates the order,
2. `inventory-service` reserves the stock,
3. `payment-service` takes the money,
4. and if step 3 fails, step 2 must be **undone**.

A saga replaces the missing distributed transaction with a sequence of local transactions,
each with a compensating action. The design question is who decides what happens next.

- **Orchestration** — a coordinator holds the state machine and issues commands:
  "reserve stock", "take payment", "release stock".
- **Choreography** — each service reacts to events and publishes its own, with no central
  brain.

## Decision

Choreography. Services publish facts about what happened; other services decide what that
means for them.

```
order-service    ──order.placed──►      inventory-service
inventory-service──inventory.reserved──► order-service   (then calls payment)
order-service    ──order.confirmed──►   inventory-service, notification-service
order-service    ──order.cancelled──►   inventory-service (compensate), notification-service
```

`inventory-service` does not know that payment exists. It knows how to reserve stock, how to
confirm a reservation, and how to release one. `order-service` never commands it — it
announces that an order was placed, confirmed or cancelled, and inventory draws its own
conclusions.

## Consequences

**What this buys.**

- *Real decoupling.* Adding `notification-service` required changing **no existing service** —
  it subscribed to `order.confirmed` and `order.cancelled` and started working. That is the
  test of whether decoupling is genuine, and it passed.
- *No single point of failure* in the flow, and no service whose availability gates every
  order.
- *Naturally resilient.* Each consumer retries independently against its own dead-letter
  topic. A slow inventory service does not block order acceptance at all.

**What it costs, and this is the honest part.**

- *The flow is not written down anywhere.* No file describes the order lifecycle end to end.
  Understanding it means reading five listeners across three services and reconstructing the
  sequence mentally. This is the standard criticism of choreography and it is completely fair
   — it is why this repository has an architecture diagram and an interview guide, which is
  really documentation compensating for a design property.
- *Cyclic knowledge.* `order.confirmed` flows back to inventory, so the services are not in a
  clean layered hierarchy even though neither imports the other.
- *Debugging spans services.* Answering "why is this order stuck?" means correlating logs in
  three places, which is why correlation IDs exist here — and why the missing distributed
  tracing is listed as a real gap.

## Alternatives rejected

**A central orchestrator.** The right choice as step count grows — the flow becomes one
readable state machine, and adding a step is a local change. Rejected for two reasons. The
flow here is short (three steps and one compensation), so the readability benefit is small;
and the orchestrator quietly becomes a service that must know about every participant, which
recreates the coupling the split was meant to remove. Worth revisiting at seven or eight
steps, or the first time a step needs conditional branching.

**A workflow engine (Temporal, Camunda).** Solves durability and visibility properly and
would be a serious contender in production. Rejected as disproportionate: it introduces a
substantial runtime dependency and moves the interesting logic into a framework, when the
purpose here is to show the saga and compensation mechanics explicitly.

**Two-phase commit across MySQL and Kafka.** Not available in any practical form, and XA
across services is exactly the coupling and the availability trap that microservices exist to
avoid. A participant that fails while holding a prepared transaction blocks the others.

## Note on compensation

Compensation is a **new forward action**, not a rollback. Releasing stock is an ordinary
insert-and-update that happens to reverse an earlier effect. This matters because a
compensating action can itself fail and must therefore be retryable and idempotent — which it
is, via the same `processed_event` mechanism as everything else.
