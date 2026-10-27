package com.tiendaposs.auth;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.AuthDtos.LoginResponse;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.JwtService;
import com.tiendaposs.tenant.TenantRepository;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Traspaso de sesión: el login de ecomerxio.com valida al usuario contra este POS (POST /api/auth/login), pide
 * un código de un solo uso y manda al navegador a pos.ecomerxio.com/sso?code=..., que lo canjea por su sesión.
 * El token nunca viaja en la dirección; el código vence en 60 s y sirve una sola vez.
 */
@Service
public class LoginHandoffService {

    static final Duration TTL = Duration.ofSeconds(60);

    private final LoginHandoffRepository handoffs;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final JwtService jwt;
    private final AuthService auth;
    private final AuditService audit;

    public LoginHandoffService(LoginHandoffRepository handoffs, UserRepository users, TenantRepository tenants,
                               JwtService jwt, AuthService auth, AuditService audit) {
        this.handoffs = handoffs;
        this.users = users;
        this.tenants = tenants;
        this.jwt = jwt;
        this.auth = auth;
        this.audit = audit;
    }

    public record HandoffCode(String code, long expiresInSeconds) {
    }

    /** Para el usuario de la sesión actual. */
    @Transactional
    public HandoffCode create() {
        handoffs.deleteExpiredBefore(Instant.now().minus(Duration.ofHours(1)));
        String code = Passwords.randomToken();
        handoffs.save(new LoginHandoff(CurrentUser.get().userId(), Passwords.sha256(code), Instant.now().plus(TTL)));
        return new HandoffCode(code, TTL.toSeconds());
    }

    /** Para un usuario elegido por la plataforma (soporte desde el Super Admin de Ecomerxio). */
    @Transactional
    public HandoffCode createFor(Long userId) {
        handoffs.deleteExpiredBefore(Instant.now().minus(Duration.ofHours(1)));
        String code = Passwords.randomToken();
        handoffs.save(new LoginHandoff(userId, Passwords.sha256(code), Instant.now().plus(TTL)));
        return new HandoffCode(code, TTL.toSeconds());
    }

    @Transactional
    public LoginResponse redeem(String code) {
        String hash = Passwords.sha256(code == null ? "" : code.trim());
        if (handoffs.consume(hash, Instant.now()) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El enlace para entrar venció o ya se usó. Inicia sesión de nuevo.");
        }
        Long userId = handoffs.findByCodeHash(hash).orElseThrow().getUserId();
        User user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        if (!user.isActive()) throw ApiException.forbidden("Este usuario está desactivado. Habla con el dueño de la tienda.");
        if (user.getTenantId() != null && !"ACTIVE".equals(tenants.findById(user.getTenantId()).orElseThrow().getStatus())) {
            throw ApiException.forbidden("La tienda no está activa.");
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);
        audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), "LOGIN", "Inició sesión desde ecomerxio.com", false);
        return new LoginResponse(jwt.generate(user.getId(), user.getTenantId(), user.getTokenVersion()), jwt.expirationMinutes(), auth.me(user));
    }
}
