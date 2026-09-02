package com.demo.auth_service.service;

import com.demo.auth_service.models.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Signs and verifies with one shared HS256 secret -- {@code auth-service} signs,
 * the gateway's {@code JwtAuthFilter} verifies, nothing else needs to. Real OAuth2/OIDC
 * (separate signing keys, JWKS rotation, refresh tokens) would be the honest next step
 * for anything beyond a demo of the mechanics; deliberately not built here, the same way
 * this project has never claimed more resilience than it actually implemented.
 *
 * <p>No default secret. A missing {@code JWT_SECRET} must fail loudly at startup, not
 * silently sign with a guessable value -- same rule this project already applies to
 * database credentials.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiry-seconds:3600}") long expirySeconds) {

        // HS256 needs a key of at least 256 bits (32 bytes). A short secret would fail
        // at first sign, not at startup -- worth failing here instead, with a clear reason.
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 bytes for HS256; got " + secretBytes.length);
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expirySeconds = expirySeconds;
    }

    public long expirySeconds() {
        return expirySeconds;
    }

    public String issue(String username, Role role, String businessId) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim("businessId", businessId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    /** Throws (unchecked, from jjwt) on a bad signature, malformed token, or expiry. */
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
