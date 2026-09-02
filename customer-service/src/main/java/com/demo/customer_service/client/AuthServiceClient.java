package com.demo.customer_service.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls auth-service's internal, never-gateway-routed {@code /auth/credentials} at
 * onboarding time. This is a deliberate, accepted dual-write -- creating the Vendor row
 * and creating its login are two separate services' separate transactions, so a crash
 * between them leaves a vendor that exists but cannot log in. Not put through the outbox
 * pattern because the failure is loudly obvious (admin sees the onboarding call fail, or
 * the vendor reports they cannot sign in) rather than a silent data-correctness bug the
 * way a missed inventory event would be. See auth-service's own note on
 * {@code CreateCredentialRequest} for the mirror image of this reasoning.
 */
@Component
public class AuthServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceClient.class);

    private final RestClient restClient;

    public AuthServiceClient(@Value("${auth.base-url:http://localhost:8085}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        log.info("Auth service client targeting {}", baseUrl);
    }

    public void createCredential(String username, String password, String role, String businessId) {
        restClient.post()
                .uri("/auth/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCredentialRequest(username, password, role, businessId))
                .retrieve()
                .toBodilessEntity();
    }

    private record CreateCredentialRequest(String username, String password, String role, String businessId) {
    }
}
