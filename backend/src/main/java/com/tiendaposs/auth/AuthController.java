package com.tiendaposs.auth;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;
    private final RateLimiter limiter;

    public AuthController(AuthService auth, RateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@Valid @RequestBody RegisterRequest req) {
        limiter.check("register", AuditService.clientIp());
        return auth.register(req);
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        limiter.check("login", AuditService.clientIp());
        return auth.login(req);
    }

    @GetMapping("/auth/me")
    public MeResponse me() {
        return auth.currentMe();
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        auth.logout();
    }

    @PostMapping("/auth/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll() {
        auth.logoutEverywhere();
    }

    @PostMapping("/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(req);
    }

    @PostMapping("/auth/forgot-password")
    public Map<String, String> forgot(@Valid @RequestBody ForgotPasswordRequest req) {
        limiter.check("forgot", AuditService.clientIp());
        auth.forgotPassword(req);
        return Map.of("message", "Si el correo está registrado, te enviamos un enlace para restablecer la contraseña.");
    }

    @PostMapping("/auth/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest req) {
        limiter.check("reset", AuditService.clientIp());
        auth.resetPassword(req);
    }
}
