# ADR-0006 — A real HTTP service that mocks payments, not a mocked client

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; implemented in Phase 8)

## Context

The saga needs a payment step, and its failure is what triggers compensation. No real payment
provider is going to be integrated with a portfolio project, so payment has to be simulated
somehow. There are three places to put the simulation: inside `order-service` behind an
interface, in a test double, or in a separate running service.

The decision matters more than it first appears, because **the failure path is the whole
point**. Retry, circuit breaking, timeouts and compensation only exist because a network call
can fail, and a simulation that removes the network removes exactly the thing being
demonstrated.

## Decision

A real Spring Boot service, `payment-service` on port 8084, called over HTTP by
`order-service` through a `RestClient` wrapped in Resilience4j. It is mocked *internally* —
it approves or declines according to a runtime switch — but it is a genuine process on a
genuine socket.

```java
@CircuitBreaker(name = CIRCUIT)
@Retry(name = CIRCUIT, fallbackMethod = "paymentUnavailable")
public PaymentResult pay(String orderId, BigDecimal amount) { ... }
```

Its behaviour is controllable at runtime, without editing code or restarting anything:

```bash
curl -X POST 'http://localhost:8084/api/payments/behaviour?mode=DECLINE'
curl -X POST 'http://localhost:8084/api/payments/behaviour?mode=SLOW&delayMs=5000'
curl -X POST 'http://localhost:8084/api/payments/behaviour?mode=APPROVE'
```

`DECLINE` exercises the business-failure compensation path. `SLOW` exercises timeouts.
Stopping the container entirely (`docker compose stop payment-service`) exercises connection
refusal, retry exhaustion and the circuit breaker.

It is also **idempotent by `orderId`** — a repeated payment request for the same order returns
the original result rather than charging twice. A mock that ignored idempotency would let the
rest of the system get away with something a real provider would punish.

## Consequences

**What this buys.** The resilience configuration is exercised against real sockets, real
connection-refused errors and real timeouts. That is not a theoretical benefit — it is what
caught the bug below.

**The bug it caught.** The Resilience4j `fallbackMethod` was originally on `@CircuitBreaker`
rather than `@Retry`. Both annotations were present, the code compiled, the tests passed, and
the fallback worked. But because the fallback sat on the *inner* aspect, it swallowed the
exception before `@Retry` — the outer aspect — ever saw a failure. **The retry never
retried.** Every "resilient" call was a single attempt with a friendly error message.

Nothing about the code's appearance reveals this. It was found by counting the requests that
actually arrived at the payment service: expected 3, received 1. That measurement is only
possible because payment is a real server that can count requests. A Mockito stub would have
been asked "were you called?", answered "yes", and the bug would have shipped.

The rule that came out of it: **put the fallback on the outermost aspect**, and verify aspect
ordering by observation rather than by reading the annotations.

**What it costs.** One more service to build, containerise, health-check and run — roughly
250 MB of RAM and a slower `docker compose up`. Cheap for what it buys.

## Alternatives rejected

**A `PaymentClient` interface with a `FakePaymentClient` implementation.** Zero
infrastructure, and the honest default for most projects. Rejected because it makes the call
an in-process method invocation. There is no socket, so there is nothing for a timeout to
time out, nothing for a connection to refuse, and no way for a circuit breaker to be anything
but decoration. It would have hidden the fallback/retry bug completely.

**WireMock in the integration tests only.** Good for tests, and something similar is in fact
used in the resilience test suite. Rejected as the primary answer because it exists only
during a test run — the running system would have no payment step at all, so `docker compose
up` would not demonstrate the saga end to end, and the failure path could not be shown
interactively.

**Integrate a real provider's sandbox (Stripe test mode).** More impressive on paper. Rejected
because it makes the project depend on an external account and network access to run, and
because sandbox APIs make it *harder*, not easier, to trigger the failure modes that matter
here — declines are simulated through magic card numbers, and "the provider is completely
down" cannot be simulated at all.
