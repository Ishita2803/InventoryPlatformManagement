package com.demo.customer_service.exception;

public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(Long id) {
        super("Address not found: " + id);
    }
}
