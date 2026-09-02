package com.demo.inventory_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateWarehouseRequest {

    @NotBlank(message = "Warehouse ID is required")
    private String warehouseId;

    @NotBlank(message = "Location is required")
    private String location;

    @NotBlank(message = "Region is required")
    private String region;
}
