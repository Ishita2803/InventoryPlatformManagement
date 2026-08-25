package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.exception.ReservationConflictException;
import com.demo.inventory_service.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract: that domain failures surface as the right status code, and that invalid
 * input is rejected before it reaches the service at all.
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    private static final String ORDER_ID = "33333333-3333-3333-3333-333333333333";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    @DisplayName("negative quantity is rejected as 400 with the offending field named")
    void negativeQuantityIsRejected() throws Exception {

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveJson(ORDER_ID, 1, "WH-1", -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.quantity").exists());

        // Rejected at the edge: the service is never bothered with invalid input.
        verify(inventoryService, never()).reserveInventory(any());
    }

    @Test
    @DisplayName("a missing orderId is rejected as 400: without it nothing can be idempotent")
    void missingOrderIdIsRejected() throws Exception {

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"warehouseId":"WH-1","quantity":2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.orderId").exists());
    }

    @Test
    @DisplayName("out of stock maps to 409, not 500")
    void insufficientStockMapsToConflict() throws Exception {

        when(inventoryService.reserveInventory(any()))
                .thenThrow(new InsufficientInventoryException("Available=1, requested=5"));

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveJson(ORDER_ID, 1, "WH-1", 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_INVENTORY"));
    }

    @Test
    @DisplayName("unknown inventory maps to 404")
    void unknownInventoryMapsToNotFound() throws Exception {

        when(inventoryService.reserveInventory(any()))
                .thenThrow(new InventoryNotFoundException("Inventory not found for productId=99"));

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveJson(ORDER_ID, 99, "WH-1", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("exhausted retries map to 409 rather than leaking a 500 stack trace")
    void reservationConflictMapsToConflict() throws Exception {

        when(inventoryService.reserveInventory(any()))
                .thenThrow(new ReservationConflictException("Could not complete after 4 attempts"));

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveJson(ORDER_ID, 1, "WH-1", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVATION_CONFLICT"));
    }

    @Test
    @DisplayName("a valid reservation returns 200 and the resulting stock levels")
    void validReservationSucceeds() throws Exception {

        when(inventoryService.reserveInventory(any()))
                .thenReturn(new InventoryResponse(1L, "WH-1", 6, 4));

        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveJson(ORDER_ID, 1, "WH-1", 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(6))
                .andExpect(jsonPath("$.reservedQuantity").value(4));
    }

    @Test
    @DisplayName("release requires an orderId, because it is order-scoped by design")
    void releaseWithoutOrderIdIsRejected() throws Exception {

        mockMvc.perform(post("/api/inventory/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.orderId").exists());
    }

    private String reserveJson(String orderId, long productId, String warehouseId, int quantity) {
        return """
                {"orderId":"%s","productId":%d,"warehouseId":"%s","quantity":%d}
                """.formatted(orderId, productId, warehouseId, quantity);
    }
}
