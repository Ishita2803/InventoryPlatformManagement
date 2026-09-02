package com.demo.carrier_service.controller;

import com.demo.carrier_service.dto.CreateWeightTierRequest;
import com.demo.carrier_service.dto.WeightTierDto;
import com.demo.carrier_service.service.WeightTierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The carrier identity for every mutation is {@code X-User-Business-Id} -- set by the
 * gateway from the caller's verified JWT, never a client-supplied field. */
@RestController
@RequestMapping("/api/carrier/weight-restrictions")
@RequiredArgsConstructor
public class WeightTierController {

    private final WeightTierService weightTierService;

    @PostMapping
    public ResponseEntity<WeightTierDto> add(
            @RequestHeader("X-User-Business-Id") String carrierCode,
            @Valid @RequestBody CreateWeightTierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(weightTierService.add(carrierCode, request));
    }

    @GetMapping
    public ResponseEntity<List<WeightTierDto>> list(
            @RequestHeader("X-User-Business-Id") String carrierCode) {
        return ResponseEntity.ok(weightTierService.list(carrierCode));
    }

    @DeleteMapping("/{tierId}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Business-Id") String carrierCode,
            @PathVariable Long tierId) {
        weightTierService.delete(carrierCode, tierId);
        return ResponseEntity.noContent().build();
    }
}
