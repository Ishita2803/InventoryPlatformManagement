package com.demo.customer_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddressDto {

    @NotBlank(message = "Address line is required")
    private String line;

    @NotBlank(message = "City is required")
    private String city;

    /** The zone code Phase D7's fulfillment search matches against a warehouse's region --
     * e.g. "MUMBAI", "PUNE". Not validated against a fixed list on purpose; see Address. */
    @NotBlank(message = "Region is required")
    private String region;
}
