package com.demo.customer_service.models;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@code region} is not decoration -- it is the zone code Phase D7's fulfillment search
 * matches against a warehouse's own region (Phase D5), the "simple region/zone matching"
 * chosen over real geodistance specifically so no address here ever needs a geocoding
 * call. A free-text region ("MUMBAI", "PUNE") is deliberately not its own lookup table:
 * there is no behaviour attached to a region beyond string equality, so a table would be
 * pure ceremony.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Address {

    @Column(name = "address_line", nullable = false, length = 500)
    private String line;

    @Column(name = "city", nullable = false, length = 200)
    private String city;

    @Column(name = "region", nullable = false, length = 100)
    private String region;

    public Address(String line, String city, String region) {
        this.line = line;
        this.city = city;
        this.region = region;
    }
}
