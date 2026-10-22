package com.tiendaposs.payments;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.payments.PaymentRepos.*;
import com.tiendaposs.pos.Payment;
import com.tiendaposs.pos.PosRepos.PaymentRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pagos digitales: quedan PENDIENTES hasta verificarse (a mano, conciliando el extracto o por
 * webhook de una integración oficial). Un pantallazo del cliente no es prueba de pago.
 * Estados: PENDIENTE, CONFIRMADO, RECHAZADO, REEMBOLSADO (efectivo/datáfono nacen confirmados;
 * el fiado queda como CREDITO y se sigue en cuentas por cobrar).
 */
@Service
public class PaymentService {

    public static final Set<String> DIGITAL = Set.of("TRANSFERENCIA", "NEQUI", "DAVIPLATA", "BREB");
    public static final String PENDIENTE = "PENDIENTE", CONFIRMADO = "CONFIRMADO", RECHAZADO = "RECHAZADO",
            REEMBOLSADO = "REEMBOLSADO", CREDITO = "CREDITO";

    private final PaymentRepository payments;
    private final SaleRepository sales;
    private final PaymentEventRepository events;
    private final PaymentAccountRepository accounts;
    private final PaymentIntegrationRepository integrations;
    private final ReconciliationRepository reconciliations;
    private final ReconciliationRowRepository reconRows;
    private final ShiftService shifts;
    private final SettingsService settings;
    private final AuditService audit;
    private final EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.tiendaposs.credit.CreditService credit;

