package com.demo.carrier_service.exception;

public class DuplicateCarrierCodeException extends RuntimeException {

    public DuplicateCarrierCodeException(String carrierCode) {
        super("Carrier code already exists: " + carrierCode);
    }
}
