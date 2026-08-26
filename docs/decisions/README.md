# Architecture decision records

One file per decision that would be expensive to reverse, or that a reviewer would reasonably
challenge. Each records the **alternatives that were rejected and why** — a decision without
its discarded options is just a description of the code.

These are written after the fact, from the reasoning at the time. Where a decision later
caused a problem, the problem is recorded in the same file rather than quietly edited out.

| ADR | Decision | The question it answers |
|---|---|---|
| [0001](0001-transactional-outbox.md) | Transactional outbox | How do you write to the database and publish an event atomically, with no distributed transaction? |
| [0002](0002-choreography-over-orchestration.md) | Choreographed saga | Who decides what happens next in a multi-service flow? |
| [0003](0003-optimistic-locking.md) | Optimistic locking with retry outside the transaction | How do you stop two orders selling the same last unit? |
| [0004](0004-idempotency-via-processed-event.md) | `processed_event` idempotency table | Kafka delivers at least once — how do you make the effect exactly once? |
| [0005](0005-per-service-event-classes.md) | Duplicated event classes | Why is there no shared `common-events` module? |
| [0006](0006-mock-payment-service.md) | A real HTTP service that mocks payments | Why a whole service instead of a stubbed interface? |
| [0007](0007-gateway-mvc-over-webflux.md) | Spring Cloud Gateway MVC | Why the servlet gateway when every tutorial uses WebFlux? |
| [0008](0008-reconcile-state-not-messages.md) | Reconcile state, not messages | Idempotency stops double work — what stops work that never finished? |

## Format

Context → Decision → Consequences → Alternatives rejected. No template ceremony beyond that.

The **Consequences** section is required to state costs, not only benefits. A decision record
that lists no downside is a decision that was not really made.
