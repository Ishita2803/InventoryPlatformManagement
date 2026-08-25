package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.ReservationConflictException;
import com.demo.inventory_service.models.Inventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry policy around optimistic-locking failures.
 *
 * <p>These are deliberately mock-based: the point is to pin the <em>policy</em> (how many
 * attempts, which exceptions are retryable, what happens when the budget runs out) rather
 * than the locking itself, which is proved against a real database in
 * {@code InventoryConcurrencyTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceRetryTest {

    private static final String ORDER_ID = "22222222-2222-2222-2222-222222222222";

    @Mock
    private InventoryTxService tx;

    @InjectMocks
    private InventoryService inventoryService;

    @Test
    @DisplayName("a reservation that loses two races still succeeds on the third attempt")
    void retriesUntilTheRaceIsWon() {

        InventoryResponse success = new InventoryResponse(1L, "WH-1", 6, 4);

        when(tx.reserve(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .thenReturn(success);

        InventoryResponse response = inventoryService.reserveInventory(request());

        assertThat(response).isSameAs(success);
        verify(tx, times(3)).reserve(any());
    }

    @Test
    @DisplayName("sustained contention gives up after a bounded number of attempts, as a 409")
    void givesUpAfterBoundedAttempts() {

        when(tx.reserve(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L));

        assertThatThrownBy(() -> inventoryService.reserveInventory(request()))
                .isInstanceOf(ReservationConflictException.class)
                .hasMessageContaining("after 4 attempts");

        // Bounded, not infinite: an unbounded retry under contention is a livelock.
        verify(tx, times(4)).reserve(any());
    }

    @Test
    @DisplayName("business failures are not retried: out of stock is an answer, not a race")
    void doesNotRetryBusinessFailures() {

        when(tx.reserve(any()))
                .thenThrow(new InsufficientInventoryException("Available=0, requested=1"));

        assertThatThrownBy(() -> inventoryService.reserveInventory(request()))
                .isInstanceOf(InsufficientInventoryException.class);

        verify(tx, times(1)).reserve(any());
    }

    private ReserveInventoryRequest request() {
        ReserveInventoryRequest request = new ReserveInventoryRequest();
        request.setOrderId(ORDER_ID);
        request.setProductId(1L);
        request.setWarehouseId("WH-1");
        request.setQuantity(4);
        return request;
    }
}
