package com.demo.carrier_service.exception;

public class CarrierNotFoundException extends RuntimeException {

    public CarrierNotFoundException(String carrierCode) {
        super("Carrier not found: " + carrierCode);
    }
}
