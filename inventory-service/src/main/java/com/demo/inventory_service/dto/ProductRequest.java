package com.demo.inventory_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductRequest {

    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Product name is required")
    private String name;
}
