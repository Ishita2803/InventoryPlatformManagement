package com.demo.customer_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAddressRequest {

    private String label;

    @NotNull(message = "Address is required")
    @Valid
    private AddressDto address;
}
