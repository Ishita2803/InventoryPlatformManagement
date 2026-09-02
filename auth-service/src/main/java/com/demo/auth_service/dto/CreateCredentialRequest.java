package com.demo.auth_service.dto;

import com.demo.auth_service.models.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Called by vendor-service / customer-service / carrier-service at the moment admin
 * onboards a new party (Phase D2-D4) -- creating the business record and creating the
 * login are two separate writes in two separate services, so this is a deliberate,
 * accepted dual-write: worst case, onboarding a vendor succeeds but their login does
 * not, and admin retries. Not put through the outbox pattern because a missing login is
 * loudly obvious (the vendor cannot sign in) rather than a silent data-correctness bug
 * the way a missed inventory event would be.
 */
@Data
public class CreateCredentialRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    @NotNull(message = "Role is required")
    private Role role;

    @NotBlank(message = "businessId is required")
    private String businessId;
}
