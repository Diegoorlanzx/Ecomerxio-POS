package com.tiendaposs.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String storeName,
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String ownerName,
            @NotBlank @Email(message = "no es un correo válido") String email,
            @NotBlank String password,
            String phone) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email(message = "no es un correo válido") String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record TenantInfo(Long id, String name, String logo, String currency, String timezone) {
    }

    public record SetPinRequest(@NotBlank String currentPassword, String pin) {
    }

    public record MeResponse(Long id, String fullName, String email, String phone, boolean owner, boolean hasPin,
                             List<String> roles, List<String> permissions, TenantInfo tenant) {
    }

    public record LoginResponse(String token, long expiresInMinutes, MeResponse me) {
    }
}
