package com.demo.carrier_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OnboardCarrierRequest {

    @NotBlank(message = "Carrier code is required")
    private String carrierCode;

    @NotBlank(message = "Carrier name is required")
    private String carrierName;

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
