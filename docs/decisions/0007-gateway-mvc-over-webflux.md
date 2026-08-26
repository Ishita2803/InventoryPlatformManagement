# ADR-0007 — Spring Cloud Gateway MVC rather than the WebFlux gateway

**Status:** Accepted · **Date:** 2026-08-26 (recorded retrospectively; decided in Phase 3)

## Context

Spring Cloud Gateway ships in two flavours:

- **`spring-cloud-starter-gateway-server-webflux`** — the original, built on Project Reactor
  and Netty. Fully non-blocking.
- **`spring-cloud-starter-gateway-server-webmvc`** — servlet-based, running on Tomcat.

Nearly every tutorial and Stack Overflow answer uses the WebFlux one, because for years it
was the only one. Choosing the other requires a reason.

## Decision

The servlet-based **Gateway MVC**, with routes configured under
`spring.cloud.gateway.server.webmvc.routes`.

## Rationale

**Mixing WebFlux and MVC in one application is a well-known trap.** If both
`spring-boot-starter-web` and `spring-boot-starter-webflux` are on the classpath, Spring Boot
picks MVC and the gateway silently stops routing — no error, no warning, just 404s on every
route. Choosing the servlet gateway means there is only ever one web stack present and that
failure cannot occur.

**Every other service in this platform is servlet-based**, using blocking JPA and blocking
JDBC. A reactive gateway in front of a blocking system buys very little: the request is
non-blocking for the few milliseconds it spends being routed, and then blocks anyway. The
consistency is worth more than the theoretical concurrency.

**Reactor makes debugging materially harder.** Stack traces from a reactive pipeline are
assembled from operator chains rather than call frames, and reading them is a skill of its
own. For a filter that adds a correlation ID and an exception handler that returns a 503,
that cost buys nothing.

**Virtual threads change the arithmetic.** The historical argument for the reactive gateway
was thread-per-request exhaustion under high concurrency. On Java 21 that is addressable with
virtual threads, which get most of the scalability benefit while keeping ordinary blocking
code and readable stack traces.

## Consequences

**What this buys.** A single, consistent web stack across all seven services. Filters are
ordinary `OncePerRequestFilter` implementations — `CorrelationIdFilter` is a plain servlet
filter wrapping the request, not a `GlobalFilter` returning a `Mono<Void>`. The exception
handler is a normal `@RestControllerAdvice`. Anyone who knows Spring MVC can read the gateway.

**What it costs.**

- *One thread per in-flight request.* At genuinely high concurrency with slow upstreams, the
  WebFlux gateway would hold up better on the same hardware. Not a constraint at this scale,
  and mitigable with virtual threads.
- *Documentation and search results mostly describe the other one.* Property paths differ —
  `spring.cloud.gateway.server.webmvc.routes` rather than `spring.cloud.gateway.routes` — and
  copying a configuration snippet from a blog post produces a gateway that starts cleanly and
  routes nothing. Worth knowing before losing an hour to it.

## Alternatives rejected

**The WebFlux gateway.** The right choice for a genuinely high-throughput edge in front of
reactive or slow upstreams, and the more common one. Rejected because this system is blocking
end to end, so it would add a second programming model to the codebase in exchange for
concurrency headroom that is not needed.

**No gateway; clients call services directly.** Simpler, and briefly tempting. Rejected
because it pushes service discovery, correlation-ID generation and eventually authentication
into every client, and it makes the eventual Kubernetes Ingress the only place cross-cutting
concerns can live.

**Nginx or Envoy as the edge proxy.** What a production deployment would most likely use, and
better at the proxying job itself. Rejected because routing here is resolved through Eureka
(`lb://order-service`), so the gateway participates in the Spring Cloud service-discovery
model rather than needing a static upstream list — and because keeping the edge in Java means
the correlation-ID logic lives with the rest of the code.
