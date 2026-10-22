package com.tiendaposs.accounting;

import com.tiendaposs.accounting.AccountingRepos.TaxSettingsRepository;
import com.tiendaposs.accounting.AccountingRepos.WithholdingRepository;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.fiscal.FiscalProfile;
import com.tiendaposs.fiscal.FiscalRepos.FiscalProfileRepository;
import com.tiendaposs.purchasing.GoodsReceipt;
import com.tiendaposs.purchasing.GoodsReceiptLine;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptLineRepository;
import com.tiendaposs.purchasing.PurchasingRepos.GoodsReceiptRepository;
import com.tiendaposs.purchasing.PurchasingRepos.SupplierInvoiceRepository;
import com.tiendaposs.purchasing.Supplier;
import com.tiendaposs.purchasing.SupplierInvoice;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Fase 14: retenciones en compras (retención en la fuente, ReteIVA y ReteICA). El sistema las PROPONE al recibir la
 * mercancía según las reglas de la tienda y los datos del proveedor; una persona las revisa y puede cambiarlas. Lo
 * retenido no se le paga al proveedor (baja su cuenta por pagar) y queda por declarar.
 * <p>
 * Los valores por defecto (UVT, base mínima y tarifas) son una propuesta: cambian con la norma y los confirma el contador.
 */
@Service
public class WithholdingService {

    public static final List<String> KINDS = List.of("RETEFUENTE", "RETEIVA", "RETEICA");

    private final TaxSettingsRepository settingsRepo;
    private final WithholdingRepository withholdings;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository receiptLines;
    private final SupplierInvoiceRepository supplierInvoices;
    private final FiscalProfileRepository profiles;
    private final AuditService audit;
    private final EntityManager em;

    @Autowired
    @Lazy
    private AccountingService accounting;

    public WithholdingService(TaxSettingsRepository settingsRepo, WithholdingRepository withholdings, GoodsReceiptRepository receipts,
                              GoodsReceiptLineRepository receiptLines, SupplierInvoiceRepository supplierInvoices, FiscalProfileRepository profiles,
                              AuditService audit, EntityManager em) {
        this.settingsRepo = settingsRepo;
        this.withholdings = withholdings;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.supplierInvoices = supplierInvoices;
        this.profiles = profiles;
        this.audit = audit;
        this.em = em;
    }

    // ---------------- Reglas de la tienda ----------------

    public record SettingsDto(BigDecimal uvt, boolean retentionAgent, BigDecimal purchasesBaseUvt, BigDecimal purchasesBase,
                              BigDecimal purchasesRateDeclarant, BigDecimal purchasesRateOther, boolean reteivaAgent, BigDecimal reteivaRate,
                              BigDecimal reteicaPerMil, boolean configured, List<String> hints) {
    }

