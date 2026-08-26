package com.demo.payment_service.service;

import com.demo.payment_service.dto.PaymentRequest;
import com.demo.payment_service.dto.PaymentResponse;
import com.demo.payment_service.dto.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency is the property the rest of the platform depends on.
 *
 * <p>order-service puts a retry in front of this service. That retry only makes sense if
 * charging twice for the same order is impossible — otherwise the resilience mechanism
 * intended to protect the customer is the thing that double-charges them.
 */
class PaymentServiceTest {

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService();
    }

    @Test
    @DisplayName("a repeated charge for the same order returns the original decision")
    void repeatedChargeIsIdempotent() {

        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("25.00"));

        PaymentResponse first = paymentService.pay(request);
        PaymentResponse second = paymentService.pay(request);

        assertThat(second.paymentId())
                .as("the same payment id, so the customer was charged once")
                .isEqualTo(first.paymentId());
        assertThat(second.processedAt()).isEqualTo(first.processedAt());
    }

    @Test
    @DisplayName("concurrent retries for one order still produce exactly one payment")
    void concurrentRetriesChargeOnce() throws Exception {

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);

        try {
            List<Future<PaymentResponse>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        startGun.await();
                        return paymentService.pay(
                                new PaymentRequest("order-1", new BigDecimal("25.00")));
                    }))
                    .toList();

            startGun.countDown();

            Set<String> paymentIds = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(20, TimeUnit.SECONDS).paymentId();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    })
                    .collect(Collectors.toSet());

            // putIfAbsent, not put: two racing retries must not mint two payment ids.
            assertThat(paymentIds)
                    .as("all concurrent callers must see the same single payment")
                    .hasSize(1);

        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a different order gets its own payment")
    void differentOrdersAreIndependent() {

        PaymentResponse first = paymentService.pay(
                new PaymentRequest("order-1", new BigDecimal("10.00")));
        PaymentResponse second = paymentService.pay(
                new PaymentRequest("order-2", new BigDecimal("10.00")));

        assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
    }

    @Test
    @DisplayName("DECLINE mode returns a declined decision, and remembers it")
    void declineModeDeclines() {

        paymentService.setBehaviour(PaymentService.Behaviour.DECLINE, 0L);

        PaymentResponse response = paymentService.pay(
                new PaymentRequest("order-1", new BigDecimal("10.00")));

        assertThat(response.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(response.message()).contains("declined");

        // Switching back must not change a decision already given.
        paymentService.setBehaviour(PaymentService.Behaviour.APPROVE, 0L);
        assertThat(paymentService.pay(new PaymentRequest("order-1", new BigDecimal("10.00")))
                .status()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    @DisplayName("reset clears remembered decisions, so a demo can re-run the same order")
    void resetClearsDecisions() {

        PaymentResponse before = paymentService.pay(
                new PaymentRequest("order-1", new BigDecimal("10.00")));

        paymentService.reset();

        PaymentResponse after = paymentService.pay(
                new PaymentRequest("order-1", new BigDecimal("10.00")));

        assertThat(after.paymentId()).isNotEqualTo(before.paymentId());
    }
}
