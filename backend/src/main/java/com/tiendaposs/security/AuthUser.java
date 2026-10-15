package com.tiendaposs.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Usuario autenticado de la petición actual (cargado desde BD en cada petición). */
public record AuthUser(Long userId, Long tenantId, String fullName, String email, boolean owner,
                       Set<String> permissions) {

    public Collection<? extends GrantedAuthority> authorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
    }

    public boolean can(Permission p) {
        return permissions.contains(p.name());
    }
}
