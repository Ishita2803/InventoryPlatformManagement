package com.demo.auth_service.dto;

import com.demo.auth_service.models.Role;

public record LoginResponse(String token, Role role, String businessId, long expiresInSeconds) {
}
