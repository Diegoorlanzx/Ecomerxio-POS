package com.tiendaposs.accounting;

import com.tiendaposs.accounting.AccountingRepos.*;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.credit.CreditEntry;
import com.tiendaposs.finance.FinanceCategory;
import com.tiendaposs.finance.FinanceEntry;
import com.tiendaposs.finance.FinanceRepos.FinanceCategoryRepository;
import com.tiendaposs.finance.MoneyAccount;
import com.tiendaposs.finance.MoneyAccountMove;
import com.tiendaposs.finance.SupplierPayment;
import com.tiendaposs.fiscal.FiscalCodes;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.inventory.StockMovement;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.Payment;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.pos.SaleReturn;
import com.tiendaposs.pos.SaleReturnLine;
import com.tiendaposs.pos.Shift;
import com.tiendaposs.purchasing.GoodsReceipt;
import com.tiendaposs.purchasing.GoodsReceiptLine;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptLineRepository;
import com.tiendaposs.purchasing.PurchasingRepos.SupplierInvoiceRepository;
import com.tiendaposs.purchasing.Supplier;
import com.tiendaposs.purchasing.SupplierInvoice;
import com.tiendaposs.security.CurrentUser;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Fase 14: contabilidad automática.
 * <ul>
 *   <li>Cada operación genera su comprobante en la MISMA transacción, cuadrado (débitos = créditos) y enlazado al origen.
 *       El índice único por origen impide contabilizar dos veces.</li>
 *   <li>Si falta una cuenta en el mapa, la operación va a "Pendientes por contabilizar" con el motivo. Las llaves que nunca
 *       se han configurado toman la cuenta PROPUESTA (la misma que se precarga al activar); si alguien dejó una llave sin
 *       cuenta, la operación queda pendiente: nunca se inventa una cuenta distinta a la propuesta.</li>
 *   <li>Las operaciones se contabilizan con su fecha real. Si su mes está cerrado, el comprobante se registra el primer
 *       día del siguiente mes abierto y guarda la fecha original.</li>
 *   <li>Operaciones: ventas, costo, devoluciones, anulaciones, compras (con IVA descontable y retenciones), pagos a
 *       proveedores, gastos e ingresos, abonos de fiado, ajustes de inventario, diferencias de caja, movimientos de
 *       bancos y notas manuales. Todo es una PROPUESTA que revisa un contador antes de venderse.</li>
 * </ul>
 */
@Service
public class AccountingService {

    public static final String VENTA = "VENTA", DEVOLUCION = "DEVOLUCION", ANULACION = "ANULACION", COMPRA = "COMPRA",
            PAGO_PROVEEDOR = "PAGO_PROVEEDOR", GASTO = "GASTO", INGRESO = "INGRESO", ABONO = "ABONO_FIADO", INVENTARIO = "INVENTARIO",
            CAJA = "DIFERENCIA_CAJA", BANCO = "BANCO", AJUSTE_RETENCION = "AJUSTE_RETENC", NOTA = "NOTA", CIERRE = "CIERRE", REVERSA = "REVERSA";
    /** Movimientos de inventario que no son ventas, compras ni devoluciones de clientes: faltantes, sobrantes y bajas. */
    public static final Set<String> STOCK_ADJUSTMENTS = Set.of("AJUSTE_ENTRADA", "AJUSTE_SALIDA", "DANADO", "CONTEO", "CORRECCION", "BAJA_SERIAL");
    static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final SettingsRepository settingsRepo;
    private final AccountRepository accounts;
    private final MapRepository map;
    private final EntryRepository entries;
    private final LineRepository lines;
    private final PendingRepository pending;
    private final PeriodRepository periods;
    private final WithholdingRepository withholdings;
    private final SaleRepository sales;
    private final SaleLineRepository saleLines;
    private final PaymentRepository payments;
    private final SaleReturnRepository saleReturns;
    private final SaleReturnLineRepository returnLines;
    private final CustomerRepository customers;
    private final GoodsReceiptLineRepository receiptLines;
    private final SupplierInvoiceRepository supplierInvoices;
    private final FinanceCategoryRepository categories;
    private final FiscalProfileRepository fiscalProfiles;
    private final SettingsService storeSettings;
    private final WithholdingService withholdingService;
    private final Counters counters;
    private final AuditService audit;
    private final EntityManager em;

