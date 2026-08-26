package com.demo.order_service.payment;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The one synchronous call in the platform, and therefore the only thing a circuit breaker
 * can usefully protect.
 *
 * <p>Everything else here talks over Kafka, where the broker already absorbs a slow or
 * absent consumer. Payment is different: you cannot ship an order and discover later whether
 * it was paid for. So this call blocks, and blocking calls are where timeouts, retries,
 * circuit breakers and fallbacks earn their place.
 *
 * <h2>What each mechanism is actually for</h2>
 * <ul>
 *   <li><strong>Read timeout</strong> — bounds a call that would otherwise hang. Note this
 *       is an HTTP read timeout, not Resilience4j's {@code TimeLimiter}: TimeLimiter needs a
 *       {@code CompletableFuture} to cancel, and there is nothing to cancel in a blocking
 *       call. Using it here would be cargo cult.</li>
 *   <li><strong>Retry</strong> — covers a transient blip. Outermost by Resilience4j's
 *       default aspect order, so each attempt passes through the breaker.</li>
 *   <li><strong>Circuit breaker</strong> — stops hammering a service that is clearly down,
 *       and fails fast instead of making every order wait for a timeout. This is the
 *       difference between one broken dependency and a thread pool exhausted by requests
 *       all waiting on it.</li>
 *   <li><strong>Fallback</strong> — turns "no answer" into a definite business decision, so
 *       the Saga can compensate rather than leaving the order in limbo.</li>
 * </ul>
 *
 * <p><strong>A declined payment is not a failure.</strong> It comes back as a normal 200
 * with {@code DECLINED}, so it is not retried and does not count towards the breaker. The
 * bank saying no is an answer; retrying it would be pointless and would eventually trip the
 * breaker for a service that is working perfectly.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    public static final String CIRCUIT = "payment";

    private final RestClient restClient;

    public PaymentClient(
            @Value("${payment.base-url:http://localhost:8084}") String baseUrl,
            @Value("${payment.connect-timeout-ms:1000}") int connectTimeoutMs,
            @Value("${payment.read-timeout-ms:2000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        log.info("Payment client targeting {} (connect {} ms, read {} ms)",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // The fallback goes on @Retry, the OUTERMOST aspect -- not on @CircuitBreaker.
    // Resilience4j nests them Retry(CircuitBreaker(call)), so a fallback declared on the
    // circuit breaker fires on the FIRST failure and returns normally; Retry then sees a
    // successful call and never retries. The retry is silently dead, the configuration
    // still looks correct, and only a test counting requests at the server catches it.
    @CircuitBreaker(name = CIRCUIT)
    @Retry(name = CIRCUIT, fallbackMethod = "paymentUnavailable")
    public PaymentResult pay(String orderId, BigDecimal amount) {

        log.info("Charging {} for order {}", amount, orderId);

        PaymentResponse response = restClient.post()
                .uri("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                // orderId is the idempotency key: the retry above means this request can
                // legitimately arrive more than once, and the customer must be charged once.
                .body(new PaymentRequest(orderId, amount))
                .retrieve()
                .body(PaymentResponse.class);

        if (response == null) {
            throw new IllegalStateException("Payment service returned an empty body");
        }

        boolean approved = "APPROVED".equals(response.status());

        log.info("Payment for order {} -> {} ({})", orderId, response.status(), response.paymentId());

        return approved
                ? PaymentResult.approved(response.paymentId())
                : PaymentResult.declined(response.message());
    }

    /**
     * Called when the payment service could not be reached, was too slow, or the circuit is
     * open. Resilience4j requires the failure as a trailing parameter.
     *
     * <p>Fails <strong>closed</strong>: an order nobody could charge is cancelled, not
     * confirmed. Approving on failure would ship goods for free; leaving it pending would
     * hold stock indefinitely. Cancelling releases the stock and gives the customer a
     * definite answer, and it is the option that is safe to be wrong about.
     */
    @SuppressWarnings("unused")
    private PaymentResult paymentUnavailable(String orderId, BigDecimal amount, Throwable failure) {

        log.error("Payment unavailable for order {}: {} — cancelling and releasing stock",
                orderId, failure.toString());

        return PaymentResult.unavailable(failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    /** Request body. Duplicated rather than shared with payment-service, as with the events. */
    private record PaymentRequest(String orderId, BigDecimal amount) {
    }

    private record PaymentResponse(String paymentId, String orderId, String status, String message) {
    }
}
