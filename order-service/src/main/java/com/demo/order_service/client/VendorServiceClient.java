package com.demo.order_service.client;

import com.demo.order_service.exception.VendorSkuNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Resolves which vendor owns a sku, at the moment admin places a purchase order.
 * Duplicated from inventory-service's client of the same name and shape rather than
 * shared -- same "duplicated per service" reasoning as the Kafka event classes.
 */
@Component
public class VendorServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VendorServiceClient.class);

    private final RestClient restClient;

    public VendorServiceClient(@Value("${vendor.base-url:http://localhost:8086}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        log.info("Vendor service client targeting {}", baseUrl);
    }

    public VendorProduct getProductBySku(String sku) {
        try {
            VendorProductResponse response = restClient.get()
                    .uri("/api/vendor/products/by-sku/{sku}", sku)
                    .retrieve()
                    .body(VendorProductResponse.class);

            if (response == null) {
                throw new VendorSkuNotFoundException(sku);
            }

            return new VendorProduct(response.vendorId(), response.unitWeight());

        } catch (HttpClientErrorException.NotFound notFound) {
            throw new VendorSkuNotFoundException(sku);
        }
    }

    public record VendorProduct(String vendorId, BigDecimal unitWeight) {
    }

    private record VendorProductResponse(
            Long productId, String vendorId, String productName, String skuNumber,
            BigDecimal unitWeight
    ) {
    }
}
