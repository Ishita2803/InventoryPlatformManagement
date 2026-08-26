package com.demo.payment_service.controller;

import com.demo.payment_service.dto.PaymentRequest;
import com.demo.payment_service.dto.PaymentResponse;
import com.demo.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Charge for an order. Safe to call more than once: decisions are keyed by orderId. */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(paymentService.pay(request));
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
