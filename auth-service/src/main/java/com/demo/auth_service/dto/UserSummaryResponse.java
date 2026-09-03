package com.demo.auth_service.dto;

import com.demo.auth_service.models.Role;

/** Admin-facing user directory row. Never carries {@code passwordHash} -- the same
 * "never return the hash" discipline every login-adjacent response in this service follows. */
public record UserSummaryResponse(
        String username,
        Role role,
        String businessId,
        boolean enabled
) {
}
