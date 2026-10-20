package com.tiendaposs.credit;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.pos.*;
import com.tiendaposs.pos.PosRepos.CashMovementRepository;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fiado (cuentas por cobrar). Cada venta fiada es un CARGO con fecha de vencimiento; cada ABONO
 * se aplica a los cargos más viejos primero. El saldo nunca se escribe a mano: sale de los movimientos.
 * Fiar por encima del cupo o a un cliente en mora necesita autorización (FIADO_AUTORIZAR).
 */
@Service
public class CreditService {

    public interface CreditEntryRepository extends JpaRepository<CreditEntry, Long> {
        List<CreditEntry> findByCustomerIdOrderByCreatedAtAscIdAsc(Long customerId);

        @Query("select e from CreditEntry e where e.customerId = ?1 and e.type = 'CARGO' and e.paidAmount < e.amount order by e.createdAt, e.id")
        List<CreditEntry> openCharges(Long customerId);

        List<CreditEntry> findByTenantIdAndType(Long tenantId, String type);

        List<CreditEntry> findByTenantIdAndCreatedAtBefore(Long tenantId, Instant before);

        List<CreditEntry> findBySaleIdAndType(Long saleId, String type);

        List<CreditEntry> findByShiftIdAndType(Long shiftId, String type);
    }

    private final CreditEntryRepository entries;
    private final CustomerRepository customers;
    private final CashMovementRepository cashMovements;
    private final ShiftService shifts;
    // Pagos digitales: se inyectan aparte (PaymentService también usa este servicio para reversar abonos rechazados).
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.tiendaposs.payments.PaymentService payments;
    @org.springframework.beans.factory.annotation.Autowired
    private PosRepos.PaymentRepository paymentRepo;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    private final ApprovalService approvals;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final AuditService audit;

    public CreditService(CreditEntryRepository entries, CustomerRepository customers, CashMovementRepository cashMovements,
                         ShiftService shifts, ApprovalService approvals, SettingsService settings, TenantRepository tenants,
                         AuditService audit) {
        this.entries = entries;
        this.customers = customers;
        this.cashMovements = cashMovements;
        this.shifts = shifts;
        this.approvals = approvals;
        this.settings = settings;
        this.tenants = tenants;
        this.audit = audit;
    }

    // ---------------- Al vender ----------------

