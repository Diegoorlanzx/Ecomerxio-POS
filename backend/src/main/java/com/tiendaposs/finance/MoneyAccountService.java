package com.tiendaposs.finance;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.finance.FinanceRepos.MoneyAccountMoveRepository;
import com.tiendaposs.finance.FinanceRepos.MoneyAccountRepository;
import com.tiendaposs.payments.PaymentRepos.ReconciliationRepository;
import com.tiendaposs.payments.Reconciliation;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Fase 15: bancos y billeteras como cuentas con saldo. No se digita cada movimiento: el saldo sale de lo que ya registra
 * el sistema por los medios de pago de la cuenta —cobros confirmados, abonos de fiado, anticipos, ingresos y gastos
 * pagados "desde el banco", pagos a proveedores y devoluciones— más las consignaciones, retiros, traslados, comisiones
 * y ajustes que se anotan a mano para cuadrar con el extracto.
 */
@Service
public class MoneyAccountService {

    /** Medios que pueden caer en una cuenta (el efectivo vive en la caja). */
    public static final List<String> METHODS = List.of("TRANSFERENCIA", "NEQUI", "DAVIPLATA", "BREB", "TARJETA", "OTRO");
    public static final Set<String> MOVE_TYPES = Set.of("CONSIGNACION", "RETIRO", "TRASLADO", "COMISION", "AJUSTE");

    private final MoneyAccountRepository accounts;
    private final MoneyAccountMoveRepository moves;
    private final ReconciliationRepository reconciliations;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final EntityManager em;

    public MoneyAccountService(MoneyAccountRepository accounts, MoneyAccountMoveRepository moves, ReconciliationRepository reconciliations,
                               TenantRepository tenants, AuditService audit, EntityManager em) {
        this.accounts = accounts;
        this.moves = moves;
        this.reconciliations = reconciliations;
        this.tenants = tenants;
        this.audit = audit;
        this.em = em;
    }

    // ---------------- DTOs ----------------

    public record AccountRequest(@NotBlank(message = "ponle un nombre") @Size(max = 80) String name,
                                 @NotBlank @Pattern(regexp = "BANCO|BILLETERA|OTRA", message = "tipo no válido") String kind,
                                 List<String> methods,
                                 BigDecimal openingBalance, LocalDate openingDate, Boolean active) {
    }

    public record LastReconciliation(String method, LocalDate dateTo, int matched, int inconsistent, int unmatched, Instant createdAt) {
    }

    public record AccountDto(Long id, String name, String kind, List<String> methods, BigDecimal openingBalance, LocalDate openingDate,
                             boolean active, BigDecimal balance, BigDecimal monthIn, BigDecimal monthOut, BigDecimal pendingVerification,
                             LastReconciliation lastReconciliation) {
    }

    public record MoveRequest(@NotNull Long accountId,
                              @NotBlank @Pattern(regexp = "CONSIGNACION|RETIRO|TRASLADO|COMISION|AJUSTE", message = "tipo no válido") String type,
                              @NotNull BigDecimal amount, LocalDate moveDate, Long toAccountId, @Size(max = 300) String note) {
    }

    /** Una línea del movimiento de la cuenta. refType: SALE, CREDIT, SERVICE, ENTRY, SUPPLIER, RETURN o MOVE. */
    public record Line(LocalDate date, Instant at, String concept, BigDecimal amount, BigDecimal balance, String refType, Long refId) {
    }

    public record Ledger(AccountDto account, LocalDate from, LocalDate to, BigDecimal openingBalance, BigDecimal in, BigDecimal out,
                         BigDecimal closingBalance, List<Line> lines, boolean truncated) {
    }

    /** Lo que no tiene cuenta: medios sin asignar que sí tuvieron movimiento (para avisar). */
    public record Overview(List<AccountDto> accounts, BigDecimal total, List<String> unassignedMethods, List<String> availableMethods) {
    }

    // ---------------- Cuentas ----------------

