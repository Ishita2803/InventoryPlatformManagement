package com.demo.carrier_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Unlike {@code Vendor.vendorId}/{@code Customer.customerNo}, {@code carrierCode} is
 * NOT server-minted -- admin supplies it at onboarding (e.g. "BLUEDART", "DTDC"). A
 * carrier code is a real-world mnemonic identifier businesses already have, not an
 * internal handle Impulse invents; forcing a UUID onto something that already has a
 * meaningful short name would be worse than the identifiers it's modeled after.
 */
@Entity
@Table(name = "carrier")
@Getter
@Setter
@NoArgsConstructor
public class Carrier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier_code", nullable = false, unique = true, length = 32, updatable = false)
    private String carrierCode;

    @Column(name = "carrier_name", nullable = false, length = 200)
    private String carrierName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Carrier(String carrierCode, String carrierName) {
        this.carrierCode = carrierCode;
        this.carrierName = carrierName;
        this.createdAt = Instant.now();
    }
}
