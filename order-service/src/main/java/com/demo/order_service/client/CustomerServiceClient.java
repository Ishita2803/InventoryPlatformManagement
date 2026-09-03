package com.demo.order_service.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The synchronous, internal, never-through-the-gateway call {@code SalesOrderService}/
 * {@code DirectOrderService} make at invoicing time to resolve a customer's email address
 * for the invoice email. Same shape as {@code VendorServiceClient} -- a rare, per-invoice
 * lookup, not a per-line one, so no circuit breaker.
 *
 * <p>Returns {@code null}, not an exception, for an unknown {@code customerId}: the
 * legacy demo flow and this project's own seeded demo credentials use a bare business id
 * that was never actually onboarded through {@code customer-service}, so "no real
 * customer record" is an expected, common case here -- not a bug to surface as a 404 to
 * the caller placing an order.
 */
@Component
public class CustomerServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceClient.class);

    private final RestClient restClient;

    public CustomerServiceClient(@Value("${customer.base-url:http://localhost:8087}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        log.info("Customer service client targeting {}", baseUrl);
    }

    public String getEmail(String customerNo) {
        try {
            CustomerResponse response = restClient.get()
                    .uri("/api/customer/customers/{customerNo}", customerNo)
                    .retrieve()
                    .body(CustomerResponse.class);

            return response == null ? null : response.email();

        } catch (HttpClientErrorException.NotFound notFound) {
            log.info("No onboarded customer record for customerId={} -- invoice will not be emailed", customerNo);
            return null;
        }
    }

    /** Only the field this service actually needs -- addresses are customer-service's own
     * business, not ours. */
    private record CustomerResponse(String customerNo, String name, String email) {
    }
}
