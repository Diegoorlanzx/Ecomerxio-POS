package com.tiendaposs.finance;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.finance.FinanceRepos.*;
import com.tiendaposs.pos.CashMovement;
import com.tiendaposs.pos.PosRepos.CashMovementRepository;
import com.tiendaposs.pos.Shift;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.purchasing.GoodsReceipt;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptRepository;
import com.tiendaposs.purchasing.PurchasingRepos.SupplierRepository;
import com.tiendaposs.purchasing.Supplier;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gastos, ingresos no operativos, cuentas por pagar a proveedores y resultados (utilidad bruta y
 * neta estimada, flujo de caja, reporte mensual). Lo que se paga o recibe "de la caja" queda
 * también como movimiento del turno, así el arqueo y las finanzas siempre cuadran.
 */
@Service
public class FinanceService {

    /** Fase 14: comprobante contable de cada operación. */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.tiendaposs.accounting.AccountingService accounting;

    private final FinanceCategoryRepository categories;
    private final FinanceEntryRepository entries;
    private final SupplierPaymentRepository supplierPayments;
    private final SupplierPaymentAllocationRepository allocations;
    private final GoodsReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final CashMovementRepository cashMovements;
    private final ShiftService shifts;
    private final CreditService credit;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final EntityManager em;

    public FinanceService(FinanceCategoryRepository categories, FinanceEntryRepository entries, SupplierPaymentRepository supplierPayments,
                          SupplierPaymentAllocationRepository allocations, GoodsReceiptRepository receipts, SupplierRepository suppliers,
                          CashMovementRepository cashMovements, ShiftService shifts, CreditService credit, SettingsService settings,
                          TenantRepository tenants, AuditService audit, EntityManager em) {
        this.categories = categories;
        this.entries = entries;
        this.supplierPayments = supplierPayments;
        this.allocations = allocations;
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.cashMovements = cashMovements;
        this.shifts = shifts;
        this.credit = credit;
        this.settings = settings;
        this.tenants = tenants;
        this.audit = audit;
        this.em = em;
    }

    // ---------------- Categorías ----------------

    public record CategoryDto(Long id, String kind, String name, boolean fixed, boolean active) {
        static CategoryDto of(FinanceCategory c) {
            return new CategoryDto(c.id, c.kind, c.name, c.fixed, c.active);
        }
    }

    public record CategoryRequest(@NotBlank @Pattern(regexp = "GASTO|INGRESO") String kind, @NotBlank @Size(max = 80) String name, boolean fixed) {
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        return categories.findByTenantIdOrderByKindAscNameAsc(TenantContext.require()).stream().map(CategoryDto::of).toList();
    }

    @Transactional
    public CategoryDto createCategory(CategoryRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        if (categories.findByName(t, r.kind(), r.name().trim()).isPresent()) throw ApiException.field("name", "ya existe esa categoría");
        FinanceCategory c = new FinanceCategory();
        c.tenantId = t;
        c.kind = r.kind();
        c.name = r.name().trim();
        c.fixed = r.fixed();
        return CategoryDto.of(categories.save(c));
    }

    /** Categorías iniciales para una tienda nueva. */
    public void seed(Long tenantId) {
        String[][] defs = {{"GASTO", "Arriendo", "1"}, {"GASTO", "Servicios públicos", "1"}, {"GASTO", "Internet y teléfono", "1"}, {"GASTO", "Nómina", "1"},
                {"GASTO", "Transporte", "0"}, {"GASTO", "Insumos", "0"}, {"GASTO", "Mantenimiento", "0"}, {"GASTO", "Impuestos", "0"},
                {"GASTO", "Otros gastos", "0"}, {"INGRESO", "Aporte del dueño", "0"}, {"INGRESO", "Préstamo", "0"}, {"INGRESO", "Otros ingresos", "0"}};
        for (String[] d : defs) {
            FinanceCategory c = new FinanceCategory();
            c.tenantId = tenantId;
            c.kind = d[0];
            c.name = d[1];
            c.fixed = d[2].equals("1");
            categories.save(c);
        }
    }

    // ---------------- Gastos e ingresos ----------------

    public record EntryRequest(@NotBlank @Pattern(regexp = "GASTO|INGRESO", message = "tipo no válido") String kind, Long categoryId,
                               @NotBlank(message = "describe el movimiento") @Size(max = 300) String description,
                               @NotNull @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                               LocalDate entryDate, @NotBlank String method,
                               @NotBlank @Pattern(regexp = "CAJA|BANCO|OTRO", message = "elige de dónde salió el dinero") String paidFrom,
                               @Size(max = 80) String reference) {
    }

