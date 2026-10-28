package com.tiendaposs.security;

import com.tiendaposs.billing.SubscriptionService;
import com.tiendaposs.tenant.Tenant;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Autentica con el JWT y vuelve a cargar el usuario desde BD en cada petición: así un usuario
 * desactivado, un cambio de roles o un "cerrar sesión en todos lados" se aplica al instante,
 * sin esperar a que el token expire.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Lo que una tienda en solo lectura (suscripción vencida) todavía puede hacer: pagar, salir, aceptar textos y sacar copias. */
    private static final List<String> ALLOWED_WHEN_READ_ONLY = List.of("/api/auth/", "/api/subscription", "/api/legal/",
            "/api/notifications", "/api/backups");

    private final JwtService jwtService;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final boolean enforceSubscription;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users, TenantRepository tenants,
                                   @org.springframework.beans.factory.annotation.Value("${app.billing.enforce:true}") boolean enforceSubscription) {
        this.jwtService = jwtService;
        this.users = users;
        this.tenants = tenants;
        this.enforceSubscription = enforceSubscription;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            Tenant tenant = null;
            if (header != null && header.startsWith("Bearer ")) {
                tenant = authenticate(header.substring(7));
            }
            if (tenant != null && blockedByReadOnly(request, tenant)) {
                response.setStatus(402);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":402,\"code\":\"SUSCRIPCION_VENCIDA\",\"message\":\"Tu suscripción venció y la cuenta "
                        + "está en solo lectura: puedes consultar y descargar tu información. Para volver a vender, paga en Más → Suscripción.\"}");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean blockedByReadOnly(HttpServletRequest request, Tenant tenant) {
        if (!enforceSubscription || !WRITES.contains(request.getMethod())) return false;
        String path = request.getRequestURI();
        if (ALLOWED_WHEN_READ_ONLY.stream().anyMatch(path::startsWith)) return false;
        return SubscriptionService.readOnly(tenant, Instant.now());
    }

    /** Autentica la petición; devuelve la tienda del usuario (nula si no hay sesión válida o el usuario es de la plataforma). */
    private Tenant authenticate(String token) {
        Claims claims = jwtService.parseOrNull(token);
        if (claims == null) return null;
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
        Integer tv = claims.get("tv", Integer.class);
        User user = users.findById(userId).orElse(null);
        if (user == null || !user.isActive() || tv == null || tv != user.getTokenVersion()) return null;
        Tenant tenant = null;
        if (user.getTenantId() != null) {
            tenant = tenants.findById(user.getTenantId()).orElse(null);
            if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) return null;
        }
        var principal = new AuthUser(user.getId(), user.getTenantId(), user.getFullName(), user.getEmail(),
                user.isOwner(), user.effectivePermissions());
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.set(user.getTenantId());
        return tenant;
    }
}
