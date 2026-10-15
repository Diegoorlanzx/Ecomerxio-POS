package com.tiendaposs.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class UserDtos {

    private UserDtos() {
    }

    public record RoleRef(Long id, String name, String systemCode) {
    }

    public record UserDto(Long id, String fullName, String email, String phone, boolean active, boolean owner,
                          boolean locked, List<RoleRef> roles, Instant lastLoginAt, Instant createdAt) {
        public static UserDto of(User u) {
            var roles = u.getRoles().stream()
                    .map(r -> new RoleRef(r.getId(), r.getName(), r.getSystemCode() == null ? null : r.getSystemCode().name()))
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
            return new UserDto(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.isActive(), u.isOwner(),
                    u.isLocked(), roles, u.getLastLoginAt(), u.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String fullName,
            @NotBlank @Email(message = "no es un correo válido") String email,
            String phone,
            @NotBlank String password,
            @NotNull(message = "elige al menos un rol") @Size(min = 1, message = "elige al menos un rol") Set<Long> roleIds) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String fullName,
            @NotBlank @Email(message = "no es un correo válido") String email,
            String phone,
            @NotNull(message = "elige al menos un rol") @Size(min = 1, message = "elige al menos un rol") Set<Long> roleIds) {
    }

    public record SetPasswordRequest(@NotBlank String password) {
    }

    public record RoleDto(Long id, String name, String description, String systemCode, boolean editable,
                          boolean deletable, List<String> permissions, long userCount) {
    }

    public record RoleRequest(
            @NotBlank @Size(max = 60, message = "máximo 60 caracteres") String name,
            @Size(max = 255, message = "máximo 255 caracteres") String description,
            Set<String> permissions) {
    }

    public record PermissionDto(String code, String group, String label) {
    }
}
