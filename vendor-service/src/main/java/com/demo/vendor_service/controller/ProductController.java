package com.demo.vendor_service.controller;

import com.demo.vendor_service.dto.CreateProductRequest;
import com.demo.vendor_service.dto.ProductResponse;
import com.demo.vendor_service.dto.UpdateProductRequest;
import com.demo.vendor_service.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The vendor identity for every mutation comes from {@code X-User-Business-Id}, a header
 * the gateway's {@code JwtAuthFilter} sets from the caller's own verified JWT -- never
 * from a client-supplied field. A vendor cannot create a product "as" another vendor by
 * editing a request body, because the body has no vendorId field to edit in the first
 * place.
 */
@RestController
@RequestMapping("/api/vendor/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @RequestHeader("X-User-Business-Id") String vendorId,
            @Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(vendorId, request));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(
            @RequestHeader("X-User-Business-Id") String vendorId,
            @PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.update(vendorId, productId, request));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Business-Id") String vendorId,
            @PathVariable Long productId) {
        productService.delete(vendorId, productId);
        return ResponseEntity.noContent().build();
    }

    /**
     * A vendor sees their own catalog; an admin sees every vendor's (needed to place a
     * purchase order against any vendor, Phase D6). The role comes from the same
     * gateway-forwarded header the mutations trust.
     */
    @GetMapping
    public ResponseEntity<List<ProductResponse>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestHeader("X-User-Business-Id") String vendorId) {
        return ResponseEntity.ok("ADMIN".equals(role)
                ? productService.listAll()
                : productService.listForVendor(vendorId));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> get(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.get(productId));
    }

    @GetMapping("/by-sku/{sku}")
    public ResponseEntity<ProductResponse> getBySku(@PathVariable String sku) {
        return ResponseEntity.ok(productService.getBySku(sku));
    }
}
