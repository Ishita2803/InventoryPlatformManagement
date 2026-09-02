package com.demo.inventory_service.exception;

public class WarehouseNotFoundException extends RuntimeException {

    public WarehouseNotFoundException(String warehouseId) {
        super("Warehouse not found: " + warehouseId);
    }
}
