package com.demo.order_service.controller;

import com.demo.order_service.dto.CreatePurchaseOrderRequest;
import com.demo.order_service.dto.PurchaseOrderResponse;
import com.demo.order_service.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Placing a stocking order is ADMIN-only. Listing: admin sees every purchase order;
 * a vendor sees only their own (Vendor page's "view purchase order history"), scoped by
 * the gateway-forwarded X-User-Business-Id -- never a client-supplied vendorId. */
@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<PurchaseOrderResponse>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestHeader("X-User-Business-Id") String businessId) {
        return ResponseEntity.ok("ADMIN".equals(role)
                ? purchaseOrderService.listAll()
                : purchaseOrderService.listForVendor(businessId));
    }
}
