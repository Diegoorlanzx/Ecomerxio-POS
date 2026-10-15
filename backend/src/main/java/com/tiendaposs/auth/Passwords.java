package com.tiendaposs.auth;

import com.tiendaposs.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Passwords {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    /** Mínimo 8 caracteres con al menos una letra y un número. */
    public static void validate(String field, String password) {
        if (password == null || password.length() < 8) {
            throw ApiException.field(field, "debe tener al menos 8 caracteres");
        }
        if (password.length() > 72) {
            throw ApiException.field(field, "es demasiado larga (máximo 72 caracteres)");
        }
        if (!password.matches(".*[A-Za-zÁÉÍÓÚáéíóúÑñ].*") || !password.matches(".*\\d.*")) {
            throw ApiException.field(field, "debe tener letras y números");
        }
    }

    public static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
