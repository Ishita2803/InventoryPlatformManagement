package com.demo.customer_service.dto;

import java.time.Instant;

public record EndUserResponse(
        String endUserId, String customerNo, String name, AddressResponse shippingAddress, Instant createdAt
) {
}
