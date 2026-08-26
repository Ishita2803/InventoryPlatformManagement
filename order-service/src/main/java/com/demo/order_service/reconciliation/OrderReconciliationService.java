package com.demo.order_service.reconciliation;

import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.service.OrderSettlementService;
import com.demo.order_service.service.SettlementOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finishes orders that stalled half-way through the Saga.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>It is not hypothetical. During Phase 8, three orders were left permanently at
 * {@code INVENTORY_RESERVED} holding six units of stock, and the sequence was:
 *
 * <ol>
 *   <li>{@code inventory.reserved} arrives; the consumer writes its {@code processed_event}
 *       row and moves the order to INVENTORY_RESERVED. That transaction commits.</li>
 *   <li>The settlement that should follow fails — in that case on a {@code TINYTEXT} column
 *       that could not hold the payload.</li>
 *   <li>Kafka redelivers the event, and the consumer <strong>correctly</strong> sees it in
 *       {@code processed_event} and skips it.</li>
 *   <li>The order never advances. It never will, and it holds its stock for ever.</li>
 * </ol>
 *
 * <p>Everything behaved as designed. That is the point worth understanding: idempotency
 * guarantees work is not done <em>twice</em>, and offers nothing at all about work that was
 * never <em>finished</em>. Marking an event processed is a promise the work happened, and if
 * the work is ever not in that same transaction, the marker becomes a lie that suppresses
 * every future attempt to fix it.
 *
 * <p>No amount of retrying the consumer helps, because the consumer is right to skip. The
 * missing piece is something that looks at <em>state</em> rather than at messages, and this
 * is it.
 *
 * <h2>Why resuming is safe</h2>
 *
 * <p>Nothing here re-reads or re-writes {@code processed_event}. It re-drives settlement,
 * which is a different transaction that was never marked as done. Payment is idempotent by
 * {@code orderId}, so an order that <em>was</em> charged before the crash gets the original
 * result back rather than a second charge, and {@code settleOrder} refuses to act on an order
 * that has already reached a terminal status.
 *
 * <h2>Two thresholds, deliberately</h2>
 *
 * <p>{@code stuck-after} is when an order is presumed abandoned and worth resuming. It must
 * comfortably exceed the normal settlement time or the sweeper starts racing the Kafka
 * listener for live orders — which is safe, thanks to the optimistic lock, but is pure waste.
 *
 * <p>{@code give-up-after} is the ceiling. When payment cannot be reached the order is
 * normally left alone for the next pass, because cancelling a backlog over a provider blip
 * turns a temporary outage into permanently lost business. But stock cannot be held for ever
 * either, so past this much older threshold the order is cancelled and the stock released.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderReconciliationService {

    private final OrderRepository orderRepository;
    private final OrderSettlementService orderSettlementService;

    /** How long an order may sit in INVENTORY_RESERVED before it is presumed stalled. */
    @Value("${reconciliation.stuck-after-ms:300000}")
    private long stuckAfterMs;

    /** The ceiling: past this, an unpayable order is cancelled so its stock is freed. */
    @Value("${reconciliation.give-up-after-ms:3600000}")
    private long giveUpAfterMs;

    /** Cap per sweep, so a large backlog drains over several passes instead of one long one. */
    @Value("${reconciliation.batch-size:50}")
    private int batchSize;

    /** Orders stuck at PENDING are reported, never acted on. See {@link #reconcile()}. */
    @Value("${reconciliation.pending-stuck-after-ms:600000}")
    private long pendingStuckAfterMs;

    /**
     * {@code fixedDelay}, not {@code fixedRate}: a sweep that takes longer than the interval
     * must not have the next one start on top of it. Same reasoning as the outbox publisher.
     */
    @Scheduled(
            fixedDelayString = "${reconciliation.interval-ms:60000}",
            initialDelayString = "${reconciliation.initial-delay-ms:60000}"
    )
    public void reconcileScheduled() {
        try {
            reconcile();
        } catch (Exception unexpected) {
            // A scheduled method that throws is silently never rescheduled by some executors,
            // and this job's whole purpose is to still be running months later when something
            // finally goes wrong. It must not be able to kill itself.
            log.error("Reconciliation sweep failed; will try again next interval", unexpected);
        }
    }

    /**
     * Runs one sweep and reports what it found.
     *
     * <p>Public and returning a report so tests can drive it directly rather than waiting on a
     * timer, and so the counts can be asserted precisely. "Processed 12 orders" is close to
     * useless operationally: twelve confirmed is a healthy recovery, twelve cancelled means
     * payment has been failing for an hour, and twelve already-settled means the threshold is
     * too tight and this job is fighting the listener.
     */
    public ReconciliationReport reconcile() {

        Instant now = Instant.now();
        Instant stuckBefore = now.minus(Duration.ofMillis(stuckAfterMs));
        Instant giveUpBefore = now.minus(Duration.ofMillis(giveUpAfterMs));

        List<String> stuck = orderRepository.findStuckOrderIds(
                OrderStatus.INVENTORY_RESERVED, stuckBefore, PageRequest.of(0, batchSize));

        // Fetched once, not per order. Both queries are oldest-first over the same status, so
        // everything past the ceiling is by definition among the oldest and appears here.
        Set<String> pastCeiling = new HashSet<>(orderRepository.findStuckOrderIds(
                OrderStatus.INVENTORY_RESERVED, giveUpBefore, PageRequest.of(0, batchSize)));

        int confirmed = 0;
        int cancelled = 0;
        int waiting = 0;
        int skipped = 0;

        for (String orderId : stuck) {

            SettlementOutcome outcome = pastCeiling.contains(orderId)
                    ? orderSettlementService.settleCancellingOnOutage(orderId)
                    : orderSettlementService.settle(orderId);

            switch (outcome) {
                case CONFIRMED -> {
                    confirmed++;
                    log.info("Reconciliation resumed stalled order {} -> CONFIRMED", orderId);
                }
                case CANCELLED -> {
                    cancelled++;
                    log.info("Reconciliation resumed stalled order {} -> CANCELLED, "
                            + "stock released", orderId);
                }
                case PAYMENT_UNAVAILABLE -> {
                    waiting++;
                    log.warn("Order {} still stalled: payment unreachable. Leaving it for the "
                            + "next sweep rather than cancelling over an outage.", orderId);
                }
                case ALREADY_SETTLED, RACED -> skipped++;
                case UNKNOWN_ORDER -> {
                    skipped++;
                    log.error("Reconciliation found order {} in the index but could not load "
                            + "it", orderId);
                }
            }
        }

        // Reported, never resumed. A PENDING order either never had its OrderPlaced published,
        // or had it published and inventory's answer was lost. Resuming would mean guessing
        // whether stock is held, and guessing wrong in one direction oversells while guessing
        // wrong in the other loses stock. Inventory owns that answer, and asking it would mean
        // a synchronous call from order-service into inventory-service — a coupling this
        // architecture spends real effort avoiding. So: surface it, and let a human decide.
        long pendingStuck = orderRepository.countByStatusAndUpdatedAtBefore(
                OrderStatus.PENDING, now.minus(Duration.ofMillis(pendingStuckAfterMs)));

        if (pendingStuck > 0) {
            log.warn("{} order(s) have been PENDING for over {}ms. Not resumed automatically — "
                    + "inventory owns whether stock was reserved.", pendingStuck,
                    pendingStuckAfterMs);
        }

        ReconciliationReport report = new ReconciliationReport(
                stuck.size(), confirmed, cancelled, waiting, skipped, pendingStuck);

        if (report.resumed() > 0 || waiting > 0) {
            log.info("Reconciliation sweep: {}", report);
        }

        return report;
    }
}
