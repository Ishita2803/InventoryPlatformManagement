package com.demo.inventory_service.exception;

public class DuplicateWarehouseIdException extends RuntimeException {

    public DuplicateWarehouseIdException(String warehouseId) {
        super("Warehouse id already exists: " + warehouseId);
    }
}
