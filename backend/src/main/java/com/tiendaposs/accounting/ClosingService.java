package com.tiendaposs.accounting;

import com.tiendaposs.accounting.AccountingRepos.*;
import com.tiendaposs.accounting.AccountingService.Draft;
import com.tiendaposs.accounting.AccountingService.DraftLine;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Fase 14: notas contables manuales, cierres de mes y de año, y terceros.
 * <ul>
 *   <li><b>Cierre de mes:</b> lo que llegue después con fecha de un mes cerrado se registra el primer día del siguiente
 *       mes abierto (con su fecha original). Solo el dueño lo reabre, con motivo, y queda en la auditoría.</li>
 *   <li><b>Cierre de año:</b> lleva ingresos, costos y gastos a la utilidad (o pérdida) del ejercicio.</li>
 *   <li><b>Notas manuales:</b> saldos iniciales, depreciaciones, provisiones, préstamos, aportes y correcciones. Tienen
 *       que cuadrar y no pueden caer en un mes cerrado; se corrigen con una reversa, nunca se borran.</li>
 * </ul>
 */
@Service
public class ClosingService {

    private final AccountingService accounting;
    private final SettingsRepository settingsRepo;
    private final PeriodRepository periods;
    private final EntryRepository entries;
    private final LineRepository lines;
    private final PendingRepository pending;
    private final AccountRepository accounts;
    private final Counters counters;
    private final AuditService audit;
    private final EntityManager em;

    public ClosingService(AccountingService accounting, SettingsRepository settingsRepo, PeriodRepository periods, EntryRepository entries,
                          LineRepository lines, PendingRepository pending, AccountRepository accounts, Counters counters, AuditService audit,
                          EntityManager em) {
        this.accounting = accounting;
        this.settingsRepo = settingsRepo;
        this.periods = periods;
        this.entries = entries;
        this.lines = lines;
        this.pending = pending;
        this.accounts = accounts;
        this.counters = counters;
        this.audit = audit;
        this.em = em;
    }

    private static LocalDate today() {
        return LocalDate.now(AccountingService.ZONE);
    }

    private AccountingSettings requireEnabled(Long t) {
        AccountingSettings s = settingsRepo.findById(t).orElse(null);
        if (s == null || !s.enabled || s.startDate == null) throw ApiException.badRequest("Activa primero la contabilidad.");
        return s;
    }

    // =================================================================== Meses

    public record PeriodDto(String period, boolean closed, long entries, long pending, Instant closedAt, String closedBy, Instant reopenedAt,
                            String reopenedBy, String reopenReason, boolean canClose) {
    }

    public record YearDto(int year, boolean closed, Long entryId, boolean canClose) {
    }

    public record Periods(List<PeriodDto> months, List<YearDto> years) {
    }

    @Transactional(readOnly = true)
    public Periods list() {
        Long t = TenantContext.require();
        AccountingSettings s = settingsRepo.findById(t).orElse(null);
        Map<String, AccountingPeriod> byKey = new HashMap<>();
        periods.findByTenantIdOrderByPeriodDesc(t).forEach(p -> byKey.put(p.period, p));
        YearMonth current = YearMonth.from(today());
        YearMonth first = s == null || s.startDate == null ? current.minusMonths(11) : YearMonth.from(s.startDate);
        if (first.isBefore(current.minusMonths(23))) first = current.minusMonths(23);
        List<PeriodDto> months = new ArrayList<>();
        for (YearMonth m = current; !m.isBefore(first); m = m.minusMonths(1)) {
            AccountingPeriod p = byKey.get(m.toString());
            long n = entries.countBetween(t, m.atDay(1), m.atEndOfMonth());
            long pend = pending.countOpenBetween(t, m.atDay(1), m.atEndOfMonth());
            boolean closed = p != null && p.closed;
            months.add(new PeriodDto(m.toString(), closed, n, pend, p == null ? null : p.closedAt, p == null ? null : p.closedBy,
                    p == null ? null : p.reopenedAt, p == null ? null : p.reopenedBy, p == null ? null : p.reopenReason,
                    !closed && m.isBefore(current) && pend == 0));
        }
        List<YearDto> years = new ArrayList<>();
        int startYear = s == null || s.startDate == null ? current.getYear() : s.startDate.getYear();
        for (int y = current.getYear(); y >= startYear; y--) {
            Long entryId = entries.findFirstByTenantIdAndSourceTypeAndSourceId(t, "YEAR_CLOSE", (long) y).map(e -> e.id).orElse(null);
            years.add(new YearDto(y, entryId != null, entryId, entryId == null && y < current.getYear()));
        }
        return new Periods(months, years);
    }

