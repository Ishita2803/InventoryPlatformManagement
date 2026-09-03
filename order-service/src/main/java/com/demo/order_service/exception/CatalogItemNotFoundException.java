package com.demo.order_service.exception;

public class CatalogItemNotFoundException extends RuntimeException {

    public CatalogItemNotFoundException(String sku) {
        super("Catalog item not found for sku: " + sku);
    }
}
