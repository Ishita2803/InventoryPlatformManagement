package com.demo.auth_service.controller;

import com.demo.auth_service.dto.CreateCredentialRequest;
import com.demo.auth_service.dto.LoginRequest;
import com.demo.auth_service.dto.LoginResponse;
import com.demo.auth_service.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Called by other services at onboarding time (Phase D2-D4), never by an end user
     * directly -- there is deliberately no public self-registration endpoint. Not yet
     * restricted to internal-only network access; the gateway route for this path is
     * simply never exposed publicly, which is the same boundary {@code payment-service}
     * relies on today (reachable in-cluster, not routed through the gateway).
     */
    @PostMapping("/credentials")
    public ResponseEntity<Void> createCredential(@Valid @RequestBody CreateCredentialRequest request) {
        authService.createCredential(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Proves the whole chain end to end: a client with a valid JWT calls the gateway,
     * the gateway's {@code JwtAuthFilter} verifies it and forwards the decoded claims as
     * headers, and this service reads back exactly what it received -- with no JWT
     * library of its own and no knowledge of the signing secret. Useful as the one route
     * every role can call, for a smoke test that authentication is wired correctly before
     * any real business route depends on it.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(
            @RequestHeader(name = "X-User-Name", required = false) String username,
            @RequestHeader(name = "X-User-Role", required = false) String role,
            @RequestHeader(name = "X-User-Business-Id", required = false) String businessId) {

        return ResponseEntity.ok(Map.of(
                "username", username == null ? "" : username,
                "role", role == null ? "" : role,
                "businessId", businessId == null ? "" : businessId));
    }
}
