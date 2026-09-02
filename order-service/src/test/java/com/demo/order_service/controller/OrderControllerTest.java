package com.demo.order_service.controller;

import com.demo.order_service.dto.OrderItemResponse;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String ORDER_ID = "44444444-4444-4444-4444-444444444444";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    @DisplayName("a valid order returns 201 with the order body")
    void validOrderIsCreated() throws Exception {

        when(orderService.createOrder(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-1",
                                  "items": [
                                    {"productId":1,"warehouseId":"WH-1","quantity":2,"unitPrice":10.50}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(21.00));
    }

    @Test
    @DisplayName("the internal surrogate id is never exposed to clients")
    void responseDoesNotLeakInternalId() throws Exception {

        when(orderService.createOrder(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-1",
                                  "items": [
                                    {"productId":1,"warehouseId":"WH-1","quantity":2,"unitPrice":10.50}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @DisplayName("an order with no items is rejected as 400")
    void emptyItemsRejected() throws Exception {

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"CUST-1","items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.items").exists());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("a negative line quantity is rejected, naming the exact item that failed")
    void negativeItemQuantityRejected() throws Exception {

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-1",
                                  "items": [
                                    {"productId":1,"warehouseId":"WH-1","quantity":-3,"unitPrice":10.50}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                // Nested path: proves @Valid cascaded into the collection.
                .andExpect(jsonPath("$.fieldErrors['items[0].quantity']").exists());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("a missing customerId is rejected as 400")
    void missingCustomerIdRejected() throws Exception {

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"productId":1,"warehouseId":"WH-1","quantity":1,"unitPrice":1.00}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customerId").exists());
    }

    @Test
    @DisplayName("an unknown order returns 404, not an empty 200")
    void unknownOrderReturnsNotFound() throws Exception {

        when(orderService.getOrder(anyString()))
                .thenThrow(new OrderNotFoundException("Order not found: nope"));

        mockMvc.perform(get("/api/orders/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("listing orders returns the page contents")
    void listOrdersReturnsResults() throws Exception {

        when(orderService.listOrders(any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/orders").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(ORDER_ID));
    }

    private OrderResponse sampleResponse() {
        return new OrderResponse(
                ORDER_ID,
                "CUST-1",
                OrderStatus.PENDING,
                new BigDecimal("21.00"),
                List.of(new OrderItemResponse(
                        1L, "WH-1", null, 2, new BigDecimal("10.50"), new BigDecimal("21.00"))),
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z"),
                null
        );
    }
}