    @Transactional
    public Periods close(String period) {
        CurrentUser.require(Permission.CONTABILIDAD_EDITAR);
        Long t = TenantContext.require();
        requireEnabled(t);
        YearMonth m = parse(period);
        if (!m.isBefore(YearMonth.from(today()))) throw ApiException.badRequest("Solo se cierran meses que ya terminaron.");
        long pend = pending.countOpenBetween(t, m.atDay(1), m.atEndOfMonth());
        if (pend > 0) {
            throw ApiException.badRequest("El mes tiene " + pend + " operaciones pendientes por contabilizar: complétalas en el mapa contable antes de cerrarlo.");
        }
        AccountingPeriod p = periods.findById(key(t, m.toString())).orElseGet(() -> {
            AccountingPeriod n = new AccountingPeriod();
            n.tenantId = t;
            n.period = m.toString();
            return n;
        });
        if (p.closed && p.closedAt != null) throw ApiException.badRequest("El mes ya está cerrado.");
        p.closed = true;
        p.closedAt = Instant.now();
        p.closedBy = CurrentUser.get().fullName();
        periods.save(p);
        audit.sensitive("MES_CONTABLE_CERRADO", "TENANT", t, "Cerró el mes contable " + m);
        return list();
    }

    public record ReopenRequest(@NotBlank(message = "explica por qué se reabre") @Size(max = 300) String reason) {
    }

    /** Solo el dueño reabre un mes, con motivo. */
    @Transactional
    public Periods reopen(String period, ReopenRequest r) {
        if (!CurrentUser.get().owner()) throw ApiException.forbidden("Solo el dueño puede reabrir un mes cerrado.");
        Long t = TenantContext.require();
        YearMonth m = parse(period);
        AccountingPeriod p = periods.findById(key(t, m.toString())).filter(x -> x.closed).orElseThrow(() -> ApiException.badRequest("El mes no está cerrado."));
        int y = m.getYear();
        if (entries.existsByTenantIdAndSourceTypeAndSourceId(t, "YEAR_CLOSE", (long) y)) {
            throw ApiException.badRequest("El año " + y + " ya tiene cierre: sus meses no se reabren.");
        }
        p.closed = false;
        p.reopenedAt = Instant.now();
        p.reopenedBy = CurrentUser.get().fullName();
        p.reopenReason = r.reason().trim();
        periods.save(p);
        audit.sensitive("MES_CONTABLE_REABIERTO", "TENANT", t, "Reabrió el mes contable " + m + ": " + p.reopenReason);
        return list();
    }

    // =================================================================== Año

