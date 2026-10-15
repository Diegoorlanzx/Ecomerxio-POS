package com.tiendaposs.security;

import com.tiendaposs.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    /** Nulo si la petición es anónima. */
    public static AuthUser getOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) {
            return u;
        }
        return null;
    }

    public static AuthUser get() {
        AuthUser u = getOrNull();
        if (u == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Inicia sesión para continuar");
        }
        return u;
    }

    public static void require(Permission p) {
        if (!get().can(p)) {
            throw ApiException.forbidden("No tienes permiso para esta acción");
        }
    }
}