    public record EntryDto(Long id, String kind, Long categoryId, String categoryName, String description, BigDecimal amount,
                           LocalDate entryDate, String method, String paidFrom, String reference, boolean fromCashRegister,
                           String createdBy, Instant createdAt, boolean voided, String voidedBy) {
    }

    @Transactional(readOnly = true)
    public List<EntryDto> entries(LocalDate from, LocalDate to, String kind, Long categoryId) {
        Long t = TenantContext.require();
        Map<Long, String> names = categories.findByTenantIdOrderByKindAscNameAsc(t).stream().collect(Collectors.toMap(c -> c.id, c -> c.name));
        return entries.search(t, from, to, blank(kind), categoryId).stream().map(e -> toDto(e, names)).toList();
    }

    @Transactional
    public EntryDto createEntry(EntryRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        FinanceCategory c = r.categoryId() == null ? defaultCategory(t, r.kind())
                : categories.findByIdAndTenantId(r.categoryId(), t).orElseThrow(() -> ApiException.field("categoryId", "la categoría no existe"));
        if (!c.kind.equals(r.kind())) throw ApiException.field("categoryId", "la categoría no corresponde al tipo");
        FinanceEntry e = new FinanceEntry();
        e.tenantId = t;
        e.kind = r.kind();
        e.categoryId = c.id;
        e.description = r.description().trim();
        e.amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        e.entryDate = r.entryDate() == null ? today(t) : r.entryDate();
        e.method = r.method().trim().toUpperCase();
        e.paidFrom = r.paidFrom();
        e.reference = blank(r.reference());
        e.createdBy = me.fullName();
        if ("CAJA".equals(r.paidFrom())) {
            Shift s = shifts.openShiftOfCurrentUser().orElseThrow(() -> ApiException.conflict("Abre tu turno para registrar dinero de la caja"));
            if ("GASTO".equals(r.kind()) && e.amount.compareTo(shifts.summary(s).expectedCash()) > 0) {
                throw ApiException.field("amount", "en caja solo debería haber " + Money.fmt(shifts.summary(s).expectedCash()));
            }
            e.method = "EFECTIVO";
            e.entryDate = today(t);
            CashMovement m = new CashMovement();
            m.tenantId = t;
            m.shiftId = s.id;
            m.type = r.kind();
            m.amount = e.amount;
            m.reason = e.description;
            m.category = c.name;
            m.userId = me.userId();
            m.userName = me.fullName();
            e.cashMovementId = cashMovements.save(m).id;
        }
        e = entries.save(e);
        accounting.onFinanceEntry(e);
        audit.log(e.kind.equals("GASTO") ? "GASTO" : "INGRESO", "FINANCE", e.id, (e.kind.equals("GASTO") ? "Registró un gasto de " : "Registró un ingreso de ")
                + Money.fmt(e.amount) + " (" + c.name + "): " + e.description);
        return toDto(e, Map.of(c.id, c.name));
    }

    /** Lo llama la caja: un gasto o ingreso del turno también queda en finanzas. */
    public void fromCashMovement(CashMovement m) {
        if (!"GASTO".equals(m.type) && !"INGRESO".equals(m.type)) return;
        FinanceCategory c = m.category == null ? null : categories.findByName(m.tenantId, m.type, m.category).orElse(null);
        if (c == null) c = defaultCategory(m.tenantId, m.type);
        FinanceEntry e = new FinanceEntry();
        e.tenantId = m.tenantId;
        e.kind = m.type;
        e.categoryId = c.id;
        e.description = m.reason;
        e.amount = m.amount;
        e.entryDate = today(m.tenantId);
        e.method = "EFECTIVO";
        e.paidFrom = "CAJA";
        e.cashMovementId = m.id;
        e.createdBy = m.userName;
        entries.save(e);
        accounting.onFinanceEntry(e);
    }

