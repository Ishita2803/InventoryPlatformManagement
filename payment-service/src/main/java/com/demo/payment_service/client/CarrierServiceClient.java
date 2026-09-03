package com.demo.payment_service.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The synchronous, internal, never-through-the-gateway call {@code InvoiceService} makes
 * to price an order's weight surcharge. Same shape as every other rare cross-service
 * lookup in this project (vendor-service's product-by-sku, inventory-service's D5 vendor
 * lookup) -- called once per invoice, not once per order line, so no circuit breaker.
 */
@Component
public class CarrierServiceClient {

    private final RestClient restClient;

    public CarrierServiceClient(@Value("${carrier.base-url:http://localhost:8088}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public BigDecimal surchargeFor(String carrierCode, BigDecimal weightKg) {

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/carrier/carriers/{carrierCode}/surcharge")
                        .queryParam("weightKg", weightKg)
                        .build(carrierCode))
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("surcharge") == null) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(response.get("surcharge").toString());
    }
}
