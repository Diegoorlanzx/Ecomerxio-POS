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
    private final LoginHandoffService handoff;

    public AuthController(AuthService auth, RateLimiter limiter, LoginHandoffService handoff) {
        this.auth = auth;
        this.limiter = limiter;
        this.handoff = handoff;
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

    /** Código de un solo uso (60 s) para abrir esta sesión en pos.ecomerxio.com; lo pide el login de ecomerxio.com. */
    @PostMapping("/auth/handoff")
    public LoginHandoffService.HandoffCode createHandoff() {
        return handoff.create();
    }

    public record RedeemRequest(@jakarta.validation.constraints.NotBlank String code) {
    }

    @PostMapping("/auth/handoff/redeem")
    public LoginResponse redeemHandoff(@Valid @RequestBody RedeemRequest req) {
        limiter.check("handoff", AuditService.clientIp());
        return handoff.redeem(req.code());
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

    @PostMapping("/auth/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPin(@Valid @RequestBody SetPinRequest req) {
        auth.setPin(req);
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