    @Transactional
    public EntryDto voidEntry(Long id, String reason) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        FinanceEntry e = entries.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("No encontrado"));
        if (e.voidedAt != null) throw ApiException.badRequest("Ya está anulado");
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "explica por qué se anula");
        if (e.cashMovementId != null) {
            // Devuelve el efectivo al turno abierto de quien anula (el movimiento original no se borra).
            Shift s = shifts.openShiftOfCurrentUser().orElseThrow(() -> ApiException.conflict("Abre tu turno para anular un movimiento de caja"));
            CashMovement m = new CashMovement();
            m.tenantId = t;
            m.shiftId = s.id;
            m.type = e.kind.equals("GASTO") ? "AJUSTE_ENTRADA" : "AJUSTE_SALIDA";
            m.amount = e.amount;
            m.reason = "Anulación: " + e.description + " (" + reason.trim() + ")";
            m.userId = CurrentUser.get().userId();
            m.userName = CurrentUser.get().fullName();
            m.authorizedBy = CurrentUser.get().fullName();
            cashMovements.save(m);
        }
        e.voidedAt = Instant.now();
        e.voidedBy = CurrentUser.get().fullName();
        accounting.onFinanceEntryVoided(e);
        audit.sensitive("FINANZAS_ANULADO", "FINANCE", e.id, "Anuló " + e.kind.toLowerCase() + " de " + Money.fmt(e.amount) + ": " + e.description + ". Motivo: " + reason.trim());
        return toDto(e, Map.of());
    }

    private FinanceCategory defaultCategory(Long t, String kind) {
        String name = "GASTO".equals(kind) ? "Otros gastos" : "Otros ingresos";
        return categories.findByName(t, kind, name).orElseGet(() -> {
            FinanceCategory c = new FinanceCategory();
            c.tenantId = t;
            c.kind = kind;
            c.name = name;
            return categories.save(c);
        });
    }

    private EntryDto toDto(FinanceEntry e, Map<Long, String> names) {
        String cat = names.containsKey(e.categoryId) ? names.get(e.categoryId)
                : e.categoryId == null ? null : categories.findById(e.categoryId).map(c -> c.name).orElse(null);
        return new EntryDto(e.id, e.kind, e.categoryId, cat, e.description, e.amount, e.entryDate, e.method, e.paidFrom, e.reference,
                e.cashMovementId != null, e.createdBy, e.createdAt, e.voidedAt != null, e.voidedBy);
    }

    // ---------------- Cuentas por pagar ----------------

    public record PayableDto(Long receiptId, long receiptNumber, Long supplierId, String supplierName, String invoiceNumber,
                             BigDecimal total, BigDecimal paid, BigDecimal balance, LocalDate dueDate, long daysToDue, Instant receivedAt,
                             /** Retenciones practicadas (no se le pagan al proveedor). */ BigDecimal withheld) {
    }

    public record SupplierBalance(Long supplierId, String supplierName, BigDecimal balance, BigDecimal overdue, LocalDate nextDue, int invoices) {
    }

    public record Payables(BigDecimal total, BigDecimal overdue, BigDecimal dueThisWeek, List<SupplierBalance> bySupplier, List<PayableDto> invoices) {
    }

    @Transactional(readOnly = true)
    public Payables payables(Long supplierId) {
        Long t = TenantContext.require();
        LocalDate today = today(t);
        Map<Long, String> names = suppliers.findByTenantIdOrderByNameAsc(t).stream().collect(Collectors.toMap(s -> s.id, s -> s.name));
        List<PayableDto> list = receipts.findByTenantIdOrderByReceivedAtDesc(t).stream()
                .filter(r -> r.payable().signum() > 0 && (supplierId == null || r.supplierId.equals(supplierId)))
                .map(r -> new PayableDto(r.id, r.number, r.supplierId, names.get(r.supplierId), r.invoiceNumber, r.total, r.paidAmount,
                        r.payable(), r.dueDate, r.dueDate == null ? 0 : ChronoUnit.DAYS.between(today, r.dueDate), r.receivedAt, r.withheld))
                .sorted(Comparator.comparing(PayableDto::daysToDue)).toList();
        Map<Long, List<PayableDto>> bySup = list.stream().collect(Collectors.groupingBy(PayableDto::supplierId));
        List<SupplierBalance> sb = bySup.entrySet().stream().map(e -> new SupplierBalance(e.getKey(), names.get(e.getKey()),
                        e.getValue().stream().map(PayableDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().stream().filter(p -> p.daysToDue() < 0).map(PayableDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().stream().map(PayableDto::dueDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                        e.getValue().size()))
                .sorted(Comparator.comparing(SupplierBalance::balance).reversed()).toList();
        BigDecimal total = list.stream().map(PayableDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdue = list.stream().filter(p -> p.daysToDue() < 0).map(PayableDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal week = list.stream().filter(p -> p.daysToDue() >= 0 && p.daysToDue() <= 7).map(PayableDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Payables(total, overdue, week, sb, list);
    }

    public record SupplierPaymentRequest(@NotNull Long supplierId, @NotNull @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                                         @NotBlank String method, @NotBlank @Pattern(regexp = "CAJA|BANCO|OTRO") String paidFrom,
                                         @Size(max = 80) String reference, @Size(max = 300) String note, List<Long> receiptIds) {
    }

    public record SupplierPaymentDto(Long id, Long supplierId, String supplierName, BigDecimal amount, String method, String paidFrom,
                                     String reference, String note, LocalDate paidOn, String userName, List<String> appliedTo) {
    }

    /** Paga a un proveedor: se aplica primero a las facturas elegidas y luego a las que vencen antes. */
    @Transactional
    public SupplierPaymentDto paySupplier(SupplierPaymentRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Supplier s = suppliers.findByIdAndTenantId(r.supplierId(), t).orElseThrow(() -> ApiException.field("supplierId", "el proveedor no existe"));
        List<GoodsReceipt> open = new ArrayList<>(receipts.findBySupplierIdOrderByReceivedAtDesc(s.id).stream()
                .filter(x -> x.payable().signum() > 0).toList());
        BigDecimal balance = open.stream().map(GoodsReceipt::payable).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(balance) > 0) throw ApiException.field("amount", "a " + s.name + " solo le debes " + Money.fmt(balance));
        List<Long> preferred = r.receiptIds() == null ? List.of() : r.receiptIds();
        open.sort(Comparator.comparing((GoodsReceipt x) -> !preferred.contains(x.id)).thenComparing(x -> x.dueDate == null ? LocalDate.MAX : x.dueDate));
        SupplierPayment p = new SupplierPayment();
        p.tenantId = t;
        p.supplierId = s.id;
        p.amount = amount;
        p.method = r.method().trim().toUpperCase();
        p.paidFrom = r.paidFrom();
        p.reference = blank(r.reference());
        p.note = blank(r.note());
        p.paidOn = today(t);
        p.userName = me.fullName();
        if ("CAJA".equals(r.paidFrom())) {
            Shift sh = shifts.openShiftOfCurrentUser().orElseThrow(() -> ApiException.conflict("Abre tu turno para pagar con dinero de la caja"));
            if (amount.compareTo(shifts.summary(sh).expectedCash()) > 0) {
                throw ApiException.field("amount", "en caja solo debería haber " + Money.fmt(shifts.summary(sh).expectedCash()));
            }
            p.method = "EFECTIVO";
            CashMovement m = new CashMovement();
            m.tenantId = t;
            m.shiftId = sh.id;
            m.type = "PAGO_PROVEEDOR";
            m.amount = amount;
            m.reason = "Pago a " + s.name;
            m.userId = me.userId();
            m.userName = me.fullName();
            p.cashMovementId = cashMovements.save(m).id;
        }
        p = supplierPayments.save(p);
        accounting.onSupplierPayment(p);
        BigDecimal left = amount;
        List<String> applied = new ArrayList<>();
        for (GoodsReceipt g : open) {
            if (left.signum() <= 0) break;
            BigDecimal take = g.payable().min(left);
            g.paidAmount = g.paidAmount.add(take);
            left = left.subtract(take);
            SupplierPaymentAllocation a = new SupplierPaymentAllocation();
            a.paymentId = p.id;
            a.receiptId = g.id;
            a.amount = take;
            allocations.save(a);
            applied.add("Recepción #" + g.number + (g.invoiceNumber == null ? "" : " (fact. " + g.invoiceNumber + ")") + ": " + Money.fmt(take));
        }
        audit.sensitive("PAGO_PROVEEDOR", "SUPPLIER", s.id, "Pagó " + Money.fmt(amount) + " a " + s.name + " por " + p.method.toLowerCase()
                + " (" + ("CAJA".equals(p.paidFrom) ? "de la caja" : p.paidFrom.toLowerCase()) + ")");
        return new SupplierPaymentDto(p.id, s.id, s.name, p.amount, p.method, p.paidFrom, p.reference, p.note, p.paidOn, p.userName, applied);
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentDto> supplierPayments(Long supplierId, LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        Map<Long, String> names = suppliers.findByTenantIdOrderByNameAsc(t).stream().collect(Collectors.toMap(s -> s.id, s -> s.name));
        List<SupplierPayment> list = supplierId != null ? supplierPayments.findBySupplierIdOrderByPaidOnDescIdDesc(supplierId)
                : supplierPayments.between(t, from == null ? LocalDate.of(2000, 1, 1) : from, to == null ? today(t) : to);
        return list.stream().filter(p -> p.tenantId.equals(t)).map(p -> new SupplierPaymentDto(p.id, p.supplierId, names.get(p.supplierId), p.amount,
                p.method, p.paidFrom, p.reference, p.note, p.paidOn, p.userName,
                allocations.findByPaymentId(p.id).stream().map(a -> "Recepción " + a.receiptId + ": " + Money.fmt(a.amount)).toList())).toList();
    }

    // ---------------- Resultados ----------------

    public record Amount(String label, BigDecimal amount) {
    }

    public record DayFlow(LocalDate date, BigDecimal sales, BigDecimal inflow, BigDecimal outflow) {
    }

    public record Summary(LocalDate from, LocalDate to, long salesCount, BigDecimal grossSales, BigDecimal refunds, BigDecimal netSales,
                          BigDecimal discounts, BigDecimal taxes, BigDecimal revenueExTax, BigDecimal costOfSales, BigDecimal grossProfit,
                          BigDecimal grossMarginPct, BigDecimal expenses, List<Amount> expensesByCategory, BigDecimal netProfit,
                          BigDecimal netMarginPct, BigDecimal otherIncome, List<Amount> incomeByCategory, BigDecimal inflow,
                          BigDecimal outflow, BigDecimal netCashFlow, List<Amount> inflowByMethod, List<Amount> outflowByConcept,
                          BigDecimal creditSales, BigDecimal creditCollected, BigDecimal receivables, BigDecimal payables,
                          BigDecimal purchases, BigDecimal supplierPaid, BigDecimal pendingVerification, BigDecimal purchaseBudget,
                          List<DayFlow> days) {
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.FINANZAS_VER);
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        Timestamp f = Timestamp.from(from.atStartOfDay(z).toInstant());
        Timestamp tt = Timestamp.from(to.plusDays(1).atStartOfDay(z).toInstant());
        String tz = z.getId();
        Object[] s = (Object[]) em.createNativeQuery("""
                        select count(*), coalesce(sum(s.total),0), coalesce(sum(s.discount_total),0), coalesce(sum(s.tax_total),0)
                        from sales s where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt""")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult();
        Object[] lines = (Object[]) em.createNativeQuery("""
                        select coalesce(sum(case when l.quantity > 0 then (l.line_total - l.tax) * (l.quantity - l.returned_qty) / l.quantity else 0 end),0),
                               coalesce(sum(l.unit_cost * (l.quantity - l.returned_qty)),0)
                        from sale_lines l join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt""")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult();
        BigDecimal refunds = dec(em.createNativeQuery("select coalesce(sum(refund_amount),0) from sale_returns where tenant_id = :t and created_at >= :f and created_at < :tt")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult());
        BigDecimal gross = dec(s[1]);
        BigDecimal net = gross.subtract(refunds);
        BigDecimal revenueEx = dec(lines[0]).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cogs = dec(lines[1]).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossProfit = revenueEx.subtract(cogs);

        Map<Long, String> catNames = categories.findByTenantIdOrderByKindAscNameAsc(t).stream().collect(Collectors.toMap(c -> c.id, c -> c.name));
        List<FinanceEntry> fe = entries.search(t, from, to, null, null).stream().filter(e -> e.voidedAt == null).toList();
        Map<String, BigDecimal> expCat = new TreeMap<>(), incCat = new TreeMap<>();
        BigDecimal expenses = BigDecimal.ZERO, otherIncome = BigDecimal.ZERO;
        for (FinanceEntry e : fe) {
            String cat = catNames.getOrDefault(e.categoryId, "Sin categoría");
            if (e.kind.equals("GASTO")) {
                expenses = expenses.add(e.amount);
                expCat.merge(cat, e.amount, BigDecimal::add);
            } else {
                otherIncome = otherIncome.add(e.amount);
                incCat.merge(cat, e.amount, BigDecimal::add);
            }
        }
        BigDecimal netProfit = grossProfit.subtract(expenses);

        // Flujo de caja: lo que entró por ventas (sin fiado ni pagos rechazados), abonos, otros ingresos; lo que salió.
        List<Object[]> byMethod = em.createNativeQuery("""
                        select p.method, coalesce(sum(p.amount),0) from payments p join sales s on s.id = p.sale_id
                        where p.tenant_id = :t and s.status = 'PAGADA' and p.method not in ('FIADO','ANTICIPO') and p.status not in ('RECHAZADO','ANULADO')
                          and p.created_at >= :f and p.created_at < :tt group by p.method order by 2 desc""")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getResultList();
        Map<String, BigDecimal> inflowMap = new LinkedHashMap<>();
        BigDecimal pending = BigDecimal.ZERO;
        for (Object[] r : byMethod) inflowMap.merge((String) r[0], dec(r[1]), BigDecimal::add);
        pending = dec(em.createNativeQuery("select coalesce(sum(p.amount),0) from payments p where p.tenant_id = :t and p.status = 'PENDIENTE'")
                .setParameter("t", t).getSingleResult());
        BigDecimal creditSales = dec(em.createNativeQuery("select coalesce(sum(amount),0) from credit_entries where tenant_id = :t and type = 'CARGO' and created_at >= :f and created_at < :tt")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult());
        BigDecimal collected = dec(em.createNativeQuery("select coalesce(sum(amount),0) from credit_entries where tenant_id = :t and type = 'ABONO' and created_at >= :f and created_at < :tt")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult());
        if (collected.signum() > 0) inflowMap.merge("ABONOS_FIADO", collected, BigDecimal::add);
        BigDecimal advances = dec(em.createNativeQuery("select coalesce(sum(amount),0) from service_order_deposits where tenant_id = :t and created_at >= :f and created_at < :tt")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult());
        if (advances.signum() > 0) inflowMap.merge("ANTICIPOS", advances, BigDecimal::add);
        if (otherIncome.signum() > 0) inflowMap.merge("OTROS_INGRESOS", otherIncome, BigDecimal::add);
        BigDecimal inflow = inflowMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal supplierPaid = supplierPayments.between(t, from, to).stream().map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> outMap = new LinkedHashMap<>();
        if (expenses.signum() > 0) outMap.put("GASTOS", expenses);
        if (supplierPaid.signum() > 0) outMap.put("PAGOS_PROVEEDORES", supplierPaid);
        if (refunds.signum() > 0) outMap.put("DEVOLUCIONES", refunds);
        BigDecimal outflow = outMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal purchases = dec(em.createNativeQuery("select coalesce(sum(total),0) from goods_receipts where tenant_id = :t and received_at >= :f and received_at < :tt")
                .setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult());

        // Serie diaria.
        Map<LocalDate, BigDecimal[]> daysMap = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to) && daysMap.size() < 400; d = d.plusDays(1)) daysMap.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select cast(s.completed_at at time zone :tz as date), coalesce(sum(s.total),0) from sales s
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt group by 1""")
                .setParameter("tz", tz).setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getResultList()) {
            BigDecimal[] v = daysMap.get(localDate(r[0]));
            if (v != null) v[0] = dec(r[1]);
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select cast(p.created_at at time zone :tz as date), coalesce(sum(p.amount),0) from payments p join sales s on s.id = p.sale_id
                        where p.tenant_id = :t and s.status = 'PAGADA' and p.method not in ('FIADO','ANTICIPO') and p.status not in ('RECHAZADO','ANULADO')
                          and p.created_at >= :f and p.created_at < :tt group by 1""")
                .setParameter("tz", tz).setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getResultList()) {
            BigDecimal[] v = daysMap.get(localDate(r[0]));
            if (v != null) v[1] = v[1].add(dec(r[1]));
        }
        for (FinanceEntry e : fe) {
            BigDecimal[] v = daysMap.get(e.entryDate);
            if (v == null) continue;
            if (e.kind.equals("GASTO")) v[2] = v[2].add(e.amount);
            else v[1] = v[1].add(e.amount);
        }
        for (SupplierPayment p : supplierPayments.between(t, from, to)) {
            BigDecimal[] v = daysMap.get(p.paidOn);
            if (v != null) v[2] = v[2].add(p.amount);
        }
        List<DayFlow> days = daysMap.entrySet().stream().map(e -> new DayFlow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();

        StoreSettings st = settings.forTenant(t);
        return new Summary(from, to, ((Number) s[0]).longValue(), gross, refunds, net, dec(s[2]), dec(s[3]), revenueEx, cogs, grossProfit,
                pctOf(grossProfit, revenueEx), expenses, amounts(expCat), netProfit, pctOf(netProfit, revenueEx), otherIncome, amounts(incCat),
                inflow, outflow, inflow.subtract(outflow), amounts(inflowMap), amounts(outMap), creditSales, collected, credit.receivables(t),
                payables(null).total(), purchases, supplierPaid, pending, st.purchaseBudget, days);
    }

    public record MonthlyReport(String month, Summary current, Summary previous, BigDecimal salesChangePct, BigDecimal profitChangePct) {
    }

    @Transactional(readOnly = true)
    public MonthlyReport monthly(YearMonth month) {
        Summary cur = summary(month.atDay(1), month.atEndOfMonth().isAfter(today(TenantContext.require())) ? today(TenantContext.require()) : month.atEndOfMonth());
        YearMonth prev = month.minusMonths(1);
        Summary pr = summary(prev.atDay(1), prev.atEndOfMonth());
        return new MonthlyReport(month.toString(), cur, pr, change(cur.netSales(), pr.netSales()), change(cur.netProfit(), pr.netProfit()));
    }

    /** CSV del resumen y de los movimientos del período (abre en Excel). */
    @Transactional
    public String exportCsv(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.REPORTES_EXPORTAR);
        Summary s = summary(from, to);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Reporte financiero;").append(from).append(" a ").append(to).append("\r\n\r\n");
        row(sb, "Ventas brutas", s.grossSales());
        row(sb, "Devoluciones", s.refunds());
        row(sb, "Ventas netas", s.netSales());
        row(sb, "Impuestos incluidos", s.taxes());
        row(sb, "Ventas sin impuestos", s.revenueExTax());
        row(sb, "Costo de ventas", s.costOfSales());
        row(sb, "Utilidad bruta", s.grossProfit());
        row(sb, "Gastos operativos", s.expenses());
        row(sb, "Utilidad neta estimada", s.netProfit());
        row(sb, "Otros ingresos (no ventas)", s.otherIncome());
        row(sb, "Entradas de dinero", s.inflow());
        row(sb, "Salidas de dinero", s.outflow());
        row(sb, "Flujo neto", s.netCashFlow());
        row(sb, "Cuentas por cobrar (fiado)", s.receivables());
        row(sb, "Cuentas por pagar (proveedores)", s.payables());
        sb.append("\r\nGastos por categoría\r\n");
        for (Amount a : s.expensesByCategory()) row(sb, a.label(), a.amount());
        sb.append("\r\nFecha;Tipo;Categoría;Descripción;Valor;Medio;Origen\r\n");
        for (EntryDto e : entries(from, to, null, null)) {
            if (e.voided()) continue;
            sb.append(e.entryDate()).append(';').append(e.kind()).append(';').append(nn(e.categoryName())).append(';')
                    .append(e.description().replace(";", ",")).append(';').append(e.amount().toPlainString()).append(';').append(e.method())
                    .append(';').append(e.paidFrom()).append("\r\n");
        }
        sb.append("\r\nDía;Ventas;Entradas;Salidas\r\n");
        for (DayFlow d : s.days()) sb.append(d.date()).append(';').append(d.sales().toPlainString()).append(';').append(d.inflow().toPlainString())
                .append(';').append(d.outflow().toPlainString()).append("\r\n");
        audit.log("REPORTE_EXPORTADO", "FINANCE", null, "Exportó el reporte financiero del " + from + " al " + to);
        return sb.toString();
    }

    // ---------------- utilidades ----------------

    private static void row(StringBuilder sb, String label, BigDecimal v) {
        sb.append(label).append(';').append(v == null ? "" : v.setScale(0, RoundingMode.HALF_UP).toPlainString()).append("\r\n");
    }

    private static List<Amount> amounts(Map<String, BigDecimal> m) {
        return m.entrySet().stream().map(e -> new Amount(e.getKey(), e.getValue())).sorted(Comparator.comparing(Amount::amount).reversed()).toList();
    }

    private static BigDecimal pctOf(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal change(BigDecimal cur, BigDecimal prev) {
        if (prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev.abs(), 1, RoundingMode.HALF_UP);
    }

    private LocalDate today(Long t) {
        return LocalDate.now(zone(t));
    }

    private ZoneId zone(Long t) {
        return ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static LocalDate localDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
