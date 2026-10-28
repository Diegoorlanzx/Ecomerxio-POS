package com.tiendaposs.billing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.tenant.Plans;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suscripción de Ecomerxio POS pagada con el checkout de Wompi, sin tarjeta guardada ni cobro automático (como en Ecomerxio):
 * cada pago deja la tienda activa un mes o un año más; si aún tenía días pagados, se suman al final.
 *
 * <ol>
 *   <li>{@link #start}: arma una referencia propia con lo que se paga y devuelve los datos firmados del widget.</li>
 *   <li>{@link #confirm} (al cerrar la ventana), {@link #verifyOwn} («Verificar mi pago») y {@link #reconcileRecent} (cada 10 min)
 *       preguntan la transacción a Wompi y solo entonces aplican el pago. Son idempotentes: una transacción se aplica una vez.</li>
 * </ol>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** POSS-{tienda}-{PLAN}-{M|A}-{centavos}-{epoch}. El prefijo la distingue de las suscripciones de Ecomerxio (SUBP-). */
    static final Pattern REFERENCE = Pattern.compile("^POSS-(\\d+)-([A-Z]+)-([MA])-(\\d+)-(\\d+)$");
    private static final Duration RECONCILE_WINDOW = Duration.ofDays(3);

    public record Event(String type, String metadata, Instant createdAt) {
    }

    public record PlanOption(String code, String name, long priceMonthly, long priceAnnual) {
    }

    public record Status(String state, String plan, String planName, String billingPeriod, Instant trialEndsAt, Instant paidUntil,
                         boolean paymentsEnabled, List<PlanOption> plans, List<Event> history,
                         /** Vencida: desde cuándo queda en solo lectura (o desde cuándo lo está). Nulo si no está vencida. */
                         Instant readOnlyFrom, boolean readOnly) {
    }

    public record StartResult(WompiBillingGateway.WidgetParams widget, long amount) {
    }

    public record ConfirmResult(String status, String message, Status subscription) {
    }

    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final WompiBillingGateway wompi;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final boolean schedulerEnabled;

    public SubscriptionService(TenantRepository tenants, JdbcTemplate jdbc, WompiBillingGateway wompi, AuditService audit,
                               TransactionTemplate tx, @Value("${app.scheduler.enabled:true}") boolean schedulerEnabled) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.wompi = wompi;
        this.audit = audit;
        this.tx = tx;
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Días con aviso después del vencimiento antes de pasar a solo lectura (Términos: "después de avisar … y de dar un plazo
     * de cinco (5) días calendario"). En solo lectura se puede ver, descargar y pagar, pero no vender ni modificar.
     */
    public static final int GRACE_DAYS = 5;

    /** Desde cuándo la tienda vencida queda en solo lectura; nulo si no está vencida. */
    public static Instant readOnlyFrom(Tenant t, Instant now) {
        if (!"VENCIDA".equals(state(t, now))) return null;
        Instant expired = t.getPaidUntil() != null ? t.getPaidUntil() : t.getTrialEndsAt();
        return expired.plus(GRACE_DAYS, ChronoUnit.DAYS);
    }

    public static boolean readOnly(Tenant t, Instant now) {
        Instant from = readOnlyFrom(t, now);
        return from != null && !now.isBefore(from);
    }

    /** ACTIVA (pagada), PRUEBA, VENCIDA, o CORTESIA (tiendas anteriores a los planes, sin prueba ni pago). */
    public static String state(Tenant t, Instant now) {
        if (t.getPaidUntil() != null && t.getPaidUntil().isAfter(now)) return "ACTIVA";
        if (t.getPaidUntil() == null && t.getTrialEndsAt() == null) return "CORTESIA";
        if (t.getPaidUntil() == null && t.getTrialEndsAt().isAfter(now)) return "PRUEBA";
        return "VENCIDA";
    }

    public Status status(Long tenantId) {
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tienda no encontrada"));
        List<Event> history = jdbc.query("SELECT type, metadata, created_at FROM subscription_events WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT 30",
                (rs, i) -> new Event(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), tenantId);
        List<PlanOption> plans = Plans.ALL.stream().map(p -> new PlanOption(p.code(), p.name(), p.priceMonthly(), p.priceAnnual())).toList();
        Instant now = Instant.now();
        return new Status(state(t, now), t.getPlan(), Plans.of(t.getPlan()).name(), t.getBillingPeriod(), t.getTrialEndsAt(),
                t.getPaidUntil(), wompi.isEnabled(), plans, history, readOnlyFrom(t, now), readOnly(t, now));
    }

    public StartResult start(Long tenantId, String planCode, String period) {
        String code = planCode == null ? "" : planCode.trim().toUpperCase(Locale.ROOT);
        Plans.Plan plan = Plans.ALL.stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.field("plan", "no es un plan válido"));
        boolean annual = "ANUAL".equalsIgnoreCase(period);
        long amount = annual ? plan.priceAnnual() : plan.priceMonthly();
        long cents = amount * 100;
        String reference = "POSS-%d-%s-%s-%d-%d".formatted(tenantId, plan.code(), annual ? "A" : "M", cents, Instant.now().getEpochSecond());
        WompiBillingGateway.WidgetParams widget = wompi.widgetParams(reference, cents);
        record(tenantId, "CHECKOUT_INICIADO", reference);
        return new StartResult(widget, amount);
    }

    /** El dueño cerró la ventana de pago: se pregunta a Wompi y, si está aprobada, se aplica. */
    public ConfirmResult confirm(Long tenantId, String transactionId) {
        if (transactionId == null || transactionId.isBlank()) throw ApiException.badRequest("Falta el número de la transacción");
        WompiBillingGateway.TransactionInfo info = wompi.fetchTransaction(transactionId.trim());
        String result = tx.execute(s -> process(tenantId, info));
        String message = switch (result) {
            case "APLICADO", "YA_APLICADO" -> "¡Pago recibido! Tu suscripción quedó activa.";
            case "PENDIENTE" -> "Tu pago sigue en proceso con el banco. Usa «Verificar mi pago» en unos minutos.";
            default -> info.statusMessage() != null ? info.statusMessage() : "El pago no fue aprobado.";
        };
        return new ConfirmResult(result, message, status(tenantId));
    }

    /** «¿Pagaste y no se activó?»: busca en Wompi los checkouts recientes de la tienda y aplica los aprobados. */
    public ConfirmResult verifyOwn(Long tenantId) {
        int[] c = reconcileTenant(tenantId);
        String message = c[0] > 0 ? "Encontramos tu pago aprobado y tu suscripción quedó activa."
                : c[1] > 0 ? "Tu pago sigue en proceso con el banco. Vuelve a verificar en unos minutos."
                : c[2] == 0 ? "No hay pagos recientes por verificar."
                : "No encontramos un pago aprobado. Si el dinero salió de tu cuenta, escríbenos con el comprobante.";
        return new ConfirmResult(c[0] > 0 ? "APLICADO" : c[1] > 0 ? "PENDIENTE" : "SIN_PAGO", message, status(tenantId));
    }

    /** Revisión periódica de los checkouts de los últimos 3 días: no depende de que el dueño vuelva a la pantalla. */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void scheduledReconcile() {
        if (schedulerEnabled && wompi.isEnabled()) reconcileRecent();
    }

    public int reconcileRecent() {
        int applied = 0;
        for (Long id : jdbc.queryForList("SELECT DISTINCT tenant_id FROM subscription_events WHERE type = 'CHECKOUT_INICIADO' AND created_at > ?",
                Long.class, java.sql.Timestamp.from(Instant.now().minus(RECONCILE_WINDOW)))) {
            try {
                applied += reconcileTenant(id)[0];
            } catch (Exception e) {
                log.warn("Conciliación de la suscripción de la tienda {} falló: {}", id, e.getMessage());
            }
        }
        return applied;
    }

    /** {aplicados, en proceso, referencias revisadas}. Las consultas a Wompi van fuera de la transacción. */
    private int[] reconcileTenant(Long tenantId) {
        Set<String> refs = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT metadata FROM subscription_events WHERE tenant_id = ? AND type = 'CHECKOUT_INICIADO' AND created_at > ? ORDER BY created_at DESC",
                String.class, tenantId, java.sql.Timestamp.from(Instant.now().minus(RECONCILE_WINDOW))));
        int applied = 0, pending = 0;
        for (String ref : refs) {
            for (WompiBillingGateway.TransactionInfo info : wompi.fetchByReference(ref)) {
                if ("APPROVED".equals(info.status())) {
                    if ("APLICADO".equals(tx.execute(s -> process(tenantId, info)))) applied++;
                } else if ("PENDING".equals(info.status())) {
                    pending++;
                }
            }
        }
        return new int[]{applied, pending, refs.size()};
    }

    /** Aplica una transacción ya consultada a Wompi. Devuelve APLICADO, YA_APLICADO, PENDIENTE o RECHAZADO. */
    String process(Long tenantId, WompiBillingGateway.TransactionInfo info) {
        Matcher m = REFERENCE.matcher(info.reference() == null ? "" : info.reference());
        if (!m.matches() || Long.parseLong(m.group(1)) != tenantId) {
            throw ApiException.badRequest("Esa transacción no corresponde a la suscripción de esta tienda");
        }
        // Bloquea la tienda mientras se aplica, para que dos verificaciones simultáneas no sumen el periodo dos veces.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", Long.class, tenantId);
        Integer issued = jdbc.queryForObject("SELECT count(*) FROM subscription_events WHERE tenant_id = ? AND type = 'CHECKOUT_INICIADO' AND metadata = ?",
                Integer.class, tenantId, info.reference());
        if (issued == null || issued == 0) throw ApiException.badRequest("Referencia de pago desconocida");
        if (Long.parseLong(m.group(4)) != info.amountInCents()) throw ApiException.badRequest("El monto pagado no coincide con el del pago iniciado");

        String prefix = "tx " + info.id() + " ";
        if (seen(tenantId, "PAGO_APLICADO", prefix)) return "YA_APLICADO";
        switch (info.status() == null ? "" : info.status()) {
            case "APPROVED" -> {
                apply(tenantId, m, info, prefix);
                return "APLICADO";
            }
            case "PENDING" -> {
                return "PENDIENTE";
            }
            default -> {
                if (!seen(tenantId, "PAGO_RECHAZADO", prefix)) {
                    record(tenantId, "PAGO_RECHAZADO", prefix + "· " + info.status() + (info.statusMessage() == null ? "" : ": " + info.statusMessage()));
                }
                return "RECHAZADO";
            }
        }
    }

    private void apply(Long tenantId, Matcher ref, WompiBillingGateway.TransactionInfo info, String prefix) {
        long amount = info.amountInCents() / 100;
        Tenant t = extend(tenantId, ref.group(2), "A".equals(ref.group(3)), prefix + "· $" + amount + " COP");
        audit.logInCurrentTx(tenantId, null, "Wompi", "SUSCRIPCION_PAGADA",
                "Pagó el plan " + Plans.of(t.getPlan()).name() + " " + t.getBillingPeriod().toLowerCase(Locale.ROOT) + " ($" + amount + ", transacción " + info.id() + ")");
        log.info("Suscripción del POS pagada: tienda {} plan {} {} (tx {})", tenantId, t.getPlan(), t.getBillingPeriod(), info.id());
    }

    /** Pago recibido por fuera de Wompi (transferencia, efectivo), registrado desde el Super Admin de Ecomerxio. */
    public Tenant applyManual(Long tenantId, String planCode, boolean annual, String note) {
        String code = planCode == null ? "" : planCode.trim().toUpperCase(Locale.ROOT);
        if (Plans.ALL.stream().noneMatch(p -> p.code().equals(code))) throw ApiException.field("plan", "no es un plan válido");
        return tx.execute(s -> {
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", Long.class, tenantId);
            Tenant t = extend(tenantId, code, annual, "manual · " + (note == null || note.isBlank() ? "sin nota" : note.trim()));
            audit.logInCurrentTx(tenantId, null, "Soporte Ecomerxio", "SUSCRIPCION_PAGO_MANUAL",
                    "Soporte registró un pago del plan " + Plans.of(code).name() + (annual ? " anual" : " mensual"));
            return t;
        });
    }

    /** Suma un mes o un año (desde hoy o desde el fin del periodo ya pagado), deja el plan y anota el pago. */
    private Tenant extend(Long tenantId, String planCode, boolean annual, String detail) {
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tienda no encontrada"));
        Instant now = Instant.now();
        Instant from = t.getPaidUntil() != null && t.getPaidUntil().isAfter(now) ? t.getPaidUntil() : now;
        String previous = t.getPlan();
        t.setPlan(planCode);
        t.setBillingPeriod(annual ? "ANUAL" : "MENSUAL");
        t.setPaidUntil(from.plus(annual ? 365 : 30, ChronoUnit.DAYS));
        t.setTrialEndsAt(null);
        tenants.save(t);
        String metadata = detail + " · " + previous + " → " + planCode + " " + t.getBillingPeriod().toLowerCase(Locale.ROOT);
        record(tenantId, "PAGO_APLICADO", metadata.length() > 500 ? metadata.substring(0, 500) : metadata);
        return t;
    }

    private boolean seen(Long tenantId, String type, String prefix) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM subscription_events WHERE tenant_id = ? AND type = ? AND metadata LIKE ?",
                Integer.class, tenantId, type, prefix.replace("%", "") + "%");
        return n != null && n > 0;
    }

    public void record(Long tenantId, String type, String metadata) {
        jdbc.update("INSERT INTO subscription_events (tenant_id, type, metadata) VALUES (?, ?, ?)", tenantId, type, metadata);
    }
}
