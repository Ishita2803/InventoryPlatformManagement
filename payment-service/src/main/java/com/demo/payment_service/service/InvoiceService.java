package com.demo.payment_service.service;

import com.demo.payment_service.client.CarrierServiceClient;
import com.demo.payment_service.dto.InvoiceRequest;
import com.demo.payment_service.dto.InvoiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase D8: {@code shipQuantity × unitPrice} for every shipped line, plus one weight-based
 * carrier surcharge for the whole order. Same "mock provider, real idempotency" shape as
 * {@link PaymentService} -- an in-memory map keyed by orderId, not a database, because
 * this service still has none. A real billing system persists invoices; this one proves the
 * calculation and the idempotency, which is what an interview question about it actually
 * probes.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final CarrierServiceClient carrierServiceClient;

    /** Decided invoices, keyed by orderId. Same reasoning as {@code PaymentService.decisions}:
     * the caller may retry, and an invoice recomputed twice for the same order must return
     * the same number both times. */
    private final Map<String, InvoiceResponse> invoices = new ConcurrentHashMap<>();

    public InvoiceService(CarrierServiceClient carrierServiceClient) {
        this.carrierServiceClient = carrierServiceClient;
    }

    public InvoiceResponse generate(InvoiceRequest request) {

        InvoiceResponse existing = invoices.get(request.orderId());
        if (existing != null) {
            log.info("Returning the existing invoice for order {} (idempotent replay)", request.orderId());
            return existing;
        }

        BigDecimal lineTotal = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (InvoiceRequest.Line line : request.lines()) {
            BigDecimal quantity = BigDecimal.valueOf(line.quantity());
            lineTotal = lineTotal.add(line.unitPrice().multiply(quantity));
            totalWeight = totalWeight.add(line.unitWeight().multiply(quantity));
        }

        BigDecimal surcharge = carrierServiceClient.surchargeFor(request.carrierCode(), totalWeight);

        InvoiceResponse response = new InvoiceResponse(
                "inv-" + UUID.randomUUID(),
                request.orderId(),
                lineTotal,
                totalWeight,
                surcharge,
                lineTotal.add(surcharge),
                Instant.now());

        // putIfAbsent, not put: two concurrent retries for the same order must not end up
        // with two different invoice ids, the same reasoning PaymentService.pay already
        // applies.
        InvoiceResponse raced = invoices.putIfAbsent(request.orderId(), response);
        if (raced != null) {
            return raced;
        }

        log.info("Invoice {} for order {}: lineTotal={} + weightSurcharge={} (weight {}kg, carrier {}) = {}",
                response.invoiceId(), request.orderId(), lineTotal, surcharge, totalWeight,
                request.carrierCode(), response.totalAmount());

        return response;
    }
}
