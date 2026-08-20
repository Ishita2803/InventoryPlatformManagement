package com.demo.inventory_service.models;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigInteger;

@Entity
@Data
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String sku;
    @Column(nullable = false)
    private String name;
//    private Boolean active;
}