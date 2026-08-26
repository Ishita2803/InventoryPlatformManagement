package com.demo.order_service.reconciliation;

import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderItem;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.repository.OutboxEventRepository;
import com.demo.order_service.payment.PaymentClient;
import com.demo.order_service.payment.PaymentResult;
import com.demo.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconciliation sweep, against a real database.
 *
 * <p>These tests recreate the exact Phase 8 failure rather than an approximation of it: an
 * order at INVENTORY_RESERVED whose {@code processed_event} row is already committed, so
 * redelivery would skip it and no consumer-side retry can ever help.
 *
 * <p><strong>The age of the order is the whole mechanism</strong>, so it is set directly with
 * SQL. Going through JPA would trip {@code @PreUpdate} and stamp {@code updatedAt} back to
 * now, which would silently make every one of these tests pass for the wrong reason — the
 * sweep would find nothing and "no orders were wrongly settled" would look like success.
 */
@SpringBootTest
@Transactional
class OrderReconciliationServiceTest {

    @Autowired
    private OrderReconciliationService reconciliationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();

        // Tight thresholds so the tests describe minutes, not hours.
        ReflectionTestUtils.setField(reconciliationService, "stuckAfterMs", 300_000L);
        ReflectionTestUtils.setField(reconciliationService, "giveUpAfterMs", 3_600_000L);
        ReflectionTestUtils.setField(reconciliationService, "pendingStuckAfterMs", 600_000L);
        ReflectionTestUtils.setField(reconciliationService, "batchSize", 50);
    }

    @Test
    @DisplayName("an order stalled at INVENTORY_RESERVED is resumed and confirmed")
    void resumesStalledOrder() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(10));
        when(paymentClient.pay(eq(orderId), any())).thenReturn(PaymentResult.approved("pay-1"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.examined()).isEqualTo(1);
        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("resuming queues the settlement event, so inventory is actually told")
    void resumingQueuesTheSettlementEvent() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(10));
        when(paymentClient.pay(eq(orderId), any())).thenReturn(PaymentResult.approved("pay-1"));

        reconciliationService.reconcile();

        // Without this the order would read CONFIRMED while inventory still held the stock
        // reserved for ever — a fix that looks complete and releases nothing.
        assertThat(outboxEventRepository.findAll())
                .as("an order.confirmed event must be queued for inventory")
                .anySatisfy(event -> {
                    assertThat(event.getTopic()).isEqualTo("order.confirmed");
                    assertThat(event.getPayload()).contains(orderId);
                });
    }

    @Test
    @DisplayName("an order younger than the threshold is left alone")
    void leavesFreshOrdersAlone() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(1));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.examined()).isZero();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        // The listener may still be mid-flight on this one; charging it would be a race.
        verify(paymentClient, never()).pay(any(), any());
    }

    @Test
    @DisplayName("a declined payment cancels the order so the stock is released")
    void declinedPaymentCancels() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(10));
        when(paymentClient.pay(eq(orderId), any()))
                .thenReturn(PaymentResult.declined("insufficient funds"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.cancelled()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxEventRepository.findAll())
                .anySatisfy(event -> assertThat(event.getTopic()).isEqualTo("order.cancelled"));
    }

    @Test
    @DisplayName("a payment outage does NOT cancel: the order waits for the next sweep")
    void outageDoesNotCancel() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(10));
        when(paymentClient.pay(eq(orderId), any()))
                .thenReturn(PaymentResult.unavailable("connection refused"));

        ReconciliationReport report = reconciliationService.reconcile();

        // The distinction that stops a five-minute provider outage cancelling a whole
        // backlog. DECLINED is the provider's answer; UNAVAILABLE is our own failure.
        assertThat(report.waiting()).isEqualTo(1);
        assertThat(report.cancelled()).isZero();
        assertThat(statusOf(orderId))
                .as("an outage must not lose the order")
                .isEqualTo(OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    @DisplayName("past the give-up ceiling, an unpayable order IS cancelled to free the stock")
    void ceilingCancelsEventually() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(120));
        when(paymentClient.pay(eq(orderId), any()))
                .thenReturn(PaymentResult.unavailable("connection refused"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.cancelled()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("terminal orders are never touched, however old")
    void ignoresTerminalOrders() {

        stalledOrder(OrderStatus.CONFIRMED, minutesAgo(500));
        stalledOrder(OrderStatus.CANCELLED, minutesAgo(500));
        stalledOrder(OrderStatus.INVENTORY_FAILED, minutesAgo(500));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.examined()).isZero();
        verify(paymentClient, never()).pay(any(), any());
    }

    @Test
    @DisplayName("a second sweep does not settle the same order twice")
    void sweepIsIdempotent() {

        String orderId = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(10));
        when(paymentClient.pay(eq(orderId), any())).thenReturn(PaymentResult.approved("pay-1"));

        reconciliationService.reconcile();
        ReconciliationReport second = reconciliationService.reconcile();

        // The order is CONFIRMED now, so the second sweep must not even see it.
        assertThat(second.examined()).isZero();
        verify(paymentClient, times(1)).pay(eq(orderId), any());
        assertThat(outboxEventRepository.findAll())
                .filteredOn(event -> "order.confirmed".equals(event.getTopic()))
                .as("exactly one settlement event, not one per sweep")
                .hasSize(1);
    }

    @Test
    @DisplayName("orders stalled at PENDING are reported but never resumed")
    void reportsPendingWithoutActing() {

        String orderId = stalledOrder(OrderStatus.PENDING, minutesAgo(30));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.pendingStuck()).isEqualTo(1);
        assertThat(report.examined()).isZero();
        // Whether stock is reserved is inventory's answer to give. Guessing wrong oversells
        // in one direction and loses stock in the other.
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
        verify(paymentClient, never()).pay(any(), any());
    }

    @Test
    @DisplayName("the batch size caps one sweep, and the oldest go first")
    void batchSizeCapsTheSweep() {

        ReflectionTestUtils.setField(reconciliationService, "batchSize", 2);

        String oldest = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(60));
        String middle = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(40));
        String newest = stalledOrder(OrderStatus.INVENTORY_RESERVED, minutesAgo(20));

        when(paymentClient.pay(any(), any())).thenReturn(PaymentResult.approved("pay-x"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.examined()).isEqualTo(2);
        assertThat(statusOf(oldest)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(statusOf(middle)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(statusOf(newest))
                .as("the newest waits for the next sweep — oldest first frees stock soonest")
                .isEqualTo(OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    @DisplayName("a quiet system produces a quiet report")
    void quietWhenNothingIsWrong() {
        assertThat(reconciliationService.reconcile().isQuiet()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private Instant minutesAgo(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    /**
     * Creates an order in the given status, aged with SQL.
     *
     * <p>The UPDATE is not a shortcut. Saving through JPA fires {@code @PreUpdate}, which
     * stamps {@code updatedAt} to now — so the order would never look stale and the sweep
     * would find nothing. Every test here would then pass while proving nothing at all.
     */
    private String stalledOrder(OrderStatus status, Instant updatedAt) {

        String orderId = UUID.randomUUID().toString();

        Order order = new Order();
        order.setOrderId(orderId);
        order.setCustomerId("CUST-RECON");
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("19.98"));

        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setWarehouseId("WH-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("9.99"));
        order.addItem(item);

        orderRepository.saveAndFlush(order);

        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE order_id = ?",
                java.sql.Timestamp.from(updatedAt), orderId);

        return orderId;
    }

    private OrderStatus statusOf(String orderId) {
        return orderRepository.findByOrderId(orderId).orElseThrow().getStatus();
    }
}