    /**
     * Cierre de año: deja en cero las cuentas de resultados (clases 4 a 7) contra la utilidad o la pérdida del ejercicio,
     * con fecha 31 de diciembre. Exige que el año haya terminado, que sus 12 meses estén cerrados y que no haya pendientes.
     */
    @Transactional
    public Periods closeYear(int year) {
        CurrentUser.require(Permission.CONTABILIDAD_EDITAR);
        Long t = TenantContext.require();
        AccountingSettings s = requireEnabled(t);
        if (year >= today().getYear()) throw ApiException.badRequest("Solo se cierran años que ya terminaron.");
        if (entries.existsByTenantIdAndSourceTypeAndSourceId(t, "YEAR_CLOSE", (long) year)) throw ApiException.badRequest("El año ya está cerrado.");
        if (year > s.startDate.getYear() && !entries.existsByTenantIdAndSourceTypeAndSourceId(t, "YEAR_CLOSE", (long) (year - 1))
                && hasResults(t, year - 1)) {
            throw ApiException.badRequest("Cierra primero el año " + (year - 1) + ".");
        }
        Set<String> closed = accounting.closedPeriods(t);
        List<String> open = new ArrayList<>();
        for (int mo = 1; mo <= 12; mo++) {
            YearMonth m = YearMonth.of(year, mo);
            if (m.atEndOfMonth().isBefore(s.startDate)) continue;
            if (!closed.contains(m.toString())) open.add(m.toString());
        }
        if (!open.isEmpty()) throw ApiException.badRequest("Cierra primero los meses: " + String.join(", ", open) + ".");
        LocalDate end = LocalDate.of(year, 12, 31);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select l.account_code, coalesce(sum(l.debit - l.credit), 0)
                from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and e.entry_date <= :end and substr(l.account_code, 1, 1) in ('4', '5', '6', '7')
                group by l.account_code having sum(l.debit - l.credit) <> 0 order by l.account_code""")
                .setParameter("t", t).setParameter("end", end).getResultList();
        Draft d = new Draft(AccountingService.CIERRE, "YEAR_CLOSE", (long) year, end, "Cierre del año " + year);
        d.fixedDate = true;
        BigDecimal result = BigDecimal.ZERO;   // débitos − créditos de resultados: positivo = pérdida
        for (Object[] r : rows) {
            BigDecimal bal = (BigDecimal) r[1];
            result = result.add(bal);
            if (bal.signum() > 0) d.lines.add(new DraftLine((String) r[0], null, BigDecimal.ZERO, bal, null, null, "Cierre"));
            else d.lines.add(new DraftLine((String) r[0], null, bal.negate(), BigDecimal.ZERO, null, null, "Cierre"));
        }
        if (d.lines.isEmpty()) throw ApiException.badRequest("El año no tiene movimientos de resultados para cerrar.");
        if (result.signum() < 0) d.lines.add(new DraftLine(null, AccountingCatalog.PROFIT, BigDecimal.ZERO, result.negate(), null, null, "Utilidad del ejercicio " + year));
        else if (result.signum() > 0) d.lines.add(new DraftLine(null, AccountingCatalog.LOSS, result, BigDecimal.ZERO, null, null, "Pérdida del ejercicio " + year));
        List<String> missing = accounting.resolve(t, d);
        if (!missing.isEmpty()) throw ApiException.badRequest("Falta la cuenta en el mapa contable para: " + String.join(", ", missing));
        accounting.save(t, d);
        audit.sensitive("AÑO_CONTABLE_CERRADO", "TENANT", t, "Cerró el año contable " + year + " con " + (result.signum() <= 0 ? "utilidad" : "pérdida")
                + " de " + Money.fmt(result.abs()));
        return list();
    }

    private boolean hasResults(Long t, int year) {
        Number n = (Number) em.createNativeQuery("""
                select count(*) from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and extract(year from e.entry_date) = :y and substr(l.account_code, 1, 1) in ('4', '5', '6', '7')""")
                .setParameter("t", t).setParameter("y", year).getSingleResult();
        return n.longValue() > 0;
    }

    // =================================================================== Notas manuales

    public record NoteLine(@NotBlank(message = "elige la cuenta") String accountCode, @Size(max = 30) String thirdPartyId,
                           @Size(max = 160) String thirdPartyName, @DecimalMin("0") BigDecimal debit, @DecimalMin("0") BigDecimal credit,
                           @Size(max = 200) String description) {
    }

    public record NoteRequest(@NotNull LocalDate date, @NotBlank(message = "describe la nota") @Size(max = 300) String description,
                              @NotNull @Size(min = 2, message = "una nota lleva al menos dos líneas") List<@Valid NoteLine> lines) {
    }

    /** Nota contable manual (p. ej. saldos iniciales). Tiene que cuadrar y no puede caer en un mes cerrado. */
    @Transactional
    public AccountingService.EntryDto note(NoteRequest r) {
        CurrentUser.require(Permission.CONTABILIDAD_EDITAR);
        Long t = TenantContext.require();
        requireEnabled(t);
        if (r.date().isAfter(today())) throw ApiException.field("date", "no puede ser una fecha futura");
        if (accounting.closedPeriods(t).contains(YearMonth.from(r.date()).toString())) {
            throw ApiException.field("date", "el mes " + YearMonth.from(r.date()) + " está cerrado");
        }
        Set<String> active = new HashSet<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> {
            if (a.active) active.add(a.code);
        });
        Draft d = new Draft(AccountingService.NOTA, "MANUAL", counters.next(t, "NOTA_CONTABLE"), r.date(), r.description().trim());
        d.fixedDate = true;
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (NoteLine l : r.lines()) {
            BigDecimal dr = nz(l.debit()).setScale(2, RoundingMode.HALF_UP), cr = nz(l.credit()).setScale(2, RoundingMode.HALF_UP);
            if (dr.signum() > 0 && cr.signum() > 0) throw ApiException.field("lines", "cada línea va al débito o al crédito, no a ambos");
            if (dr.signum() == 0 && cr.signum() == 0) throw ApiException.field("lines", "hay una línea sin valor");
            if (!active.contains(l.accountCode().trim())) throw ApiException.field("lines", "la cuenta " + l.accountCode() + " no existe o está inactiva");
            debit = debit.add(dr);
            credit = credit.add(cr);
            d.lines.add(new DraftLine(l.accountCode().trim(), null, dr, cr, blank(l.thirdPartyId()), blank(l.thirdPartyName()), blank(l.description())));
        }
        if (debit.compareTo(credit) != 0) {
            throw ApiException.field("lines", "no cuadra: débitos " + Money.fmt(debit) + " y créditos " + Money.fmt(credit));
        }
        JournalEntry e = accounting.save(t, d);
        audit.sensitive("NOTA_CONTABLE", "JOURNAL", e.id, "Registró la nota contable #" + e.number + " por " + Money.fmt(debit) + ": " + d.description);
        return accounting.journal(e.entryDate, e.entryDate, 500).stream().filter(x -> x.id().equals(e.id)).findFirst().orElseThrow();
    }

    public record ReverseRequest(@NotBlank(message = "explica por qué se reversa") @Size(max = 300) String reason) {
    }

    /** Reversa una nota manual (con fecha de hoy o del siguiente mes abierto). */
    @Transactional
    public AccountingService.EntryDto reverseNote(Long entryId, ReverseRequest r) {
        CurrentUser.require(Permission.CONTABILIDAD_EDITAR);
        Long t = TenantContext.require();
        JournalEntry original = entries.findById(entryId).filter(e -> e.tenantId.equals(t)).orElseThrow(() -> ApiException.notFound("Comprobante no encontrado"));
        if (!AccountingService.NOTA.equals(original.entryType)) throw ApiException.badRequest("Solo las notas manuales se reversan a mano; lo demás se anula desde su operación.");
        if (entries.existsByTenantIdAndSourceTypeAndSourceId(t, "MANUAL_REV", original.id)) throw ApiException.badRequest("Esta nota ya fue reversada.");
        Draft d = new Draft(AccountingService.REVERSA, "MANUAL_REV", original.id, today(), "Reversa de la nota #" + original.number + ": " + r.reason().trim());
        d.reversalOf = original.id;
        for (JournalLine l : lines.findByEntryIdOrderByIdAsc(original.id)) {
            d.lines.add(new DraftLine(l.accountCode, null, l.credit, l.debit, l.thirdPartyId, l.thirdPartyName, "Reversa"));
        }
        JournalEntry e = accounting.save(t, d);
        audit.sensitive("NOTA_CONTABLE_REVERSADA", "JOURNAL", original.id, "Reversó la nota contable #" + original.number + ": " + r.reason().trim());
        return accounting.journal(e.entryDate, e.entryDate, 500).stream().filter(x -> x.id().equals(e.id)).findFirst().orElseThrow();
    }

    // =================================================================== Terceros

    public record ThirdPartyRow(String id, String name, String kind, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    /**
     * Terceros con movimiento en el período (clientes, proveedores y los de notas manuales), con sus totales. El saldo es
     * débitos − créditos de las cuentas por cobrar (13xx) y por pagar (22xx, 23xx, 28xx) a la fecha final.
     */
    @Transactional(readOnly = true)
    public List<ThirdPartyRow> thirdParties(LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select l.third_party_id, max(l.third_party_name),
                       coalesce(sum(case when e.entry_date between :f and :to then l.debit else 0 end), 0),
                       coalesce(sum(case when e.entry_date between :f and :to then l.credit else 0 end), 0),
                       coalesce(sum(case when substr(l.account_code, 1, 2) in ('13', '22', '23', '28') then l.debit - l.credit else 0 end), 0),
                       bool_or(substr(l.account_code, 1, 2) in ('22', '23')), bool_or(substr(l.account_code, 1, 2) = '13' or substr(l.account_code, 1, 1) = '4')
                from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and l.third_party_id is not null and e.entry_date <= :to
                group by l.third_party_id
                having sum(case when e.entry_date between :f and :to then 1 else 0 end) > 0
                    or sum(case when substr(l.account_code, 1, 2) in ('13', '22', '23', '28') then l.debit - l.credit else 0 end) <> 0
                order by 2""").setParameter("t", t).setParameter("f", from).setParameter("to", to).getResultList();
        List<ThirdPartyRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            boolean supplier = Boolean.TRUE.equals(r[5]), customer = Boolean.TRUE.equals(r[6]);
            out.add(new ThirdPartyRow((String) r[0], (String) r[1], supplier && customer ? "CLIENTE_PROVEEDOR" : supplier ? "PROVEEDOR" : customer ? "CLIENTE" : "OTRO",
                    (BigDecimal) r[2], (BigDecimal) r[3], (BigDecimal) r[4]));
        }
        return out;
    }

    public record ThirdPartyLine(LocalDate date, long number, String entryType, String accountCode, String description, BigDecimal debit,
                                 BigDecimal credit) {
    }

    /** Auxiliar de un tercero: todos sus movimientos del período. */
    @Transactional(readOnly = true)
    public List<ThirdPartyLine> thirdPartyLedger(String thirdId, LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select e.entry_date, e.number, e.entry_type, l.account_code, coalesce(l.description, e.description), l.debit, l.credit
                from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and l.third_party_id = :id and e.entry_date between :f and :to
                order by e.entry_date, e.number, l.id""").setParameter("t", t).setParameter("id", thirdId).setParameter("f", from)
                .setParameter("to", to).getResultList();
        return rows.stream().map(r -> new ThirdPartyLine(r[0] instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) r[0],
                ((Number) r[1]).longValue(), (String) r[2], (String) r[3], (String) r[4], (BigDecimal) r[5], (BigDecimal) r[6])).toList();
    }

    /** Terceros en CSV (identificación, nombre, tipo) para crearlos en el software del contador antes de importar comprobantes. */
    @Transactional(readOnly = true)
    public String thirdPartiesCsv(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("Identificación;Nombre;Tipo\r\n");
        for (ThirdPartyRow r : thirdParties(from, to)) {
            sb.append(AccountingService.csv(r.id())).append(';').append(AccountingService.csv(r.name())).append(';').append(r.kind()).append("\r\n");
        }
        return sb.toString();
    }

    // =================================================================== auxiliares

    private static AccountingPeriod.Key key(Long t, String period) {
        AccountingPeriod.Key k = new AccountingPeriod.Key();
        k.tenantId = t;
        k.period = period;
        return k;
    }

    private static YearMonth parse(String period) {
        try {
            return YearMonth.parse(period);
        } catch (Exception e) {
            throw ApiException.badRequest("Mes no válido: usa AAAA-MM");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
