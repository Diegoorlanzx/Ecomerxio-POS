package com.tiendaposs.accounting;

import com.tiendaposs.accounting.AccountingRepos.*;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.fiscal.FiscalCodes;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.Payment;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.pos.SaleReturn;
import com.tiendaposs.pos.SaleReturnLine;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Fase 14 (primer bloque): contabilidad automática de ventas, costo, devoluciones y anulaciones.
 * <ul>
 *   <li>Cada operación genera su comprobante en la MISMA transacción, cuadrado (débitos = créditos) y enlazado al origen.
 *       El índice único por origen impide contabilizar dos veces.</li>
 *   <li>Si falta una cuenta en el mapa, la operación va a "Pendientes por contabilizar" con el motivo: nunca se inventa
 *       una cuenta. Al completar el mapa se procesan los pendientes.</li>
 *   <li>Las ventas hechas sin internet se contabilizan con su fecha real.</li>
 * </ul>
 */
@Service
public class AccountingService {

    public static final String VENTA = "VENTA", DEVOLUCION = "DEVOLUCION", ANULACION = "ANULACION";
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final SettingsRepository settingsRepo;
    private final AccountRepository accounts;
    private final MapRepository map;
    private final EntryRepository entries;
    private final LineRepository lines;
    private final PendingRepository pending;
    private final SaleRepository sales;
    private final SaleLineRepository saleLines;
    private final PaymentRepository payments;
    private final SaleReturnRepository saleReturns;
    private final SaleReturnLineRepository returnLines;
    private final CustomerRepository customers;
    private final SettingsService storeSettings;
    private final Counters counters;
    private final AuditService audit;
    private final EntityManager em;

    public AccountingService(SettingsRepository settingsRepo, AccountRepository accounts, MapRepository map, EntryRepository entries,
                             LineRepository lines, PendingRepository pending, SaleRepository sales, SaleLineRepository saleLines,
                             PaymentRepository payments, SaleReturnRepository saleReturns, SaleReturnLineRepository returnLines,
                             CustomerRepository customers, SettingsService storeSettings, Counters counters, AuditService audit,
                             EntityManager em) {
        this.settingsRepo = settingsRepo;
        this.accounts = accounts;
        this.map = map;
        this.entries = entries;
        this.lines = lines;
        this.pending = pending;
        this.sales = sales;
        this.saleLines = saleLines;
        this.payments = payments;
        this.saleReturns = saleReturns;
        this.returnLines = returnLines;
        this.customers = customers;
        this.storeSettings = storeSettings;
        this.counters = counters;
        this.audit = audit;
        this.em = em;
    }

    // =================================================================== Activación

    public record Status(boolean enabled, LocalDate startDate, long entries, long pending) {
    }

    public Status status() {
        Long t = TenantContext.require();
        AccountingSettings s = settingsRepo.findById(t).orElse(null);
        return new Status(s != null && s.enabled, s == null ? null : s.startDate, entries.countByTenantId(t),
                pending.countByTenantIdAndResolvedAtIsNull(t));
    }

    /** Activa la contabilidad desde una fecha (por defecto hoy). La primera vez precarga el PUC y el mapa propuestos. */
    @Transactional
    public Status enable(boolean on, LocalDate startDate) {
        Long t = TenantContext.require();
        AccountingSettings s = settingsRepo.findById(t).orElseGet(() -> {
            AccountingSettings n = new AccountingSettings();
            n.tenantId = t;
            return n;
        });
        if (on) {
            LocalDate start = startDate == null ? LocalDate.now(ZONE) : startDate;
            if (start.isAfter(LocalDate.now(ZONE).plusDays(31))) throw ApiException.field("startDate", "no puede ser tan lejana");
            if (s.startDate != null && entries.countByTenantId(t) > 0 && !start.equals(s.startDate)) {
                throw ApiException.field("startDate", "ya hay comprobantes: la fecha de inicio no se puede cambiar");
            }
            s.startDate = start;
            seed(t);
        }
        s.enabled = on;
        settingsRepo.save(s);
        audit.sensitive(on ? "CONTABILIDAD_ACTIVADA" : "CONTABILIDAD_DESACTIVADA", "TENANT", t,
                (on ? "Activó" : "Desactivó") + " la contabilidad automática" + (on ? " desde el " + s.startDate : ""));
        return status();
    }