    /** Valida un fiado en el cobro. Devuelve quién lo autorizó (nulo si no hizo falta). */
    public String validateCharge(Long customerId, BigDecimal amount, List<String> tokens) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        if (!st.creditEnabled) throw ApiException.badRequest("La tienda no tiene el fiado activado");
        CurrentUser.require(Permission.FIADO_GESTIONAR);
        if (customerId == null) throw ApiException.badRequest("Para fiar elige un cliente registrado (con nombre y cédula o celular)");
        Customer c = customers.findByIdAndTenantId(customerId, t).orElseThrow(() -> ApiException.badRequest("El cliente no existe"));
        if (c.creditBlocked) throw ApiException.badRequest(c.name + " tiene el fiado bloqueado");
        Summary s = summary(c, st);
        String who = null;
        if (s.overdue().signum() > 0) {
            who = approvals.require(Permission.FIADO_AUTORIZAR, tokens, c.name + " tiene " + Money.fmt(s.overdue()) + " vencido: fiarle de nuevo");
        }
        if (s.balance().add(amount).compareTo(s.limit()) > 0) {
            String w = approvals.require(Permission.FIADO_AUTORIZAR, tokens, "El fiado supera el cupo de " + c.name + " (" + Money.fmt(s.limit())
                    + ", debe " + Money.fmt(s.balance()) + ")");
            if (w != null) who = w;
        }
        return who;
    }

    /** Registra el cargo de una venta fiada (después de guardar la venta). */
    public void charge(Sale sale, Payment p, String authorizedBy) {
        Customer c = customers.findById(sale.customerId).orElseThrow();
        StoreSettings st = settings.forTenant(sale.tenantId);
        CreditEntry e = new CreditEntry();
        e.tenantId = sale.tenantId;
        e.customerId = c.id;
        e.type = "CARGO";
        e.amount = p.amount;
        e.saleId = sale.id;
        e.shiftId = sale.shiftId;
        e.dueDate = LocalDate.now(zone(sale.tenantId)).plusDays(c.creditDays != null ? c.creditDays : st.defaultCreditDays);
        e.note = "Venta #" + sale.number;
        e.userName = sale.userName;
        e.authorizedBy = authorizedBy;
        entries.save(e);
        audit.log("FIADO", "CUSTOMER", c.id, "Fió " + Money.fmt(p.amount) + " a " + c.name + " (venta #" + sale.number + ")"
                + (authorizedBy == null ? "" : ", autorizado por " + authorizedBy));
    }

    /** Anulación de una venta fiada: el cargo queda saldado con un ajuste. */
    public void reverseSale(Sale sale, String reason) {
        for (CreditEntry ch : entries.findBySaleIdAndType(sale.id, "CARGO")) {
            BigDecimal open = ch.amount.subtract(ch.paidAmount);
            if (open.signum() <= 0) continue;
            ch.paidAmount = ch.amount;
            CreditEntry adj = new CreditEntry();
            adj.tenantId = ch.tenantId;
            adj.customerId = ch.customerId;
            adj.type = "AJUSTE";
            adj.amount = open;
            adj.saleId = sale.id;
            adj.method = "ANULACION";
            adj.note = reason;
            adj.userName = CurrentUser.get().fullName();
            entries.save(adj);
        }
    }

    /** Devolución de una venta fiada: rebaja la deuda en vez de devolver dinero. */
    public void creditReturn(Sale sale, BigDecimal amount, String reason) {
        if (sale.customerId == null) throw ApiException.badRequest("La venta no tiene cliente: no se puede abonar al fiado");
        CreditEntry adj = new CreditEntry();
        adj.tenantId = sale.tenantId;
        adj.customerId = sale.customerId;
        adj.type = "AJUSTE";
        adj.amount = amount;
        adj.saleId = sale.id;
        adj.method = "DEVOLUCION";
        adj.note = reason;
        adj.userName = CurrentUser.get().fullName();
        entries.save(adj);
        apply(sale.customerId, amount, sale.id);
    }

    // ---------------- Abonos ----------------

    public record PaymentRequest(@NotNull @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                                 @NotBlank String method, @Size(max = 80) String reference, @Size(max = 300) String note) {
    }

    @Transactional
    public CustomerCredit pay(Long customerId, PaymentRequest r) {
        CurrentUser.require(Permission.FIADO_GESTIONAR);
        Long t = TenantContext.require();
        Customer c = customers.findByIdAndTenantId(customerId, t).orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
        StoreSettings st = settings.forTenant(t);
        String m = r.method().trim().toUpperCase();
        if (m.equals("FIADO") || !st.paymentMethodList().contains(m)) throw ApiException.field("method", "medio de pago no válido");
        Summary s = summary(c, st);
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(s.balance()) > 0) throw ApiException.field("amount", "debe " + Money.fmt(s.balance()) + ": el abono no puede ser mayor");
        Shift shift = shifts.openShiftOfCurrentUser().orElse(null);
        if (m.equals("EFECTIVO") && shift == null) throw ApiException.conflict("Abre tu turno de caja para recibir el abono en efectivo");
        AuthUser me = CurrentUser.get();
        CreditEntry e = new CreditEntry();
        e.tenantId = t;
        e.customerId = c.id;
        e.type = "ABONO";
        e.amount = amount;
        e.method = m;
        e.reference = blank(r.reference());
        e.note = blank(r.note());
        e.shiftId = shift == null ? null : shift.id;
        e.userName = me.fullName();
        entries.save(e);
        apply(c.id, amount, null);
        // Los abonos que no son en efectivo quedan como pago para verificar en "Pagos digitales" (y en la conciliación
        // con el extracto). Si se rechaza, el abono se reversa (reverseRejectedPayment).
        if (!m.equals("EFECTIVO")) {
            Payment p = new Payment();
            p.tenantId = t;
            p.creditEntryId = e.id;
            p.shiftId = e.shiftId;
            p.method = m;
            p.amount = amount;
            p.reference = e.reference;
            p.status = payments.initialStatus(m, st);
            p.userId = me.userId();
            p.userName = me.fullName();
            p = paymentRepo.save(p);
            payments.event(p, "REGISTRADO", "Abono de fiado de " + Money.fmt(amount) + " de " + c.name + " por " + m.toLowerCase()
                    + (p.reference == null ? "" : " · ref. " + p.reference));
        }
        if (m.equals("EFECTIVO")) {
            CashMovement cm = new CashMovement();
            cm.tenantId = t;
            cm.shiftId = shift.id;
            cm.type = "ABONO_FIADO";
            cm.amount = amount;
            cm.reason = "Abono de " + c.name;
            cm.userId = me.userId();
            cm.userName = me.fullName();
            cashMovements.save(cm);
        }
        audit.log("ABONO_FIADO", "CUSTOMER", c.id, "Recibió un abono de " + Money.fmt(amount) + " de " + c.name + " por " + m.toLowerCase());
        return detail(c.id);
    }

    /** Aplica un valor a los cargos abiertos, del más viejo al más nuevo (o primero al de la venta indicada). */
    /**
     * El pago digital de un abono fue rechazado (la plata no llegó): queda un movimiento "abono rechazado" que vuelve a
     * subir la deuda, y lo abonado se quita de los cargos (de los últimos pagados hacia atrás).
     */
    public void reverseRejectedPayment(Payment p, String reason) {
        CreditEntry abono = entries.findById(p.creditEntryId).orElseThrow();
        Customer c = customers.findById(abono.customerId).orElseThrow();
        CreditEntry back = new CreditEntry();
        back.tenantId = abono.tenantId;
        back.customerId = abono.customerId;
        back.type = "RECHAZO";
        back.amount = abono.amount;
        back.method = abono.method;
        back.reference = abono.reference;
        back.note = "Pago rechazado: " + reason;
        back.userName = CurrentUser.get().fullName();
        entries.save(back);
        BigDecimal left = abono.amount;
        List<CreditEntry> paid = entries.findByCustomerIdOrderByCreatedAtAscIdAsc(abono.customerId).stream()
                .filter(x -> x.type.equals("CARGO") && x.paidAmount.signum() > 0).collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(paid);
        for (CreditEntry ch : paid) {
            if (left.signum() <= 0) break;
            BigDecimal take = ch.paidAmount.min(left);
            ch.paidAmount = ch.paidAmount.subtract(take);
            left = left.subtract(take);
        }
        audit.sensitive("ABONO_RECHAZADO", "CUSTOMER", c.id, "Se rechazó el abono de " + Money.fmt(abono.amount) + " de " + c.name
                + " por " + abono.method.toLowerCase() + ": " + reason + ". La deuda vuelve a subir.");
    }

    private void apply(Long customerId, BigDecimal amount, Long preferSaleId) {
        BigDecimal left = amount;
        List<CreditEntry> open = new ArrayList<>(entries.openCharges(customerId));
        if (preferSaleId != null) open.sort(Comparator.comparing((CreditEntry e) -> !preferSaleId.equals(e.saleId)));
        for (CreditEntry ch : open) {
            if (left.signum() <= 0) break;
            BigDecimal take = ch.amount.subtract(ch.paidAmount).min(left);
            ch.paidAmount = ch.paidAmount.add(take);
            left = left.subtract(take);
        }
    }

    // ---------------- Consultas ----------------

    public record Summary(BigDecimal balance, BigDecimal overdue, BigDecimal limit, int days, LocalDate oldestDue, long maxDaysOverdue) {
    }

    public record CustomerCredit(Long customerId, String name, String docNumber, String phone, BigDecimal balance, BigDecimal overdue,
                                 BigDecimal limit, int creditDays, boolean blocked, LocalDate oldestDue, long daysOverdue,
                                 String status, Instant lastPaymentAt, List<StatementLine> statement) {
    }

    public record StatementLine(Long id, String type, BigDecimal amount, BigDecimal balanceAfter, Long saleId, String method,
                                String reference, LocalDate dueDate, BigDecimal openAmount, String note, String userName,
                                String authorizedBy, Instant createdAt) {
    }

    public Summary summary(Customer c, StoreSettings st) {
        LocalDate today = LocalDate.now(zone(c.tenantId));
        BigDecimal balance = BigDecimal.ZERO, overdue = BigDecimal.ZERO;
        LocalDate oldest = null, firstDue = null;
        for (CreditEntry ch : entries.openCharges(c.id)) {
            BigDecimal open = ch.amount.subtract(ch.paidAmount);
            balance = balance.add(open);
            if (ch.dueDate != null && (firstDue == null || ch.dueDate.isBefore(firstDue))) firstDue = ch.dueDate;
            if (ch.dueDate != null && ch.dueDate.isBefore(today)) {
                overdue = overdue.add(open);
                if (oldest == null || ch.dueDate.isBefore(oldest)) oldest = ch.dueDate;
            }
        }
        BigDecimal limit = c.creditLimit != null ? c.creditLimit : st.defaultCreditLimit;
        return new Summary(balance, overdue, limit, c.creditDays != null ? c.creditDays : st.defaultCreditDays, firstDue,
                oldest == null ? 0 : ChronoUnit.DAYS.between(oldest, today));
    }

    /** Clientes con saldo (o con cupo configurado), con semáforo: AL_DIA, POR_VENCER, VENCIDO, BLOQUEADO. */
    @Transactional(readOnly = true)
    public List<CustomerCredit> list(boolean onlyWithBalance) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        Set<Long> withEntries = entries.findByTenantIdAndType(t, "CARGO").stream().map(e -> e.customerId).collect(Collectors.toSet());
        List<CustomerCredit> out = new ArrayList<>();
        for (Long id : withEntries) {
            Customer c = customers.findById(id).orElse(null);
            if (c == null) continue;
            CustomerCredit cc = toCredit(c, st, false);
            if (!onlyWithBalance || cc.balance().signum() > 0) out.add(cc);
        }
        out.sort(Comparator.comparing(CustomerCredit::daysOverdue).reversed().thenComparing(CustomerCredit::balance, Comparator.reverseOrder()));
        return out;
    }

    @Transactional(readOnly = true)
    public CustomerCredit detail(Long customerId) {
        Customer c = customers.findByIdAndTenantId(customerId, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
        return toCredit(c, settings.forTenant(c.tenantId), true);
    }

    public record TermsRequest(@DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal creditLimit,
                               @Min(value = 0, message = "no puede ser negativo") @Max(value = 365, message = "máximo 365") Integer creditDays,
                               Boolean blocked) {
    }

    @Transactional
    public CustomerCredit setTerms(Long customerId, TermsRequest r) {
        CurrentUser.require(Permission.FIADO_AUTORIZAR);
        Customer c = customers.findByIdAndTenantId(customerId, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
        c.creditLimit = r.creditLimit();
        c.creditDays = r.creditDays();
        if (r.blocked() != null) c.creditBlocked = r.blocked();
        audit.sensitive("FIADO_CUPO", "CUSTOMER", c.id, "Cambió el fiado de " + c.name + ": cupo " + (c.creditLimit == null ? "el de la tienda" : Money.fmt(c.creditLimit))
                + ", plazo " + (c.creditDays == null ? "el de la tienda" : c.creditDays + " días") + (c.creditBlocked ? ", BLOQUEADO" : ""));
        return detail(c.id);
    }

    /** Mensaje de cobro amable por WhatsApp. */
    @Transactional(readOnly = true)
    public Map<String, String> reminder(Long customerId) {
        CustomerCredit cc = detail(customerId);
        Customer c = customers.findById(customerId).orElseThrow();
        Tenant t = tenants.findById(c.tenantId).orElseThrow();
        String msg = "Hola " + c.name.split(" ")[0] + ", le saluda " + t.getName() + ". Le recordamos que tiene un saldo pendiente de "
                + Money.fmt(cc.balance()) + (cc.overdue().signum() > 0 ? " (vencido " + Money.fmt(cc.overdue()) + ")" : "")
                + ". Puede abonar cuando pase por la tienda. ¡Gracias!";
        String phone = c.phone == null ? null : c.phone.replaceAll("\\D", "");
        if (phone != null && phone.length() == 10 && phone.startsWith("3")) phone = "57" + phone;
        return Map.of("message", msg, "whatsappUrl", phone == null ? "" : "https://wa.me/" + phone + "?text=" + URLEncoder.encode(msg, StandardCharsets.UTF_8));
    }

    // ---------------- Cuadre de fiados ----------------

    public record LedgerRow(Long customerId, String name, BigDecimal opening, BigDecimal charged, BigDecimal paid,
                            BigDecimal rejected, BigDecimal adjusted, BigDecimal closing) {
    }

    /**
     * Cuadre de fiados de un período: saldo al inicio + fiado − abonos + abonos rechazados − anulaciones/devoluciones =
     * saldo al final. Si el período llega hasta hoy, el saldo final debe ser igual a lo que deben hoy los clientes
     * (balanced); si no cuadra, algo se registró por fuera y hay que revisarlo.
     */
    public record Ledger(LocalDate from, LocalDate to, BigDecimal opening, BigDecimal charged, BigDecimal paid,
                         BigDecimal paidCash, BigDecimal paidDigital, BigDecimal paidPendingVerification, BigDecimal rejected,
                         BigDecimal voided, BigDecimal returned, BigDecimal closing, BigDecimal currentReceivables,
                         Boolean balanced, List<LedgerRow> customers) {
    }

    @Transactional(readOnly = true)
    public Ledger ledger(LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        LocalDate today = LocalDate.now(z);
        if (to == null || to.isAfter(today)) to = today;
        if (from == null) from = to;
        if (from.isAfter(to)) throw ApiException.field("from", "la fecha inicial va antes de la final");
        Instant f = from.atStartOfDay(z).toInstant();
        Instant tt = to.plusDays(1).atStartOfDay(z).toInstant();
        Map<Long, BigDecimal[]> byCustomer = new HashMap<>(); // opening, charged, paid, rejected, adjusted
        BigDecimal paidCash = BigDecimal.ZERO, paidDigital = BigDecimal.ZERO, voided = BigDecimal.ZERO, returned = BigDecimal.ZERO;
        for (CreditEntry e : entries.findByTenantIdAndCreatedAtBefore(t, tt)) {
            BigDecimal[] v = byCustomer.computeIfAbsent(e.customerId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            boolean before = e.createdAt.isBefore(f);
            BigDecimal signed = e.type.equals("CARGO") || e.type.equals("RECHAZO") ? e.amount : e.amount.negate();
            if (before) {
                v[0] = v[0].add(signed);
                continue;
            }
            switch (e.type) {
                case "CARGO" -> v[1] = v[1].add(e.amount);
                case "ABONO" -> {
                    v[2] = v[2].add(e.amount);
                    if ("EFECTIVO".equals(e.method)) paidCash = paidCash.add(e.amount);
                    else paidDigital = paidDigital.add(e.amount);
                }
                case "RECHAZO" -> v[3] = v[3].add(e.amount);
                default -> {
                    v[4] = v[4].add(e.amount);
                    if ("DEVOLUCION".equals(e.method)) returned = returned.add(e.amount);
                    else voided = voided.add(e.amount);
                }
            }
        }
        Map<Long, String> names = new HashMap<>();
        customers.findAllById(byCustomer.keySet()).forEach(c -> names.put(c.id, c.name));
        List<LedgerRow> rows = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map.Entry<Long, BigDecimal[]> en : byCustomer.entrySet()) {
            BigDecimal[] v = en.getValue();
            BigDecimal closing = v[0].add(v[1]).subtract(v[2]).add(v[3]).subtract(v[4]);
            for (int i = 0; i < 5; i++) tot[i] = tot[i].add(v[i]);
            boolean moved = v[1].signum() != 0 || v[2].signum() != 0 || v[3].signum() != 0 || v[4].signum() != 0;
            if (moved || closing.signum() != 0) {
                rows.add(new LedgerRow(en.getKey(), names.getOrDefault(en.getKey(), "Cliente"), v[0], v[1], v[2], v[3], v[4], closing));
            }
        }
        rows.sort(Comparator.comparing(LedgerRow::closing).reversed().thenComparing(LedgerRow::name));
        BigDecimal closing = tot[0].add(tot[1]).subtract(tot[2]).add(tot[3]).subtract(tot[4]);
        BigDecimal pendingVerification;
        Object pv = em.createNativeQuery("""
                        select coalesce(sum(p.amount),0) from payments p join credit_entries ce on ce.id = p.credit_entry_id
                        where p.tenant_id = :t and p.status = 'PENDIENTE' and ce.created_at >= :f and ce.created_at < :tt""")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult();
        pendingVerification = pv instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(pv));
        BigDecimal current = receivables(t);
        Boolean balanced = to.equals(today) ? closing.compareTo(current) == 0 : null;
        return new Ledger(from, to, tot[0], tot[1], tot[2], paidCash, paidDigital, pendingVerification, tot[3], voided, returned,
                closing, current, balanced, rows);
    }

    /** Total por cobrar de la tienda (para finanzas). */
    public BigDecimal receivables(Long tenantId) {
        return entries.findByTenantIdAndType(tenantId, "CARGO").stream().map(e -> e.amount.subtract(e.paidAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CustomerCredit toCredit(Customer c, StoreSettings st, boolean withStatement) {
        Summary s = summary(c, st);
        List<CreditEntry> all = entries.findByCustomerIdOrderByCreatedAtAscIdAsc(c.id);
        Instant lastPay = all.stream().filter(e -> e.type.equals("ABONO")).map(e -> e.createdAt).max(Comparator.naturalOrder()).orElse(null);
        LocalDate today = LocalDate.now(zone(c.tenantId));
        String status = c.creditBlocked ? "BLOQUEADO" : s.overdue().signum() > 0 ? "VENCIDO"
                : all.stream().anyMatch(e -> e.type.equals("CARGO") && e.paidAmount.compareTo(e.amount) < 0 && e.dueDate != null && !e.dueDate.isAfter(today.plusDays(3)))
                ? "POR_VENCER" : "AL_DIA";
        List<StatementLine> lines = new ArrayList<>();
        if (withStatement) {
            BigDecimal run = BigDecimal.ZERO;
            for (CreditEntry e : all) {
                run = e.type.equals("CARGO") || e.type.equals("RECHAZO") ? run.add(e.amount) : run.subtract(e.amount);
                lines.add(new StatementLine(e.id, e.type, e.amount, run, e.saleId, e.method, e.reference, e.dueDate,
                        e.type.equals("CARGO") ? e.amount.subtract(e.paidAmount) : null, e.note, e.userName, e.authorizedBy, e.createdAt));
            }
            Collections.reverse(lines);
        }
        return new CustomerCredit(c.id, c.name, c.docNumber, c.phone, s.balance(), s.overdue(), s.limit(), s.days(), c.creditBlocked,
                s.oldestDue(), s.maxDaysOverdue(), status, lastPay, lines);
    }

    private ZoneId zone(Long tenantId) {
        return ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
