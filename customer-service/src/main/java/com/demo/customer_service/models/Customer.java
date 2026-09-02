package com.demo.customer_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code customerNo} is server-minted, same reasoning as {@code Vendor.vendorId}: a
 * cross-service business identifier (JWT {@code businessId} claim, referenced by sales
 * orders later) must not be an auto-increment surrogate key.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_no", nullable = false, unique = true, length = 64, updatable = false)
    private String customerNo;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String email;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line", column = @Column(name = "default_billing_line")),
            @AttributeOverride(name = "city", column = @Column(name = "default_billing_city")),
            @AttributeOverride(name = "region", column = @Column(name = "default_billing_region"))
    })
    private Address defaultBillingAddress;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line", column = @Column(name = "default_shipping_line")),
            @AttributeOverride(name = "city", column = @Column(name = "default_shipping_city")),
            @AttributeOverride(name = "region", column = @Column(name = "default_shipping_region"))
    })
    private Address defaultShippingAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Customer(String name, String email, Address defaultBillingAddress, Address defaultShippingAddress) {
        this.customerNo = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
        this.defaultBillingAddress = defaultBillingAddress;
        this.defaultShippingAddress = defaultShippingAddress;
        this.createdAt = Instant.now();
    }
}
