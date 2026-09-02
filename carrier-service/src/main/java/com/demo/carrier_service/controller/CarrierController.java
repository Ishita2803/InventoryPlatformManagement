package com.demo.carrier_service.controller;

import com.demo.carrier_service.dto.CarrierResponse;
import com.demo.carrier_service.dto.OnboardCarrierRequest;
import com.demo.carrier_service.service.CarrierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carrier")
@RequiredArgsConstructor
public class CarrierController {

    private final CarrierService carrierService;

    @PostMapping("/onboard")
    public ResponseEntity<CarrierResponse> onboard(@Valid @RequestBody OnboardCarrierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(carrierService.onboard(request));
    }

    /** ADMIN-only listing, same as vendor-service's vendor list -- needed so a customer
     * placing a sales order (Phase D7) has a source of truth for "which carriers exist,"
     * surfaced through the admin/customer screens rather than this route directly. */
    @GetMapping("/carriers")
    public ResponseEntity<List<CarrierResponse>> listAll() {
        return ResponseEntity.ok(carrierService.listAll());
    }

    /** Any authenticated role may look up one carrier's details, including its weight
     * tiers -- a customer needs to see a carrier's tiers to choose one at order time. */
    @GetMapping("/carriers/{carrierCode}")
    public ResponseEntity<CarrierResponse> get(@PathVariable String carrierCode) {
        return ResponseEntity.ok(carrierService.get(carrierCode));
    }
}
