package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReservationLine;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.dto.ReserveOutcome;
import com.demo.inventory_service.exception.ReservationConflictException;
import com.demo.inventory_service.models.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The public API of the inventory domain, and the home of the optimistic-lock retry.
 *
 * <p>{@code Inventory} carries a {@code @Version} column, so two concurrent reservations
 * against the same stock row will see one of them fail at flush with
 * {@link ObjectOptimisticLockingFailureException}. That is not an error condition -- it is
 * optimistic locking working exactly as designed, and the correct response is to re-read and
 * try again, not to surface a 500.
 *
 * <p>The retry deliberately lives here, outside the transaction; see {@link InventoryTxService}
 * for why that requires a second bean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    /**
     * Four attempts, not "until it succeeds". An unbounded retry under sustained contention
     * is a livelock that presents as a hung request and takes the thread pool with it.
     */
    private static final int MAX_ATTEMPTS = 4;

    private static final long BASE_BACKOFF_MILLIS = 20L;

    private final InventoryTxService tx;

    public Product createProduct(ProductRequest request) {
        return tx.createProduct(request);
    }

    public InventoryResponse addInventory(InventoryRequest request) {
        return tx.addInventory(request);
    }

    public InventoryResponse getInventory(Long productId, String warehouseId) {
        return tx.getInventory(productId, warehouseId);
    }

    public InventoryResponse reserveInventory(ReserveInventoryRequest request) {
        return withOptimisticLockRetry(
                () -> tx.reserve(request),
                "reserve orderId=" + request.getOrderId()
                        + " productId=" + request.getProductId()
        );
    }

    /**
     * Reserves a whole order exactly once, retrying the entire transaction on contention.
     *
     * <p>The retry wraps the complete unit of work, so a losing attempt re-reads every line
     * rather than re-applying part of a stale snapshot.
     */
    public ReserveOutcome reserveOrder(String eventId, String orderId, List<ReservationLine> lines) {
        return withOptimisticLockRetry(
                () -> tx.reserveOrder(eventId, orderId, lines),
                "reserveOrder orderId=" + orderId + " eventId=" + eventId
        );
    }

    public List<InventoryResponse> releaseInventory(String orderId) {
        return withOptimisticLockRetry(
                () -> tx.releaseByOrderId(orderId),
                "release orderId=" + orderId
        );
    }

    public List<InventoryResponse> confirmReservation(String orderId) {
        return withOptimisticLockRetry(
                () -> tx.confirmByOrderId(orderId),
                "confirm orderId=" + orderId
        );
    }

    /**
     * Runs {@code action} in a fresh transaction, retrying a bounded number of times when it
     * loses an optimistic-locking race.
     *
     * <p>{@link DataIntegrityViolationException} is retried too, because two threads racing
     * to reserve the same order line can both pass the "already reserved?" check and then
     * collide on the unique constraint. On the retry the loser finds the winner's row and
     * returns idempotently. Only the reserve path inserts, so release and confirm cannot
     * reach that branch.
     */
    private <T> T withOptimisticLockRetry(Supplier<T> action, String description) {

        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException failure) {
                lastFailure = failure;
                log.debug("Contention on attempt {}/{} for {}", attempt, MAX_ATTEMPTS, description);
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }

        log.warn("Giving up after {} attempts: {}", MAX_ATTEMPTS, description);
        throw new ReservationConflictException(
                "Could not complete " + description + " after " + MAX_ATTEMPTS
                        + " attempts due to concurrent modification. Please retry.",
                lastFailure
        );
    }

    /**
     * Exponential backoff with jitter. The jitter matters: without it, contending threads
     * back off by identical amounts and collide again in lockstep on every retry.
     */
    private void backoff(int attempt) {

        long ceiling = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        long delay = ThreadLocalRandom.current().nextLong(1, ceiling + 1);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ReservationConflictException(
                    "Interrupted while backing off before retry", interrupted
            );
        }
    }
}
