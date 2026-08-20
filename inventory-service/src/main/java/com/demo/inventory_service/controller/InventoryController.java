package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

        InventoryResponse response =
                inventoryService.addInventory(request);

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
                inventoryService.getInventory(
                        productId,
                        warehouseId
                )
        );
    }

    @PostMapping("/inventory/reserve")
    public ResponseEntity<InventoryResponse> reserveInventory(
            @Valid @RequestBody ReserveInventoryRequest request
    ) {

        InventoryResponse response =
                inventoryService.reserveInventory(
                        request.getProductId(),
                        request.getWarehouseId(),
                        request.getQuantity()
                );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/inventory/release")
    public ResponseEntity<InventoryResponse> releaseInventory(
            @Valid @RequestBody ReserveInventoryRequest request
    ) {

        InventoryResponse response =
                inventoryService.releaseInventory(
                        request.getProductId(),
                        request.getWarehouseId(),
                        request.getQuantity()
                );

        return ResponseEntity.ok(response);
    }
}