package com.demo.customer_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer, many end users -- "Vijay Sales" (the {@link Customer}) has end users
 * "Vijay Sales Mumbai", "Vijay Sales Pune", each with their own shipping address. A sales
 * order can be placed for an end user rather than the customer directly (Phase D7); the
 * customer's own billing/login identity stays {@code customerNo} either way.
 */
@Entity
@Table(name = "end_user")
@Getter
@Setter
@NoArgsConstructor
public class EndUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "end_user_id", nullable = false, unique = true, length = 64, updatable = false)
    private String endUserId;

    @Column(name = "customer_no", nullable = false, length = 64, updatable = false)
    private String customerNo;

    @Column(nullable = false, length = 200)
    private String name;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line", column = @Column(name = "shipping_line")),
            @AttributeOverride(name = "city", column = @Column(name = "shipping_city")),
            @AttributeOverride(name = "region", column = @Column(name = "shipping_region"))
    })
    private Address shippingAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EndUser(String customerNo, String name, Address shippingAddress) {
        this.endUserId = UUID.randomUUID().toString();
        this.customerNo = customerNo;
        this.name = name;
        this.shippingAddress = shippingAddress;
        this.createdAt = Instant.now();
    }
}
