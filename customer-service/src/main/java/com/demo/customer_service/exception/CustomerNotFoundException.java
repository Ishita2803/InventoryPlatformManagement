package com.demo.customer_service.exception;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String customerNo) {
        super("Customer not found: " + customerNo);
    }
}
