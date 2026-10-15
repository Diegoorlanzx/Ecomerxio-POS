package com.tiendaposs.auth;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.AuthDtos.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.JwtService;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantRepository;
import com.tiendaposs.users.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

@Service
public class AuthService {

    /** Hash bcrypt de relleno: comparar contra él cuando el correo no existe iguala los tiempos
     * de respuesta y no revela qué correos están registrados. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWjJAu7fq6HgWa2VYzCwN0Dj2y1W";

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final RoleSeeder roleSeeder;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final PasswordResetTokenRepository resetTokens;
    private final MailService mail;
    private final int maxFailed;
    private final int lockMinutes;
    private final String frontendUrl;

    public AuthService(UserRepository users, TenantRepository tenants, RoleRepository roles, RoleSeeder roleSeeder,
                       PasswordEncoder encoder, JwtService jwt, AuditService audit,
                       PasswordResetTokenRepository resetTokens, MailService mail,
                       @Value("${app.login.max-failed-attempts}") int maxFailed,
                       @Value("${app.login.lock-minutes}") int lockMinutes,
                       @Value("${app.frontend-url}") String frontendUrl) {
        this.users = users;
        this.tenants = tenants;
        this.roles = roles;
        this.roleSeeder = roleSeeder;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.resetTokens = resetTokens;
        this.mail = mail;
        this.maxFailed = maxFailed;
        this.lockMinutes = lockMinutes;
        this.frontendUrl = frontendUrl;
    }

    /** Crea una tienda nueva con sus 8 roles iniciales y su dueño. */
    @Transactional
    public LoginResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        Passwords.validate("password", req.password());
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.field("email", "ya está registrado. Inicia sesión o recupera tu contraseña");
        }
        Tenant t = new Tenant();
        t.setName(req.storeName().trim());
        t.setEmail(email);
        t.setPhone(blankToNull(req.phone()));
        t = tenants.save(t);
        roleSeeder.seed(t.getId());

        User owner = new User();
        owner.setTenantId(t.getId());
        owner.setEmail(email);
        owner.setFullName(req.ownerName().trim());
        owner.setPhone(blankToNull(req.phone()));
        owner.setPasswordHash(encoder.encode(req.password()));
        owner.setRoles(Set.of(roles.findByTenantIdAndSystemCode(t.getId(), SystemRole.DUENO).orElseThrow()));
        owner.setLastLoginAt(Instant.now());
        owner = users.save(owner);

        audit.logInCurrentTx(t.getId(), owner.getId(), owner.getFullName(), "TIENDA_CREADA",
                "Creó la tienda \"" + t.getName() + "\"");
        return new LoginResponse(jwt.generate(owner.getId(), t.getId(), owner.getTokenVersion()),
                jwt.expirationMinutes(), me(owner));
    }

    /** No es @Transactional a propósito: el contador de intentos fallidos debe guardarse aunque el login falle. */
    public LoginResponse login(LoginRequest req) {
        User user = users.findByEmail(req.email().trim()).orElse(null);
        if (user == null) {
            encoder.matches(req.password(), DUMMY_HASH);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos");
        }
        if (user.isLocked()) {
            long minutes = Math.max(1, Duration.between(Instant.now(), user.getLockedUntil()).toMinutes() + 1);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Cuenta bloqueada por varios intentos fallidos. Intenta de nuevo en " + minutes + " min.");
        }
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            boolean locked = user.getFailedAttempts() >= maxFailed;
            if (locked) {
                user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(lockMinutes)));
                user.setFailedAttempts(0);
            }
            users.save(user);
            audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), locked ? "CUENTA_BLOQUEADA" : "LOGIN_FALLIDO",
                    locked ? "Cuenta bloqueada " + lockMinutes + " min por intentos fallidos" : "Contraseña incorrecta", true);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos");
        }
        if (!user.isActive()) {
            audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), "LOGIN_FALLIDO",
                    "Intento de entrar con un usuario desactivado", true);
            throw new ApiException(HttpStatus.FORBIDDEN, "Este usuario está desactivado. Habla con el dueño de la tienda.");
        }
        if (user.getTenantId() != null) {
            Tenant t = tenants.findById(user.getTenantId()).orElseThrow();
            if (!"ACTIVE".equals(t.getStatus())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "La tienda no está activa.");
            }
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), "LOGIN", "Inició sesión", false);
        return new LoginResponse(jwt.generate(user.getId(), user.getTenantId(), user.getTokenVersion()),
                jwt.expirationMinutes(), me(user));
    }

    @Transactional(readOnly = true)
    public MeResponse currentMe() {
        return me(users.findById(CurrentUser.get().userId()).orElseThrow());
    }

    public void logout() {
        var u = CurrentUser.get();
        audit.log("LOGOUT", "USER", u.userId(), "Cerró sesión");
    }

    /** Invalida todos los tokens del usuario (en todos los aparatos). */
    @Transactional
    public void logoutEverywhere() {
        User user = users.findById(CurrentUser.get().userId()).orElseThrow();
        user.setTokenVersion(user.getTokenVersion() + 1);
        audit.sensitive("LOGOUT_TODOS", "USER", user.getId(), "Cerró sesión en todos los aparatos");
    }

    @Transactional
    public void changePassword(ChangePasswordRequest req) {
        User user = users.findById(CurrentUser.get().userId()).orElseThrow();
        if (!encoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.field("currentPassword", "no es correcta");
        }
        Passwords.validate("newPassword", req.newPassword());
        user.setPasswordHash(encoder.encode(req.newPassword()));
        audit.sensitive("CLAVE_CAMBIADA", "USER", user.getId(), "Cambió su contraseña");
    }

    /** Siempre responde igual exista o no el correo, para no revelar qué cuentas existen. */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        users.findByEmail(req.email().trim()).filter(User::isActive).ifPresent(user -> {
            resetTokens.invalidateAllFor(user.getId());
            String token = Passwords.randomToken();
            resetTokens.save(new PasswordResetToken(user.getId(), Passwords.sha256(token),
                    Instant.now().plus(Duration.ofMinutes(30))));
            String link = frontendUrl + "/restablecer?token=" + token;
            mail.send(user.getEmail(), "Restablece tu contraseña de TiendaPOSS",
                    "Hola " + user.getFullName() + ",\n\nPara crear una contraseña nueva entra a este enlace "
                            + "(vence en 30 minutos):\n" + link
                            + "\n\nSi no lo pediste, ignora este correo: tu contraseña no cambia.");
            audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), "CLAVE_RECUPERACION",
                    "Pidió recuperar su contraseña", true);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        PasswordResetToken t = resetTokens.findByTokenHash(Passwords.sha256(req.token()))
                .filter(PasswordResetToken::usable)
                .orElseThrow(() -> ApiException.badRequest("El enlace no es válido o ya venció. Pide uno nuevo."));
        Passwords.validate("password", req.password());
        User user = users.findById(t.getUserId()).orElseThrow();
        user.setPasswordHash(encoder.encode(req.password()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        t.markUsed();
        audit.logFor(user.getTenantId(), user.getId(), user.getFullName(), "CLAVE_RESTABLECIDA",
                "Restableció su contraseña con el enlace de recuperación", true);
    }

    public MeResponse me(User u) {
        Tenant t = u.getTenantId() == null ? null : tenants.findById(u.getTenantId()).orElse(null);
        var roleNames = new ArrayList<String>();
        u.getRoles().forEach(r -> roleNames.add(r.getName()));
        roleNames.sort(String::compareTo);
        return new MeResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.isOwner(), roleNames,
                new ArrayList<>(u.effectivePermissions()),
                t == null ? null : new TenantInfo(t.getId(), t.getName(), t.getLogo(), t.getCurrency(), t.getTimezone()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
