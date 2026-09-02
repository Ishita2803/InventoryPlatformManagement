package com.demo.inventory_service.controller;

import com.demo.inventory_service.dto.CreateWarehouseRequest;
import com.demo.inventory_service.dto.WarehouseResponse;
import com.demo.inventory_service.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Registration is ADMIN-only (gateway-gated); listing is open to any authenticated role
 * -- a customer placing a sales order (Phase D7) needs to see warehouses exist, even if
 * they never see internal stock counts. */
@RestController
@RequestMapping("/api/inventory/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody CreateWarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(warehouseService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<WarehouseResponse>> listAll() {
        return ResponseEntity.ok(warehouseService.listAll());
    }

    @GetMapping("/{warehouseId}")
    public ResponseEntity<WarehouseResponse> get(@PathVariable String warehouseId) {
        return ResponseEntity.ok(warehouseService.get(warehouseId));
    }
}
