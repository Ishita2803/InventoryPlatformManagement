package com.demo.inventory_service.client;

import com.demo.inventory_service.exception.VendorSkuNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * The synchronous, internal, never-through-the-gateway lookup {@code CatalogService}
 * needs at the moment admin sets a sale price: which vendor owns this sku, and how much
 * does one unit weigh. Same shape as {@code PaymentClient} in order-service -- one
 * blocking call, made rarely (price-setting, not every order), so no circuit breaker is
 * warranted the way payment's per-order call earned one in Phase 8.
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

    /** Only the fields this service actually needs -- costPrice and description are
     * vendor-service's business, not ours. */
    private record VendorProductResponse(
            Long productId, String vendorId, String productName, String skuNumber,
            BigDecimal unitWeight
    ) {
    }
}
