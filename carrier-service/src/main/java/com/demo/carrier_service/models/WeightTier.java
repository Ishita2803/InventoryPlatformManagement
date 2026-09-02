package com.demo.carrier_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One weight-upper-limit -> additional-cost pair. An order's weight (Phase D8, sum of
 * {@code unitWeight × shipQuantity} across its lines) is matched against a carrier's
 * tiers ordered by {@code upperLimitKg} ascending; the first tier whose limit the order's
 * weight does not exceed is the one that applies. A weight heavier than every defined
 * tier uses the heaviest tier's surcharge as a ceiling -- documented here rather than
 * discovered as a surprise when Phase D8 actually needs the lookup.
 */
@Entity
@Table(name = "weight_tier")
@Getter
@Setter
@NoArgsConstructor
public class WeightTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier_code", nullable = false, length = 32, updatable = false)
    private String carrierCode;

    @Column(name = "upper_limit_kg", nullable = false, precision = 10, scale = 3)
    private BigDecimal upperLimitKg;

    @Column(name = "additional_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal additionalCost;

    public WeightTier(String carrierCode, BigDecimal upperLimitKg, BigDecimal additionalCost) {
        this.carrierCode = carrierCode;
        this.upperLimitKg = upperLimitKg;
        this.additionalCost = additionalCost;
    }
}