    private void seed(Long t) {
        if (accounts.findByTenantIdOrderByCodeAsc(t).isEmpty()) {
            for (AccountingCatalog.DefaultAccount a : AccountingCatalog.PUC) {
                Account acc = new Account();
                acc.tenantId = t;
                acc.code = a.code();
                acc.name = a.name();
                acc.nature = a.nature();
                accounts.save(acc);
            }
        }
        Set<String> existing = new HashSet<>();
        map.findByTenantId(t).forEach(m -> existing.add(m.mapKey));
        AccountingCatalog.defaultMap(storeSettings.forTenant(t).paymentMethodList()).forEach((k, v) -> {
            if (existing.contains(k)) return;
            MapEntry m = new MapEntry();
            m.tenantId = t;
            m.mapKey = k;
            m.accountCode = v;
            map.save(m);
        });
    }

    private boolean active(Long t, LocalDate date) {
        AccountingSettings s = settingsRepo.findById(t).orElse(null);
        return s != null && s.enabled && s.startDate != null && !date.isBefore(s.startDate);
    }

    // =================================================================== Ganchos desde el POS

    /** Venta cobrada: ingreso, impuestos y medios de pago; y el costo contra el inventario. */
    public void onSaleCompleted(Sale s) {
        LocalDate date = LocalDate.ofInstant(s.completedAt == null ? Instant.now() : s.completedAt, ZONE);
        if (!active(s.tenantId, date)) return;
        post(s.tenantId, "SALE", s.id, date, () -> buildSale(s, date));
    }

    /** Venta anulada: reversa exacta del comprobante original (con fecha de hoy). */
    public void onSaleVoided(Sale s) {
        Long t = s.tenantId;
        JournalEntry original = entries.findByTenantIdAndSourceTypeAndSourceIdAndEntryType(t, "SALE", s.id, VENTA).orElse(null);
        if (original == null) {
            // Nunca se contabilizó (estaba pendiente o es anterior a la fecha de inicio): no hay nada que reversar.
            pending.findFirstByTenantIdAndSourceTypeAndSourceIdAndResolvedAtIsNull(t, "SALE", s.id).ifPresent(p -> {
                p.resolvedAt = Instant.now();
                p.reason = p.reason + " (la venta se anuló: ya no hace falta)";
                pending.save(p);
            });
            return;
        }
        LocalDate today = LocalDate.now(ZONE);
        Draft d = new Draft(ANULACION, "VOID", s.id, today, "Anulación de la venta #" + s.number + ": " + s.voidReason);
        d.reversalOf = original.id;
        for (JournalLine l : lines.findByEntryIdOrderByIdAsc(original.id)) {
            d.lines.add(new DraftLine(l.accountCode, null, l.credit, l.debit, l.thirdPartyId, l.thirdPartyName, "Reversa: " + nz(l.description)));
        }
        save(t, d);
    }

    /** Devolución: devoluciones en ventas e impuesto contra el medio de reembolso; el costo vuelve al inventario. */
    public void onReturn(Sale s, SaleReturn r) {
        LocalDate date = LocalDate.ofInstant(r.createdAt, ZONE);
        if (!active(s.tenantId, date)) return;
        post(s.tenantId, "RETURN", r.id, date, () -> buildReturn(s, r, date));
    }

    // =================================================================== Armado de comprobantes

    /** Un comprobante en construcción; las cuentas se resuelven al final (si falta alguna, va a pendientes). */
    private static final class Draft {
        final String type, sourceType, description;
        final Long sourceId;
        final LocalDate date;
        Long reversalOf;
        final List<DraftLine> lines = new ArrayList<>();

        Draft(String type, String sourceType, Long sourceId, LocalDate date, String description) {
            this.type = type;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.date = date;
            this.description = description.length() > 300 ? description.substring(0, 300) : description;
        }
    }

    /** Una línea con una llave del mapa (mapKey) o una cuenta ya resuelta (accountCode). */
    private record DraftLine(String accountCode, String mapKey, BigDecimal debit, BigDecimal credit, String thirdId,
                             String thirdName, String description) {
    }

