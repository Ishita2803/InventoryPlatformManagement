package com.demo.vendor_service.controller;

import com.demo.vendor_service.dto.OnboardVendorRequest;
import com.demo.vendor_service.dto.VendorResponse;
import com.demo.vendor_service.service.VendorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Both routes here are gateway-gated to {@code ADMIN} only (see the gateway's
 * {@code JwtAuthFilter}) -- onboarding a vendor, and browsing the full vendor list, are
 * both admin-only capabilities per the spec. A vendor never needs to see this controller
 * at all; their own identity comes from their JWT, not a lookup here.
 */
@RestController
@RequestMapping("/api/vendor")
@RequiredArgsConstructor
public class VendorController {

    private final VendorService vendorService;

    @PostMapping("/onboard")
    public ResponseEntity<VendorResponse> onboard(@Valid @RequestBody OnboardVendorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vendorService.onboard(request));
    }

    @GetMapping("/vendors")
    public ResponseEntity<List<VendorResponse>> listAll() {
        return ResponseEntity.ok(vendorService.listAll());
    }

    @GetMapping("/vendors/{vendorId}")
    public ResponseEntity<VendorResponse> get(@PathVariable String vendorId) {
        return ResponseEntity.ok(vendorService.get(vendorId));
    }
}
