package com.demo.customer_service.controller;

import com.demo.customer_service.dto.CustomerResponse;
import com.demo.customer_service.dto.OnboardCustomerRequest;
import com.demo.customer_service.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Both routes here are gateway-gated to ADMIN only -- same shape as vendor-service's
 * VendorController. */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping("/onboard")
    public ResponseEntity<CustomerResponse> onboard(@Valid @RequestBody OnboardCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.onboard(request));
    }

    @GetMapping("/customers")
    public ResponseEntity<List<CustomerResponse>> listAll() {
        return ResponseEntity.ok(customerService.listAll());
    }

    @GetMapping("/customers/{customerNo}")
    public ResponseEntity<CustomerResponse> get(@PathVariable String customerNo) {
        return ResponseEntity.ok(customerService.get(customerNo));
    }
}
