package com.demo.inventory_service.models;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private String warehouseId;
    @Column(nullable = false)
    private Integer availableQuantity;
    @Column(nullable = false)
    private Integer reservedQuantity;
    @Version
    private Long version;
}

