package com.tiendaposs.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** En producción no arranca con valores de desarrollo: secreto de sesión débil o direcciones locales. */
@Component
@Profile("prod")
public class ProductionGuard {

    private final String jwtSecret;
    private final String frontendUrl;

    public ProductionGuard(@Value("${app.jwt.secret}") String jwtSecret, @Value("${app.frontend-url}") String frontendUrl) {
        this.jwtSecret = jwtSecret;
        this.frontendUrl = frontendUrl;
    }

    @PostConstruct
    void check() {
        if (jwtSecret == null || jwtSecret.length() < 32 || jwtSecret.startsWith("dev-only")) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres aleatorios en producción");
        }
        if (frontendUrl == null || !frontendUrl.startsWith("https://")) {
            throw new IllegalStateException("FRONTEND_URL debe ser la dirección pública con https:// (por ejemplo https://app.mitienda.com)");
        }
    }
}
