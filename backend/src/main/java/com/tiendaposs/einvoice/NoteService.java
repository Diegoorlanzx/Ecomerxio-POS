package com.tiendaposs.einvoice;

import com.tiendaposs.accounting.AccountingService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.einvoice.EInvoiceRepos.DocumentRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fase 16E: notas que no nacen de una devolución o anulación.
 * <ul>
 *   <li>Nota débito sobre una factura aceptada: intereses de mora de un fiado, gastos por cobrar, cambio de valor.</li>
 *   <li>Nota crédito manual: rebaja o descuento, ajuste de precio, pronto pago, volumen.</li>
 *   <li>Nota de ajuste al documento soporte (compras a no obligados).</li>
 * </ul>
 * Cada nota se arregla por la cuenta del cliente (FIADO: la nota débito suma a su deuda, la crédito la rebaja) o por un
 * medio de pago aparte; con eso se arma el comprobante contable.
 */
@Service
public class NoteService {

    /** Conceptos DIAN por tipo de nota (los de anulación y devolución salen solos de Ventas). */
    public static final Map<String, Map<String, String>> CONCEPTS = Map.of(
            EInvoiceService.NOTA_CREDITO, Map.of("3", "Rebaja o descuento", "4", "Ajuste de precio",
                    "5", "Descuento por pronto pago", "6", "Descuento por volumen de ventas"),
            EInvoiceService.NOTA_DEBITO, Map.of("1", "Intereses", "2", "Gastos por cobrar", "3", "Cambio del valor", "4", "Otros"),
            EInvoiceService.NOTA_AJUSTE_SOPORTE, Map.of("1", "Devolución parcial", "2", "Anulación", "3", "Rebaja o descuento",
                    "4", "Ajuste de precio", "5", "Otros"));

    private static final Set<BigDecimal> RATES = Set.of(BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("19"));

    private final EInvoiceService einvoice;
    private final DocumentRepository docs;
    private final SaleRepository sales;
    private final CreditService credit;
    private final AccountingService accounting;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    public NoteService(EInvoiceService einvoice, DocumentRepository docs, SaleRepository sales, CreditService credit,
                       AccountingService accounting, SettingsService settings, TransactionTemplate tx) {
        this.einvoice = einvoice;
        this.docs = docs;
        this.sales = sales;
        this.credit = credit;
        this.accounting = accounting;
        this.settings = settings;
        this.tx = tx;
    }

    public record NoteRequest(@NotBlank @Pattern(regexp = "NOTA_CREDITO|NOTA_DEBITO|NOTA_AJUSTE_SOPORTE", message = "tipo de nota no válido") String type,
                              @NotBlank(message = "elige el concepto") String concept,
                              @NotBlank(message = "describe la nota") @Size(max = 200, message = "máximo 200 caracteres") String description,
                              @NotNull(message = "escribe el valor") @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                              BigDecimal taxRate,
                              @NotBlank(message = "escribe el motivo") @Size(max = 300, message = "máximo 300 caracteres") String reason,
                              /** FIADO o un medio de pago; no aplica al documento soporte. */ String settlement) {
    }

    public ElectronicDocument create(Long originalId, NoteRequest r) {
        Long t = TenantContext.require();
        Long id = tx.execute(st -> {
            ElectronicDocument original = docs.findByIdAndTenantId(originalId, t).orElseThrow(() -> ApiException.notFound("Documento no encontrado"));
            return issue(original, r).id;
        });
        return docs.findById(id).orElseThrow();
    }

