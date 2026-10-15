package com.tiendaposs.auth;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    /** Solo se guarda el hash SHA-256: quien lea la BD no puede usar el enlace. */
    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt = Instant.now();

    protected PasswordResetToken() {
    }

    public PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean usable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }

    public Long getUserId() { return userId; }
    public void markUsed() { this.usedAt = Instant.now(); }
}
