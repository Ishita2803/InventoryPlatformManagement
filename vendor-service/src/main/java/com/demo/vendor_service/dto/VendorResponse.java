package com.demo.vendor_service.dto;

import java.time.Instant;

public record VendorResponse(String vendorId, String name, Instant createdAt) {
}
