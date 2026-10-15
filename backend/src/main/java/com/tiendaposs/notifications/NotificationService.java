package com.tiendaposs.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.MailService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.finance.FinanceService;
import com.tiendaposs.notifications.NotificationEntities.*;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.purchasing.InsightsService;
import com.tiendaposs.purchasing.PurchaseService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Notificaciones: revisa el negocio cada pocos minutos (y cuando alguien abre la campana), crea
 * avisos sin repetirlos (clave de deduplicación) y los envía por los canales que la tienda active.
 * Dentro del sistema siempre se ven; correo, webhook y WhatsApp son opcionales por tipo de aviso.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> ALL_PERMISSIONS = Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final NotificationRepository notifications;
    private final DeliveryRepository deliveries;
    private final RuleRepository rules;
    private final ChannelsRepository channels;
    private final InsightsService insights;
    private final PurchaseService purchases;
    private final PaymentService payments;
    private final FinanceService finance;
    private final CreditService credit;
    private final AnalyticsService analytics;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final MailService mail;
    private final AuditService audit;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean schedulerEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NotificationService(NotificationRepository notifications, DeliveryRepository deliveries, RuleRepository rules,
                               ChannelsRepository channels, InsightsService insights, PurchaseService purchases, PaymentService payments,
                               FinanceService finance, CreditService credit, AnalyticsService analytics, SettingsService settings,
                               TenantRepository tenants, MailService mail, AuditService audit, EntityManager em, TransactionTemplate tx,
                               ObjectMapper json, @Value("${app.scheduler.enabled:true}") boolean schedulerEnabled) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.rules = rules;
        this.channels = channels;
        this.insights = insights;
        this.purchases = purchases;
        this.payments = payments;
        this.finance = finance;
        this.credit = credit;
        this.analytics = analytics;
        this.settings = settings;
        this.tenants = tenants;
        this.mail = mail;
        this.audit = audit;
        this.em = em;
        this.tx = tx;
        this.json = json;
        this.schedulerEnabled = schedulerEnabled;
    }

    // =====================================================================
    // Bandeja del usuario
    // =====================================================================

    public record NotificationDto(Long id, String type, String typeLabel, String severity, String title, String body, String link,
                                  Instant createdAt, boolean read) {
    }

    public record Inbox(long unread, List<NotificationDto> items) {
    }

    @Transactional(readOnly = true)
    public Inbox inbox(boolean onlyUnread, int limit) {
        AuthUser me = CurrentUser.get();
        Long t = TenantContext.require();
        Set<Long> read = readIds(t, me.userId());
        List<Notification> visible = notifications.recent(t, Instant.now().minus(60, ChronoUnit.DAYS)).stream()
                .filter(n -> n.permission == null || me.permissions().contains(n.permission)).toList();
        long unread = visible.stream().filter(n -> !read.contains(n.id)).count();
        List<NotificationDto> items = visible.stream().filter(n -> !onlyUnread || !read.contains(n.id)).limit(Math.max(1, Math.min(limit, 300)))
                .map(n -> new NotificationDto(n.id, n.type, label(n.type), n.severity, n.title, n.body, n.link, n.createdAt, read.contains(n.id))).toList();
        return new Inbox(unread, items);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> readIds(Long t, Long userId) {
        List<Number> ids = em.createNativeQuery("""
                        select r.notification_id from notification_reads r join notifications n on n.id = r.notification_id
                        where n.tenant_id = :t and r.user_id = :u and n.created_at >= now() - interval '61 days'""")
                .setParameter("t", t).setParameter("u", userId).getResultList();
        return ids.stream().map(Number::longValue).collect(Collectors.toSet());
    }

    @Transactional
    public void markRead(Long id) {
        Long t = TenantContext.require();
        notifications.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Notificación no encontrada"));
        em.createNativeQuery("insert into notification_reads (notification_id, user_id) values (:n, :u) on conflict do nothing")
                .setParameter("n", id).setParameter("u", CurrentUser.get().userId()).executeUpdate();
    }

    @Transactional
    public int markAllRead() {
        AuthUser me = CurrentUser.get();
        Long t = TenantContext.require();
        Set<Long> read = readIds(t, me.userId());
        int n = 0;
        for (Notification x : notifications.recent(t, Instant.now().minus(60, ChronoUnit.DAYS))) {
            if (read.contains(x.id) || (x.permission != null && !me.permissions().contains(x.permission))) continue;
            em.createNativeQuery("insert into notification_reads (notification_id, user_id) values (:n, :u) on conflict do nothing")
                    .setParameter("n", x.id).setParameter("u", me.userId()).executeUpdate();
            n++;
        }
        return n;
    }

    public record DeliveryDto(Long id, Long notificationId, String channel, String status, String detail, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<DeliveryDto> deliveries(Long notificationId) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = TenantContext.require();
        List<Delivery> list = notificationId == null ? deliveries.latest(t)
                : deliveries.findByNotificationIdOrderByIdAsc(notifications.findByIdAndTenantId(notificationId, t)
                .orElseThrow(() -> ApiException.notFound("Notificación no encontrada")).id);
        return list.stream().map(d -> new DeliveryDto(d.id, d.notificationId, d.channel, d.status, d.detail, d.createdAt)).toList();
    }

    // =====================================================================
    // Reglas (umbrales) y canales
    // =====================================================================

    public record RuleDto(String type, String label, String description, String permission, String severity, boolean enabled,
                          BigDecimal threshold, String thresholdLabel, boolean email, boolean webhook, boolean whatsapp) {
    }

    public record RuleRequest(@NotBlank String type, boolean enabled, @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal threshold,
                              boolean email, boolean webhook, boolean whatsapp) {
    }

    @Transactional(readOnly = true)
    public List<RuleDto> rules() {
        Long t = TenantContext.require();
        Map<String, Rule> saved = rules.findByTenantId(t).stream().collect(Collectors.toMap(r -> r.type, r -> r));
        List<RuleDto> out = new ArrayList<>();
        for (NotificationType nt : NotificationType.values()) {
            Rule r = saved.get(nt.name());
            out.add(new RuleDto(nt.name(), nt.label, nt.description, nt.permission, nt.severity, r == null || r.enabled,
                    nt.defaultThreshold == null ? null : r != null && r.threshold != null ? r.threshold : nt.defaultThreshold, nt.thresholdLabel,
                    r != null && r.email, r != null && r.webhook, r != null && r.whatsapp));
        }
        return out;
    }

    @Transactional
    public List<RuleDto> saveRules(List<RuleRequest> reqs) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = TenantContext.require();
        for (RuleRequest q : reqs) {
            NotificationType nt;
            try {
                nt = NotificationType.valueOf(q.type());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Tipo de aviso desconocido: " + q.type());
            }
            Rule r = rules.findById(new RuleKey(t, nt.name())).orElseGet(() -> {
                Rule x = new Rule();
                x.tenantId = t;
                x.type = nt.name();
                return x;
            });
            r.enabled = q.enabled();
            r.threshold = nt.defaultThreshold == null ? null : q.threshold() == null ? nt.defaultThreshold : q.threshold();
            r.email = q.email();
            r.webhook = q.webhook();
            r.whatsapp = q.whatsapp();
            rules.save(r);
        }
        audit.sensitive("CONFIG_ALERTAS", "TENANT", t, "Actualizó los umbrales y canales de las alertas");
        return rules();
    }

    public record ChannelsDto(String emailTo, String webhookUrl, boolean webhookSecretSet, String whatsappPhoneId, boolean whatsappTokenSet,
                              String whatsappTo, int dailyReportHour, Instant lastScanAt, boolean smtpConfigured) {
    }

    public record ChannelsRequest(@Size(max = 300) String emailTo, @Size(max = 300) String webhookUrl, @Size(max = 80) String webhookSecret,
                                  @Size(max = 40) String whatsappPhoneId, @Size(max = 400) String whatsappToken, @Size(max = 40) String whatsappTo,
                                  @Min(value = 0, message = "entre 0 y 23") @Max(value = 23, message = "entre 0 y 23") int dailyReportHour) {
    }

    @Transactional
    public ChannelsDto channels() {
        Long t = TenantContext.require();
        return toDto(channelsOf(t));
    }

    @Transactional
    public ChannelsDto saveChannels(ChannelsRequest r) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = TenantContext.require();
        Channels c = channelsOf(t);
        String emails = blank(r.emailTo());
        if (emails != null) {
            for (String e : emails.split("[,;\\s]+")) {
                if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.field("emailTo", "correo no válido: " + e);
            }
        }
        String url = blank(r.webhookUrl());
        if (url != null && !url.startsWith("https://")) throw ApiException.field("webhookUrl", "debe empezar por https://");
        c.emailTo = emails;
        c.webhookUrl = url;
        if (blank(r.webhookSecret()) != null) c.webhookSecret = r.webhookSecret().trim();
        c.whatsappPhoneId = blank(r.whatsappPhoneId());
        if (blank(r.whatsappToken()) != null) c.whatsappToken = r.whatsappToken().trim();
        String to = blank(r.whatsappTo());
        if (to != null) {
            to = to.replaceAll("\\D", "");
            if (to.length() == 10 && to.startsWith("3")) to = "57" + to;
            if (to.length() < 10) throw ApiException.field("whatsappTo", "número no válido");
        }
        c.whatsappTo = to;
        c.dailyReportHour = r.dailyReportHour();
        c.updatedAt = Instant.now();
        audit.sensitive("CONFIG_CANALES", "TENANT", t, "Actualizó los canales de notificación");
        return toDto(c);
    }

    private Channels channelsOf(Long t) {
        return channels.findById(t).orElseGet(() -> {
            channels.ensure(t);
            em.flush();
            return channels.findById(t).orElseThrow();
        });
    }

    private ChannelsDto toDto(Channels c) {
        return new ChannelsDto(c.emailTo, c.webhookUrl, c.webhookSecret != null, c.whatsappPhoneId, c.whatsappToken != null, c.whatsappTo,
                c.dailyReportHour, c.lastScanAt, mail.configured());
    }

    /** Envía un mensaje de prueba por un canal y devuelve el resultado (no crea notificación). */
    public Map<String, String> test(String channel) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = TenantContext.require();
        Channels c = tx.execute(s -> channelsOf(t));
        Notification n = new Notification();
        n.tenantId = t;
        n.type = "PRUEBA";
        n.severity = "INFO";
        n.title = "Mensaje de prueba de TiendaPOSS";
        n.body = "Si recibes esto, el canal quedó bien configurado.";
        String[] result = send(channel, c, n, storeName(t));
        return Map.of("channel", channel, "status", result[0], "detail", result[1] == null ? "" : result[1]);
    }

    // =====================================================================
    // Revisión del negocio
    // =====================================================================

    /** Lo usa la campana: si la última revisión fue hace más de 2 minutos, revisa de nuevo. */
    public void scanIfStale() {
        Long t = TenantContext.require();
        Instant last = tx.execute(s -> channelsOf(t).lastScanAt);
        if (last == null || last.isBefore(Instant.now().minus(2, ChronoUnit.MINUTES))) scanNow(t);
    }

    /** Revisa una tienda ya, con permisos de sistema; devuelve cuántos avisos nuevos creó. */
    public int scanNow(Long t) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        Long prevTenant = TenantContext.get();
        try {
            TenantContext.set(t);
            AuthUser system = new AuthUser(0L, t, "Sistema", null, true, ALL_PERMISSIONS);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(system, null, system.authorities()));
            List<Long> created = tx.execute(s -> scan(t));
            deliver(t, created == null ? List.of() : created);
            return created == null ? 0 : created.size();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
            if (prevTenant == null) TenantContext.clear();
            else TenantContext.set(prevTenant);
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void scheduledScan() {
        if (!schedulerEnabled) return;
        for (Tenant tenant : tenants.findAll()) {
            try {
                scanNow(tenant.getId());
            } catch (Exception e) {
                log.warn("No se pudo revisar la tienda {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    private record Ctx(Long t, ZoneId z, LocalDate today, Map<String, Rule> rules, List<Long> created) {
        boolean on(NotificationType nt) {
            Rule r = rules.get(nt.name());
            return r == null || r.enabled;
        }

        BigDecimal threshold(NotificationType nt) {
            Rule r = rules.get(nt.name());
            return r != null && r.threshold != null ? r.threshold : nt.defaultThreshold;
        }
    }

    @SuppressWarnings("unchecked")
    List<Long> scan(Long t) {
        Channels ch = channelsOf(t);
        ZoneId z = analytics.zone(t);
        LocalDate today = LocalDate.now(z);
        Ctx c = new Ctx(t, z, today, rules.findByTenantId(t).stream().collect(Collectors.toMap(r -> r.type, r -> r)), new ArrayList<>());
        StoreSettings st = settings.forTenant(t);

        // ---- Inventario ----
        if (c.on(NotificationType.STOCK_CRITICO) || c.on(NotificationType.PROXIMO_AGOTAMIENTO) || c.on(NotificationType.SIN_ROTACION)
                || c.on(NotificationType.EXCESO_INVENTARIO)) {
            List<InsightsService.ProductInsight> ps = insights.insights().products();
            BigDecimal days = c.threshold(NotificationType.PROXIMO_AGOTAMIENTO);
            for (InsightsService.ProductInsight p : ps) {
                if (c.on(NotificationType.STOCK_CRITICO) && p.flags().contains("AGOTADO")) {
                    create(c, NotificationType.STOCK_CRITICO, "CRITICA", p.name() + " está agotado",
                            p.dailyVelocity().signum() > 0 ? "Se venden " + num(p.dailyVelocity()) + " al día: estás perdiendo ventas." : "Sin existencias.",
                            "/inventario/inteligente", "STOCK_CRITICO:" + p.productId() + ":" + today);
                } else if (c.on(NotificationType.STOCK_CRITICO) && p.flags().contains("BAJO_MINIMO")) {
                    create(c, NotificationType.STOCK_CRITICO, null, p.name() + " llegó al mínimo",
                            "Quedan " + num(p.stock()) + " (mínimo " + num(p.stockMin()) + ").", "/inventario/inteligente",
                            "STOCK_CRITICO:" + p.productId() + ":" + today);
                }
                if (c.on(NotificationType.PROXIMO_AGOTAMIENTO) && p.stock().signum() > 0 && p.coverageDays() != null
                        && p.coverageDays().compareTo(days) <= 0) {
                    create(c, NotificationType.PROXIMO_AGOTAMIENTO, null, p.name() + " se acaba en " + num(p.coverageDays()) + " días",
                            "Quedan " + num(p.stock()) + " y se venden " + num(p.dailyVelocity()) + " al día.", "/compras",
                            "PROXIMO_AGOTAMIENTO:" + p.productId() + ":" + today);
                }
            }
            String week = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            List<InsightsService.ProductInsight> still = ps.stream().filter(p -> p.flags().contains("SIN_ROTACION")).toList();
            if (c.on(NotificationType.SIN_ROTACION) && !still.isEmpty()) {
                create(c, NotificationType.SIN_ROTACION, null, still.size() + (still.size() == 1 ? " producto sin rotación" : " productos sin rotación"),
                        "Sin ventas en " + st.noRotationDays + " días: " + names(still.stream().map(InsightsService.ProductInsight::name).toList(), 8)
                                + ". Considera una promoción o dejar de pedirlos.", "/inventario/inteligente", "SIN_ROTACION:" + week);
            }
            List<InsightsService.ProductInsight> excess = ps.stream().filter(p -> p.flags().contains("EXCESO")).toList();
            if (c.on(NotificationType.EXCESO_INVENTARIO) && !excess.isEmpty()) {
                create(c, NotificationType.EXCESO_INVENTARIO, null, excess.size() + (excess.size() == 1 ? " producto con exceso" : " productos con exceso de inventario"),
                        "Alcanzan para más de " + st.excessDays + " días: " + names(excess.stream().map(InsightsService.ProductInsight::name).toList(), 8) + ".",
                        "/inventario/inteligente", "EXCESO_INVENTARIO:" + week);
            }
        }

        // ---- Compras ----
        if (c.on(NotificationType.PEDIDO_RECOMENDADO)) {
            for (InsightsService.SupplierSuggestion s : insights.suggestions().suppliers()) {
                if (s.lines().isEmpty() || (s.critical() == 0 && (s.nextOrderDate() == null || s.nextOrderDate().isAfter(today)))) continue;
                create(c, NotificationType.PEDIDO_RECOMENDADO, s.critical() > 0 ? "ALTA" : null,
                        "Pedido sugerido a " + s.supplierName(), s.lines().size() + (s.lines().size() == 1 ? " producto" : " productos") + (s.critical() > 0 ? " (" + s.critical() + " críticos)" : "")
                                + (s.estimatedTotal() == null ? "" : " por " + Money.fmt(s.estimatedTotal())) + ".",
                        "/compras", "PEDIDO_RECOMENDADO:" + s.supplierId() + ":" + today);
            }
        }
        if (c.on(NotificationType.PEDIDO_POR_APROBAR)) {
            for (PurchaseService.OrderDto o : purchases.list("POR_APROBAR", null)) {
                create(c, NotificationType.PEDIDO_POR_APROBAR, null, "Pedido #" + o.number() + " a " + o.supplierName() + " espera aprobación",
                        (o.estimatedTotal() == null ? "" : "Total estimado " + Money.fmt(o.estimatedTotal()) + ". ") + "Lo creó " + o.createdBy() + ".",
                        "/compras/" + o.id(), "PEDIDO_POR_APROBAR:" + o.id());
            }
        }
        if (c.on(NotificationType.PEDIDO_RETRASADO)) {
            for (PurchaseService.OrderDto o : purchases.late()) {
                create(c, NotificationType.PEDIDO_RETRASADO, null, "Pedido #" + o.number() + " de " + o.supplierName() + " retrasado",
                        "Debía llegar el " + o.expectedDate() + ".", "/compras/" + o.id(), "PEDIDO_RETRASADO:" + o.id() + ":" + today);
            }
        }
        if (c.on(NotificationType.MERCANCIA_RECIBIDA)) {
            for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                            select g.id, g.number, s.name, g.total, g.received_by, g.order_id from goods_receipts g join suppliers s on s.id = g.supplier_id
                            where g.tenant_id = :t and g.received_at >= :since""")
                    .setParameter("t", t).setParameter("since", Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS))).getResultList()) {
                create(c, NotificationType.MERCANCIA_RECIBIDA, null, "Llegó mercancía de " + r[2],
                        "Recepción #" + r[1] + " por " + Money.fmt(dec(r[3])) + (r[4] == null ? "" : ", la recibió " + r[4]) + ".",
                        r[5] == null ? "/compras" : "/compras/" + r[5], "MERCANCIA_RECIBIDA:" + r[0]);
            }
        }

        // ---- Pagos ----
        if (c.on(NotificationType.PAGO_POR_VERIFICAR) || c.on(NotificationType.PAGO_INCONSISTENTE)) {
            long minutes = c.threshold(NotificationType.PAGO_POR_VERIFICAR).longValue();
            for (PaymentService.PaymentRow p : payments.list(PaymentService.PENDIENTE, null, today.minusDays(7), today)) {
                if (c.on(NotificationType.PAGO_POR_VERIFICAR) && p.minutesAgo() >= minutes) {
                    create(c, NotificationType.PAGO_POR_VERIFICAR, null, "Pago de " + Money.fmt(p.amount()) + " por " + p.method().toLowerCase() + " sin verificar",
                            "Venta #" + p.saleNumber() + (p.reference() == null ? "" : " · ref. " + p.reference()) + " · hace " + ago(p.minutesAgo()) + ".",
                            "/pagos", "PAGO_POR_VERIFICAR:" + p.id());
                }
                if (c.on(NotificationType.PAGO_INCONSISTENTE) && p.duplicateRef()) {
                    create(c, NotificationType.PAGO_INCONSISTENTE, null, "Referencia repetida en la venta #" + p.saleNumber(),
                            p.method() + " · ref. " + p.reference() + " · " + Money.fmt(p.amount()) + ". Verifica antes de confirmarlo.", "/pagos",
                            "PAGO_INCONSISTENTE:dup:" + p.id());
                }
            }
            if (c.on(NotificationType.PAGO_INCONSISTENTE)) {
                for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                                select e.id, e.payment_id, e.detail from payment_events e
                                where e.tenant_id = :t and e.type = 'IMPORTE_INCONSISTENTE' and e.created_at >= :since""")
                        .setParameter("t", t).setParameter("since", Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS))).getResultList()) {
                    create(c, NotificationType.PAGO_INCONSISTENTE, null, "Valor de pago inconsistente", (String) r[2], "/pagos", "PAGO_INCONSISTENTE:evt:" + r[0]);
                }
            }
        }

        // ---- Caja ----
        if (c.on(NotificationType.DIFERENCIA_CAJA)) {
            BigDecimal min = c.threshold(NotificationType.DIFERENCIA_CAJA);
            for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                            select s.id, s.number, s.user_name, s.difference, rg.name, s.close_notes from shifts s join registers rg on rg.id = s.register_id
                            where s.tenant_id = :t and s.status = 'CLOSED' and s.closed_at >= :since and abs(coalesce(s.difference,0)) >= :min""")
                    .setParameter("t", t).setParameter("since", Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS))).setParameter("min", min)
                    .getResultList()) {
                BigDecimal d = dec(r[3]);
                create(c, NotificationType.DIFERENCIA_CAJA, d.signum() < 0 ? "ALTA" : "MEDIA",
                        (d.signum() < 0 ? "Faltante de " : "Sobrante de ") + Money.fmt(d.abs()) + " en " + r[4],
                        "Turno #" + r[1] + " de " + r[2] + (r[5] == null ? "" : ". Nota: " + r[5]), "/caja", "DIFERENCIA_CAJA:" + r[0]);
            }
        }
        if (c.on(NotificationType.DEVOLUCIONES_INUSUALES)) {
            int max = c.threshold(NotificationType.DEVOLUCIONES_INUSUALES).intValue();
            Timestamp from = Timestamp.from(today.atStartOfDay(z).toInstant());
            Map<String, int[]> byUser = new TreeMap<>();
            Map<String, BigDecimal> amount = new HashMap<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                            select voided_by, total from sales where tenant_id = :t and status = 'ANULADA' and voided_at >= :f and voided_by is not null""")
                    .setParameter("t", t).setParameter("f", from).getResultList()) {
                byUser.computeIfAbsent((String) r[0], k -> new int[2])[0]++;
                amount.merge((String) r[0], dec(r[1]), BigDecimal::add);
            }
            for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                            select user_name, refund_amount from sale_returns where tenant_id = :t and created_at >= :f and user_name is not null""")
                    .setParameter("t", t).setParameter("f", from).getResultList()) {
                byUser.computeIfAbsent((String) r[0], k -> new int[2])[1]++;
                amount.merge((String) r[0], dec(r[1]), BigDecimal::add);
            }
            for (Map.Entry<String, int[]> e : byUser.entrySet()) {
                int total = e.getValue()[0] + e.getValue()[1];
                if (total < max) continue;
                create(c, NotificationType.DEVOLUCIONES_INUSUALES, null, e.getKey() + " hizo " + total + " anulaciones o devoluciones hoy",
                        e.getValue()[0] + " anulaciones y " + e.getValue()[1] + " devoluciones por " + Money.fmt(amount.get(e.getKey())) + ". Revisa el historial.",
                        "/ventas", "DEVOLUCIONES_INUSUALES:" + e.getKey().toLowerCase().replaceAll("\\s+", "_") + ":" + today + ":" + (total / Math.max(max, 1)));
            }
        }

        // ---- Finanzas ----
        if (c.on(NotificationType.PAGO_PROVEEDOR)) {
            long days = c.threshold(NotificationType.PAGO_PROVEEDOR).longValue();
            for (FinanceService.PayableDto p : finance.payables(null).invoices()) {
                if (p.dueDate() == null || p.daysToDue() > days) continue;
                boolean late = p.daysToDue() < 0;
                create(c, NotificationType.PAGO_PROVEEDOR, late ? "ALTA" : null,
                        late ? "Factura vencida de " + p.supplierName() : "Pago a " + p.supplierName() + (p.daysToDue() == 0 ? " vence hoy" : " vence en " + p.daysToDue() + " días"),
                        "Saldo " + Money.fmt(p.balance()) + (p.invoiceNumber() == null ? "" : " · factura " + p.invoiceNumber()) + " · vence " + p.dueDate() + ".",
                        "/finanzas/por-pagar", "PAGO_PROVEEDOR:" + p.receiptId() + (late ? ":vencida" : ":por_vencer"));
            }
        }
        if (c.on(NotificationType.FIADO_VENCIDO) && settings.forTenant(t).creditEnabled) {
            List<CreditService.CustomerCredit> overdue = credit.list(true).stream().filter(x -> x.overdue().signum() > 0).toList();
            if (!overdue.isEmpty()) {
                String week = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                BigDecimal total = overdue.stream().map(CreditService.CustomerCredit::overdue).reduce(BigDecimal.ZERO, BigDecimal::add);
                create(c, NotificationType.FIADO_VENCIDO, null, overdue.size() + (overdue.size() == 1 ? " cliente con fiado vencido" : " clientes con fiado vencido"),
                        Money.fmt(total) + " vencido: " + names(overdue.stream().map(x -> x.name() + " (" + Money.fmt(x.overdue()) + ")").toList(), 6) + ".",
                        "/fiados", "FIADO_VENCIDO:" + week);
            }
        }

        // ---- Reportes ----
        LocalDateTime now = LocalDateTime.now(z);
        if (c.on(NotificationType.REPORTE_DIARIO) && now.getHour() >= ch.dailyReportHour && hadSales(t, z, today, today)) {
            create(c, NotificationType.REPORTE_DIARIO, "INFO", "Reporte del día " + today, report(t, z, today, today, "de hoy"), "/analitica",
                    "REPORTE_DIARIO:" + today);
        }
        LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
        if (c.on(NotificationType.RESUMEN_SEMANAL) && hadSales(t, z, monday, monday.plusDays(6))) {
            create(c, NotificationType.RESUMEN_SEMANAL, "INFO", "Resumen de la semana del " + monday + " al " + monday.plusDays(6),
                    report(t, z, monday, monday.plusDays(6), "de la semana"), "/analitica", "RESUMEN_SEMANAL:" + monday);
        }
        YearMonth prev = YearMonth.from(today).minusMonths(1);
        if (c.on(NotificationType.RESUMEN_MENSUAL) && hadSales(t, z, prev.atDay(1), prev.atEndOfMonth())) {
            create(c, NotificationType.RESUMEN_MENSUAL, "INFO", "Resumen de " + monthName(prev), report(t, z, prev.atDay(1), prev.atEndOfMonth(), "del mes"),
                    "/analitica", "RESUMEN_MENSUAL:" + prev);
        }
        ch.lastScanAt = Instant.now();
        return c.created();
    }

    /** Texto del reporte: ventas, transacciones, ticket, utilidad, comparación, más vendido, medios y diferencias de caja. */
    @SuppressWarnings("unchecked")
    String report(Long t, ZoneId z, LocalDate from, LocalDate to, String what) {
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        AnalyticsService.Kpis k = analytics.kpis(t, z, from, to, true);
        AnalyticsService.Kpis p = analytics.kpis(t, z, from.minusDays(len), to.minusDays(len), false);
        StringBuilder sb = new StringBuilder();
        sb.append("Ventas ").append(what).append(": ").append(Money.fmt(k.sales())).append(" en ").append(k.transactions()).append(" ventas");
        if (p.sales().signum() > 0) {
            BigDecimal ch = k.sales().subtract(p.sales()).multiply(BigDecimal.valueOf(100)).divide(p.sales(), 1, RoundingMode.HALF_UP);
            sb.append(" (").append(ch.signum() >= 0 ? "+" : "").append(ch.toPlainString().replace('.', ',')).append("% frente al período anterior)");
        }
        sb.append(".\nTicket promedio: ").append(Money.fmt(k.avgTicket())).append(". Utilidad bruta estimada: ").append(Money.fmt(k.grossProfit()))
                .append(" (margen ").append(k.marginPct().toPlainString().replace('.', ',')).append("%).");
        List<Object[]> top = em.createNativeQuery("""
                        select l.product_name, sum((l.quantity - l.returned_qty) * l.factor) u from sale_lines l join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt
                        group by l.product_name order by u desc limit 3""")
                .setParameter("t", t).setParameter("f", Timestamp.from(from.atStartOfDay(z).toInstant()))
                .setParameter("tt", Timestamp.from(to.plusDays(1).atStartOfDay(z).toInstant())).getResultList();
        if (!top.isEmpty()) sb.append("\nMás vendidos: ").append(top.stream().map(r -> r[0] + " (" + num(dec(r[1])) + ")").collect(Collectors.joining(", "))).append('.');
        List<AnalyticsService.Group> methods = analytics.methods(t, z, from, to);
        if (!methods.isEmpty()) sb.append("\nMedios de pago: ").append(methods.stream().map(m -> m.key().toLowerCase() + " " + Money.fmt(m.sales())).collect(Collectors.joining(", "))).append('.');
        Object[] diff = (Object[]) em.createNativeQuery("""
                        select count(*), coalesce(sum(difference),0) from shifts
                        where tenant_id = :t and status = 'CLOSED' and closed_at >= :f and closed_at < :tt and coalesce(difference,0) <> 0""")
                .setParameter("t", t).setParameter("f", Timestamp.from(from.atStartOfDay(z).toInstant()))
                .setParameter("tt", Timestamp.from(to.plusDays(1).atStartOfDay(z).toInstant())).getSingleResult();
        if (((Number) diff[0]).longValue() > 0) sb.append("\nDiferencias de caja: ").append(diff[0]).append(" turnos, neto ").append(Money.fmt(dec(diff[1]))).append('.');
        if (k.voidedCount() > 0 || k.refunds().signum() > 0) {
            sb.append("\nAnulaciones: ").append(k.voidedCount()).append(" (").append(Money.fmt(k.voidedTotal())).append("). Devoluciones: ").append(Money.fmt(k.refunds())).append('.');
        }
        return sb.toString();
    }

    private boolean hadSales(Long t, ZoneId z, LocalDate from, LocalDate to) {
        return analytics.kpis(t, z, from, to, false).transactions() > 0;
    }

    private void create(Ctx c, NotificationType type, String severity, String title, String body, String link, String dedup) {
        if (notifications.existsByTenantIdAndDedupKey(c.t(), dedup)) return;
        Notification n = new Notification();
        n.tenantId = c.t();
        n.type = type.name();
        n.severity = severity == null ? type.severity : severity;
        n.title = title.length() > 200 ? title.substring(0, 197) + "…" : title;
        n.body = body;
        n.link = link;
        n.permission = type.permission;
        n.dedupKey = dedup.length() > 160 ? dedup.substring(0, 160) : dedup;
        c.created().add(notifications.save(n).id);
    }

    // =====================================================================
    // Canales externos
    // =====================================================================

    private void deliver(Long t, List<Long> ids) {
        if (ids.isEmpty()) return;
        Channels c = tx.execute(s -> channelsOf(t));
        Map<String, Rule> rs = tx.execute(s -> rules.findByTenantId(t).stream().collect(Collectors.toMap(r -> r.type, r -> r)));
        String store = storeName(t);
        for (Long id : ids) {
            Notification n = notifications.findById(id).orElse(null);
            Rule r = n == null ? null : rs.get(n.type);
            if (r == null) continue;
            if (r.email) record(n, "EMAIL", send("EMAIL", c, n, store));
            if (r.webhook) record(n, "WEBHOOK", send("WEBHOOK", c, n, store));
            if (r.whatsapp) record(n, "WHATSAPP", send("WHATSAPP", c, n, store));
        }
    }

    private void record(Notification n, String channel, String[] result) {
        Delivery d = new Delivery();
        d.notificationId = n.id;
        d.tenantId = n.tenantId;
        d.channel = channel;
        d.status = result[0];
        d.detail = result[1] == null ? null : result[1].length() > 500 ? result[1].substring(0, 500) : result[1];
        deliveries.save(d);
    }

    /** Devuelve {estado, detalle}: ENVIADO, FALLIDO o SIN_CONFIGURAR. */
    String[] send(String channel, Channels c, Notification n, String store) {
        String text = "[" + store + "] " + n.title + (n.body == null ? "" : "\n" + n.body);
        try {
            switch (channel) {
                case "EMAIL" -> {
                    if (c.emailTo == null) return new String[]{"SIN_CONFIGURAR", "Falta el correo de destino"};
                    if (!mail.configured()) return new String[]{"SIN_CONFIGURAR", "El servidor no tiene SMTP configurado (el correo quedó en el registro)"};
                    for (String to : c.emailTo.split("[,;\\s]+")) mail.send(to, "[" + store + "] " + n.title, n.body == null ? n.title : n.body);
                    return new String[]{"ENVIADO", c.emailTo};
                }
                case "WEBHOOK" -> {
                    if (c.webhookUrl == null) return new String[]{"SIN_CONFIGURAR", "Falta la URL del webhook"};
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("store", store);
                    payload.put("type", n.type);
                    payload.put("severity", n.severity);
                    payload.put("title", n.title);
                    payload.put("body", n.body);
                    payload.put("link", n.link);
                    payload.put("createdAt", n.createdAt.toString());
                    String body = json.writeValueAsString(payload);
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.webhookUrl)).timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                    if (c.webhookSecret != null) b.header("X-Signature", hmac(c.webhookSecret, body));
                    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    return res.statusCode() / 100 == 2 ? new String[]{"ENVIADO", "HTTP " + res.statusCode()} : new String[]{"FALLIDO", "HTTP " + res.statusCode()};
                }
                case "WHATSAPP" -> {
                    if (c.whatsappPhoneId == null || c.whatsappToken == null || c.whatsappTo == null) {
                        return new String[]{"SIN_CONFIGURAR", "Faltan los datos de WhatsApp Cloud API (ID del número, token y destino)"};
                    }
                    String body = json.writeValueAsString(Map.of("messaging_product", "whatsapp", "to", c.whatsappTo, "type", "text",
                            "text", Map.of("body", text.length() > 4000 ? text.substring(0, 4000) : text)));
                    HttpRequest req = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/v20.0/" + c.whatsappPhoneId + "/messages"))
                            .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("Authorization", "Bearer " + c.whatsappToken)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                    return res.statusCode() / 100 == 2 ? new String[]{"ENVIADO", "HTTP " + res.statusCode()}
                            : new String[]{"FALLIDO", "HTTP " + res.statusCode() + ": " + res.body()};
                }
                default -> {
                    return new String[]{"FALLIDO", "Canal desconocido"};
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[]{"FALLIDO", "Interrumpido"};
        } catch (Exception e) {
            return new String[]{"FALLIDO", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())};
        }
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    private String storeName(Long t) {
        return tenants.findById(t).map(Tenant::getName).orElse("TiendaPOSS");
    }

    private static String label(String type) {
        try {
            return NotificationType.valueOf(type).label;
        } catch (IllegalArgumentException e) {
            return type;
        }
    }

    private static String ago(long minutes) {
        if (minutes < 60) return minutes + " min";
        if (minutes < 60 * 24) return (minutes / 60) + (minutes / 60 == 1 ? " hora" : " horas");
        return (minutes / 1440) + (minutes / 1440 == 1 ? " día" : " días");
    }

    private static String names(List<String> names, int max) {
        return names.stream().limit(max).collect(Collectors.joining(", ")) + (names.size() > max ? " y " + (names.size() - max) + " más" : "");
    }

    private static String monthName(YearMonth ym) {
        String[] m = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
        return m[ym.getMonthValue() - 1] + " de " + ym.getYear();
    }

    private static String num(BigDecimal v) {
        return v == null ? "0" : v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
