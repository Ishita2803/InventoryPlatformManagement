package com.demo.order_service.payment;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timeout, retry, circuit breaker and fallback — against a real HTTP server that can be told
 * to misbehave.
 *
 * <p>These are the claims that are easiest to make and hardest to back up. Configuring
 * Resilience4j proves nothing; what matters is whether the breaker actually opens, whether
 * the fallback actually fires, and whether a decline is correctly *not* treated as a fault.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "payment.connect-timeout-ms=500",
        "payment.read-timeout-ms=600",
        "resilience4j.retry.instances.payment.max-attempts=2",
        "resilience4j.retry.instances.payment.wait-duration=20ms",
        "resilience4j.circuitbreaker.instances.payment.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.payment.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.payment.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.payment.wait-duration-in-open-state=60s"
})
class PaymentClientIT {

    private static HttpServer stub;

    /** APPROVE, DECLINE or SLOW — flipped per test. */
    private static final AtomicReference<String> mode = new AtomicReference<>("APPROVE");

    /** How many requests actually reached the server, which is how retries are counted. */
    private static final AtomicInteger callsReceived = new AtomicInteger();

    @Autowired
    private PaymentClient paymentClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void paymentStub(DynamicPropertyRegistry registry) throws IOException {

        stub = HttpServer.create(new InetSocketAddress(0), 0);

        stub.createContext("/api/payments", exchange -> {
            callsReceived.incrementAndGet();

            if ("SLOW".equals(mode.get())) {
                try {
                    Thread.sleep(3_000);   // comfortably past the 600 ms read timeout
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            String status = "DECLINE".equals(mode.get()) ? "DECLINED" : "APPROVED";
            byte[] payload = ("""
                    {"paymentId":"pay-1","orderId":"o1","status":"%s","message":"stub says %s"}
                    """.formatted(status, status)).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });

        // A thread pool, not the default. HttpServer's default executor handles requests
        // one at a time, so in SLOW mode the retry's second request would queue behind the
        // still-sleeping first handler and never reach it -- making a working retry look
        // like a broken one.
        stub.setExecutor(Executors.newFixedThreadPool(4));
        stub.start();
        registry.add("payment.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        mode.set("APPROVE");
        callsReceived.set(0);
        circuitBreakerRegistry.circuitBreaker(PaymentClient.CIRCUIT).reset();
    }

    @Test
    @DisplayName("a healthy provider approves, in a single call")
    void approves() {

        PaymentResult result = paymentClient.pay("o1", new BigDecimal("10.00"));

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.APPROVED);
        assertThat(result.paymentId()).isEqualTo("pay-1");
        assertThat(callsReceived.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a decline is a business answer: not retried, and not counted as a failure")
    void declineIsNotAFault() {

        mode.set("DECLINE");

        PaymentResult result = paymentClient.pay("o1", new BigDecimal("10.00"));

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.DECLINED);

        // One call, not two: retrying the bank's "no" would be pointless...
        assertThat(callsReceived.get()).isEqualTo(1);

        // ...and would eventually trip the breaker on a service that is working perfectly.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PaymentClient.CIRCUIT);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a slow provider hits the read timeout, is retried, then falls back")
    void slowProviderTimesOutAndFallsBack() {

        mode.set("SLOW");

        PaymentResult result = paymentClient.pay("o1", new BigDecimal("10.00"));

        // The fallback fired rather than the exception escaping.
        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNAVAILABLE);
        assertThat(result.reason()).isNotBlank();

        // Two attempts, because max-attempts is 2 for this test.
        assertThat(callsReceived.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("sustained failure opens the circuit, and further calls fail fast without a call")
    void circuitOpensAndThenFailsFast() {

        mode.set("SLOW");
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PaymentClient.CIRCUIT);

        // Enough failures to satisfy minimum-number-of-calls and the 50% threshold.
        paymentClient.pay("o1", new BigDecimal("10.00"));
        paymentClient.pay("o2", new BigDecimal("10.00"));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = callsReceived.get();
        PaymentResult result = paymentClient.pay("o3", new BigDecimal("10.00"));

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.UNAVAILABLE);

        // The point of the breaker: no request left the process at all. Without it, every
        // order would sit through the full timeout while the provider is down.
        assertThat(callsReceived.get())
                .as("an open circuit must not reach the downstream service")
                .isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("recovery: once the provider is healthy again the breaker closes")
    void circuitClosesAfterRecovery() {

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PaymentClient.CIRCUIT);

        mode.set("SLOW");
        paymentClient.pay("o1", new BigDecimal("10.00"));
        paymentClient.pay("o2", new BigDecimal("10.00"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Simulates the wait-duration elapsing, without making the test sleep for it.
        breaker.transitionToHalfOpenState();
        mode.set("APPROVE");

        PaymentResult result = paymentClient.pay("o3", new BigDecimal("10.00"));

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.APPROVED);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
