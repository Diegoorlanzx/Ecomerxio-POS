package com.tiendaposs.pharmacy;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService.ProductLotRepository;
import com.tiendaposs.inventory.ProductLot;
import com.tiendaposs.pos.SaleDtos.PrescriptionRequest;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Droguería (tipos de negocio, fase 2): registro INVIMA y principio activo por producto, fórmula médica obligatoria al vender
 * medicamentos que la exigen, nunca vender unidades de lotes vencidos y lote + vencimiento obligatorios al recibir.
 * Todo aplica solo a las tiendas con el módulo DROGUERIA.
 */
@Service
public class PharmacyService {

    public static final String MODULE = "DROGUERIA";

    public record PharmacyInfo(String invimaCode, String activeIngredient, boolean requiresPrescription, boolean controlled) {
    }

    public record PrescriptionRow(Long saleId, Long saleNumber, Instant soldAt, String sellerName, String doctorName, String doctorLicense,
                                  String prescriptionNumber, LocalDate prescriptionDate, String patientName, String patientDoc,
                                  boolean controlled, String products) {
    }

    private final SettingsService settings;
    private final ProductRepository products;
    private final ProductLotRepository lots;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PharmacyService(SettingsService settings, ProductRepository products, ProductLotRepository lots, JdbcTemplate jdbc, AuditService audit) {
        this.settings = settings;
        this.products = products;
        this.lots = lots;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean enabled(Long tenantId) {
        return settings.forTenant(tenantId).hasModule(MODULE);
    }

    // ------------------------------------------------------------ Producto

    @Transactional
    public PharmacyInfo update(Long productId, PharmacyInfo r) {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), MODULE);
        Product p = products.findByIdAndTenantId(productId, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        String invima = clean(r.invimaCode(), 40);
        if (invima != null && !invima.matches("[A-Za-z0-9\\-/ ]{4,40}")) throw ApiException.field("invimaCode", "escribe el registro como aparece en el empaque, p. ej. INVIMA 2019M-0012345");
        p.invimaCode = invima;
        p.activeIngredient = clean(r.activeIngredient(), 160);
        p.requiresPrescription = r.requiresPrescription() || r.controlled();
        p.controlled = r.controlled();
        products.save(p);
        audit.log("PRODUCTO_DROGUERIA", "PRODUCT", p.id, "Datos de droguería de " + p.name + (p.requiresPrescription ? " (con fórmula)" : "")
                + (p.controlled ? " · control especial" : ""));
        return info(p);
    }

    public static PharmacyInfo info(Product p) {
        return new PharmacyInfo(p.invimaCode, p.activeIngredient, p.requiresPrescription, p.controlled);
    }

    // ------------------------------------------------------------ Venta

    /**
     * Antes de cobrar: si la venta lleva medicamentos con fórmula, exige los datos de la fórmula; y no deja vender unidades
     * de lotes vencidos. Sin internet la venta ya se hizo: no se rechaza, queda marcada para revisión.
     */
    public void checkSale(Long tenantId, List<SaleLine> lines, PrescriptionRequest rx, boolean offline, Set<String> flags) {
        if (!enabled(tenantId)) return;
        List<String> needRx = new ArrayList<>();
        Map<Long, BigDecimal> units = new LinkedHashMap<>();
        Map<Long, Product> byId = new HashMap<>();
        for (SaleLine l : lines) {
            Product p = products.findByIdAndTenantId(l.productId, tenantId).orElseThrow();
            byId.put(p.id, p);
            if (p.requiresPrescription && !needRx.contains(p.name)) needRx.add(p.name);
            if (p.trackLots) units.merge(p.id, l.quantity.multiply(l.factor), BigDecimal::add);
        }
        if (!needRx.isEmpty() && !complete(rx)) {
            if (!offline) {
                throw ApiException.badRequest("Esta venta lleva medicamentos con fórmula médica (" + String.join(", ", needRx)
                        + "): registra el médico, su registro, la fecha de la fórmula y el paciente.");
            }
            flags.add("FORMULA_FALTANTE");
        }
        LocalDate today = LocalDate.now();
        for (var e : units.entrySet()) {
            BigDecimal good = BigDecimal.ZERO;
            boolean anyExpired = false;
            for (ProductLot lot : lots.available(e.getKey())) {
                if (lot.expiryDate != null && lot.expiryDate.isBefore(today)) anyExpired = true;
                else good = good.add(lot.remaining);
            }
            if (anyExpired && good.compareTo(e.getValue()) < 0) {
                if (!offline) {
                    throw ApiException.badRequest(byId.get(e.getKey()).name + ": solo quedan " + good.stripTrailingZeros().toPlainString()
                            + " unidades sin vencer. Las vencidas no se venden: dalas de baja en Inventario.");
                }
                flags.add("LOTE_VENCIDO");
            }
        }
    }

