package com.demo.inventory_service.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class InventoryResponse {
    private Long productId;
    private String warehouseId;
    private Integer availableQuantity;
    private Integer reservedQuantity;
}