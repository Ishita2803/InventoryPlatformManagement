package com.demo.vendor_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** SKU is deliberately not editable -- it is the identifier other services key stock and
 * orders against; changing it out from under them is a different operation (retire this
 * product, create a new one), not an update. */
@Data
public class UpdateProductRequest {

    @NotBlank(message = "Product name is required")
    private String productName;

    private String description;

    @NotNull(message = "Unit weight is required")
    @DecimalMin(value = "0.001", message = "Unit weight must be positive")
    private BigDecimal unitWeight;

    @NotNull(message = "Cost price is required")
    @DecimalMin(value = "0.00", message = "Cost price cannot be negative")
    private BigDecimal costPrice;
}
