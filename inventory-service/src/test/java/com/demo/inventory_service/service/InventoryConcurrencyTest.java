package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.ReservationConflictException;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that actually earns the {@code @Version} column: concurrent reservations against
 * one stock row must never oversell.
 *
 * <p>Runs against a real (in-memory) database rather than mocks, because optimistic locking
 * lives in the {@code UPDATE ... WHERE version = ?} statement Hibernate emits. A mock cannot
 * lose a race, so a mock cannot prove this property.
 *
 * <p>{@code Propagation.NOT_SUPPORTED} switches off the transaction that {@code @DataJpaTest}
 * normally wraps each test in. Without that, every write would sit uncommitted in the test's
 * own transaction and the worker threads would contend over nothing.
 */
@DataJpaTest
@Import({InventoryService.class, InventoryTxService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryConcurrencyTest {

    private static final String WAREHOUSE_ID = "WH-1";
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName("Widget");
        productId = productRepository.save(product).getId();

        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setWarehouseId(WAREHOUSE_ID);
        inventory.setAvailableQuantity(INITIAL_STOCK);
        inventory.setReservedQuantity(0);
        inventoryRepository.save(inventory);
    }

    @Test
    @DisplayName("ten concurrent orders chasing five units of stock never oversell")
    void concurrentReservationsNeverOversell() throws Exception {

        int threads = 10;
        int quantityEach = 2;   // total demand 20 against stock of 10

        List<Boolean> outcomes = runConcurrently(
                threads,
                index -> "order-" + index,
                quantityEach
        );

        long successes = outcomes.stream().filter(Boolean::booleanValue).count();

        Inventory finalState = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID)
                .orElseThrow();

        // The invariant that matters: stock never goes negative.
        assertThat(finalState.getAvailableQuantity())
                .as("available stock must never go negative")
                .isGreaterThanOrEqualTo(0);

        // Nothing created, nothing destroyed.
        assertThat(finalState.getAvailableQuantity() + finalState.getReservedQuantity())
                .as("available + reserved must still equal the stock we started with")
                .isEqualTo(INITIAL_STOCK);

        // Every unit of reserved stock is backed by exactly one successful reservation.
        assertThat(finalState.getReservedQuantity())
                .isEqualTo((int) successes * quantityEach);

        assertThat(reservationRepository.findAll()).hasSize((int) successes);
        assertThat(successes).isBetween(1L, (long) (INITIAL_STOCK / quantityEach));
    }

    @Test
    @DisplayName("the same order delivered eight times concurrently reserves exactly once")
    void duplicateDeliveriesReserveExactlyOnce() throws Exception {

        int threads = 8;
        int quantity = 3;
        String sharedOrderId = "order-" + UUID.randomUUID();

        // Every thread carries the SAME orderId, which is what at-least-once delivery from
        // Kafka looks like: the identical event, more than once, possibly in parallel.
        runConcurrently(threads, index -> sharedOrderId, quantity);

        Inventory finalState = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID)
                .orElseThrow();

        assertThat(reservationRepository.findAll())
                .as("the unique constraint on (orderId, productId, warehouseId) is the idempotency key")
                .hasSize(1);

        assertThat(finalState.getReservedQuantity()).isEqualTo(quantity);
        assertThat(finalState.getAvailableQuantity()).isEqualTo(INITIAL_STOCK - quantity);

        assertThat(reservationRepository.findByOrderId(sharedOrderId))
                .singleElement()
                .satisfies(reservation -> {
                    assertThat(reservation.getQuantity()).isEqualTo(quantity);
                    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
                });
    }

    @Test
    @DisplayName("release returns stock, and releasing twice does not return it twice")
    void releaseIsIdempotent() {

        String orderId = "order-" + UUID.randomUUID();
        inventoryService.reserveInventory(request(orderId, 4));

        inventoryService.releaseInventory(orderId);
        inventoryService.releaseInventory(orderId);   // second call must be a no-op

        Inventory finalState = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID)
                .orElseThrow();

        assertThat(finalState.getAvailableQuantity()).isEqualTo(INITIAL_STOCK);
        assertThat(finalState.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    /**
     * Fires {@code threads} reservations simultaneously and reports which ones succeeded.
     * A latch is used so the threads start together rather than trickling in, which is what
     * makes contention actually happen.
     */
    private List<Boolean> runConcurrently(
            int threads,
            java.util.function.IntFunction<String> orderIdForIndex,
            int quantityEach
    ) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                String orderId = orderIdForIndex.apply(i);
                futures.add(pool.submit(() -> {
                    startGun.await();
                    try {
                        inventoryService.reserveInventory(request(orderId, quantityEach));
                        return true;
                    } catch (InsufficientInventoryException | ReservationConflictException expected) {
                        // Both are legitimate outcomes under contention, not failures.
                        return false;
                    }
                }));
            }

            startGun.countDown();

            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;

        } finally {
            pool.shutdownNow();
        }
    }

    private ReserveInventoryRequest request(String orderId, int quantity) {
        ReserveInventoryRequest request = new ReserveInventoryRequest();
        request.setOrderId(orderId);
        request.setProductId(productId);
        request.setWarehouseId(WAREHOUSE_ID);
        request.setQuantity(quantity);
        return request;
    }
}
