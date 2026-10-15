package com.tiendaposs.settings;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class SettingsService {

    public static final Set<String> PAYMENT_METHODS = Set.of("EFECTIVO", "TRANSFERENCIA", "NEQUI", "DAVIPLATA", "BREB", "TARJETA", "OTRO", "FIADO");

    @Repository
    public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Long> {
    }

    private final StoreSettingsRepository repo;
    private final AuditService audit;

    public SettingsService(StoreSettingsRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Ajustes de la tienda actual; si no existen (tienda vieja) se crean con los valores por defecto. */
    @Transactional
    public StoreSettings current() {
        return forTenant(TenantContext.require());
    }

    @Transactional
    public StoreSettings forTenant(Long tenantId) {
        return repo.findById(tenantId).orElseGet(() -> {
            StoreSettings s = new StoreSettings();
            s.tenantId = tenantId;
            return repo.save(s);
        });
    }

    public record SettingsRequest(
            boolean pricesIncludeTax,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal maxDiscountPct,
            boolean allowNegativeStock,
            boolean requireOpenShift,
            @Min(value = 1, message = "mínimo 1") @Max(value = 100, message = "máximo 100") int openAccountsLimit,
            @NotBlank @Pattern(regexp = "PROMEDIO|ULTIMO", message = "usa PROMEDIO o ULTIMO") String costMethod,
            @NotNull @Size(min = 1, message = "activa al menos un medio de pago") List<String> paymentMethods,
            @Size(max = 500, message = "máximo 500 caracteres") String receiptHeader,
            @Size(max = 500, message = "máximo 500 caracteres") String receiptFooter,
            @Min(value = 58, message = "usa 58 u 80") @Max(value = 80, message = "usa 58 u 80") int receiptWidthMm,
            boolean receiptShowTax,
            @NotBlank @Size(max = 30, message = "máximo 30 caracteres") String defaultTaxName,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal defaultTaxRate,
            @NotBlank String cashDenominations) {
    }

    /** Parámetros de inventario y compras: ventanas de análisis, alertas, ABC y presupuesto. */
    public record InventoryParams(
            @Min(value = 7, message = "mínimo 7 días") @Max(value = 365, message = "máximo 365 días") int analysisDays,
            @Min(value = 7, message = "mínimo 7 días") @Max(value = 365, message = "máximo 365 días") int noRotationDays,
            @Min(value = 7, message = "mínimo 7 días") @Max(value = 730, message = "máximo 730 días") int excessDays,
            @Min(value = 1, message = "mínimo 1 día") @Max(value = 180, message = "máximo 180 días") int expiryAlertDays,
            @Min(value = 0, message = "no puede ser negativo") @Max(value = 90, message = "máximo 90 días") int defaultLeadDays,
            @Min(value = 1, message = "mínimo 1 día") @Max(value = 60, message = "máximo 60 días") int reviewDays,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal unusualDiffPct,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unusualDiffValue,
            @NotNull @DecimalMin(value = "1", message = "mínimo 1") @DecimalMax(value = "99", message = "máximo 99") BigDecimal abcAPct,
            @NotNull @DecimalMin(value = "1", message = "mínimo 1") @DecimalMax(value = "99.9", message = "máximo 99,9") BigDecimal abcBPct,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal purchaseBudget) {
    }

    /** Pagos digitales y fiado. */
    public record FinanceParams(boolean verifyDigitalPayments, boolean creditEnabled,
                                @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal defaultCreditLimit,
                                @Min(value = 0, message = "no puede ser negativo") @Max(value = 365, message = "máximo 365") int defaultCreditDays) {
    }

    @Transactional
    public StoreSettings updateFinance(FinanceParams r) {
        StoreSettings s = current();
        s.verifyDigitalPayments = r.verifyDigitalPayments();
        s.creditEnabled = r.creditEnabled();
        s.defaultCreditLimit = r.defaultCreditLimit();
        s.defaultCreditDays = r.defaultCreditDays();
        if (!s.creditEnabled && s.paymentMethodList().contains("FIADO")) {
            s.paymentMethods = String.join(",", s.paymentMethodList().stream().filter(m -> !m.equals("FIADO")).toList());
        }
        // Activar el fiado lo deja disponible como medio de pago en el POS.
        if (s.creditEnabled && !s.paymentMethodList().contains("FIADO")) s.paymentMethods = s.paymentMethods + ",FIADO";
        s.updatedAt = Instant.now();
        audit.sensitive("CONFIG_FINANZAS", "TENANT", s.tenantId, "Actualizó la configuración de pagos y fiado");
        return s;
    }

    @Transactional
    public StoreSettings updateInventory(InventoryParams r) {
        if (r.abcBPct().compareTo(r.abcAPct()) <= 0) throw ApiException.field("abcBPct", "debe ser mayor que el % de la clase A");
        StoreSettings s = current();
        s.analysisDays = r.analysisDays();
        s.noRotationDays = r.noRotationDays();
        s.excessDays = r.excessDays();
        s.expiryAlertDays = r.expiryAlertDays();
        s.defaultLeadDays = r.defaultLeadDays();
        s.reviewDays = r.reviewDays();
        s.unusualDiffPct = r.unusualDiffPct();
        s.unusualDiffValue = r.unusualDiffValue();
        s.abcAPct = r.abcAPct();
        s.abcBPct = r.abcBPct();
        s.purchaseBudget = r.purchaseBudget();
        s.updatedAt = Instant.now();
        audit.sensitive("CONFIG_INVENTARIO", "TENANT", s.tenantId, "Actualizó los parámetros de inventario y compras");
        return s;
    }

    @Transactional
    public StoreSettings update(SettingsRequest r) {
        StoreSettings s = current();
        for (String m : r.paymentMethods()) {
            if (!PAYMENT_METHODS.contains(m)) throw ApiException.field("paymentMethods", "medio de pago desconocido: " + m);
        }
        if (r.paymentMethods().contains("FIADO") && !s.creditEnabled) {
            throw ApiException.field("paymentMethods", "activa primero el fiado en la configuración de pagos");
        }
        if (!r.paymentMethods().contains("EFECTIVO")) {
            throw ApiException.field("paymentMethods", "el efectivo siempre debe estar activo");
        }
        String denoms = r.cashDenominations().replaceAll("\\s", "");
        if (!denoms.matches("\\d+(,\\d+)*")) throw ApiException.field("cashDenominations", "escribe valores separados por coma");
        s.pricesIncludeTax = r.pricesIncludeTax();
        s.maxDiscountPct = r.maxDiscountPct();
        s.allowNegativeStock = r.allowNegativeStock();
        s.requireOpenShift = r.requireOpenShift();
        s.openAccountsLimit = r.openAccountsLimit();
        s.costMethod = r.costMethod();
        s.paymentMethods = String.join(",", r.paymentMethods());
        s.receiptHeader = blank(r.receiptHeader());
        s.receiptFooter = blank(r.receiptFooter());
        s.receiptWidthMm = r.receiptWidthMm() < 70 ? 58 : 80;
        s.receiptShowTax = r.receiptShowTax();
        s.defaultTaxName = r.defaultTaxName().trim();
        s.defaultTaxRate = r.defaultTaxRate();
        s.cashDenominations = denoms;
        s.updatedAt = Instant.now();
        audit.sensitive("CONFIG_POS", "TENANT", s.tenantId, "Actualizó la configuración del punto de venta (impuestos, comprobante, medios de pago, límites)");
        return s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