    public AccountingService(SettingsRepository settingsRepo, AccountRepository accounts, MapRepository map, EntryRepository entries,
                             LineRepository lines, PendingRepository pending, PeriodRepository periods, WithholdingRepository withholdings,
                             SaleRepository sales, SaleLineRepository saleLines, PaymentRepository payments, SaleReturnRepository saleReturns,
                             SaleReturnLineRepository returnLines, CustomerRepository customers, GoodsReceiptLineRepository receiptLines,
                             SupplierInvoiceRepository supplierInvoices, FinanceCategoryRepository categories,
                             FiscalProfileRepository fiscalProfiles, SettingsService storeSettings, WithholdingService withholdingService,
                             Counters counters, AuditService audit, EntityManager em) {
        this.settingsRepo = settingsRepo;
        this.accounts = accounts;
        this.map = map;
        this.entries = entries;
        this.lines = lines;
        this.pending = pending;
        this.periods = periods;
        this.withholdings = withholdings;
        this.sales = sales;
        this.saleLines = saleLines;
        this.payments = payments;
        this.saleReturns = saleReturns;
        this.returnLines = returnLines;
        this.customers = customers;
        this.receiptLines = receiptLines;
        this.supplierInvoices = supplierInvoices;
        this.categories = categories;
        this.fiscalProfiles = fiscalProfiles;
        this.storeSettings = storeSettings;
        this.withholdingService = withholdingService;
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
        Set<String> codes = new HashSet<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> codes.add(a.code));
        for (AccountingCatalog.DefaultAccount a : AccountingCatalog.PUC) {
            if (codes.contains(a.code())) continue;
            Account acc = new Account();
            acc.tenantId = t;
            acc.code = a.code();
            acc.name = a.name();
            acc.nature = a.nature();
            accounts.save(acc);
        }
        Set<String> existing = new HashSet<>();
        map.findByTenantId(t).forEach(m -> existing.add(m.mapKey));
        defaults(t).forEach((k, v) -> {
            if (existing.contains(k)) return;
            MapEntry m = new MapEntry();
            m.tenantId = t;
            m.mapKey = k;
            m.accountCode = v;
            map.save(m);
        });
    }

    /** El mapa propuesto completo de la tienda: llaves fijas, medios de pago y categorías de gastos e ingresos. */
    private Map<String, String> defaults(Long t) {
        Map<String, String> d = AccountingCatalog.defaultMap(storeSettings.forTenant(t).paymentMethodList());
        for (FinanceCategory c : categories.findByTenantIdOrderByKindAscNameAsc(t)) {
            d.put(categoryKey(c), AccountingCatalog.categoryAccount(c.kind, c.name));
        }
        return d;
    }

    private static String categoryKey(FinanceCategory c) {
        return "GASTO".equals(c.kind) ? AccountingCatalog.expenseKey(c.id) : AccountingCatalog.incomeKey(c.id);
    }

    private boolean active(Long t, LocalDate date) {
        AccountingSettings s = settingsRepo.findById(t).orElse(null);
        return s != null && s.enabled && s.startDate != null && !date.isBefore(s.startDate);
    }

    // =================================================================== Ganchos: ventas

    /** Venta cobrada: ingreso, impuestos y medios de pago; y el costo contra el inventario. */
    public void onSaleCompleted(Sale s) {
        LocalDate date = date(s.completedAt);
        if (!active(s.tenantId, date)) return;
        post(s.tenantId, "SALE", s.id, date, () -> buildSale(s, date));
    }

    /** Venta anulada: reversa exacta del comprobante original (con fecha de hoy). */
    public void onSaleVoided(Sale s) {
        reverse(s.tenantId, "SALE", s.id, ANULACION, "VOID", s.id, "Anulación de la venta #" + s.number + ": " + s.voidReason);
    }

    /** Devolución: devoluciones en ventas e impuesto contra el medio de reembolso; el costo vuelve al inventario. */
    public void onReturn(Sale s, SaleReturn r) {
        LocalDate date = date(r.createdAt);
        if (!active(s.tenantId, date)) return;
        post(s.tenantId, "RETURN", r.id, date, () -> buildReturn(s, r, date));
    }

    // =================================================================== Ganchos: compras, pagos, gastos, cartera, inventario, caja y bancos

    /** Compra recibida: primero se proponen las retenciones (aunque la contabilidad esté apagada); luego el comprobante. */
    public void onGoodsReceipt(GoodsReceipt rec) {
        withholdingService.propose(rec);
        LocalDate date = date(rec.receivedAt);
        if (!active(rec.tenantId, date)) return;
        post(rec.tenantId, "RECEIPT", rec.id, date, () -> buildReceipt(rec, date));
    }

    /** Las retenciones de una compra cambiaron: se ajusta la diferencia (si la compra ya está contabilizada). */
    void onWithholdingChanged(GoodsReceipt rec, Map<String, BigDecimal> deltas) {
        Long t = rec.tenantId;
        if (!entries.existsByTenantIdAndSourceTypeAndSourceId(t, "RECEIPT", rec.id)) return; // pendiente o sin contabilidad: se arma con lo nuevo
        Supplier s = em.find(Supplier.class, rec.supplierId);
        String[] third = supplierThird(s);
        Draft d = new Draft(AJUSTE_RETENCION, "WH_ADJ", counters.next(t, "AJUSTE_RETENCION"), LocalDate.now(ZONE),
                "Ajuste de retenciones de la recepción #" + rec.number + " (" + s.name + ")");
        deltas.forEach((kind, delta) -> {
            if (delta.signum() > 0) {
                d.lines.add(new DraftLine(null, AccountingCatalog.SUPPLIERS, delta, BigDecimal.ZERO, third[0], third[1], "Menor valor por pagar"));
                d.lines.add(new DraftLine(null, AccountingCatalog.whKey(kind), BigDecimal.ZERO, delta, third[0], third[1], "Retención " + kind.toLowerCase()));
            } else if (delta.signum() < 0) {
                d.lines.add(new DraftLine(null, AccountingCatalog.whKey(kind), delta.negate(), BigDecimal.ZERO, third[0], third[1], "Menos retención " + kind.toLowerCase()));
                d.lines.add(new DraftLine(null, AccountingCatalog.SUPPLIERS, BigDecimal.ZERO, delta.negate(), third[0], third[1], "Mayor valor por pagar"));
            }
        });
        if (d.lines.isEmpty()) return;
        List<String> missing = resolve(t, d);
        if (!missing.isEmpty()) throw ApiException.badRequest("Falta la cuenta en el mapa contable para: " + String.join(", ", missing));
        save(t, d);
    }

    public void onSupplierPayment(SupplierPayment p) {
        LocalDate date = p.paidOn == null ? LocalDate.now(ZONE) : p.paidOn;
        if (!active(p.tenantId, date)) return;
        post(p.tenantId, "SUPPLIER_PAY", p.id, date, () -> buildSupplierPayment(p, date));
    }

    /** Gasto o ingreso registrado en Finanzas (incluye los de la caja). */
    public void onFinanceEntry(FinanceEntry e) {
        if (!active(e.tenantId, e.entryDate)) return;
        post(e.tenantId, "FINANCE", e.id, e.entryDate, () -> buildFinance(e));
    }

    public void onFinanceEntryVoided(FinanceEntry e) {
        reverse(e.tenantId, "FINANCE", e.id, REVERSA, "FINANCE_VOID", e.id, "Anulación: " + e.description);
    }

    public void onCreditPayment(CreditEntry abono) {
        LocalDate date = date(abono.createdAt);
        if (!active(abono.tenantId, date)) return;
        post(abono.tenantId, "CREDIT_PAY", abono.id, date, () -> buildCreditPayment(abono, date));
    }

    /** El abono no llegó (pago digital rechazado): se reversa. */
    public void onCreditPaymentReversed(CreditEntry abono) {
        reverse(abono.tenantId, "CREDIT_PAY", abono.id, REVERSA, "CREDIT_PAY_REV", abono.id, "Abono rechazado: reversa");
    }

    public void onStockMovement(StockMovement m) {
        if (!STOCK_ADJUSTMENTS.contains(m.type) || m.quantity == null || m.quantity.signum() == 0) return;
        LocalDate date = date(m.createdAt);
        if (!active(m.tenantId, date)) return;
        post(m.tenantId, "STOCK_MOVE", m.id, date, () -> buildStock(m, date));
    }

    public void onShiftClosed(Shift s) {
        if (s.difference == null || s.difference.signum() == 0) return;
        LocalDate date = date(s.closedAt);
        if (!active(s.tenantId, date)) return;
        post(s.tenantId, "SHIFT_CLOSE", s.id, date, () -> buildShiftClose(s, date));
    }

    public void onMoneyMove(MoneyAccountMove m) {
        if (!active(m.tenantId, m.moveDate)) return;
        post(m.tenantId, "MONEY_MOVE", m.id, m.moveDate, () -> buildMoneyMove(m));
    }

    public void onMoneyMoveVoided(MoneyAccountMove m) {
        reverse(m.tenantId, "MONEY_MOVE", m.id, REVERSA, "MONEY_MOVE_VOID", m.id, "Anulación de movimiento de banco");
    }

    // =================================================================== Armado de comprobantes

    /** Un comprobante en construcción; las cuentas se resuelven al final (si falta alguna, va a pendientes). */
    static final class Draft {
        final String type, sourceType, description;
        final Long sourceId;
        final LocalDate date;
        Long reversalOf;
        /** true = no se mueve aunque el mes esté cerrado (cierre de año). */
        boolean fixedDate;
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
    record DraftLine(String accountCode, String mapKey, BigDecimal debit, BigDecimal credit, String thirdId,
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

    /**
     * Compra: inventario al costo recibido; si el negocio es responsable de IVA y la factura importada trae IVA, ese IVA
     * va como descontable; lo demás de la factura (cargos o descuentos) a su cuenta. Al proveedor se le debe el total
     * menos las retenciones, que quedan por pagar a la DIAN o al municipio.
     */
    private Draft buildReceipt(GoodsReceipt rec, LocalDate date) {
        Supplier s = em.find(Supplier.class, rec.supplierId);
        String[] third = supplierThird(s);
        Draft d = new Draft(COMPRA, "RECEIPT", rec.id, date, "Compra: recepción #" + rec.number + " de " + s.name
                + (rec.invoiceNumber == null ? "" : " (factura " + rec.invoiceNumber + ")"));
        BigDecimal value = BigDecimal.ZERO;
        for (GoodsReceiptLine l : receiptLines.findByReceiptIdIn(List.of(rec.id))) {
            value = value.add(l.unitsReceived.multiply(l.unitCost));
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = rec.total.setScale(2, RoundingMode.HALF_UP);
        BigDecimal iva = BigDecimal.ZERO;
        SupplierInvoice inv = supplierInvoices.findByReceiptId(rec.id).orElse(null);
        boolean ivaResponsible = fiscalProfiles.findById(rec.tenantId).map(p -> Boolean.TRUE.equals(p.ivaResponsible)).orElse(false);
        if (inv != null && ivaResponsible && inv.taxTotal != null && inv.taxTotal.signum() > 0) {
            iva = inv.taxTotal.min(total.subtract(value).max(BigDecimal.ZERO));
        }
        BigDecimal other = total.subtract(value).subtract(iva);
        d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY, value, BigDecimal.ZERO, third[0], third[1], "Entrada de mercancía"));
        if (iva.signum() > 0) d.lines.add(new DraftLine(null, "TAX_CREDIT:IVA", iva, BigDecimal.ZERO, third[0], third[1], "IVA descontable"));
        if (other.signum() > 0) d.lines.add(new DraftLine(null, AccountingCatalog.PURCHASE_OTHER, other, BigDecimal.ZERO, third[0], third[1], "Otros cargos de la factura"));
        if (other.signum() < 0) d.lines.add(new DraftLine(null, AccountingCatalog.PURCHASE_OTHER, BigDecimal.ZERO, other.negate(), third[0], third[1], "Descuentos de la factura"));
        BigDecimal withheld = BigDecimal.ZERO;
        for (ReceiptWithholding w : withholdings.findByReceiptIdOrderByIdAsc(rec.id)) {
            if (w.amount.signum() <= 0) continue;
            withheld = withheld.add(w.amount);
            d.lines.add(new DraftLine(null, AccountingCatalog.whKey(w.kind), BigDecimal.ZERO, w.amount, third[0], third[1],
                    "Retención " + w.kind.toLowerCase() + " " + w.rate.stripTrailingZeros().toPlainString() + " %"));
        }
        d.lines.add(new DraftLine(null, AccountingCatalog.SUPPLIERS, BigDecimal.ZERO, total.subtract(withheld), third[0], third[1], "Por pagar al proveedor"));
        return d;
    }

    private Draft buildSupplierPayment(SupplierPayment p, LocalDate date) {
        Supplier s = em.find(Supplier.class, p.supplierId);
        String[] third = supplierThird(s);
        String method = "CAJA".equals(p.paidFrom) ? "EFECTIVO" : p.method;
        Draft d = new Draft(PAGO_PROVEEDOR, "SUPPLIER_PAY", p.id, date, "Pago a " + s.name + " por " + method.toLowerCase()
                + (p.reference == null ? "" : " (ref. " + p.reference + ")"));
        d.lines.add(new DraftLine(null, AccountingCatalog.SUPPLIERS, p.amount, BigDecimal.ZERO, third[0], third[1], "Abono a la deuda"));
        d.lines.add(new DraftLine(null, AccountingCatalog.payKey(method), BigDecimal.ZERO, p.amount, third[0], third[1], "Salida " + method.toLowerCase()));
        return d;
    }

    private Draft buildFinance(FinanceEntry e) {
        FinanceCategory c = e.categoryId == null ? null : categories.findById(e.categoryId).orElse(null);
        String method = "CAJA".equals(e.paidFrom) ? "EFECTIVO" : e.method;
        boolean expense = "GASTO".equals(e.kind);
        String catKey = c == null ? (expense ? AccountingCatalog.OTHER_EXPENSE : AccountingCatalog.OTHER_INCOME) : categoryKey(c);
        Draft d = new Draft(expense ? GASTO : INGRESO, "FINANCE", e.id, e.entryDate,
                (expense ? "Gasto: " : "Ingreso: ") + e.description + (c == null ? "" : " (" + c.name + ")"));
        if (expense) {
            d.lines.add(new DraftLine(null, catKey, e.amount, BigDecimal.ZERO, null, null, e.description));
            d.lines.add(new DraftLine(null, AccountingCatalog.payKey(method), BigDecimal.ZERO, e.amount, null, null, "Salida " + method.toLowerCase()));
        } else {
            d.lines.add(new DraftLine(null, AccountingCatalog.payKey(method), e.amount, BigDecimal.ZERO, null, null, "Entrada " + method.toLowerCase()));
            d.lines.add(new DraftLine(null, catKey, BigDecimal.ZERO, e.amount, null, null, e.description));
        }
        return d;
    }

    private Draft buildCreditPayment(CreditEntry a, LocalDate date) {
        Customer c = customers.findById(a.customerId).orElse(null);
        String[] third = c != null && c.docNumber != null ? new String[]{c.docNumber, c.name}
                : new String[]{FiscalCodes.FINAL_CONSUMER_ID, c == null ? "Cliente" : c.name};
        Draft d = new Draft(ABONO, "CREDIT_PAY", a.id, date, "Abono de fiado de " + third[1] + " por " + a.method.toLowerCase());
        d.lines.add(new DraftLine(null, AccountingCatalog.payKey(a.method), a.amount, BigDecimal.ZERO, third[0], third[1], "Recaudo"));
        d.lines.add(new DraftLine(null, AccountingCatalog.payKey("FIADO"), BigDecimal.ZERO, a.amount, third[0], third[1], "Menos cartera"));
        return d;
    }

    private Draft buildStock(StockMovement m, LocalDate date) {
        BigDecimal cost = m.unitCost == null ? BigDecimal.ZERO : m.unitCost;
        BigDecimal value = m.quantity.abs().multiply(cost).setScale(2, RoundingMode.HALF_UP);
        if (value.signum() == 0) return null;
        List<?> names = em.createNativeQuery("select name from products where id = :id").setParameter("id", m.productId).getResultList();
        String product = names.isEmpty() ? "producto" : String.valueOf(names.get(0));
        boolean in = m.quantity.signum() > 0;
        Draft d = new Draft(INVENTARIO, "STOCK_MOVE", m.id, date, (in ? "Sobrante de inventario: " : "Faltante o baja de inventario: ") + product
                + " (" + m.quantity.stripTrailingZeros().toPlainString() + ")" + (m.reason == null ? "" : " · " + m.reason));
        if (in) {
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY, value, BigDecimal.ZERO, null, null, "Entra al inventario"));
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY_GAIN, BigDecimal.ZERO, value, null, null, "Sobrante"));
        } else {
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY_LOSS, value, BigDecimal.ZERO, null, null, "Faltante o baja"));
            d.lines.add(new DraftLine(null, AccountingCatalog.INVENTORY, BigDecimal.ZERO, value, null, null, "Sale del inventario"));
        }
        return d;
    }

    private Draft buildShiftClose(Shift s, LocalDate date) {
        BigDecimal diff = s.difference;
        Draft d = new Draft(CAJA, "SHIFT_CLOSE", s.id, date, (diff.signum() > 0 ? "Sobrante" : "Faltante") + " de caja al cerrar el turno #"
                + s.number + " de " + s.userName);
        String cash = AccountingCatalog.payKey("EFECTIVO");
        if (diff.signum() > 0) {
            d.lines.add(new DraftLine(null, cash, diff, BigDecimal.ZERO, null, null, "Sobrante en caja"));
            d.lines.add(new DraftLine(null, AccountingCatalog.CASH_OVER, BigDecimal.ZERO, diff, null, null, "Sobrante de caja"));
        } else {
            d.lines.add(new DraftLine(null, AccountingCatalog.CASH_SHORT, diff.negate(), BigDecimal.ZERO, null, null, "Faltante de caja"));
            d.lines.add(new DraftLine(null, cash, BigDecimal.ZERO, diff.negate(), null, null, "Faltante en caja"));
        }
        return d;
    }

    /** La cuenta contable de una cuenta de banco o billetera es la de su primer medio de pago. */
    private String bankKey(Long accountId) {
        MoneyAccount a = em.find(MoneyAccount.class, accountId);
        List<String> methods = a == null ? List.of() : a.methodList();
        return AccountingCatalog.payKey(methods.isEmpty() ? "CUENTA_" + accountId : methods.get(0));
    }

    private Draft buildMoneyMove(MoneyAccountMove m) {
        MoneyAccount a = em.find(MoneyAccount.class, m.accountId);
        String bank = bankKey(m.accountId), cash = AccountingCatalog.payKey("EFECTIVO");
        BigDecimal amt = m.amount.abs();
        String label = switch (m.type) {
            case "CONSIGNACION" -> "Consignación en ";
            case "RETIRO" -> "Retiro de ";
            case "COMISION" -> "Comisión de ";
            case "TRASLADO" -> "Traslado desde ";
            default -> "Ajuste en ";
        };
        Draft d = new Draft(BANCO, "MONEY_MOVE", m.id, m.moveDate, label + a.name + (m.note == null ? "" : ": " + m.note));
        switch (m.type) {
            case "CONSIGNACION" -> pair(d, bank, cash, amt);
            case "RETIRO" -> pair(d, cash, bank, amt);   // se asume que el dinero va a la caja (a confirmar con el contador)
            case "COMISION" -> pair(d, AccountingCatalog.BANK_FEES, bank, amt);
            case "TRASLADO" -> pair(d, bankKey(m.toAccountId), bank, amt);
            default -> {
                if (m.amount.signum() > 0) pair(d, bank, AccountingCatalog.OTHER_INCOME, amt);
                else pair(d, AccountingCatalog.OTHER_EXPENSE, bank, amt);
            }
        }
        return d;
    }

    private static void pair(Draft d, String debitKey, String creditKey, BigDecimal amount) {
        d.lines.add(new DraftLine(null, debitKey, amount, BigDecimal.ZERO, null, null, null));
        d.lines.add(new DraftLine(null, creditKey, BigDecimal.ZERO, amount, null, null, null));
    }

    /** Tercero: el cliente identificado o "Consumidor final". */
    private String[] third(Sale s) {
        Customer c = s.customerId == null ? null : customers.findById(s.customerId).orElse(null);
        if (c != null && c.docNumber != null) return new String[]{c.docNumber, c.name};
        return new String[]{FiscalCodes.FINAL_CONSUMER_ID, "Consumidor final"};
    }

    /** Tercero proveedor: su NIT o documento (sin DV) y su razón social. */
    static String[] supplierThird(Supplier s) {
        String id = s.taxId == null ? null : s.taxId.replaceAll("-\\s*\\d$", "").replaceAll("\\D", "");
        return new String[]{id == null || id.isEmpty() ? null : id, s.legalName != null ? s.legalName : s.name};
    }

    // =================================================================== Guardar, reversar o dejar pendiente

    private void post(Long t, String sourceType, Long sourceId, LocalDate date, java.util.function.Supplier<Draft> builder) {
        if (entries.existsByTenantIdAndSourceTypeAndSourceId(t, sourceType, sourceId)) return;
        Draft d = builder.get();
        if (d == null || d.lines.isEmpty()) return;
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
        resolvePending(t, sourceType, sourceId, null);
    }

    /**
     * Reversa exacta del comprobante de una operación (con fecha de hoy). Si la operación nunca se contabilizó (estaba
     * pendiente o es anterior a la fecha de inicio), no hay nada que reversar: el pendiente se cierra.
     */
    private void reverse(Long t, String sourceType, Long sourceId, String type, String newSourceType, Long newSourceId, String description) {
        JournalEntry original = entries.findFirstByTenantIdAndSourceTypeAndSourceId(t, sourceType, sourceId).orElse(null);
        if (original == null) {
            resolvePending(t, sourceType, sourceId, " (se anuló: ya no hace falta)");
            return;
        }
        if (entries.existsByTenantIdAndSourceTypeAndSourceId(t, newSourceType, newSourceId)) return;
        Draft d = new Draft(type, newSourceType, newSourceId, LocalDate.now(ZONE), description);
        d.reversalOf = original.id;
        for (JournalLine l : lines.findByEntryIdOrderByIdAsc(original.id)) {
            d.lines.add(new DraftLine(l.accountCode, null, l.credit, l.debit, l.thirdPartyId, l.thirdPartyName, "Reversa: " + nz(l.description)));
        }
        save(t, d);
    }

    private void resolvePending(Long t, String sourceType, Long sourceId, String note) {
        pending.findFirstByTenantIdAndSourceTypeAndSourceIdAndResolvedAtIsNull(t, sourceType, sourceId).ifPresent(p -> {
            p.resolvedAt = Instant.now();
            if (note != null) p.reason = p.reason + note;
            pending.save(p);
        });
    }

    /**
     * Cambia cada llave del mapa por su cuenta; devuelve lo que falta (vacío = todo resuelto). Una llave que nunca se
     * configuró toma la cuenta propuesta (y queda guardada en el mapa); una que alguien dejó sin cuenta, no.
     */
    List<String> resolve(Long t, Draft d) {
        Map<String, String> m = new HashMap<>();
        Set<String> present = new HashSet<>();
        map.findByTenantId(t).forEach(e -> {
            present.add(e.mapKey);
            if (e.accountCode != null) m.put(e.mapKey, e.accountCode);
        });
        Set<String> activeCodes = new HashSet<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> {
            if (a.active) activeCodes.add(a.code);
        });
        Map<String, String> defaults = null;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < d.lines.size(); i++) {
            DraftLine l = d.lines.get(i);
            if (l.accountCode() != null) continue;
            String code = m.get(l.mapKey());
            if (code == null && !present.contains(l.mapKey())) {
                if (defaults == null) defaults = defaults(t);
                String proposed = defaults.get(l.mapKey());
                if (proposed != null && activeCodes.contains(proposed)) {
                    MapEntry e = new MapEntry();
                    e.tenantId = t;
                    e.mapKey = l.mapKey();
                    e.accountCode = proposed;
                    map.save(e);
                    m.put(l.mapKey(), proposed);
                    present.add(l.mapKey());
                    code = proposed;
                }
            }
            if (code == null || !activeCodes.contains(code)) {
                String label = label(t, l.mapKey());
                if (!missing.contains(label)) missing.add(label);
                continue;
            }
            d.lines.set(i, new DraftLine(code, l.mapKey(), l.debit(), l.credit(), l.thirdId(), l.thirdName(), l.description()));
        }
        return missing;
    }

    /** Guarda un comprobante cuadrado. Si su mes está cerrado, lo registra el primer día del siguiente mes abierto. */
    JournalEntry save(Long t, Draft d) {
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (DraftLine l : d.lines) {
            debit = debit.add(l.debit().setScale(2, RoundingMode.HALF_UP));
            credit = credit.add(l.credit().setScale(2, RoundingMode.HALF_UP));
        }
        if (debit.compareTo(credit) != 0) {
            // Nunca debería pasar: si pasa, es un error del motor y no se guarda un comprobante descuadrado.
            throw new IllegalStateException("Comprobante descuadrado (" + d.description + "): débitos " + debit + " ≠ créditos " + credit);
        }
        LocalDate date = d.fixedDate ? d.date : openDate(t, d.date);
        JournalEntry e = new JournalEntry();
        e.tenantId = t;
        e.number = counters.next(t, "COMPROBANTE");
        e.entryType = d.type;
        e.entryDate = date;
        e.originalDate = date.equals(d.date) ? null : d.date;
        e.sourceType = d.sourceType;
        e.sourceId = d.sourceId;
        e.reversalOf = d.reversalOf;
        e.description = d.description;
        e.total = debit;
        var me = CurrentUser.getOrNull();
        e.createdBy = me == null ? "Sistema" : me.fullName();
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
        return e;
    }

    /** Meses cerrados de la tienda (2026-10, …). */
    Set<String> closedPeriods(Long t) {
        Set<String> out = new HashSet<>();
        periods.findByTenantIdOrderByPeriodDesc(t).forEach(p -> {
            if (p.closed) out.add(p.period);
        });
        return out;
    }

    /** La fecha, o el primer día del siguiente mes abierto si su mes está cerrado. */
    LocalDate openDate(Long t, LocalDate date) {
        Set<String> closed = closedPeriods(t);
        LocalDate d = date;
        while (closed.contains(YearMonth.from(d).toString())) d = YearMonth.from(d).plusMonths(1).atDay(1);
        return d;
    }

    /** Vuelve a intentar los pendientes (después de completar el mapa contable). */
    @Transactional
    public Map<String, Integer> processPending() {
        Long t = TenantContext.require();
        int done = 0, still = 0;
        for (PendingEntry p : pending.findByTenantIdAndResolvedAtIsNullOrderByEntryDateAscIdAsc(t)) {
            java.util.function.Supplier<Draft> builder = rebuild(p);
            if (builder == null) {
                p.resolvedAt = Instant.now();
                p.reason = p.reason + " (la operación ya no existe o se anuló)";
                pending.save(p);
                continue;
            }
            post(t, p.sourceType, p.sourceId, p.entryDate, builder);
            if (pending.findById(p.id).map(x -> x.resolvedAt != null).orElse(false)) done++;
            else still++;
        }
        return Map.of("posted", done, "stillPending", still);
    }

    /** Cómo volver a armar el comprobante de un pendiente; null si la operación ya no existe o se anuló. */
    private java.util.function.Supplier<Draft> rebuild(PendingEntry p) {
        LocalDate date = p.entryDate;
        switch (p.sourceType) {
            case "SALE" -> {
                Sale s = sales.findById(p.sourceId).orElse(null);
                return s == null || "ANULADA".equals(s.status) ? null : () -> buildSale(s, date);
            }
            case "RETURN" -> {
                SaleReturn r = saleReturns.findById(p.sourceId).orElse(null);
                Sale s = r == null ? null : sales.findById(r.saleId).orElse(null);
                return s == null ? null : () -> buildReturn(s, r, date);
            }
            case "RECEIPT" -> {
                GoodsReceipt r = em.find(GoodsReceipt.class, p.sourceId);
                return r == null ? null : () -> buildReceipt(r, date);
            }
            case "SUPPLIER_PAY" -> {
                SupplierPayment x = em.find(SupplierPayment.class, p.sourceId);
                return x == null ? null : () -> buildSupplierPayment(x, date);
            }
            case "FINANCE" -> {
                FinanceEntry x = em.find(FinanceEntry.class, p.sourceId);
                return x == null || x.voidedAt != null ? null : () -> buildFinance(x);
            }
            case "CREDIT_PAY" -> {
                CreditEntry x = em.find(CreditEntry.class, p.sourceId);
                return x == null ? null : () -> buildCreditPayment(x, date);
            }
            case "STOCK_MOVE" -> {
                StockMovement x = em.find(StockMovement.class, p.sourceId);
                return x == null ? null : () -> buildStock(x, date);
            }
            case "SHIFT_CLOSE" -> {
                Shift x = em.find(Shift.class, p.sourceId);
                return x == null ? null : () -> buildShiftClose(x, date);
            }
            case "MONEY_MOVE" -> {
                MoneyAccountMove x = em.find(MoneyAccountMove.class, p.sourceId);
                return x == null || x.voidedAt != null ? null : () -> buildMoneyMove(x);
            }
            default -> {
                return null;
            }
        }
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

    /** El mapa con todas las llaves que hoy se usan: las propuestas, los medios de pago, las categorías y los impuestos vistos. */
    public List<MapRow> mapRows() {
        Long t = TenantContext.require();
        Map<String, String> current = new LinkedHashMap<>();
        defaults(t).keySet().forEach(k -> current.put(k, null));
        map.findByTenantId(t).forEach(m -> current.put(m.mapKey, m.accountCode));
        @SuppressWarnings("unchecked")
        List<String> taxNames = em.createNativeQuery("select distinct upper(coalesce(tax_name, 'IVA')) from sale_lines where tenant_id = :t and tax <> 0")
                .setParameter("t", t).getResultList();
        taxNames.forEach(n -> current.putIfAbsent(AccountingCatalog.taxKey(n), null));
        Map<String, String> names = new HashMap<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> names.put(a.code, a.name));
        return current.entrySet().stream()
                .map(e -> new MapRow(e.getKey(), label(t, e.getKey()), e.getValue(), e.getValue() == null ? null : names.get(e.getValue())))
                .toList();
    }

    /** Nombre legible de una llave (las de categorías llevan el nombre de la categoría). */
    private String label(Long t, String key) {
        if (key.startsWith("EXPENSE:") || key.startsWith("INCOME:")) {
            try {
                Long id = Long.valueOf(key.substring(key.indexOf(':') + 1));
                String name = categories.findByIdAndTenantId(id, t).map(c -> c.name).orElse("categoría " + id);
                return (key.startsWith("EXPENSE:") ? "Gasto: " : "Ingreso: ") + name;
            } catch (NumberFormatException ex) {
                return key;
            }
        }
        return AccountingCatalog.label(key);
    }

    @Transactional
    public List<MapRow> saveMap(Map<String, String> changes) {
        Long t = TenantContext.require();
        for (var e : changes.entrySet()) {
            String key = e.getKey();
            if (!AccountingCatalog.validKey(key)) throw ApiException.badRequest("Llave del mapa no válida: " + key);
            String code = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue().trim();
            if (code != null && accounts.findByTenantIdAndCode(t, code).filter(a -> a.active).isEmpty()) {
                throw ApiException.badRequest("La cuenta " + code + " no existe o está inactiva (" + label(t, key) + ")");
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
                           String description, BigDecimal total, Long reversalOf, List<LineDto> lines, LocalDate originalDate, String createdBy) {
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
                    e.reversalOf, byEntry.getOrDefault(e.id, List.of()), e.originalDate, e.createdBy);
        }).toList();
    }

    // =================================================================== Estados financieros (propuesta, pendiente de revisión del contador)

    public record StatementRow(String code, String name, BigDecimal amount) {
    }

    public record Section(String title, List<StatementRow> rows, BigDecimal total) {
    }

    /**
     * Estado de resultados del período y balance general al cierre del período, desde los comprobantes. Los comprobantes
     * de cierre de año no cuentan en el estado de resultados (si no, el año cerrado mostraría utilidad cero); el
     * resultado de los años sin cerrar se muestra dentro del patrimonio.
     */
    public record Statements(LocalDate from, LocalDate to, Section income, Section costs, Section expenses, BigDecimal netIncome,
                             Section assets, Section liabilities, Section equity, BigDecimal accumulatedResult,
                             BigDecimal liabilitiesAndEquity, boolean balanced, String notice) {
    }

    public static final String PROPOSAL_NOTICE = "Propuesta, pendiente de revisión del contador";

    public Statements statements(LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select l.account_code,
                       coalesce(sum(case when e.entry_date between :from and :to and e.entry_type <> 'CIERRE' then l.debit - l.credit else 0 end), 0) as period,
                       coalesce(sum(l.debit - l.credit), 0) as cumulative
                from journal_lines l join journal_entries e on e.id = l.entry_id
                where l.tenant_id = :t and e.entry_date <= :to
                group by l.account_code order by l.account_code
                """).setParameter("t", t).setParameter("from", from).setParameter("to", to).getResultList();
        Map<String, String> names = new HashMap<>();
        accounts.findByTenantIdOrderByCodeAsc(t).forEach(a -> names.put(a.code, a.name));
        List<StatementRow> income = new ArrayList<>(), costs = new ArrayList<>(), expenses = new ArrayList<>(),
                assets = new ArrayList<>(), liabilities = new ArrayList<>(), equity = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;   // créditos − débitos de las clases 4 a 7
        for (Object[] r : rows) {
            String code = (String) r[0];
            BigDecimal period = (BigDecimal) r[1], cumulative = (BigDecimal) r[2];
            String name = names.getOrDefault(code, "(cuenta inexistente)");
            switch (code.charAt(0)) {
                case '1' -> add(assets, code, name, cumulative);
                case '2' -> add(liabilities, code, name, cumulative.negate());
                case '3' -> add(equity, code, name, cumulative.negate());
                case '4' -> add(income, code, name, period.negate());
                case '5' -> add(expenses, code, name, period);
                default -> add(costs, code, name, period);   // 6 y 7: costos
            }
            if (code.charAt(0) >= '4') accumulated = accumulated.subtract(cumulative);
        }
        Section in = section("Ingresos", income), co = section("Costos", costs), ex = section("Gastos", expenses);
        Section as = section("Activos", assets), li = section("Pasivos", liabilities), eq = section("Patrimonio", equity);
        BigDecimal net = in.total().subtract(co.total()).subtract(ex.total());
        BigDecimal le = li.total().add(eq.total()).add(accumulated);
        return new Statements(from, to, in, co, ex, net, as, li, eq, accumulated, le, as.total().compareTo(le) == 0, PROPOSAL_NOTICE);
    }

    private static void add(List<StatementRow> list, String code, String name, BigDecimal amount) {
        if (amount.signum() != 0) list.add(new StatementRow(code, name, amount));
    }

    private static Section section(String title, List<StatementRow> rows) {
        return new Section(title, rows, rows.stream().map(StatementRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * Comprobantes del período en columnas de la plantilla de importación de comprobantes contables de Siigo Nube
     * (separador ";"). Propuesta: el contador confirma el tipo de comprobante, las cuentas auxiliares y la versión de la plantilla.
     */
    public String siigoCsv(LocalDate from, LocalDate to, String voucherType) {
        Long t = TenantContext.require();
        StringBuilder sb = new StringBuilder("Tipo de comprobante;Consecutivo comprobante;Fecha de elaboración;Código cuenta contable;"
                + "Identificación tercero;Sucursal;Descripción;Débito;Crédito;Observaciones\r\n");
        java.time.format.DateTimeFormatter dmy = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<JournalEntry> list = new ArrayList<>(entries.between(t, from, to, org.springframework.data.domain.PageRequest.of(0, 100_000)));
        list.sort(Comparator.comparing((JournalEntry e) -> e.entryDate).thenComparing(e -> e.number));
        Map<Long, List<JournalLine>> byEntry = new HashMap<>();
        lines.findByEntryIdIn(list.stream().map(e -> e.id).toList()).forEach(l -> byEntry.computeIfAbsent(l.entryId, k -> new ArrayList<>()).add(l));
        for (JournalEntry e : list) {
            for (JournalLine l : byEntry.getOrDefault(e.id, List.of())) {
                sb.append(csv(voucherType)).append(';').append(e.number).append(';').append(e.entryDate.format(dmy)).append(';')
                        .append(l.accountCode).append(';').append(csv(l.thirdPartyId)).append(";0;")
                        .append(csv(l.description == null ? e.description : l.description)).append(';')
                        .append(l.debit.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(';')
                        .append(l.credit.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(';')
                        .append(csv(e.description)).append("\r\n");
            }
        }
        return sb.toString();
    }

    static String csv(String s) {
        return s == null ? "" : s.replace(";", ",").replace("\n", " ");
    }

    public record PendingDto(Long id, String sourceType, Long sourceId, LocalDate entryDate, String description, String reason) {
    }

    public List<PendingDto> pendingList() {
        return pending.findByTenantIdAndResolvedAtIsNullOrderByEntryDateAscIdAsc(TenantContext.require()).stream()
                .map(p -> new PendingDto(p.id, p.sourceType, p.sourceId, p.entryDate, p.description, p.reason)).toList();
    }

    private static LocalDate date(Instant at) {
        return LocalDate.ofInstant(at == null ? Instant.now() : at, ZONE);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
