package com.demo.customer_service.dto;

import java.time.Instant;

public record CustomerResponse(
        String customerNo,
        String name,
        String email,
        AddressResponse defaultBillingAddress,
        AddressResponse defaultShippingAddress,
        Instant createdAt
) {
}