    /** Después de cobrar: guarda la fórmula con la venta (libro de fórmulas despachadas). */
    @Transactional
    public void recordPrescription(Long tenantId, Long saleId, List<SaleLine> lines, PrescriptionRequest rx) {
        if (!enabled(tenantId) || rx == null) return;
        List<String> meds = new ArrayList<>();
        boolean controlled = false;
        for (SaleLine l : lines) {
            Product p = products.findByIdAndTenantId(l.productId, tenantId).orElse(null);
            if (p == null || !p.requiresPrescription) continue;
            controlled |= p.controlled;
            meds.add(l.quantity.stripTrailingZeros().toPlainString() + " × " + p.name + " (" + l.presentationName + ")");
        }
        if (meds.isEmpty()) return;
        String list = String.join("; ", meds);
        jdbc.update("""
                INSERT INTO sale_prescriptions (sale_id, tenant_id, doctor_name, doctor_license, prescription_number, prescription_date,
                                                patient_name, patient_doc, controlled, products)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (sale_id) DO NOTHING""",
                saleId, tenantId, clean(rx.doctorName(), 120), clean(rx.doctorLicense(), 40), clean(rx.prescriptionNumber(), 40),
                rx.prescriptionDate() == null ? null : Date.valueOf(rx.prescriptionDate()), clean(rx.patientName(), 120),
                clean(rx.patientDoc(), 30), controlled, list.length() > 1000 ? list.substring(0, 1000) : list);
    }

    public List<PrescriptionRow> prescriptions(LocalDate from, LocalDate to, boolean onlyControlled) {
        Long t = TenantContext.require();
        return jdbc.query("""
                SELECT r.sale_id, s.number, s.completed_at, s.user_name, r.doctor_name, r.doctor_license, r.prescription_number,
                       r.prescription_date, r.patient_name, r.patient_doc, r.controlled, r.products
                FROM sale_prescriptions r JOIN sales s ON s.id = r.sale_id
                WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ? AND (? = FALSE OR r.controlled)
                ORDER BY r.created_at DESC""",
                (rs, i) -> new PrescriptionRow(rs.getLong(1), rs.getObject(2) == null ? null : rs.getLong(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate(), rs.getString(9), rs.getString(10),
                        rs.getBoolean(11), rs.getString(12)),
                t, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.plusDays(1).atStartOfDay()), onlyControlled);
    }

    // ------------------------------------------------------------ Recepción

    /** Al recibir mercancía en una droguería, los productos con lotes llevan lote y fecha de vencimiento. */
    public void checkReceipt(Product p, BigDecimal received, String lotCode, LocalDate expiry) {
        if (!p.trackLots || received == null || received.signum() <= 0 || !enabled(p.tenantId)) return;
        if (lotCode == null || lotCode.isBlank() || expiry == null) {
            throw ApiException.field("lines", p.name + ": registra el lote y la fecha de vencimiento");
        }
    }

    private static boolean complete(PrescriptionRequest rx) {
        return rx != null && notBlank(rx.doctorName()) && notBlank(rx.doctorLicense()) && rx.prescriptionDate() != null && notBlank(rx.patientName());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
