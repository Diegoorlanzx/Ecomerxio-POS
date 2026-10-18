package com.tiendaposs.auth;

import jakarta.persistence.*;

import java.time.Instant;

/** Código de un solo uso para pasar una sesión iniciada en ecomerxio.com a pos.ecomerxio.com. */
@Entity
@Table(name = "login_handoffs")
public class LoginHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    /** Solo el hash SHA-256: quien lea la BD no puede usar el código. */
    private String codeHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt = Instant.now();

    protected LoginHandoff() {
    }

    public LoginHandoff(Long userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public Long getUserId() { return userId; }
}
