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
            String phone,
            /** Tipo de negocio (perfil); si no viene queda TIENDA. */ String businessType,
            /** Plan elegido en la landing (TIENDA, NEGOCIO o PRO); si no viene o no existe queda NEGOCIO. */ String plan) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email(message = "no es un correo válido") String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record TenantInfo(Long id, String name, String logo, String currency, String timezone,
                             /** Plan y fin de la prueba (null si ya no está en prueba). */ String plan, String planName,
                             java.time.Instant trialEndsAt) {
    }

    public record SetPinRequest(@NotBlank String currentPassword, String pin) {
    }

    public record MeResponse(Long id, String fullName, String email, String phone, boolean owner, boolean hasPin,
                             List<String> roles, List<String> permissions, TenantInfo tenant,
                             /** Tipo de negocio y módulos activos: la app oculta lo que la tienda no usa. */
                             String businessType, List<String> modules,
                             /** Asistente con IA disponible en la plataforma (ASSISTANT_ENABLED). */ boolean assistantEnabled,
                             /** La tienda emite documentos electrónicos (la caja ofrece "el cliente pide factura"). */ boolean invoicingEnabled) {
    }

    public record LoginResponse(String token, long expiresInMinutes, MeResponse me) {
    }
}