    public PaymentService(PaymentRepository payments, SaleRepository sales, PaymentEventRepository events,
                          PaymentAccountRepository accounts, PaymentIntegrationRepository integrations,
                          ReconciliationRepository reconciliations, ReconciliationRowRepository reconRows, ShiftService shifts,
                          SettingsService settings, AuditService audit, EntityManager em) {
        this.payments = payments;
        this.sales = sales;
        this.events = events;
        this.accounts = accounts;
        this.integrations = integrations;
        this.reconciliations = reconciliations;
        this.reconRows = reconRows;
        this.shifts = shifts;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    // ---------------- Al cobrar ----------------

    /** Estado inicial de un pago según su medio (se llama antes de guardarlo). */
    public String initialStatus(String method, StoreSettings st) {
        if ("FIADO".equals(method)) return CREDITO;
        if (DIGITAL.contains(method) && st.verifyDigitalPayments) return PENDIENTE;
        return CONFIRMADO;
    }

    /** Eventos y detección de referencias repetidas, una vez guardados los pagos de la venta. */
    public List<String> afterSale(Sale s, List<Payment> ps) {
        List<String> flags = new ArrayList<>();
        for (Payment p : ps) {
            event(p, "REGISTRADO", "Pago de " + Money.fmt(p.amount) + " por " + p.method.toLowerCase() + " en la venta #" + s.number
                    + (p.reference == null ? "" : " · ref. " + p.reference));
            if (p.reference != null && DIGITAL.contains(p.method)
                    && payments.referenceUsed(p.tenantId, p.reference, p.method, p.saleId)) {
                p.duplicateRef = true;
                event(p, "REFERENCIA_DUPLICADA", "La referencia " + p.reference + " ya se había usado en otro pago por " + p.method.toLowerCase());
                flags.add("REFERENCIA_DUPLICADA");
                audit.sensitive("PAGO_REF_DUPLICADA", "SALE", s.id, "Venta #" + s.number + ": la referencia " + p.reference + " ya estaba registrada");
            }
        }
        return flags;
    }

    // ---------------- Verificación manual ----------------

    public record PaymentRow(Long id, Long saleId, Long saleNumber, String method, BigDecimal amount, String reference,
                             String status, boolean duplicateRef, String userName, Instant createdAt, long minutesAgo,
                             String verifiedBy, Instant verifiedAt, String verificationNote, Instant reconciledAt,
                             String customerName, boolean creditPayment) {
    }

    public record PaymentEventDto(Long id, Long paymentId, String type, String detail, String userName, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<PaymentRow> list(String status, String method, LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        Instant f = from == null ? Instant.EPOCH : from.atStartOfDay(z).toInstant();
        Instant tt = to == null ? Instant.now().plusSeconds(3600) : to.plusDays(1).atStartOfDay(z).toInstant();
        List<Payment> list = em.createQuery("""
                        select p from Payment p where p.tenantId = :t and p.createdAt >= :f and p.createdAt < :tt
                          and (:status is null or p.status = :status) and (:method is null or p.method = :method)
                          and p.method not in ('FIADO','ANTICIPO') order by p.createdAt desc""", Payment.class)
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt)
                .setParameter("status", blank(status)).setParameter("method", blank(method))
                .setMaxResults(500).getResultList();
        return rows(list);
    }

    @Transactional
    public PaymentRow confirm(Long id, String note) {
        CurrentUser.require(Permission.PAGOS_VERIFICAR);
        Payment p = find(id);
        if (!PENDIENTE.equals(p.status)) throw ApiException.badRequest("Este pago no está pendiente de verificación");
        AuthUser me = CurrentUser.get();
        p.status = CONFIRMADO;
        p.verifiedBy = me.fullName();
        p.verifiedAt = Instant.now();
        p.verificationNote = blank(note);
        event(p, "CONFIRMADO", "Verificado manualmente por " + me.fullName() + (p.verificationNote == null ? "" : ": " + p.verificationNote));
        audit.sensitive("PAGO_CONFIRMADO", "PAYMENT", p.id, "Confirmó a mano un pago de " + Money.fmt(p.amount) + " por "
                + p.method.toLowerCase() + (p.reference == null ? "" : " (ref. " + p.reference + ")")
                + (p.saleId == null ? " de un abono de fiado" : " de la venta #" + saleNumber(p)));
        return rows(List.of(p)).get(0);
    }

    /** El dinero no llegó: el pago queda rechazado y la venta marcada con saldo por cobrar. */
    @Transactional
    public PaymentRow reject(Long id, String reason) {
        CurrentUser.require(Permission.PAGOS_VERIFICAR);
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "explica por qué se rechaza");
        Payment p = find(id);
        if (!PENDIENTE.equals(p.status)) throw ApiException.badRequest("Solo se rechazan pagos pendientes");
        AuthUser me = CurrentUser.get();
        p.status = RECHAZADO;
        p.verifiedBy = me.fullName();
        p.verifiedAt = Instant.now();
        p.verificationNote = reason.trim();
        if (p.creditEntryId != null) {
            // Abono de fiado que no llegó: se reversa y la deuda del cliente vuelve a subir.
            credit.reverseRejectedPayment(p, p.verificationNote);
            event(p, "RECHAZADO", "Rechazado por " + me.fullName() + ": " + p.verificationNote + ". El abono se reversó.");
            return rows(List.of(p)).get(0);
        }
        Sale s = sales.findById(p.saleId).orElseThrow();
        s.reviewFlags = addFlag(s.reviewFlags, "PAGO_RECHAZADO");
        s.reviewedAt = null;
        event(p, "RECHAZADO", "Rechazado por " + me.fullName() + ": " + p.verificationNote);
        audit.sensitive("PAGO_RECHAZADO", "PAYMENT", p.id, "Rechazó un pago de " + Money.fmt(p.amount) + " por " + p.method.toLowerCase()
                + " de la venta #" + s.number + ". Motivo: " + p.verificationNote);
        return rows(List.of(p)).get(0);
    }

