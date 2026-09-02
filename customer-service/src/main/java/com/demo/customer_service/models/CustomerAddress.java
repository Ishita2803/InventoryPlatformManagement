package com.demo.customer_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Every address a customer has ever given us, beyond the two defaults on {@link Customer}
 * itself -- a sales order (Phase D7) picks one of these (or a default) as the delivery
 * address for that specific order.
 */
@Entity
@Table(name = "customer_address")
@Getter
@Setter
@NoArgsConstructor
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_no", nullable = false, length = 64, updatable = false)
    private String customerNo;

    /** A human label ("Warehouse dropoff", "Head office") -- optional, purely for the
     * customer's own screen; nothing else in the system reads it. */
    @Column(length = 200)
    private String label;

    @Embedded
    private Address address;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CustomerAddress(String customerNo, String label, Address address) {
        this.customerNo = customerNo;
        this.label = label;
        this.address = address;
        this.createdAt = Instant.now();
    }
}
