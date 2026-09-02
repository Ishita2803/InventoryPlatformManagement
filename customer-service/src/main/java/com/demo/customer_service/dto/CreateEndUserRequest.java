package com.demo.customer_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateEndUserRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Shipping address is required")
    @Valid
    private AddressDto shippingAddress;
}
