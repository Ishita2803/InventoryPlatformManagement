package com.demo.auth_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One login per onboarded party. Deliberately NOT keyed by the business entity's own
 * table (Vendor/Customer/Carrier each live in their own service) -- this service knows
 * nothing about vendors or customers, only that some {@code businessId} string exists
 * and belongs to some {@code role}. That id is what "only once onboarded can they
 * interact" actually means mechanically: the JWT carries it, and the gateway/downstream
 * services trust the token's claim, never a client-supplied id.
 */
@Entity
@Table(
        name = "credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_credential_username", columnNames = "username")
)
@Getter
@Setter
@NoArgsConstructor
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** Never the plaintext password -- {@code BCryptPasswordEncoder} output only. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /**
     * The id this login acts as -- {@code vendorId}, {@code customerNo}, or
     * {@code carrierCode} from the owning service, or a fixed platform id for
     * {@code ADMIN}. Goes straight into the JWT as a claim.
     */
    @Column(name = "business_id", nullable = false, length = 64)
    private String businessId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Admin-managed kill switch, added for the user-management phase. Checked at login
     * time, same exception as a wrong password -- a disabled account should not be
     * distinguishable from one that never existed or was mistyped.
     */
    @Column(nullable = false)
    private boolean enabled = true;

    public Credential(String username, String passwordHash, Role role, String businessId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.businessId = businessId;
        this.createdAt = Instant.now();
        this.enabled = true;
    }
}
