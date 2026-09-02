package com.demo.inventory_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SetSalePriceRequest {

    @NotBlank(message = "SKU number is required")
    private String skuNumber;

    @NotNull(message = "Sale price is required")
    @DecimalMin(value = "0.00", message = "Sale price cannot be negative")
    private BigDecimal salePrice;
}
