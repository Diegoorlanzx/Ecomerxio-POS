package com.tiendaposs.platform;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.LoginHandoffService;
import com.tiendaposs.auth.RateLimiter;
import com.tiendaposs.billing.SubscriptionService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.tenant.Plans;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantRepository;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * API de la plataforma para el Super Admin de Ecomerxio: ver y gestionar las tiendas del POS (plan, prueba, pagos manuales,
 * suspensión y "entrar como dueño" para soporte). No usa sesión de usuario: la llama SOLO el backend de Ecomerxio con la
 * cabecera X-Platform-Key = PLATFORM_ADMIN_KEY (la misma variable en los dos servicios). Sin la variable, todo responde 404.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformAdminController {

    private final String key;
    private final String frontendUrl;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final LoginHandoffService handoff;
    private final AuditService audit;
    private final RateLimiter limiter;

    public PlatformAdminController(@Value("${app.platform.admin-key:}") String key, @Value("${app.frontend-url}") String frontendUrl,
                                   TenantRepository tenants, UserRepository users, JdbcTemplate jdbc, SubscriptionService subscriptions,
                                   LoginHandoffService handoff, AuditService audit, RateLimiter limiter) {
        this.key = key == null ? "" : key.trim();
        this.frontendUrl = frontendUrl;
        this.tenants = tenants;
        this.users = users;
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.handoff = handoff;
        this.audit = audit;
        this.limiter = limiter;
    }

    public record StoreSummary(Long id, String name, String city, String phone, String ownerName, String ownerEmail, String plan,
                               String planName, String subscriptionState, String billingPeriod, Instant trialEndsAt, Instant paidUntil,
                               String status, Instant createdAt, long users, long sales30, BigDecimal salesTotal30, Instant lastSaleAt) {
    }

    public record StoreUser(Long id, String fullName, String email, List<String> roles, boolean active, Instant lastLoginAt) {
    }

    public record StoreDetail(StoreSummary store, String legalName, String taxId, String email, long registers, long products,
                              List<StoreUser> users, List<SubscriptionService.Event> history) {
    }

    public record PlanRequest(String plan) {
    }

    public record TrialRequest(Integer days) {
    }

    public record PaymentRequest(String plan, String period, String note) {
    }

    public record StatusRequest(String status, String reason) {
    }

    public record ImpersonationResponse(String url, long expiresInSeconds) {
    }

    @GetMapping("/stores")
    public List<StoreSummary> list(@RequestHeader(value = "X-Platform-Key", required = false) String k) {
        check(k);
        return tenants.findAll().stream().sorted(Comparator.comparing(Tenant::getId).reversed()).map(this::summary).toList();
    }

    @GetMapping("/stores/{id}")
    public StoreDetail detail(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id) {
        check(k);
        Tenant t = tenant(id);
        List<StoreUser> people = users.findByTenantIdOrderByFullNameAsc(id).stream()
                .map(u -> new StoreUser(u.getId(), u.getFullName(), u.getEmail(), u.getRoles().stream().map(r -> r.getName()).sorted().toList(),
                        u.isActive(), u.getLastLoginAt()))
                .toList();
        return new StoreDetail(summary(t), t.getLegalName(), t.getTaxId(), t.getEmail(),
                count("SELECT count(*) FROM registers WHERE tenant_id = ?", id),
                count("SELECT count(*) FROM products WHERE tenant_id = ?", id),
                people, subscriptions.status(id).history());
    }

    @PatchMapping("/stores/{id}/plan")
    @Transactional
    public StoreSummary plan(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id, @RequestBody PlanRequest req) {
        check(k);
        Tenant t = tenant(id);
        String code = req.plan() == null ? "" : req.plan().trim().toUpperCase(Locale.ROOT);
        if (Plans.ALL.stream().noneMatch(p -> p.code().equals(code))) throw ApiException.field("plan", "no es un plan válido");
        String previous = t.getPlan();
        t.setPlan(code);
        tenants.save(t);
        subscriptions.record(id, "PLAN_CAMBIADO", "soporte · " + previous + " → " + code + " (sin cobro)");
        audit.logInCurrentTx(id, null, "Soporte Ecomerxio", "SUSCRIPCION_PLAN", "Soporte cambió el plan a " + Plans.of(code).name());
        return summary(t);
    }

    @PatchMapping("/stores/{id}/trial")
    @Transactional
    public StoreSummary trial(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id, @RequestBody TrialRequest req) {
        check(k);
        int days = req.days() == null ? 0 : req.days();
        if (days < 1 || days > 90) throw ApiException.field("days", "entre 1 y 90 días");
        Tenant t = tenant(id);
        Instant now = Instant.now();
        Instant from = t.getTrialEndsAt() != null && t.getTrialEndsAt().isAfter(now) ? t.getTrialEndsAt() : now;
        t.setTrialEndsAt(from.plus(days, ChronoUnit.DAYS));
        tenants.save(t);
        subscriptions.record(id, "PRUEBA_EXTENDIDA", "soporte · +" + days + " días, hasta " + t.getTrialEndsAt().truncatedTo(ChronoUnit.DAYS));
        audit.logInCurrentTx(id, null, "Soporte Ecomerxio", "SUSCRIPCION_PRUEBA", "Soporte extendió la prueba " + days + " días");
        return summary(t);
    }

    @PostMapping("/stores/{id}/payments")
    public StoreSummary payment(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id, @RequestBody PaymentRequest req) {
        check(k);
        tenant(id);
        return summary(subscriptions.applyManual(id, req.plan(), "ANUAL".equalsIgnoreCase(req.period()), req.note()));
    }

    @PatchMapping("/stores/{id}/status")
    @Transactional
    public StoreSummary status(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id, @RequestBody StatusRequest req) {
        check(k);
        String status = req.status() == null ? "" : req.status().trim().toUpperCase(Locale.ROOT);
        if (!status.equals("ACTIVE") && !status.equals("SUSPENDED")) throw ApiException.field("status", "ACTIVE o SUSPENDED");
        Tenant t = tenant(id);
        t.setStatus(status);
        tenants.save(t);
        String reason = req.reason() == null || req.reason().isBlank() ? "" : ": " + req.reason().trim();
        subscriptions.record(id, status.equals("ACTIVE") ? "TIENDA_REACTIVADA" : "TIENDA_SUSPENDIDA", ("soporte" + reason).substring(0, Math.min(500, ("soporte" + reason).length())));
        audit.logInCurrentTx(id, null, "Soporte Ecomerxio", status.equals("ACTIVE") ? "TIENDA_REACTIVADA" : "TIENDA_SUSPENDIDA",
                (status.equals("ACTIVE") ? "Soporte reactivó la tienda" : "Soporte suspendió la tienda") + reason);
        return summary(t);
    }

    /** Soporte: código de un solo uso (60 s) para abrir la tienda como su dueño. Queda en la actividad de la tienda. */
    @PostMapping("/stores/{id}/impersonate")
    @Transactional
    public ImpersonationResponse impersonate(@RequestHeader(value = "X-Platform-Key", required = false) String k, @PathVariable Long id) {
        check(k);
        Tenant t = tenant(id);
        if (!"ACTIVE".equals(t.getStatus())) throw ApiException.badRequest("La tienda está suspendida: reactívala para entrar.");
        User owner = owner(id).orElseThrow(() -> ApiException.badRequest("La tienda no tiene un dueño activo."));
        LoginHandoffService.HandoffCode code = handoff.createFor(owner.getId());
        audit.logInCurrentTx(id, owner.getId(), "Soporte Ecomerxio", "SOPORTE_ENTRO", "Soporte de Ecomerxio entró como " + owner.getFullName());
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return new ImpersonationResponse(base + "/sso?code=" + code.code(), code.expiresInSeconds());
    }

    private void check(String provided) {
        if (key.isEmpty()) throw ApiException.notFound("No encontrado");
        boolean ok = provided != null && MessageDigest.isEqual(provided.trim().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            limiter.check("platform", AuditService.clientIp());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Llave de plataforma inválida");
        }
    }

    private Tenant tenant(Long id) {
        return tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tienda no encontrada"));
    }

    private Optional<User> owner(Long tenantId) {
        return users.findByTenantIdOrderByFullNameAsc(tenantId).stream().filter(u -> u.isActive() && u.isOwner()).findFirst();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private StoreSummary summary(Tenant t) {
        Optional<User> owner = owner(t.getId());
        Timestamp since = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        Map<String, Object> sales = jdbc.queryForMap(
                "SELECT count(*) AS n, coalesce(sum(total), 0) AS total, max(completed_at) AS last FROM sales WHERE tenant_id = ? AND status = 'PAGADA' AND completed_at >= ?",
                t.getId(), since);
        Timestamp lastAny = jdbc.queryForObject("SELECT max(completed_at) FROM sales WHERE tenant_id = ? AND status = 'PAGADA'", Timestamp.class, t.getId());
        return new StoreSummary(t.getId(), t.getName(), t.getCity(), t.getPhone(), owner.map(User::getFullName).orElse(null),
                owner.map(User::getEmail).orElse(null), t.getPlan(), Plans.of(t.getPlan()).name(), SubscriptionService.state(t, Instant.now()),
                t.getBillingPeriod(), t.getTrialEndsAt(), t.getPaidUntil(), t.getStatus(), t.getCreatedAt(),
                count("SELECT count(*) FROM users WHERE tenant_id = ?", t.getId()),
                ((Number) sales.get("n")).longValue(), (BigDecimal) sales.get("total"), lastAny == null ? null : lastAny.toInstant());
    }
}
