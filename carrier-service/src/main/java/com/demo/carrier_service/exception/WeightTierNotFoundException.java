package com.demo.carrier_service.exception;

public class WeightTierNotFoundException extends RuntimeException {

    public WeightTierNotFoundException(Long id) {
        super("Weight tier not found: " + id);
    }
}
