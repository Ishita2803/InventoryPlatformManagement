# ADR-0005 — Duplicate event classes per service instead of a shared library

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; decided in Phase 5)

## Context

`OrderPlacedEvent` is produced by `order-service` and consumed by `inventory-service`. The
class is defined twice, once in each module, with the same fields. The obvious reaction is
that this is duplication and should be a shared `common-events` module — which is what most
tutorials do, and what a reviewer will ask about.

## Decision

Each service defines its own copy of every event it produces or consumes. There is no shared
module and no `common` jar.

## Rationale

**A shared event library re-couples services at build time.** The entire point of splitting
the system was that these services deploy independently. A shared jar means a change to an
event's shape forces a coordinated version bump, a rebuild and a redeploy of every service
that depends on it — the distributed monolith, arrived at through the front door. It replaces
a compile-time dependency between classes with a compile-time dependency between *teams*.

**The contract is the JSON on the topic, not the Java class.** Two services agreeing on a
class is not the same as agreeing on a message format, and treating the class as the contract
hides that. Duplication makes the real boundary visible: the wire format is what both sides
must honour, and each side is free to model it however suits it.

**Consumers should be free to model only what they need.** `inventory-service` needs the
order ID and the line items. It has no interest in `totalAmount` or the customer's details,
and its copy can ignore them. A shared class forces every consumer to carry every field the
producer thought was interesting, and a consumer that deserialises fields it never reads is a
consumer that breaks when those fields change.

**It makes schema evolution honest.** Adding a field to the producer's event is a
non-breaking change, and with a shared class that fact is invisible — everything recompiles
and nobody thinks about it. With separate classes, the consumer simply ignores the new field
until someone deliberately decides to use it. The compatibility question gets asked at the
right moment.

## Consequences

**What it costs.** The obvious thing: the same record is written twice, and a field renamed
on one side and not the other is a runtime failure rather than a compile error. That risk is
real. It is mitigated by integration tests that assert on **payload content**, not merely
that a message was delivered — a test that only checks "a message arrived" would not catch a
field name drifting. This was a deliberate testing choice made because of this decision.

**What it buys.** Services build, version and deploy with genuinely no build-time coupling.
Adding `notification-service` in Phase 7 required changing nothing in any existing service —
it declared the two events it cared about and subscribed.

## Alternatives rejected

**A shared `common-events` module.** The default answer, and the wrong one here for the
reasons above. It is defensible in a single-team monorepo where everything deploys together —
but then the services are not independently deployable, and the argument for splitting them
was weaker than it looked.

**A schema registry with Avro or Protobuf.** The correct production answer, and strictly
better than either alternative: the contract becomes an explicit versioned artefact,
compatibility is machine-checked on publish, and neither side needs the other's classes.
Rejected here as disproportionate — it adds a registry to the deployment and a code-generation
step to every build, to solve a coordination problem that three services and one developer do
not have. It is the natural next step if this grew.

**Consume as `Map<String, Object>` or raw JSON.** Maximum decoupling and zero duplication, at
the cost of losing every type guarantee and pushing field access to runtime string lookups.
The duplication is much cheaper than that.
