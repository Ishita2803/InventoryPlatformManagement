package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.CatalogItemResponse;
import com.demo.inventory_service.dto.SetSalePriceRequest;
import com.demo.inventory_service.service.CatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Setting a price is ADMIN-only; reading the catalog is open to any authenticated role
 * -- a customer (Phase D7) needs to see sale prices to place an order. */
@RestController
@RequestMapping("/api/inventory/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @PostMapping
    public ResponseEntity<CatalogItemResponse> setSalePrice(@Valid @RequestBody SetSalePriceRequest request) {
        return ResponseEntity.ok(catalogService.setSalePrice(request));
    }

    @GetMapping
    public ResponseEntity<List<CatalogItemResponse>> listAll() {
        return ResponseEntity.ok(catalogService.listAll());
    }

    @GetMapping("/{sku}")
    public ResponseEntity<CatalogItemResponse> get(@PathVariable String sku) {
        return ResponseEntity.ok(catalogService.get(sku));
    }
}
