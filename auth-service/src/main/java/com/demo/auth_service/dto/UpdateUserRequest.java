package com.demo.auth_service.dto;

import com.demo.auth_service.models.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin edits a user's role, business id, or enabled state. The username itself is the
 * path variable and is never rewritten -- it is this service's identity key. */
@Data
public class UpdateUserRequest {

    @NotNull(message = "Role is required")
    private Role role;

    @NotBlank(message = "businessId is required")
    private String businessId;

    @NotNull(message = "enabled is required")
    private Boolean enabled;
}
