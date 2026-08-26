package com.demo.order_service.service;

import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.payment.PaymentClient;
import com.demo.order_service.payment.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Charges for an order and settles it, whichever way payment answers.
 *
 * <p><strong>Why this is a class and not a private method.</strong> It began as
 * {@code InventoryResultListener.settle(...)}, called from exactly one place. The
 * reconciliation job needs precisely the same sequence — read the order, call payment, apply
 * the answer, queue the settlement event — because resuming a stalled order means finishing
 * the work the listener started, not doing something similar to it. Two copies would drift the
 * first time either changed, and the failure would be silent: orders settled by the timer
 * would behave subtly differently from orders settled by the listener, with nothing to flag
 * it.
 *
 * <p>The step boundaries are deliberate and unchanged from the listener:
 *
 * <ol>
 *   <li>Read the order in its own transaction.</li>
 *   <li>Call payment <strong>outside</strong> any transaction. Holding a database transaction
 *       open across a network call to a third party is how one slow dependency exhausts a
 *       connection pool.</li>
 *   <li>Apply the answer and queue the settlement event in one transaction, so an order can
 *       never be CANCELLED without the release being queued.</li>
 * </ol>
 *
 * <p>Safe to call more than once for the same order. Payment is idempotent by {@code orderId}
 * so a second charge returns the first result rather than charging twice, and
 * {@link OrderTxService#settleOrder} refuses to act on an order that is already terminal.
 * That is what makes the reconciliation job possible at all.
 *
 * <p><strong>The two entry points differ only in what an outage means</strong>, and that is
 * the caller's decision rather than this class's — see
 * {@link #settleCancellingOnOutage(String)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementService {

    private final OrderTxService orderTxService;
    private final PaymentClient paymentClient;

    /** An attempt's result, carrying payment's own words so they survive into the event. */
    private record Attempt(SettlementOutcome outcome, String reason) {

        static Attempt of(SettlementOutcome outcome) {
            return new Attempt(outcome, null);
        }
    }

    /**
     * Settles, treating an unreachable payment provider as "not yet" rather than "no".
     *
     * <p>Used by the reconciliation job. By the time the sweeper sees an order nobody is
     * waiting on it, so a provider blip should not cancel a backlog; the order is left for the
     * next pass.
     */
    public SettlementOutcome settle(String orderId) {
        return attempt(orderId).outcome();
    }

    /**
     * Settles, cancelling immediately if payment cannot be reached.
     *
     * <p>This is the listener's original behaviour, unchanged: a live order whose payment call
     * fails is cancelled and its stock released, because the customer is waiting and holding
     * stock for an order that cannot be charged helps nobody.
     */
    public SettlementOutcome settleCancellingOnOutage(String orderId) {

        Attempt attempt = attempt(orderId);

        if (attempt.outcome() != SettlementOutcome.PAYMENT_UNAVAILABLE) {
            return attempt.outcome();
        }

        return applySettlement(orderId, false, null,
                "Payment unavailable: " + attempt.reason());
    }

    private Attempt attempt(String orderId) {

        OrderResponse order;
        try {
            order = orderTxService.getOrder(orderId);
        } catch (OrderNotFoundException unknown) {
            log.error("Cannot settle unknown order {}", orderId, unknown);
            return Attempt.of(SettlementOutcome.UNKNOWN_ORDER);
        }

        // Checked before paying, not after. The listener never sees a terminal order here —
        // it only settles what it just transitioned — but the reconciler routinely does, and
        // asking a payment provider about an order that is already finished is exactly the
        // sort of pointless side effect a sweeper should not have.
        if (order.status().isTerminal()) {
            log.debug("Order {} is already {} — nothing to settle", orderId, order.status());
            return Attempt.of(SettlementOutcome.ALREADY_SETTLED);
        }

        PaymentResult result = paymentClient.pay(orderId, order.totalAmount());

        return switch (result.outcome()) {

            case APPROVED -> Attempt.of(
                    applySettlement(orderId, true, result.paymentId(), null));

            case DECLINED -> Attempt.of(
                    applySettlement(orderId, false, null,
                            "Payment declined: " + result.reason()));

            // Reported rather than acted on, so the caller decides what an outage means.
            // Kept distinct from DECLINED so an outage is never buried among ordinary
            // declines — that distinction is the difference between "the customer's card was
            // refused" and "we are broken".
            case UNAVAILABLE -> new Attempt(
                    SettlementOutcome.PAYMENT_UNAVAILABLE, result.reason());
        };
    }

    private SettlementOutcome applySettlement(String orderId, boolean approved,
                                              String paymentId, String reason) {
        try {
            orderTxService.settleOrder(orderId, approved, paymentId, reason);
            return approved ? SettlementOutcome.CONFIRMED : SettlementOutcome.CANCELLED;

        } catch (InvalidOrderStateTransitionException alreadySettled) {
            log.warn("Order {} was already settled: {}", orderId, alreadySettled.getMessage());
            return SettlementOutcome.ALREADY_SETTLED;

        } catch (OptimisticLockingFailureException raced) {
            // The Kafka listener, or another instance's reconciler, got there first. Its
            // transaction committed; ours did not. Nothing is wrong and nothing is lost.
            log.info("Order {} was settled concurrently by another writer — backing off", orderId);
            return SettlementOutcome.RACED;
        }
    }
}
