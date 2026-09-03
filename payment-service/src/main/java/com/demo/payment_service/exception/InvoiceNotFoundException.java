package com.demo.payment_service.exception;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(String orderId) {
        super("No invoice generated yet for order " + orderId);
    }
}
