package com.demo.inventory_service.dto;

import java.time.Instant;

public record WarehouseResponse(String warehouseId, String location, String region, Instant createdAt) {
}
