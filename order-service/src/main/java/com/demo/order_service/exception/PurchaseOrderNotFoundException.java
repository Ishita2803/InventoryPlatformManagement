package com.demo.order_service.exception;

public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(String purchaseOrderId) {
        super("Purchase order not found: " + purchaseOrderId);
    }
}
