package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.OrderReferenceRequest;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/products")
    public ResponseEntity<Product> createProduct(
            @Valid @RequestBody ProductRequest request
    ) {

        Product product = inventoryService.createProduct(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(product);
    }

    @PostMapping("/inventory")
    public ResponseEntity<InventoryResponse> addInventory(
            @Valid @RequestBody InventoryRequest request
    ) {

        InventoryResponse response = inventoryService.addInventory(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/inventory")
    public ResponseEntity<InventoryResponse> getInventory(
            @RequestParam Long productId,
            @RequestParam String warehouseId
    ) {

        return ResponseEntity.ok(
                inventoryService.getInventory(productId, warehouseId)
        );
    }

    /**
     * Reserve stock for one order line. Safe to call repeatedly with the same orderId:
     * the reservation is created at most once.
     */
    @PostMapping("/inventory/reserve")
    public ResponseEntity<InventoryResponse> reserveInventory(
            @Valid @RequestBody ReserveInventoryRequest request
    ) {

        return ResponseEntity.ok(inventoryService.reserveInventory(request));
    }

    /**
     * Saga compensation: release every reservation held by this order. Returns one entry per
     * stock row actually released, so an empty list means there was nothing left to undo.
     */
    @PostMapping("/inventory/release")
    public ResponseEntity<List<InventoryResponse>> releaseInventory(
            @Valid @RequestBody OrderReferenceRequest request
    ) {

        return ResponseEntity.ok(
                inventoryService.releaseInventory(request.getOrderId())
        );
    }

    /**
     * The order shipped: reserved stock leaves the warehouse permanently rather than
     * returning to available.
     */
    @PostMapping("/inventory/confirm")
    public ResponseEntity<List<InventoryResponse>> confirmReservation(
            @Valid @RequestBody OrderReferenceRequest request
    ) {

        return ResponseEntity.ok(
                inventoryService.confirmReservation(request.getOrderId())
        );
    }
}
