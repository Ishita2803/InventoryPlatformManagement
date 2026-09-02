package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.FulfillmentRequest;
import com.demo.inventory_service.dto.FulfillmentResponse;
import com.demo.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, never-through-the-gateway -- called by order-service's Phase D7 sales-order
 * creation, the same boundary {@code PaymentClient} and every other cross-service client in
 * this project already relies on.
 */
@RestController
@RequestMapping("/api/inventory/fulfillment")
@RequiredArgsConstructor
public class FulfillmentController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<FulfillmentResponse> fulfill(@Valid @RequestBody FulfillmentRequest request) {
        return ResponseEntity.ok(inventoryService.fulfillSalesOrderLine(request));
    }
}
