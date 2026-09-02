package com.demo.auth_service.config;

import com.demo.auth_service.models.Credential;
import com.demo.auth_service.models.Role;
import com.demo.auth_service.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds one demo login per role, purely so Phase D1's exit criterion -- "log in as a
 * seeded test user of each of the 4 roles" -- is checkable before Phases D2-D4 exist to
 * do real, onboarding-triggered credential creation. Idempotent: skips any username
 * already present, so restarts don't error and don't reset a password changed by hand.
 *
 * <p>The seeded {@code businessId}s (e.g. {@code VENDOR-SEED-1}) do not correspond to
 * any real Vendor/Customer/Carrier row yet -- those services don't exist until
 * Phase D2-D4. That's expected here; this phase is proving the auth mechanism alone.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoUserSeeder implements ApplicationRunner {

    private final CredentialRepository credentialRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {

        seed("admin", "admin-demo-pass", Role.ADMIN, "ADMIN-1");
        seed("vendor-demo", "vendor-demo-pass", Role.VENDOR, "VENDOR-SEED-1");
        seed("customer-demo", "customer-demo-pass", Role.CUSTOMER, "CUSTOMER-SEED-1");
        seed("carrier-demo", "carrier-demo-pass", Role.CARRIER, "CARRIER-SEED-1");
    }

    private void seed(String username, String password, Role role, String businessId) {

        if (credentialRepository.existsByUsername(username)) {
            return;
        }

        credentialRepository.save(new Credential(
                username, passwordEncoder.encode(password), role, businessId));

        log.info("Seeded demo credential username={} role={}", username, role);
    }
}