    /** Arma la nota dentro de la transacción en curso (también la usa el cobro de intereses del fiado). */
    ElectronicDocument issue(ElectronicDocument original, NoteRequest r) {
        Map<String, String> concepts = CONCEPTS.get(r.type());
        if (concepts == null || !concepts.containsKey(r.concept())) throw ApiException.field("concept", "concepto no válido para esta nota");
        boolean support = EInvoiceService.NOTA_AJUSTE_SOPORTE.equals(r.type());
        if (support != EInvoiceService.SOPORTE.equals(original.documentType)) {
            throw ApiException.badRequest(support ? "La nota de ajuste es solo para documentos soporte."
                    : "Las notas crédito y débito van sobre una factura o un documento POS electrónico.");
        }
        if (!support && !Set.of(EInvoiceService.FACTURA, EInvoiceService.POS).contains(original.documentType)) {
            throw ApiException.badRequest("Las notas crédito y débito van sobre una factura o un documento POS electrónico.");
        }
        if (!EInvoiceService.ACEPTADO.equals(original.status)) {
            throw ApiException.badRequest("El documento " + original.fullNumber + " todavía no está aceptado: la nota se hace después.");
        }
        if (original.superseded) throw ApiException.badRequest("El documento " + original.fullNumber + " fue reemplazado: haz la nota sobre el nuevo.");
        BigDecimal rate = r.taxRate() == null ? BigDecimal.ZERO : r.taxRate().stripTrailingZeros();
        if (RATES.stream().noneMatch(x -> x.compareTo(rate) == 0)) throw ApiException.field("taxRate", "el IVA es 0, 5 o 19 %");
        if (support && rate.signum() != 0) throw ApiException.field("taxRate", "el documento soporte no lleva IVA");
        BigDecimal amount = r.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        boolean lowers = !EInvoiceService.NOTA_DEBITO.equals(r.type());
        if (lowers) {
            BigDecimal left = einvoice.remaining(original);
            if (amount.compareTo(left) > 0) {
                throw ApiException.field("amount", "no puede pasar de lo que queda del documento: " + com.tiendaposs.common.Money.fmt(left));
            }
            if (support && "2".equals(r.concept()) && amount.compareTo(left) != 0) {
                throw ApiException.field("amount", "la anulación es por el total que queda: " + com.tiendaposs.common.Money.fmt(left));
            }
        }

        String settlement = null;
        Sale sale = original.saleId == null ? null : sales.findById(original.saleId).orElse(null);
        if (!support) {
            settlement = r.settlement() == null || r.settlement().isBlank() ? null : r.settlement().trim().toUpperCase();
            if (settlement == null) throw ApiException.field("settlement", "elige cómo se arregla: cuenta del cliente o medio de pago");
            if ("FIADO".equals(settlement)) {
                if (sale == null || sale.customerId == null) throw ApiException.field("settlement", "la venta no tiene cliente: elige un medio de pago");
            } else if (!settings.forTenant(original.tenantId).paymentMethodList().contains(settlement)) {
                throw ApiException.field("settlement", "medio de pago no válido");
            }
        }

        ElectronicDocument note = einvoice.issueManualNote(original, r.type(), r.concept(), r.description().trim(), amount, rate,
                concepts.get(r.concept()) + ": " + r.reason().trim(), settlement);
        if ("FIADO".equals(settlement)) {
            String why = (lowers ? "Nota crédito " : "Nota débito ") + note.fullNumber + " (" + concepts.get(r.concept()).toLowerCase() + ")";
            if (lowers) credit.creditReturn(sale, amount, why);
            else credit.chargeExtra(sale, amount, why);
        }
        accounting.onElectronicNote(note);
        return note;
    }

    public record InterestRequest(@NotNull(message = "elige la venta") Long saleId,
                                  @NotNull(message = "escribe el valor") @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                                  @Size(max = 300, message = "máximo 300 caracteres") String note) {
    }

    public record InterestResult(Long saleId, BigDecimal amount, String debitNoteNumber) {
    }

    /**
     * Intereses de mora de un fiado: suman a la deuda del cliente. Si la venta tiene factura aceptada, sale además la
     * nota débito (concepto intereses); si no factura, queda solo el cargo en la cartera.
     */
    public InterestResult interest(Long customerId, InterestRequest r) {
        CurrentUser.require(com.tiendaposs.security.Permission.FIADO_AUTORIZAR);
        Long t = TenantContext.require();
        return tx.execute(st -> {
            Sale s = sales.findById(r.saleId()).filter(x -> x.tenantId.equals(t) && customerId.equals(x.customerId))
                    .orElseThrow(() -> ApiException.notFound("Esa venta no es un fiado de este cliente"));
            String why = r.note() == null || r.note().isBlank() ? "Intereses de mora de la venta #" + s.number : r.note().trim();
            ElectronicDocument doc = docs.findSaleDocument(s.id).filter(d -> EInvoiceService.ACEPTADO.equals(d.status)).orElse(null);
            if (doc == null) {
                credit.chargeExtra(s, r.amount(), why);
                return new InterestResult(s.id, r.amount(), null);
            }
            ElectronicDocument nd = issue(doc, new NoteRequest(EInvoiceService.NOTA_DEBITO, "1", "Intereses de mora", r.amount(),
                    BigDecimal.ZERO, why, "FIADO"));
            return new InterestResult(s.id, r.amount(), nd.fullNumber);
        });
    }

    /** Para la pantalla: conceptos por tipo. */
    public Map<String, List<Map<String, String>>> concepts() {
        Map<String, List<Map<String, String>>> out = new java.util.LinkedHashMap<>();
        CONCEPTS.forEach((type, m) -> out.put(type, m.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> Map.of("code", e.getKey(), "label", e.getValue())).toList()));
        return out;
    }
}
