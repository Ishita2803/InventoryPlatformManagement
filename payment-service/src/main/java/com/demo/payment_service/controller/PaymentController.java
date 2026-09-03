package com.demo.payment_service.controller;

import com.demo.payment_service.dto.InvoiceRequest;
import com.demo.payment_service.dto.InvoiceResponse;
import com.demo.payment_service.dto.PaymentRequest;
import com.demo.payment_service.dto.PaymentResponse;
import com.demo.payment_service.service.InvoiceService;
import com.demo.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final InvoiceService invoiceService;

    public PaymentController(PaymentService paymentService, InvoiceService invoiceService) {
        this.paymentService = paymentService;
        this.invoiceService = invoiceService;
    }

    /** Charge for an order. Safe to call more than once: decisions are keyed by orderId. */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(paymentService.pay(request));
    }

    /**
     * Phase D8: compute a sales order's invoice -- shipQuantity x salePrice per line, plus
     * one weight-based carrier surcharge for the whole order. Internal-only, called by
     * order-service's {@code SalesOrderService} right after a sales order is persisted.
     */
    @PostMapping("/invoices")
    public ResponseEntity<InvoiceResponse> generateInvoice(@Valid @RequestBody InvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.generate(request));
    }

    /**
     * Demo control: switch the mock between approving, declining and being slow.
     *
     * <p>Exists so the failure paths — decline, timeout, open circuit — can be shown live
     * without editing config and restarting. Obviously not something a real payment service
     * would expose; it is here because the "provider" is a stub.
     */
    @PostMapping("/behaviour")
    public ResponseEntity<Map<String, Object>> setBehaviour(
            @RequestParam PaymentService.Behaviour mode,
            @RequestParam(required = false) Long delayMs) {

        paymentService.setBehaviour(mode, delayMs);
        return ResponseEntity.ok(Map.of("behaviour", paymentService.behaviour()));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        paymentService.reset();
        return ResponseEntity.ok(Map.of("behaviour", paymentService.behaviour()));
    }
}
