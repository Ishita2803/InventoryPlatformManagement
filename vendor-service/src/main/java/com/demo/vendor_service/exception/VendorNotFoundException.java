package com.demo.vendor_service.exception;

public class VendorNotFoundException extends RuntimeException {

    public VendorNotFoundException(String vendorId) {
        super("Vendor not found: " + vendorId);
    }
}
