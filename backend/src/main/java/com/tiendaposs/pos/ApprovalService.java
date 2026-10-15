package com.tiendaposs.pos;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Autorizaciones en el mostrador: cuando el cajero no tiene un permiso (descuento alto, anular,
 * devolver, precio bajo el mínimo, ajuste de caja), un supervisor presente digita su PIN (o su
 * contraseña) y el servidor entrega un pase firmado de 5 minutos para ESA acción. Quien autorizó
 * queda registrado en la operación y en la auditoría.
 */
@Service
public class ApprovalService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SecretKey key;

    public ApprovalService(UserRepository users, PasswordEncoder encoder, AuditService audit,
                           @Value("${app.jwt.secret}") String secret) throws Exception {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest(("approval:" + secret).getBytes(StandardCharsets.UTF_8)));
    }

    public record Approver(Long id, String fullName) {
    }

    public record ApprovalResult(String token, String approverName, Instant expiresAt) {
    }

    /** Usuarios activos de la tienda que tienen el permiso (para elegir quién autoriza). */
    public List<Approver> approvers(Permission p) {
        Long t = TenantContext.require();
        return users.findByTenantIdOrderByFullNameAsc(t).stream()
                .filter(u -> u.isActive() && u.effectivePermissions().contains(p.name()))
                .map(u -> new Approver(u.getId(), u.getFullName())).toList();
    }

    public ApprovalResult approve(Long approverId, String secret, Permission p) {
        Long t = TenantContext.require();
        AuthUser requester = CurrentUser.get();
        User approver = users.findByIdAndTenantId(approverId, t).filter(User::isActive)
                .orElseThrow(() -> ApiException.badRequest("Ese supervisor no existe o está desactivado"));
        boolean ok = secret != null && !secret.isBlank()
                && ((approver.getPinHash() != null && encoder.matches(secret, approver.getPinHash()))
                || encoder.matches(secret, approver.getPasswordHash()));
        if (!ok) {
            audit.logFor(t, requester.userId(), requester.fullName(), "AUTORIZACION_FALLIDA",
                    "PIN incorrecto de " + approver.getFullName() + " para " + p.label().toLowerCase(), true);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PIN o contraseña incorrectos");
        }
        if (!approver.effectivePermissions().contains(p.name())) {
            throw ApiException.forbidden(approver.getFullName() + " no tiene permiso para autorizar esto");
        }
        Instant exp = Instant.now().plus(5, ChronoUnit.MINUTES);
        String token = Jwts.builder().subject(String.valueOf(approver.getId()))
                .claim("perm", p.name()).claim("tid", t).claim("req", requester.userId()).claim("name", approver.getFullName())
                .expiration(Date.from(exp)).signWith(key).compact();
        return new ApprovalResult(token, approver.getFullName(), exp);
    }

    /**
     * Comprueba que la acción está permitida: el propio usuario tiene el permiso, o trae un pase
     * válido de un supervisor. Devuelve el nombre de quien autorizó (nulo si fue el propio usuario).
     */
    public String require(Permission p, List<String> tokens, String what) {
        AuthUser me = CurrentUser.get();
        if (me.can(p)) return null;
        if (tokens != null) {
            for (String tk : tokens) {
                String name = verify(p, tk);
                if (name != null) return name;
            }
        }
        return require(p, (String) null, what);
    }

    /** Nombre de quien autorizó si el pase es válido para el permiso; nulo si no. */
    private String verify(Permission p, String tk) {
        if (tk == null || tk.isBlank()) return null;
        AuthUser me = CurrentUser.get();
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(tk).getPayload();
            if (p.name().equals(c.get("perm", String.class)) && me.tenantId().equals(c.get("tid", Long.class))
                    && me.userId().equals(c.get("req", Long.class))) {
                return c.get("name", String.class);
            }
        } catch (Exception ignored) {
            // pase vencido o alterado: se trata como ausente
        }
        return null;
    }

    public String require(Permission p, String approvalToken, String what) {
        AuthUser me = CurrentUser.get();
        if (me.can(p)) return null;
        if (approvalToken == null || approvalToken.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, what + " necesita autorización de un supervisor",
                    java.util.Map.of("approval", p.name()));
        }
        Claims c;
        try {
            c = Jwts.parser().verifyWith(key).build().parseSignedClaims(approvalToken).getPayload();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La autorización venció. Pídela de nuevo.", java.util.Map.of("approval", p.name()));
        }
        if (!p.name().equals(c.get("perm", String.class)) || !me.tenantId().equals(c.get("tid", Long.class))
                || !me.userId().equals(c.get("req", Long.class))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La autorización no corresponde a esta acción", java.util.Map.of("approval", p.name()));
        }
        return c.get("name", String.class);
    }
}
