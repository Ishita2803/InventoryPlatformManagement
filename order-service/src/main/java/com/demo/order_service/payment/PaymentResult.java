package com.demo.order_service.payment;

/**
 * The outcome of asking payment for a decision.
 *
 * <p>Three outcomes, not two, and the distinction matters. DECLINED means the provider
 * answered and the answer was no. UNAVAILABLE means nobody answered. Both cancel the order,
 * but only the second is a fault worth alerting on — collapsing them would hide an outage
 * behind a pile of apparently ordinary declines.
 */
public record PaymentResult(Outcome outcome, String paymentId, String reason) {

    public enum Outcome {
        APPROVED,
        DECLINED,
        UNAVAILABLE
    }

    public static PaymentResult approved(String paymentId) {
        return new PaymentResult(Outcome.APPROVED, paymentId, null);
    }

    public static PaymentResult declined(String reason) {
        return new PaymentResult(Outcome.DECLINED, null, reason);
    }

    public static PaymentResult unavailable(String reason) {
        return new PaymentResult(Outcome.UNAVAILABLE, null, reason);
    }

    public boolean isApproved() {
        return outcome == Outcome.APPROVED;
    }
}
