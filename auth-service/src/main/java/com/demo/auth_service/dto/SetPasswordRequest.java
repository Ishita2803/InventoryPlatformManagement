package com.demo.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin sets a user's password directly -- no reset-token/email flow, a deliberate
 * simplification confirmed with Karthik (see plan.md's phase for this work). */
@Data
public class SetPasswordRequest {

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "newPassword must be at least 8 characters")
    private String newPassword;
}
