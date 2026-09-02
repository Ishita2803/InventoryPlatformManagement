package com.demo.customer_service.dto;

import java.time.Instant;

public record CustomerAddressResponse(
        Long id, String customerNo, String label, AddressResponse address, Instant createdAt
) {
}
