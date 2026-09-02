package com.demo.customer_service.exception;

public class EndUserNotFoundException extends RuntimeException {

    public EndUserNotFoundException(Long id) {
        super("End user not found: " + id);
    }

    public EndUserNotFoundException(String message) {
        super(message);
    }
}