    private Draft buildSale(Sale s, LocalDate date) {
        Draft d = new Draft(VENTA, "SALE", s.id, date, "Venta #" + s.number);
        String[] third = third(s);
        for (Payment p : payments.findBySaleIdOrderByIdAsc(s.id)) {
            if ("ANULADO".equals(p.status) || p.amount == null || p.amount.signum() == 0) continue;
            d.lines.add(new DraftLine(null, AccountingCatalog.payKey(p.method), p.amount, BigDecimal.ZERO, third[0], third[1],
                    "Cobro " + p.method.toLowerCase()));
        }
        Map<String, BigDecimal> taxes = new LinkedHashMap<>();
        for (SaleLine l : saleLines.findBySaleIdOrderBySortOrderAscIdAsc(s.id)) {
            if (l.tax != null && l.tax.signum() != 0) taxes.merge(AccountingCatalog.taxKey(l.taxName), l.tax, BigDecimal::add);
        }
        BigDecimal taxTotal = taxes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        d.lines.add(new DraftLine(null, AccountingCatalog.SALES, BigDecimal.ZERO, s.total.subtract(taxTotal), third[0], third[1], "Ingreso por ventas"));
        taxes.forEach((k, v) -> d.lines.add(new DraftLine(null, k, BigDecimal.ZERO, v, third[0], third[1], "Impuesto " + k.substring(4))));
        if (s.costTotal != null && s.costTotal.signum() > 0) {
            d.lines.add(new DraftLine(null, AccountingCatalog.COST_OF_SALES, s.costTotal, BigDecimal.ZERO, null, null, "Costo de lo vendido"));
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY, BigDecimal.ZERO, s.costTotal, null, null, "Salida de inventario"));
        }
        return d;
    }

    private Draft buildReturn(Sale s, SaleReturn r, LocalDate date) {
        Draft d = new Draft(DEVOLUCION, "RETURN", r.id, date, "Devolución #" + r.number + " de la venta #" + s.number);
        String[] third = third(s);
        Map<Long, SaleLine> byId = new HashMap<>();
        saleLines.findBySaleIdOrderBySortOrderAscIdAsc(s.id).forEach(l -> byId.put(l.id, l));
        BigDecimal amount = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        Map<String, BigDecimal> taxes = new LinkedHashMap<>();
        for (SaleReturnLine rl : returnLines.findByReturnIdIn(List.of(r.id))) {
            SaleLine l = byId.get(rl.saleLineId);
            if (l == null) continue;
            amount = amount.add(rl.amount);
            if (l.tax != null && l.tax.signum() != 0 && l.lineTotal != null && l.lineTotal.signum() != 0) {
                taxes.merge(AccountingCatalog.taxKey(l.taxName), l.tax.multiply(rl.amount).divide(l.lineTotal, 2, RoundingMode.HALF_UP), BigDecimal::add);
            }
            if (rl.restock && l.unitCost != null) cost = cost.add(l.unitCost.multiply(rl.quantity).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal taxTotal = taxes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        d.lines.add(new DraftLine(null, AccountingCatalog.SALES_RETURNS, amount.subtract(taxTotal), BigDecimal.ZERO, third[0], third[1], "Devolución en ventas"));
        taxes.forEach((k, v) -> d.lines.add(new DraftLine(null, k, v, BigDecimal.ZERO, third[0], third[1], "Reversa impuesto " + k.substring(4))));
        d.lines.add(new DraftLine(null, AccountingCatalog.payKey(r.refundMethod), BigDecimal.ZERO, amount, third[0], third[1],
                "Reembolso " + r.refundMethod.toLowerCase()));
        if (cost.signum() > 0) {
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY, cost, BigDecimal.ZERO, null, null, "Reingreso al inventario"));
            d.lines.add(new DraftLine(null, AccountingCatalog.COST_OF_SALES, BigDecimal.ZERO, cost, null, null, "Menor costo de ventas"));
        }
        return d;
    }

    /** Tercero: el cliente identificado o "Consumidor final". */
    private String[] third(Sale s) {
        Customer c = s.customerId == null ? null : customers.findById(s.customerId).orElse(null);
        if (c != null && c.docNumber != null) return new String[]{c.docNumber, c.name};
        return new String[]{FiscalCodes.FINAL_CONSUMER_ID, "Consumidor final"};
    }

    // =================================================================== Guardar o dejar pendiente

    private void post(Long t, String sourceType, Long sourceId, LocalDate date, java.util.function.Supplier<Draft> builder) {
        String type = "RETURN".equals(sourceType) ? DEVOLUCION : VENTA;
        if (entries.findByTenantIdAndSourceTypeAndSourceIdAndEntryType(t, sourceType, sourceId, type).isPresent()) return;
        Draft d = builder.get();
        List<String> missing = resolve(t, d);
        if (!missing.isEmpty()) {
            PendingEntry p = pending.findFirstByTenantIdAndSourceTypeAndSourceIdAndResolvedAtIsNull(t, sourceType, sourceId).orElseGet(PendingEntry::new);
            p.tenantId = t;
            p.sourceType = sourceType;
            p.sourceId = sourceId;
            p.entryDate = date;
            p.description = d.description;
            p.reason = "Falta la cuenta en el mapa contable para: " + String.join(", ", missing);
            pending.save(p);
            return;
        }
        save(t, d);
        pending.findFirstByTenantIdAndSourceTypeAndSourceIdAndResolvedAtIsNull(t, sourceType, sourceId).ifPresent(p -> {
            p.resolvedAt = Instant.now();
            pending.save(p);
        });
    }

    /** Cambia cada llave del mapa por su cuenta; devuelve lo que falta (vacío = todo resuelto). */
    private List<String> resolve(Long t, Draft d) {
        Map<String, String> m = new HashMap<>();
        map.findByTenantId(t).forEach(e -> {
            if (e.accountCode != null) m.put(e.mapKey, e.accountCode);
        });
        Set<String> activeCodes = new HashSet<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> {
            if (a.active) activeCodes.add(a.code);
        });
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < d.lines.size(); i++) {
            DraftLine l = d.lines.get(i);
            if (l.accountCode() != null) continue;
            String code = m.get(l.mapKey());
            if (code == null || !activeCodes.contains(code)) {
                String label = AccountingCatalog.label(l.mapKey());
                if (!missing.contains(label)) missing.add(label);
                continue;
            }
            d.lines.set(i, new DraftLine(code, l.mapKey(), l.debit(), l.credit(), l.thirdId(), l.thirdName(), l.description()));
        }
        return missing;
    }

    private void save(Long t, Draft d) {
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (DraftLine l : d.lines) {
            debit = debit.add(l.debit());
            credit = credit.add(l.credit());
        }
        if (debit.compareTo(credit) != 0) {
            // Nunca debería pasar: si pasa, es un error del motor y no se guarda un comprobante descuadrado.
            throw new IllegalStateException("Comprobante descuadrado (" + d.description + "): débitos " + debit + " ≠ créditos " + credit);
        }
        JournalEntry e = new JournalEntry();
        e.tenantId = t;
        e.number = counters.next(t, "COMPROBANTE");
        e.entryType = d.type;
        e.entryDate = d.date;
        e.sourceType = d.sourceType;
        e.sourceId = d.sourceId;
        e.reversalOf = d.reversalOf;
        e.description = d.description;
        e.total = debit;
        entries.save(e);
        for (DraftLine l : d.lines) {
            if (l.debit().signum() == 0 && l.credit().signum() == 0) continue;
            JournalLine jl = new JournalLine();
            jl.tenantId = t;
            jl.entryId = e.id;
            jl.accountCode = l.accountCode();
            jl.thirdPartyId = l.thirdId();
            jl.thirdPartyName = l.thirdName();
            jl.debit = l.debit().setScale(2, RoundingMode.HALF_UP);
            jl.credit = l.credit().setScale(2, RoundingMode.HALF_UP);
            jl.description = l.description() == null ? null : l.description().length() > 200 ? l.description().substring(0, 200) : l.description();
            lines.save(jl);
        }
    }

    /** Vuelve a intentar los pendientes (después de completar el mapa contable). */
    @Transactional
    public Map<String, Integer> processPending() {
        Long t = TenantContext.require();
        int done = 0, still = 0;
        for (PendingEntry p : pending.findByTenantIdAndResolvedAtIsNullOrderByEntryDateAscIdAsc(t)) {
            if ("SALE".equals(p.sourceType)) {
                Sale s = sales.findById(p.sourceId).orElse(null);
                if (s == null || "ANULADA".equals(s.status)) {
                    p.resolvedAt = Instant.now();
                    pending.save(p);
                    continue;
                }
                post(t, "SALE", s.id, p.entryDate, () -> buildSale(s, p.entryDate));
            } else if ("RETURN".equals(p.sourceType)) {
                SaleReturn r = saleReturns.findById(p.sourceId).orElse(null);
                Sale s = r == null ? null : sales.findById(r.saleId).orElse(null);
                if (r == null || s == null) continue;
                post(t, "RETURN", r.id, p.entryDate, () -> buildReturn(s, r, p.entryDate));
            }
            if (pending.findById(p.id).map(x -> x.resolvedAt != null).orElse(false)) done++;
            else still++;
        }
        return Map.of("posted", done, "stillPending", still);
    }

    // =================================================================== Plan de cuentas y mapa

    public record AccountDto(Long id, String code, String name, String nature, boolean active, boolean hasMovements) {
    }

    public List<AccountDto> accounts() {
        Long t = TenantContext.require();
        return accounts.findByTenantIdOrderByCodeAsc(t).stream()
                .map(a -> new AccountDto(a.id, a.code, a.name, a.nature, a.active, lines.hasMovements(t, a.code))).toList();
    }

    public record AccountRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]{3,11}", message = "de 4 a 12 dígitos, sin empezar en 0") String code,
                                 @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String name,
                                 @NotBlank @Pattern(regexp = "DEBITO|CREDITO", message = "naturaleza débito o crédito") String nature,
                                 Boolean active) {
    }

    @Transactional
    public AccountDto saveAccount(Long id, AccountRequest r) {
        Long t = TenantContext.require();
        Account a = id == null ? new Account() : accounts.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Cuenta no encontrada"));
        boolean moved = a.code != null && lines.hasMovements(t, a.code);
        if (moved && !a.code.equals(r.code())) throw ApiException.field("code", "esta cuenta ya tiene movimientos: el código no se cambia");
        accounts.findByTenantIdAndCode(t, r.code()).filter(o -> !o.id.equals(a.id)).ifPresent(o -> {
            throw ApiException.field("code", "ya existe la cuenta " + o.code + " " + o.name);
        });
        a.tenantId = t;
        a.code = r.code();
        a.name = r.name().trim();
        a.nature = r.nature();
        if (r.active() != null) a.active = r.active();
        accounts.save(a);
        audit.log(id == null ? "CUENTA_CREADA" : "CUENTA_EDITADA", "ACCOUNT", a.id, (id == null ? "Creó" : "Editó") + " la cuenta " + a.code + " " + a.name);
        return new AccountDto(a.id, a.code, a.name, a.nature, a.active, moved);
    }

    public record MapRow(String key, String label, String accountCode, String accountName) {
    }

    /** El mapa con todas las llaves que hoy se usan: las propuestas, los medios de pago habilitados y los impuestos vistos. */
    public List<MapRow> mapRows() {
        Long t = TenantContext.require();
        Map<String, String> current = new LinkedHashMap<>();
        AccountingCatalog.defaultMap(storeSettings.forTenant(t).paymentMethodList()).keySet().forEach(k -> current.put(k, null));
        map.findByTenantId(t).forEach(m -> current.put(m.mapKey, m.accountCode));
        @SuppressWarnings("unchecked")
        List<String> taxNames = em.createNativeQuery("select distinct upper(coalesce(tax_name, 'IVA')) from sale_lines where tenant_id = :t and tax <> 0")
                .setParameter("t", t).getResultList();
        taxNames.forEach(n -> current.putIfAbsent(AccountingCatalog.taxKey(n), null));
        Map<String, String> names = new HashMap<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> names.put(a.code, a.name));
        return current.entrySet().stream()
                .map(e -> new MapRow(e.getKey(), AccountingCatalog.label(e.getKey()), e.getValue(), e.getValue() == null ? null : names.get(e.getValue())))
                .toList();
    }

    @Transactional
    public List<MapRow> saveMap(Map<String, String> changes) {
        Long t = TenantContext.require();
        for (var e : changes.entrySet()) {
            String key = e.getKey();
            if (!(key.startsWith("PAY:") || key.startsWith("TAX:") || Set.of(AccountingCatalog.SALES, AccountingCatalog.SALES_RETURNS,
                    AccountingCatalog.COST_OF_SALES, AccountingCatalog.INVENTORY).contains(key))) {
                throw ApiException.badRequest("Llave del mapa no válida: " + key);
            }
            String code = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue().trim();
            if (code != null && accounts.findByTenantIdAndCode(t, code).filter(a -> a.active).isEmpty()) {
                throw ApiException.badRequest("La cuenta " + code + " no existe o está inactiva (" + AccountingCatalog.label(key) + ")");
            }
            MapEntry m = new MapEntry();
            m.tenantId = t;
            m.mapKey = key;
            m.accountCode = code;
            map.save(m);
        }
        audit.log("MAPA_CONTABLE", "TENANT", t, "Actualizó el mapa contable (" + changes.size() + " cuenta(s))");
        return mapRows();
    }

    // =================================================================== Informes

    public record TrialRow(String code, String name, String nature, BigDecimal opening, BigDecimal debit, BigDecimal credit, BigDecimal closing) {
    }

    public record TrialBalance(LocalDate from, LocalDate to, List<TrialRow> rows, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {
    }

    /** Balance de prueba: saldo inicial, débitos, créditos y saldo final por cuenta. Débitos totales = créditos totales. */
    public TrialBalance trialBalance(LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select l.account_code,
                       coalesce(sum(case when e.entry_date < :from then l.debit - l.credit else 0 end), 0) as opening,
                       coalesce(sum(case when e.entry_date between :from and :to then l.debit else 0 end), 0) as debit,
                       coalesce(sum(case when e.entry_date between :from and :to then l.credit else 0 end), 0) as credit
                from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and e.entry_date <= :to
                group by l.account_code order by l.account_code
                """).setParameter("t", t).setParameter("from", from).setParameter("to", to).getResultList();
        Map<String, Account> byCode = new HashMap<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> byCode.put(a.code, a));
        List<TrialRow> out = new ArrayList<>();
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        for (Object[] r : rows) {
            String code = (String) r[0];
            Account a = byCode.get(code);
            String nature = a == null ? "DEBITO" : a.nature;
            BigDecimal openingDebitMinusCredit = (BigDecimal) r[1], debit = (BigDecimal) r[2], credit = (BigDecimal) r[3];
            BigDecimal sign = "DEBITO".equals(nature) ? BigDecimal.ONE : BigDecimal.ONE.negate();
            BigDecimal opening = openingDebitMinusCredit.multiply(sign);
            BigDecimal closing = openingDebitMinusCredit.add(debit).subtract(credit).multiply(sign);
            out.add(new TrialRow(code, a == null ? "(cuenta inexistente)" : a.name, nature, opening, debit, credit, closing));
            td = td.add(debit);
            tc = tc.add(credit);
        }
        return new TrialBalance(from, to, out, td, tc, td.compareTo(tc) == 0);
    }

    public record LineDto(String accountCode, String accountName, String thirdPartyId, String thirdPartyName, BigDecimal debit, BigDecimal credit, String description) {
    }

    public record EntryDto(Long id, long number, String entryType, LocalDate entryDate, String sourceType, Long sourceId, Long saleNumber,
                           String description, BigDecimal total, Long reversalOf, List<LineDto> lines) {
    }

    /** Libro diario del período (los más recientes primero). */
    public List<EntryDto> journal(LocalDate from, LocalDate to, int limit) {
        Long t = TenantContext.require();
        List<JournalEntry> list = entries.between(t, from, to, org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
        Map<String, String> names = new HashMap<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> names.put(a.code, a.name));
        Map<Long, List<LineDto>> byEntry = new HashMap<>();
        lines.findByEntryIdIn(list.stream().map(e -> e.id).toList()).forEach(l -> byEntry.computeIfAbsent(l.entryId, k -> new ArrayList<>())
                .add(new LineDto(l.accountCode, names.get(l.accountCode), l.thirdPartyId, l.thirdPartyName, l.debit, l.credit, l.description)));
        return list.stream().map(e -> {
            Long saleNumber = null;
            if ("SALE".equals(e.sourceType) || "VOID".equals(e.sourceType)) saleNumber = sales.findById(e.sourceId).map(s -> s.number).orElse(null);
            else if ("RETURN".equals(e.sourceType)) saleNumber = saleReturns.findById(e.sourceId).flatMap(r -> sales.findById(r.saleId)).map(s -> s.number).orElse(null);
            return new EntryDto(e.id, e.number, e.entryType, e.entryDate, e.sourceType, e.sourceId, saleNumber, e.description, e.total,
                    e.reversalOf, byEntry.getOrDefault(e.id, List.of()));
        }).toList();
    }

    public record PendingDto(Long id, String sourceType, Long sourceId, LocalDate entryDate, String description, String reason) {
    }

    public List<PendingDto> pendingList() {
        return pending.findByTenantIdAndResolvedAtIsNullOrderByEntryDateAscIdAsc(TenantContext.require()).stream()
                .map(p -> new PendingDto(p.id, p.sourceType, p.sourceId, p.entryDate, p.description, p.reason)).toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