    @Transactional(readOnly = true)
    public Overview overview() {
        CurrentUser.require(Permission.FINANZAS_VER);
        Long t = TenantContext.require();
        List<AccountDto> list = accounts.findByTenantIdOrderByActiveDescNameAsc(t).stream().map(this::dto).toList();
        BigDecimal total = list.stream().filter(AccountDto::active).map(AccountDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> used = new HashSet<>();
        list.stream().filter(AccountDto::active).forEach(a -> used.addAll(a.methods()));
        List<String> unassigned = new ArrayList<>();
        LocalDate since = today(t).minusDays(60);
        for (String m : METHODS) {
            if (used.contains(m) || "OTRO".equals(m)) continue;
            Number n = (Number) em.createNativeQuery("""
                            select count(*) from payments p where p.tenant_id = :t and p.method = :m and p.created_at >= :since""")
                    .setParameter("t", t).setParameter("m", m).setParameter("since", ts(since, zone(t))).getSingleResult();
            if (n.longValue() > 0) unassigned.add(m);
        }
        return new Overview(list, total, unassigned, METHODS.stream().filter(m -> !used.contains(m)).toList());
    }

    /** Saldo de todas las cuentas activas hoy (para el flujo proyectado y el tablero). */
    @Transactional(readOnly = true)
    public BigDecimal totalBalance(Long tenantId) {
        LocalDate today = today(tenantId);
        return accounts.findByTenantIdOrderByActiveDescNameAsc(tenantId).stream().filter(a -> a.active)
                .map(a -> balanceAt(a, today)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public AccountDto save(Long id, AccountRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        MoneyAccount a = id == null ? new MoneyAccount() : find(id);
        boolean active = r.active() == null ? a.active : r.active();
        List<String> methods = new ArrayList<>();
        for (String m : r.methods() == null ? List.<String>of() : r.methods()) {
            String x = m == null ? "" : m.trim().toUpperCase();
            if ("EFECTIVO".equals(x)) throw ApiException.field("methods", "el efectivo se maneja en la caja, no en una cuenta");
            if (!METHODS.contains(x)) throw ApiException.field("methods", "medio no válido: " + m);
            if (!methods.contains(x)) methods.add(x);
        }
        if (active) {
            for (MoneyAccount other : accounts.findByTenantIdOrderByActiveDescNameAsc(t)) {
                if (!other.active || other.id.equals(a.id)) continue;
                for (String m : other.methodList()) {
                    if (methods.contains(m)) throw ApiException.field("methods", "lo cobrado por " + m.toLowerCase() + " ya cae en «" + other.name
                            + "»: un medio va a una sola cuenta");
                }
            }
        }
        String name = r.name().trim();
        if (accounts.findByTenantIdOrderByActiveDescNameAsc(t).stream().anyMatch(x -> !x.id.equals(a.id) && x.name.equalsIgnoreCase(name))) {
            throw ApiException.field("name", "ya existe una cuenta con ese nombre");
        }
        a.tenantId = t;
        a.name = name;
        a.kind = r.kind();
        a.methods = String.join(",", methods);
        a.openingBalance = r.openingBalance() == null ? BigDecimal.ZERO : r.openingBalance();
        a.openingDate = r.openingDate() == null ? (a.openingDate != null ? a.openingDate : today(t)) : r.openingDate();
        a.active = active;
        accounts.save(a);
        audit.log(id == null ? "CUENTA_CREADA" : "CUENTA_EDITADA", "MONEY_ACCOUNT", a.id,
                (id == null ? "Creó" : "Editó") + " la cuenta " + a.name + " (saldo inicial " + Money.fmt(a.openingBalance) + " al " + a.openingDate + ")");
        return dto(a);
    }

    // ---------------- Movimientos manuales ----------------

    @Transactional
    public Ledger addMove(MoveRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        MoneyAccount a = find(r.accountId());
        BigDecimal amount = r.amount();
        if ("AJUSTE".equals(r.type())) {
            if (amount.signum() == 0) throw ApiException.field("amount", "el ajuste no puede ser cero");
            if (r.note() == null || r.note().isBlank()) throw ApiException.field("note", "explica el ajuste (p. ej. diferencia con el extracto)");
        } else if (amount.signum() <= 0) {
            throw ApiException.field("amount", "debe ser mayor que cero");
        }
        MoneyAccountMove m = new MoneyAccountMove();
        if ("TRASLADO".equals(r.type())) {
            if (r.toAccountId() == null) throw ApiException.field("toAccountId", "elige la cuenta que recibe");
            if (r.toAccountId().equals(a.id)) throw ApiException.field("toAccountId", "elige otra cuenta");
            m.toAccountId = find(r.toAccountId()).id;
        }
        m.tenantId = t;
        m.accountId = a.id;
        m.type = r.type();
        m.amount = amount;
        m.moveDate = r.moveDate() == null ? today(t) : r.moveDate();
        if (m.moveDate.isAfter(today(t))) throw ApiException.field("moveDate", "no puede ser una fecha futura");
        m.note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        m.createdBy = CurrentUser.get().fullName();
        moves.save(m);
        audit.log("CUENTA_MOVIMIENTO", "MONEY_ACCOUNT", a.id, label(m.type) + " de " + Money.fmt(amount) + " en " + a.name
                + (m.note == null ? "" : ": " + m.note));
        LocalDate from = m.moveDate.withDayOfMonth(1);
        return ledger(a.id, from, today(t));
    }

    @Transactional
    public void voidMove(Long id, String reason) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        MoneyAccountMove m = moves.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Movimiento no encontrado"));
        if (m.voidedAt != null) throw ApiException.badRequest("Ya estaba anulado");
        m.voidedAt = Instant.now();
        m.voidedBy = CurrentUser.get().fullName();
        moves.save(m);
        audit.sensitive("CUENTA_MOVIMIENTO_ANULADO", "MONEY_ACCOUNT", m.accountId, "Anuló " + label(m.type).toLowerCase() + " de "
                + Money.fmt(m.amount) + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }

    // ---------------- Libro de la cuenta ----------------

    @Transactional(readOnly = true)
    public Ledger ledger(Long accountId, LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.FINANZAS_VER);
        MoneyAccount a = find(accountId);
        Long t = a.tenantId;
        ZoneId z = zone(t);
        LocalDate start = from.isBefore(a.openingDate) ? a.openingDate : from;
        BigDecimal opening = balanceAt(a, start.minusDays(1));
        List<Object[]> rows = rows(a, ts(start, z), ts(to.plusDays(1), z));
        boolean truncated = rows.size() > 2000;
        BigDecimal run = opening, in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal amt = dec(r[2]);
            run = run.add(amt);
            if (amt.signum() >= 0) in = in.add(amt);
            else out = out.add(amt.negate());
            if (lines.size() < 2000) {
                Instant at = instant(r[0]);
                lines.add(new Line(at.atZone(z).toLocalDate(), at, (String) r[1], amt, run, (String) r[3], r[4] == null ? null : ((Number) r[4]).longValue()));
            }
        }
        Collections.reverse(lines); // lo más reciente primero
        return new Ledger(dto(a), start, to, opening, in, out, run, lines, truncated);
    }

    BigDecimal balanceAt(MoneyAccount a, LocalDate date) {
        if (date.isBefore(a.openingDate)) return a.openingBalance;
        ZoneId z = zone(a.tenantId);
        return a.openingBalance.add(sum(a, ts(a.openingDate, z), ts(date.plusDays(1), z)));
    }

    private BigDecimal sum(MoneyAccount a, Timestamp from, Timestamp to) {
        return rows(a, from, to).stream().map(r -> dec(r[2])).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Todos los movimientos de la cuenta entre dos instantes: [instante, concepto, valor con signo, tipo, id].
     * Los de solo fecha (gastos, pagos a proveedores, movimientos manuales) se ubican al comienzo de ese día.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> rows(MoneyAccount a, Timestamp from, Timestamp to) {
        List<String> m = a.methodList().isEmpty() ? List.of("__NINGUNO__") : a.methodList();
        String tz = zone(a.tenantId).getId();
        return em.createNativeQuery("""
                        select * from (
                          select p.created_at as at, 'Venta #' || s.number || ' · ' || lower(p.method) as concept, p.amount as amount,
                                 'SALE' as ref_type, s.id as ref_id
                            from payments p join sales s on s.id = p.sale_id
                           where p.tenant_id = :t and p.method in (:m) and s.status = 'PAGADA'
                             and p.status not in ('PENDIENTE', 'RECHAZADO', 'ANULADO')
                          union all
                          select c.created_at, 'Abono de fiado' || coalesce(' · ' || cu.name, ''), c.amount, 'CREDIT', c.customer_id
                            from credit_entries c left join customers cu on cu.id = c.customer_id
                           where c.tenant_id = :t and c.type = 'ABONO' and c.method in (:m)
                          union all
                          select d.created_at, 'Anticipo de orden de servicio', d.amount, 'SERVICE', d.order_id
                            from service_order_deposits d where d.tenant_id = :t and d.method in (:m)
                          union all
                          select cast(e.entry_date as timestamp) at time zone :tz,
                                 case when e.kind = 'GASTO' then 'Gasto: ' else 'Ingreso: ' end || e.description,
                                 case when e.kind = 'GASTO' then -e.amount else e.amount end, 'ENTRY', e.id
                            from finance_entries e
                           where e.tenant_id = :t and e.voided_at is null and e.paid_from = 'BANCO' and e.method in (:m)
                          union all
                          select cast(sp.paid_on as timestamp) at time zone :tz, 'Pago a ' || su.name, -sp.amount, 'SUPPLIER', sp.supplier_id
                            from supplier_payments sp join suppliers su on su.id = sp.supplier_id
                           where sp.tenant_id = :t and sp.paid_from = 'BANCO' and sp.method in (:m)
                          union all
                          select r.created_at, 'Devolución de la venta #' || s.number, -r.refund_amount, 'RETURN', s.id
                            from sale_returns r join sales s on s.id = r.sale_id
                           where r.tenant_id = :t and r.refund_method in (:m)
                          union all
                          select cast(mv.move_date as timestamp) at time zone :tz,
                                 case mv.type when 'CONSIGNACION' then 'Consignación' when 'RETIRO' then 'Retiro'
                                              when 'COMISION' then 'Comisión o costo bancario' when 'AJUSTE' then 'Ajuste'
                                              else 'Traslado a ' || coalesce(dst.name, 'otra cuenta') end || coalesce(': ' || mv.note, ''),
                                 case when mv.type in ('RETIRO', 'COMISION', 'TRASLADO') then -mv.amount else mv.amount end, 'MOVE', mv.id
                            from money_account_moves mv left join money_accounts dst on dst.id = mv.to_account_id
                           where mv.tenant_id = :t and mv.account_id = :acc and mv.voided_at is null
                          union all
                          select cast(mv.move_date as timestamp) at time zone :tz, 'Traslado desde ' || src.name || coalesce(': ' || mv.note, ''),
                                 mv.amount, 'MOVE', mv.id
                            from money_account_moves mv join money_accounts src on src.id = mv.account_id
                           where mv.tenant_id = :t and mv.to_account_id = :acc and mv.type = 'TRASLADO' and mv.voided_at is null
                        ) x where x.at >= :from and x.at < :to order by x.at, x.ref_type, x.ref_id""")
                .setParameter("t", a.tenantId).setParameter("m", m).setParameter("tz", tz).setParameter("acc", a.id)
                .setParameter("from", from).setParameter("to", to).getResultList();
    }

    // ---------------- auxiliares ----------------

    private AccountDto dto(MoneyAccount a) {
        LocalDate today = today(a.tenantId);
        ZoneId z = zone(a.tenantId);
        LocalDate monthStart = today.withDayOfMonth(1).isBefore(a.openingDate) ? a.openingDate : today.withDayOfMonth(1);
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        if (!monthStart.isAfter(today)) {
            for (Object[] r : rows(a, ts(monthStart, z), ts(today.plusDays(1), z))) {
                BigDecimal v = dec(r[2]);
                if (v.signum() >= 0) in = in.add(v);
                else out = out.add(v.negate());
            }
        }
        BigDecimal pending = BigDecimal.ZERO;
        if (!a.methodList().isEmpty()) {
            pending = dec(em.createNativeQuery("select coalesce(sum(p.amount), 0) from payments p where p.tenant_id = :t and p.status = 'PENDIENTE' and p.method in (:m)")
                    .setParameter("t", a.tenantId).setParameter("m", a.methodList()).getSingleResult());
        }
        LastReconciliation last = reconciliations.findTop30ByTenantIdOrderByCreatedAtDesc(a.tenantId).stream()
                .filter(r -> a.methodList().contains(r.method)).findFirst()
                .map(r -> new LastReconciliation(r.method, r.dateTo, r.matched, r.inconsistent, r.unmatched, r.createdAt)).orElse(null);
        return new AccountDto(a.id, a.name, a.kind, a.methodList(), a.openingBalance, a.openingDate, a.active, balanceAt(a, today), in, out,
                pending, last);
    }

    MoneyAccount find(Long id) {
        return accounts.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Cuenta no encontrada"));
    }

    private static String label(String type) {
        return switch (type) {
            case "CONSIGNACION" -> "Consignación";
            case "RETIRO" -> "Retiro";
            case "TRASLADO" -> "Traslado";
            case "COMISION" -> "Comisión";
            default -> "Ajuste";
        };
    }

    private ZoneId zone(Long tenantId) {
        return ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private LocalDate today(Long tenantId) {
        return LocalDate.now(zone(tenantId));
    }

    private static Instant instant(Object o) {
        return o instanceof Instant i ? i : o instanceof Timestamp t ? t.toInstant() : o instanceof java.time.OffsetDateTime x ? x.toInstant()
                : Instant.parse(o.toString());
    }

    private static Timestamp ts(LocalDate d, ZoneId z) {
        return Timestamp.from(d.atStartOfDay(z).toInstant());
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
