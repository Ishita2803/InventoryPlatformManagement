package com.demo.payment_service.service;

import com.demo.payment_service.dto.PaymentRequest;
import com.demo.payment_service.dto.PaymentResponse;
import com.demo.payment_service.dto.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A mocked payment provider.
 *
 * <p><strong>Why this exists at all.</strong> Everything else in this platform talks over
 * Kafka, and a purely asynchronous design has no synchronous inter-service call — so a
 * circuit breaker would have nothing to protect and would be decoration. Payment is the one
 * step that genuinely wants an answer now: you cannot ship an order and find out later
 * whether it was paid for. That makes it the honest place for a timeout, a retry, a circuit
 * breaker and a fallback, and it is what turns the Saga's compensation into something that
 * actually fires.
 *
 * <p><strong>Idempotent by orderId.</strong> The caller retries — that is the whole point of
 * putting a retry in front of it — and a payment provider that charges twice for two
 * identical requests is worse than one that is simply down. Real providers accept an
 * idempotency key for exactly this reason; here the orderId is that key.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Decided payments, keyed by orderId. In memory because this is a mock. */
    private final Map<String, PaymentResponse> decisions = new ConcurrentHashMap<>();

    private final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.APPROVE);

    @Value("${payment.delay-ms:0}")
    private long configuredDelayMillis;

    private final AtomicReference<Long> delayOverride = new AtomicReference<>(null);

    /**
     * How the mock should respond. Switchable at runtime so the failure paths can be
     * demonstrated without restarting anything — see {@code PaymentController}.
     */
    public enum Behaviour {
        /** Normal operation. */
        APPROVE,
        /** The bank says no. A business decision, not a fault: no retry should help. */
        DECLINE,
        /** Responds, eventually. Used to trip the caller's read timeout. */
        SLOW
    }

    public PaymentResponse pay(PaymentRequest request) {

        PaymentResponse alreadyDecided = decisions.get(request.orderId());
        if (alreadyDecided != null) {
            log.info("Returning the existing decision for order {} — {} (idempotent replay)",
                    request.orderId(), alreadyDecided.status());
            return alreadyDecided;
        }

        applyArtificialDelay();

        Behaviour current = behaviour.get();
        PaymentStatus status = current == Behaviour.DECLINE
                ? PaymentStatus.DECLINED
                : PaymentStatus.APPROVED;

        PaymentResponse response = new PaymentResponse(
                "pay-" + UUID.randomUUID(),
                request.orderId(),
                request.amount(),
                status,
                status == PaymentStatus.APPROVED
                        ? "Payment approved"
                        : "Payment declined by the issuing bank",
                Instant.now());

        // putIfAbsent, not put: two concurrent retries for the same order must not end up
        // with two different payment ids.
        PaymentResponse raced = decisions.putIfAbsent(request.orderId(), response);
        if (raced != null) {
            return raced;
        }

        log.info("Payment {} for order {} amount {} -> {}",
                response.paymentId(), request.orderId(), request.amount(), status);

        return response;
    }

    public Behaviour behaviour() {
        return behaviour.get();
    }

    public void setBehaviour(Behaviour next, Long delayMillis) {
        behaviour.set(next);
        delayOverride.set(delayMillis);
        log.warn("Payment behaviour switched to {} (delay {} ms)", next, effectiveDelay());
    }

    /** Forgets past decisions, so a demo can re-run the same order id. */
    public void reset() {
        decisions.clear();
        behaviour.set(Behaviour.APPROVE);
        delayOverride.set(null);
        log.warn("Payment service reset: APPROVE, no delay, no remembered decisions");
    }

    private long effectiveDelay() {
        Long override = delayOverride.get();
        if (override != null) {
            return override;
        }
        return behaviour.get() == Behaviour.SLOW ? 10_000L : configuredDelayMillis;
    }

    private void applyArtificialDelay() {
        long delay = effectiveDelay();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
