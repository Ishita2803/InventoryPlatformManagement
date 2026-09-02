package com.demo.carrier_service.dto;

import java.time.Instant;
import java.util.List;

public record CarrierResponse(
        String carrierCode, String carrierName, List<WeightTierDto> weightTiers, Instant createdAt
) {
}
