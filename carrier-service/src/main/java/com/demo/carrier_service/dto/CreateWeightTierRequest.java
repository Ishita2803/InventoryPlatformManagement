package com.demo.carrier_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateWeightTierRequest {

    @NotNull(message = "Upper limit is required")
    @DecimalMin(value = "0.001", message = "Upper limit must be positive")
    private BigDecimal upperLimitKg;

    @NotNull(message = "Additional cost is required")
    @DecimalMin(value = "0.00", message = "Additional cost cannot be negative")
    private BigDecimal additionalCost;
}