    /** Cobra de nuevo un pago rechazado (por ejemplo, el cliente vuelve y paga en efectivo). */
    @Transactional
    public PaymentRow replace(Long id, String method, BigDecimal amount, String reference) {
        Payment old = find(id);
        if (!RECHAZADO.equals(old.status)) throw ApiException.badRequest("Solo se reemplazan pagos rechazados");
        if (old.creditEntryId != null) throw ApiException.badRequest("Era un abono de fiado: registra el abono de nuevo en Fiados");
        if (amount == null || amount.compareTo(old.amount) != 0) throw ApiException.field("amount", "debe ser " + Money.fmt(old.amount));
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!settings.current().paymentMethodList().contains(m) || "FIADO".equals(m)) throw ApiException.field("method", "medio no válido");
        var shift = shifts.openShiftOfCurrentUser().orElse(null);
        if ("EFECTIVO".equals(m) && shift == null) throw ApiException.conflict("Abre tu turno para recibir efectivo");
        AuthUser me = CurrentUser.get();
        Payment p = new Payment();
        p.tenantId = old.tenantId;
        p.saleId = old.saleId;
        p.shiftId = shift == null ? old.shiftId : shift.id;
        p.method = m;
        p.amount = amount;
        p.received = "EFECTIVO".equals(m) ? amount : null;
        p.reference = blank(reference);
        p.status = initialStatus(m, settings.current());
        p.userId = me.userId();
        p.userName = me.fullName();
        p = payments.save(p);
        event(old, "REEMPLAZADO", "Reemplazado por un pago de " + Money.fmt(amount) + " por " + m.toLowerCase());
        event(p, "REGISTRADO", "Reemplaza el pago rechazado #" + old.id);
        audit.sensitive("PAGO_REEMPLAZADO", "PAYMENT", p.id, "Cobró de nuevo " + Money.fmt(amount) + " por " + m.toLowerCase()
                + " (el pago anterior fue rechazado) en la venta #" + saleNumber(p));
        return rows(List.of(p)).get(0);
    }

    /** Cuando una venta se devuelve completa, sus pagos pasan a REEMBOLSADO. */
    public void markRefunded(Sale s) {
        for (Payment p : payments.findBySaleIdOrderByIdAsc(s.id)) {
            if (CONFIRMADO.equals(p.status) || PENDIENTE.equals(p.status)) {
                p.status = REEMBOLSADO;
                event(p, "REEMBOLSADO", "Venta #" + s.number + " devuelta por completo");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentEventDto> events(Long paymentId) {
        Long t = TenantContext.require();
        List<PaymentEvent> list = paymentId == null ? events.findTop200ByTenantIdOrderByCreatedAtDesc(t)
                : events.findByPaymentIdOrderByCreatedAtAsc(find(paymentId).id);
        return list.stream().map(e -> new PaymentEventDto(e.id, e.paymentId, e.type, e.detail, e.userName, e.createdAt)).toList();
    }

    public void event(Payment p, String type, String detail) {
        AuthUser u = CurrentUser.getOrNull();
        PaymentEvent e = new PaymentEvent();
        e.tenantId = p.tenantId;
        e.paymentId = p.id;
        e.type = type;
        e.detail = detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail;
        e.userName = u == null ? "Sistema" : u.fullName();
        events.save(e);
    }

    // ---------------- Cuentas de cobro (QR) ----------------

    public record AccountRequest(@NotBlank @Pattern(regexp = "TRANSFERENCIA|NEQUI|DAVIPLATA|BREB", message = "medio no válido") String method,
                                 @NotBlank @Size(max = 80) String label, @Size(max = 120) String holder,
                                 @Size(max = 80) String accountRef, String qrImage, Boolean active) {
    }

    public record AccountDto(Long id, String method, String label, String holder, String accountRef, String qrImage, boolean active) {
        static AccountDto of(PaymentAccount a) {
            return new AccountDto(a.id, a.method, a.label, a.holder, a.accountRef, a.qrImage, a.active);
        }
    }

    @Transactional(readOnly = true)
    public List<AccountDto> accounts() {
        return accounts.findByTenantIdOrderByIdAsc(TenantContext.require()).stream().map(AccountDto::of).toList();
    }

    @Transactional
    public AccountDto saveAccount(Long id, AccountRequest r) {
        Long t = TenantContext.require();
        PaymentAccount a = id == null ? new PaymentAccount() : accounts.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Cuenta no encontrada"));
        String qr = blank(r.qrImage());
        if (qr != null && (!qr.matches("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$") || qr.length() > 400_000)) {
            throw ApiException.field("qrImage", "sube una imagen PNG/JPG de máximo 300 KB");
        }
        a.tenantId = t;
        a.method = r.method();
        a.label = r.label().trim();
        a.holder = blank(r.holder());
        a.accountRef = blank(r.accountRef());
        a.qrImage = qr;
        if (r.active() != null) a.active = r.active();
        a = accounts.save(a);
        audit.sensitive("CUENTA_COBRO", "TENANT", t, (id == null ? "Agregó" : "Editó") + " la cuenta de cobro " + a.label);
        return AccountDto.of(a);
    }

    @Transactional
    public void deleteAccount(Long id) {
        PaymentAccount a = accounts.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Cuenta no encontrada"));
        accounts.delete(a);
        audit.sensitive("CUENTA_COBRO", "TENANT", a.tenantId, "Eliminó la cuenta de cobro " + a.label);
    }

    // ---------------- Integración (confirmación automática) ----------------

    public record IntegrationDto(Long id, String provider, String publicKey, String secret, boolean active, Instant lastEventAt,
                                 String webhookPath) {
    }

    @Transactional(readOnly = true)
    public List<IntegrationDto> integrations() {
        return integrations.findByTenantId(TenantContext.require()).stream()
                .map(i -> new IntegrationDto(i.id, i.provider, i.publicKey, mask(i.secret), i.active, i.lastEventAt, "/api/webhooks/payments/" + i.publicKey))
                .toList();
    }

    /** Crea las llaves para que un proveedor de pagos (o un puente con el banco) confirme pagos. El secreto se muestra una sola vez. */
    @Transactional
    public IntegrationDto createIntegration(String provider) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = TenantContext.require();
        PaymentIntegration i = new PaymentIntegration();
        i.tenantId = t;
        i.provider = provider == null || provider.isBlank() ? "GENERICO" : provider.trim().toUpperCase();
        i.publicKey = "pk_" + random(18);
        i.secret = "sk_" + random(32);
        i = integrations.save(i);
        audit.sensitive("INTEGRACION_PAGOS", "TENANT", t, "Creó una integración de confirmación de pagos (" + i.provider + ")");
        return new IntegrationDto(i.id, i.provider, i.publicKey, i.secret, true, null, "/api/webhooks/payments/" + i.publicKey);
    }

    public record WebhookPayload(String reference, BigDecimal amount, String method, String status, String transactionId) {
    }

    /**
     * Webhook público: el proveedor firma el cuerpo con HMAC-SHA256 (cabecera X-Signature, hex).
     * Confirma el pago pendiente con esa referencia si el valor coincide; si no, lo marca inconsistente.
     */
    @Transactional
    public Map<String, Object> webhook(String publicKey, String rawBody, String signature, WebhookPayload body) {
        PaymentIntegration i = integrations.findByPublicKey(publicKey).filter(x -> x.active)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Integración no encontrada"));
        String expected = hmac(i.secret, rawBody);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma inválida");
        }
        i.lastEventAt = Instant.now();
        if (body.reference() == null || body.amount() == null) throw ApiException.badRequest("Faltan referencia o valor");
        List<Payment> found = em.createQuery("select p from Payment p where p.tenantId = :t and p.reference = :r and p.status in ('PENDIENTE','RECHAZADO') order by p.createdAt desc", Payment.class)
                .setParameter("t", i.tenantId).setParameter("r", body.reference().trim()).getResultList();
        if (found.isEmpty()) return Map.of("result", "SIN_PAGO", "message", "No hay un pago pendiente con esa referencia");
        Payment p = found.get(0);
        boolean approved = body.status() == null || body.status().equalsIgnoreCase("APPROVED") || body.status().equalsIgnoreCase("APROBADO");
        if (!approved) {
            event(p, "WEBHOOK", "El proveedor informó el pago como " + body.status());
            return Map.of("result", "IGNORADO");
        }
        if (body.amount().setScale(2, RoundingMode.HALF_UP).compareTo(p.amount) != 0) {
            event(p, "IMPORTE_INCONSISTENTE", "El proveedor reporta " + Money.fmt(body.amount()) + " y la venta registró " + Money.fmt(p.amount));
            return Map.of("result", "INCONSISTENTE");
        }
        p.status = CONFIRMADO;
        p.verifiedBy = "Integración " + i.provider;
        p.verifiedAt = Instant.now();
        p.providerTxId = blank(body.transactionId());
        event(p, "CONFIRMADO", "Confirmado automáticamente por " + i.provider + (p.providerTxId == null ? "" : " (tx " + p.providerTxId + ")"));
        return Map.of("result", "CONFIRMADO", "paymentId", p.id);
    }

    // ---------------- Conciliación con el extracto ----------------

    public record ReconRowDto(Long id, java.time.LocalDate rowDate, BigDecimal amount, String reference, String description,
                              String status, Long paymentId, Long saleNumber, String note) {
    }

    public record ReconDto(Long id, String method, String fileName, java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                           int totalRows, int matched, int inconsistent, int unmatched, String createdBy, Instant createdAt,
                           List<ReconRowDto> rows, List<PaymentRow> stillPending) {
    }

    /**
     * Cruza el extracto (CSV del banco o de la billetera) con los pagos registrados: por referencia y,
     * si no hay, por valor exacto en la misma fecha (±1 día) cuando hay un solo candidato. Lo que
     * coincide queda confirmado y conciliado; las diferencias de valor se marcan como inconsistentes.
     */
    @Transactional
    public ReconDto reconcile(String method, String fileName, String csv) {
        CurrentUser.require(Permission.PAGOS_CONCILIAR);
        Long t = TenantContext.require();
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!DIGITAL.contains(m)) throw ApiException.field("method", "elige el medio del extracto");
        List<StatementParser.Row> parsed = StatementParser.parse(csv);
        if (parsed.isEmpty()) throw ApiException.badRequest("No se encontraron movimientos de entrada en el archivo");
        AuthUser me = CurrentUser.get();
        Reconciliation r = new Reconciliation();
        r.tenantId = t;
        r.method = m;
        r.fileName = blank(fileName);
        r.createdBy = me.fullName();
        r.dateFrom = parsed.stream().map(StatementParser.Row::date).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        r.dateTo = parsed.stream().map(StatementParser.Row::date).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        r = reconciliations.save(r);
        ZoneId z = zone(t);
        Instant since = (r.dateFrom == null ? LocalDate.now(z).minusDays(60) : r.dateFrom.minusDays(2)).atStartOfDay(z).toInstant();
        List<Payment> candidates = new ArrayList<>(em.createQuery("""
                        select p from Payment p where p.tenantId = :t and p.method = :m and p.createdAt >= :since
                          and p.status in ('PENDIENTE','CONFIRMADO','RECHAZADO') and p.reconciledAt is null""", Payment.class)
                .setParameter("t", t).setParameter("m", m).setParameter("since", since).getResultList());
        Set<Long> used = new HashSet<>();
        Set<String> seenRefs = new HashSet<>();
        for (StatementParser.Row row : parsed) {
            ReconciliationRow rr = new ReconciliationRow();
            rr.reconciliationId = r.id;
            rr.rowDate = row.date();
            rr.amount = row.amount();
            rr.reference = row.reference();
            rr.description = row.description();
            r.totalRows++;
            if (row.reference() != null && !seenRefs.add(row.reference())) {
                rr.status = "DUPLICADO";
                rr.note = "La referencia aparece dos veces en el extracto";
                reconRows.save(rr);
                r.unmatched++;
                continue;
            }
            Payment byRef = row.reference() == null ? null : candidates.stream()
                    .filter(p -> !used.contains(p.id) && p.reference != null
                            && (p.reference.equalsIgnoreCase(row.reference()) || (row.description() != null && row.description().contains(p.reference))))
                    .findFirst().orElse(null);
            Payment match = byRef;
            boolean probable = false;
            if (match == null) {
                List<Payment> sameAmount = candidates.stream().filter(p -> !used.contains(p.id) && p.amount.compareTo(row.amount()) == 0
                        && (row.date() == null || Math.abs(Duration.between(row.date().atStartOfDay(z).toInstant(), p.createdAt).toDays()) <= 1)).toList();
                if (sameAmount.size() == 1) {
                    match = sameAmount.get(0);
                    probable = true;
                }
            }
            if (match == null) {
                rr.status = "SIN_REGISTRO";
                rr.note = "Entró dinero que no corresponde a ningún pago registrado";
                r.unmatched++;
            } else if (match.amount.compareTo(row.amount()) != 0) {
                used.add(match.id);
                rr.status = "INCONSISTENTE";
                rr.paymentId = match.id;
                rr.note = "El extracto dice " + Money.fmt(row.amount()) + " y la venta registró " + Money.fmt(match.amount);
                event(match, "IMPORTE_INCONSISTENTE", rr.note + " (conciliación #" + r.id + ")");
                r.inconsistent++;
            } else if (probable) {
                used.add(match.id);
                rr.status = "PROBABLE";
                rr.paymentId = match.id;
                rr.note = "Mismo valor y fecha, sin referencia: confírmalo a mano";
                r.unmatched++;
            } else {
                used.add(match.id);
                rr.status = "CONCILIADO";
                rr.paymentId = match.id;
                boolean wasPending = !CONFIRMADO.equals(match.status);
                match.status = CONFIRMADO;
                match.reconciledAt = Instant.now();
                if (wasPending) {
                    match.verifiedBy = "Conciliación";
                    match.verifiedAt = Instant.now();
                }
                event(match, "CONCILIADO", "Encontrado en el extracto" + (fileName == null ? "" : " " + fileName) + " (conciliación #" + r.id + ")");
                r.matched++;
            }
            reconRows.save(rr);
        }
        audit.sensitive("CONCILIACION", "PAYMENT", r.id, "Concilió " + m.toLowerCase() + ": " + r.matched + " conciliados, " + r.inconsistent
                + " inconsistentes, " + r.unmatched + " sin cruzar de " + r.totalRows + " movimientos");
        return reconDto(r);
    }

    @Transactional(readOnly = true)
    public List<ReconDto> reconciliations() {
        return reconciliations.findTop30ByTenantIdOrderByCreatedAtDesc(TenantContext.require()).stream()
                .map(r -> new ReconDto(r.id, r.method, r.fileName, r.dateFrom, r.dateTo, r.totalRows, r.matched, r.inconsistent, r.unmatched,
                        r.createdBy, r.createdAt, List.of(), List.of())).toList();
    }

    @Transactional(readOnly = true)
    public ReconDto reconciliation(Long id) {
        return reconDto(reconciliations.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Conciliación no encontrada")));
    }

    private ReconDto reconDto(Reconciliation r) {
        List<ReconciliationRow> rows = reconRows.findByReconciliationIdOrderByIdAsc(r.id);
        Map<Long, Long> saleNumbers = new HashMap<>();
        for (ReconciliationRow x : rows) {
            if (x.paymentId != null) payments.findById(x.paymentId).ifPresent(p -> saleNumbers.put(x.paymentId, saleNumber(p)));
        }
        List<PaymentRow> pending = list(PENDIENTE, r.method, r.dateFrom, r.dateTo);
        return new ReconDto(r.id, r.method, r.fileName, r.dateFrom, r.dateTo, r.totalRows, r.matched, r.inconsistent, r.unmatched, r.createdBy,
                r.createdAt, rows.stream().map(x -> new ReconRowDto(x.id, x.rowDate, x.amount, x.reference, x.description, x.status, x.paymentId,
                saleNumbers.get(x.paymentId), x.note)).toList(), pending);
    }

    // ---------------- utilidades ----------------

    private List<PaymentRow> rows(List<Payment> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, Sale> ss = sales.findAllById(list.stream().map(p -> p.saleId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.id, Function.identity()));
        Instant now = Instant.now();
        return list.stream().map(p -> {
            Sale s = p.saleId == null ? null : ss.get(p.saleId);
            String customer = s != null ? s.customerName : p.creditEntryId != null ? abonoCustomer(p.creditEntryId) : null;
            return new PaymentRow(p.id, p.saleId, s == null ? null : s.number, p.method, p.amount, p.reference, p.status, p.duplicateRef,
                    p.userName, p.createdAt, Duration.between(p.createdAt, now).toMinutes(), p.verifiedBy, p.verifiedAt, p.verificationNote,
                    p.reconciledAt, customer, p.creditEntryId != null);
        }).toList();
    }

    private Payment find(Long id) {
        Payment p = payments.findById(id).filter(x -> x.tenantId.equals(TenantContext.require()))
                .orElseThrow(() -> ApiException.notFound("Pago no encontrado"));
        return p;
    }

    private Long saleNumber(Payment p) {
        return p.saleId == null ? null : sales.findById(p.saleId).map(s -> s.number).orElse(null);
    }

    /** Nombre del cliente de un abono de fiado (para mostrarlo en "Pagos digitales"). */
    private String abonoCustomer(Long creditEntryId) {
        List<?> r = em.createNativeQuery("select c.name from credit_entries ce join customers c on c.id = ce.customer_id where ce.id = :id")
                .setParameter("id", creditEntryId).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    private ZoneId zone(Long t) {
        return ZoneId.of(em.find(com.tiendaposs.tenant.Tenant.class, t).getTimezone());
    }

    static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String random(int n) {
        String abc = "abcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return sb.toString();
    }

    private static String mask(String s) {
        return s.substring(0, 6) + "…" + s.substring(s.length() - 4);
    }

    static String addFlag(String flags, String f) {
        if (flags == null || flags.isBlank()) return f;
        return Arrays.asList(flags.split(",")).contains(f) ? flags : flags + "," + f;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
