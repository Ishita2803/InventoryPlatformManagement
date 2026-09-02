package com.demo.customer_service.controller;

import com.demo.customer_service.dto.CreateAddressRequest;
import com.demo.customer_service.dto.CustomerAddressResponse;
import com.demo.customer_service.service.CustomerAddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The customer identity for every operation is {@code X-User-Business-Id}, set by the
 * gateway from the caller's verified JWT -- never a client-supplied field. */
@RestController
@RequestMapping("/api/customer/addresses")
@RequiredArgsConstructor
public class CustomerAddressController {

    private final CustomerAddressService addressService;

    @PostMapping
    public ResponseEntity<CustomerAddressResponse> add(
            @RequestHeader("X-User-Business-Id") String customerNo,
            @Valid @RequestBody CreateAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.add(customerNo, request));
    }

    @GetMapping
    public ResponseEntity<List<CustomerAddressResponse>> list(
            @RequestHeader("X-User-Business-Id") String customerNo) {
        return ResponseEntity.ok(addressService.list(customerNo));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Business-Id") String customerNo,
            @PathVariable Long addressId) {
        addressService.delete(customerNo, addressId);
        return ResponseEntity.noContent().build();
    }
}
