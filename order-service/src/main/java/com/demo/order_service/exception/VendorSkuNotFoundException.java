package com.demo.order_service.exception;

public class VendorSkuNotFoundException extends RuntimeException {

    public VendorSkuNotFoundException(String sku) {
        super("No vendor product found for sku: " + sku);
    }
}