    public record SettingsRequest(@NotNull @DecimalMin(value = "1", message = "escribe la UVT del año") BigDecimal uvt, boolean retentionAgent,
                                  @NotNull @DecimalMin("0") BigDecimal purchasesBaseUvt,
                                  @NotNull @DecimalMin("0") @DecimalMax(value = "100", message = "máximo 100 %") BigDecimal purchasesRateDeclarant,
                                  @NotNull @DecimalMin("0") @DecimalMax(value = "100", message = "máximo 100 %") BigDecimal purchasesRateOther,
                                  boolean reteivaAgent,
                                  @NotNull @DecimalMin("0") @DecimalMax(value = "100", message = "máximo 100 %") BigDecimal reteivaRate,
                                  @DecimalMin("0") @DecimalMax(value = "100", message = "máximo 100 por mil") BigDecimal reteicaPerMil) {
    }

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        Long t = TenantContext.require();
        return dto(settingsOf(t));
    }

    @Transactional
    public SettingsDto saveSettings(SettingsRequest r) {
        CurrentUser.require(Permission.CONTABILIDAD_EDITAR);
        Long t = TenantContext.require();
        AccountingTaxSettings s = settingsOf(t);
        s.uvt = r.uvt();
        s.retentionAgent = r.retentionAgent();
        s.purchasesBaseUvt = r.purchasesBaseUvt();
        s.purchasesRateDeclarant = r.purchasesRateDeclarant();
        s.purchasesRateOther = r.purchasesRateOther();
        s.reteivaAgent = r.reteivaAgent();
        s.reteivaRate = r.reteivaRate();
        s.reteicaPerMil = r.reteicaPerMil() == null || r.reteicaPerMil().signum() == 0 ? null : r.reteicaPerMil();
        s.configured = true;
        s.updatedAt = java.time.Instant.now();
        settingsRepo.save(s);
        audit.sensitive("RETENCIONES_CONFIGURADAS", "TENANT", t, "Configuró las retenciones: " + (s.retentionAgent ? "retención en la fuente " +
                s.purchasesRateDeclarant.stripTrailingZeros().toPlainString() + " %/" + s.purchasesRateOther.stripTrailingZeros().toPlainString()
                + " % desde " + s.purchasesBaseUvt.stripTrailingZeros().toPlainString() + " UVT" : "sin retención en la fuente")
                + (s.reteivaAgent ? ", ReteIVA" : "") + (s.reteicaPerMil != null ? ", ReteICA " + s.reteicaPerMil.stripTrailingZeros().toPlainString() + " x mil" : ""));
        return dto(s);
    }

    private AccountingTaxSettings settingsOf(Long t) {
        return settingsRepo.findById(t).orElseGet(() -> {
            AccountingTaxSettings s = new AccountingTaxSettings();
            s.tenantId = t;
            FiscalProfile p = profiles.findById(t).orElse(null);
            if (p != null) {
                boolean simple = p.responsibilities != null && p.responsibilities.contains("O-47");
                s.retentionAgent = "JURIDICA".equals(p.personType) && !simple;
                s.reteivaAgent = p.responsibilities != null && (p.responsibilities.contains("O-13") || p.responsibilities.contains("O-23"));
            }
            return s;
        });
    }

    private SettingsDto dto(AccountingTaxSettings s) {
        List<String> hints = new ArrayList<>();
        hints.add("Valores de partida: confírmalos con tu contador. La UVT, las bases y las tarifas cambian con la norma.");
        if (!s.configured) hints.add("Mientras no guardes esta configuración no se propone ninguna retención.");
        return new SettingsDto(s.uvt, s.retentionAgent, s.purchasesBaseUvt, s.purchasesBaseUvt.multiply(s.uvt).setScale(0, RoundingMode.HALF_UP),
                s.purchasesRateDeclarant, s.purchasesRateOther, s.reteivaAgent, s.reteivaRate, s.reteicaPerMil, s.configured, hints);
    }

    // ---------------- Propuesta al recibir ----------------

    /** Base (valor antes de IVA) e IVA de una compra: de la factura importada si la hay; si no, el valor recibido. */
    private BigDecimal[] baseAndTax(GoodsReceipt rec) {
        SupplierInvoice inv = supplierInvoices.findByReceiptId(rec.id).orElse(null);
        if (inv != null) return new BigDecimal[]{inv.subtotal, inv.taxTotal == null ? BigDecimal.ZERO : inv.taxTotal};
        BigDecimal value = BigDecimal.ZERO;
        for (GoodsReceiptLine l : receiptLines.findByReceiptIdIn(List.of(rec.id))) value = value.add(l.unitsReceived.multiply(l.unitCost));
        return new BigDecimal[]{value.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO};
    }

    /** Propone las retenciones de una compra (una sola vez). No hace nada si la tienda no las ha configurado. */
    void propose(GoodsReceipt rec) {
        AccountingTaxSettings s = settingsRepo.findById(rec.tenantId).orElse(null);
        if (s == null || !s.configured) return;
        if (!withholdings.findByReceiptIdOrderByIdAsc(rec.id).isEmpty()) return;
        Supplier sup = em.find(Supplier.class, rec.supplierId);
        BigDecimal[] bt = baseAndTax(rec);
        BigDecimal base = bt[0], iva = bt[1];
        List<ReceiptWithholding> out = new ArrayList<>();
        if (s.retentionAgent && !sup.selfWithholder && !sup.simpleRegime
                && base.compareTo(s.purchasesBaseUvt.multiply(s.uvt)) >= 0) {
            out.add(row(rec, "RETEFUENTE", base, sup.incomeTaxDeclarant ? s.purchasesRateDeclarant : s.purchasesRateOther));
        }
        if (s.reteivaAgent && Boolean.TRUE.equals(sup.ivaResponsible) && !sup.greatContributor && iva.signum() > 0) {
            out.add(row(rec, "RETEIVA", iva, s.reteivaRate));
        }
        if (s.reteicaPerMil != null && base.signum() > 0) {
            out.add(row(rec, "RETEICA", base, s.reteicaPerMil.divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP)));
        }
        BigDecimal total = out.stream().map(w -> w.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return;
        if (total.compareTo(rec.payable()) > 0) return; // nunca más de lo que se le debe (p. ej. compra ya pagada)
        withholdings.saveAll(out);
        rec.withheld = total;
        receipts.save(rec);
    }

    private static ReceiptWithholding row(GoodsReceipt rec, String kind, BigDecimal base, BigDecimal rate) {
        ReceiptWithholding w = new ReceiptWithholding();
        w.tenantId = rec.tenantId;
        w.receiptId = rec.id;
        w.kind = kind;
        w.base = base.setScale(2, RoundingMode.HALF_UP);
        w.rate = rate;
        w.amount = amount(base, rate);
        return w;
    }

    /** Las retenciones se redondean al peso. */
    private static BigDecimal amount(BigDecimal base, BigDecimal ratePct) {
        return base.multiply(ratePct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    // ---------------- Revisar o cambiar las de una compra ----------------

    public record ItemDto(String kind, BigDecimal base, BigDecimal rate, BigDecimal amount, boolean confirmed) {
    }

    public record ReceiptView(Long receiptId, long number, String supplierName, BigDecimal total, BigDecimal paid, BigDecimal withheld,
                              BigDecimal payable, BigDecimal suggestedBase, BigDecimal invoiceTax, List<ItemDto> items) {
    }

    public record Item(@NotNull @Pattern(regexp = "RETEFUENTE|RETEIVA|RETEICA", message = "tipo no válido") String kind,
                       @NotNull @DecimalMin(value = "0", message = "no puede ser negativa") BigDecimal base,
                       @NotNull @DecimalMin(value = "0", message = "no puede ser negativa") @DecimalMax(value = "100", message = "máximo 100 %") BigDecimal rate) {
    }

    public record UpdateRequest(@NotNull List<@Valid Item> items) {
    }

    @Transactional(readOnly = true)
    public ReceiptView forReceipt(Long receiptId) {
        GoodsReceipt rec = receipts.findByIdAndTenantId(receiptId, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Recepción no encontrada"));
        return view(rec);
    }

    private ReceiptView view(GoodsReceipt rec) {
        Supplier s = em.find(Supplier.class, rec.supplierId);
        BigDecimal[] bt = baseAndTax(rec);
        return new ReceiptView(rec.id, rec.number, s.name, rec.total, rec.paidAmount, rec.withheld, rec.payable(), bt[0], bt[1],
                withholdings.findByReceiptIdOrderByIdAsc(rec.id).stream().map(w -> new ItemDto(w.kind, w.base, w.rate, w.amount, w.confirmed)).toList());
    }

    @Transactional
    public ReceiptView update(Long receiptId, UpdateRequest r) {
        if (!CurrentUser.get().can(Permission.CONTABILIDAD_EDITAR)) CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = TenantContext.require();
        GoodsReceipt rec = receipts.findByIdAndTenantId(receiptId, t).orElseThrow(() -> ApiException.notFound("Recepción no encontrada"));
        Set<String> seen = new HashSet<>();
        Map<String, BigDecimal> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (ReceiptWithholding w : withholdings.findByReceiptIdOrderByIdAsc(rec.id)) before.merge(w.kind, w.amount, BigDecimal::add);
        List<ReceiptWithholding> rows = new ArrayList<>();
        for (Item i : r.items()) {
            if (!seen.add(i.kind())) throw ApiException.field("items", "cada retención va una sola vez");
            ReceiptWithholding w = row(rec, i.kind(), i.base(), i.rate());
            w.confirmed = true;
            if (w.amount.signum() > 0) {
                rows.add(w);
                after.merge(w.kind, w.amount, BigDecimal::add);
            }
        }
        BigDecimal total = after.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(rec.total.subtract(rec.paidAmount)) > 0) {
            throw ApiException.field("items", "lo retenido (" + Money.fmt(total) + ") no puede pasar de lo que falta por pagar ("
                    + Money.fmt(rec.total.subtract(rec.paidAmount)) + ")");
        }
        withholdings.deleteByReceiptId(rec.id);
        withholdings.flush();
        withholdings.saveAll(rows);
        rec.withheld = total;
        receipts.save(rec);
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        for (String k : KINDS) {
            BigDecimal d = after.getOrDefault(k, BigDecimal.ZERO).subtract(before.getOrDefault(k, BigDecimal.ZERO));
            if (d.signum() != 0) deltas.put(k, d);
        }
        if (!deltas.isEmpty()) accounting.onWithholdingChanged(rec, deltas);
        audit.sensitive("RETENCIONES_EDITADAS", "RECEIPT", rec.id, "Cambió las retenciones de la recepción #" + rec.number + ": "
                + Money.fmt(before.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)) + " → " + Money.fmt(total));
        return view(rec);
    }

    // ---------------- Para declarar ----------------

    public record ReportRow(String kind, String supplierName, String supplierId, BigDecimal base, BigDecimal amount, int receipts) {
    }

    public record Report(LocalDate from, LocalDate to, Map<String, BigDecimal> totals, List<ReportRow> rows) {
    }

    /** Lo retenido en el período por tipo y por proveedor (para la declaración y los certificados). */
    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to) {
        Long t = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select w.kind, s.name, s.tax_id, sum(w.base), sum(w.amount), count(distinct r.id)
                from receipt_withholdings w join goods_receipts r on r.id = w.receipt_id join suppliers s on s.id = r.supplier_id
                where w.tenant_id = :t and cast(r.received_at at time zone 'America/Bogota' as date) between :f and :to
                group by w.kind, s.name, s.tax_id order by w.kind, sum(w.amount) desc""")
                .setParameter("t", t).setParameter("f", from).setParameter("to", to).getResultList();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        KINDS.forEach(k -> totals.put(k, BigDecimal.ZERO));
        List<ReportRow> out = new ArrayList<>();
        for (Object[] x : rows) {
            BigDecimal amount = (BigDecimal) x[4];
            totals.merge((String) x[0], amount, BigDecimal::add);
            out.add(new ReportRow((String) x[0], (String) x[1], (String) x[2], (BigDecimal) x[3], amount, ((Number) x[5]).intValue()));
        }
        return new Report(from, to, totals, out);
    }
}
