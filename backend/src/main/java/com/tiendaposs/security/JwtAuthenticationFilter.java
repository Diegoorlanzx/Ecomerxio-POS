package com.tiendaposs.security;

import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Autentica con el JWT y vuelve a cargar el usuario desde BD en cada petición: así un usuario
 * desactivado, un cambio de roles o un "cerrar sesión en todos lados" se aplica al instante,
 * sin esperar a que el token expire.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;
    private final TenantRepository tenants;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users, TenantRepository tenants) {
        this.jwtService = jwtService;
        this.users = users;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                authenticate(header.substring(7));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token) {
        Claims claims = jwtService.parseOrNull(token);
        if (claims == null) return;
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return;
        }
        Integer tv = claims.get("tv", Integer.class);
        User user = users.findById(userId).orElse(null);
        if (user == null || !user.isActive() || tv == null || tv != user.getTokenVersion()) return;
        if (user.getTenantId() != null) {
            var tenant = tenants.findById(user.getTenantId()).orElse(null);
            if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) return;
        }
        var principal = new AuthUser(user.getId(), user.getTenantId(), user.getFullName(), user.getEmail(),
                user.isOwner(), user.effectivePermissions());
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.set(user.getTenantId());
    }
}
