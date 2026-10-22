package com.tiendaposs.pos;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.PosRepos.PromotionRepository;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Clientes y promociones. */
@Service
public class PosCrudService {

    private final CustomerRepository customers;
    private final PromotionRepository promotions;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final AuditService audit;

    public PosCrudService(CustomerRepository customers, PromotionRepository promotions, ProductRepository products,
                          CategoryRepository categories, AuditService audit) {
        this.customers = customers;
        this.promotions = promotions;
        this.products = products;
        this.categories = categories;
        this.audit = audit;
    }

    // ---------------- Clientes ----------------

    public record CustomerDto(Long id, String name, String docType, String docNumber, String phone, String email,
                              String address, String notes,
                              /** Datos para facturarle a su nombre (Fase 13A). */
                              String dv, String personType, String invoiceEmail, List<String> fiscalResponsibilities,
                              String municipalityCode, Boolean ivaResponsible,
                              /** Fase 16A: SIEMPRE, NUNCA o PREGUNTAR. */ String invoicePreference,
                              /** Qué falta para facturarle a su nombre (vacío = listo). */
                              List<String> fiscalMissing) {
        static CustomerDto of(Customer c) {
            return new CustomerDto(c.id, c.name, c.docType, c.docNumber, c.phone, c.email, c.address, c.notes,
                    c.dv, c.personType, c.invoiceEmail,
                    c.fiscalResponsibilities == null ? List.of() : List.of(c.fiscalResponsibilities.split(",")),
                    c.municipalityCode, c.ivaResponsible, c.invoicePreference, PosCrudService.fiscalMissing(c));
        }
    }

    /** Lo mínimo para expedirle factura electrónica a su nombre. */
    public static List<String> fiscalMissingOf(Customer c) {
        return fiscalMissing(c);
    }

    static List<String> fiscalMissing(Customer c) {
        List<String> m = new java.util.ArrayList<>();
        if (c.docType == null || !com.tiendaposs.fiscal.FiscalCodes.ID_TYPES.containsKey(c.docType)) m.add("tipo de documento");
        if (c.docNumber == null) m.add("número de documento");
        else if ("NIT".equals(c.docType) && !com.tiendaposs.fiscal.Nit.matches(c.docNumber, c.dv)) m.add("dígito de verificación correcto");
        if (c.personType == null) m.add("tipo de persona");
        if (c.invoiceEmail == null && c.email == null) m.add("correo para enviarle la factura");
        return m;
    }

    public record CustomerUpsert(@NotBlank @Size(max = 120, message = "máximo 120 caracteres") String name,
                                 @Size(max = 10) String docType, @Size(max = 30) String docNumber,
                                 @Size(max = 30) String phone, @Email(message = "no es un correo válido") String email,
                                 @Size(max = 200) String address, @Size(max = 500) String notes,
                                 @Size(max = 1, message = "es un solo dígito") String dv,
                                 @Pattern(regexp = "NATURAL|JURIDICA", message = "elige persona natural o jurídica") String personType,
                                 @Email(message = "no es un correo válido") @Size(max = 160) String invoiceEmail,
                                 List<String> fiscalResponsibilities,
                                 @Pattern(regexp = "^$|[0-9]{5}", message = "son 5 dígitos (código DANE del municipio)") String municipalityCode,
                                 Boolean ivaResponsible,
                                 @Pattern(regexp = "SIEMPRE|NUNCA|PREGUNTAR", message = "elige siempre, nunca o preguntar") String invoicePreference) {
    }

    @Transactional(readOnly = true)
    public List<CustomerDto> searchCustomers(String q) {
        String like = "%" + (q == null ? "" : q.trim().toLowerCase()) + "%";
        return customers.search(TenantContext.require(), like, PageRequest.of(0, 30)).stream().map(CustomerDto::of).toList();
    }

    @Transactional
    public CustomerDto saveCustomer(Long id, CustomerUpsert r) {
        Long t = TenantContext.require();
        Customer c = id == null ? new Customer() : customers.findByIdAndTenantId(id, t)
                .orElseThrow(() -> ApiException.notFound("Cliente no encontrado"));
        String doc = blank(r.docNumber());
        if (doc != null) {
            customers.findByTenantIdAndDocNumber(t, doc).filter(o -> !o.id.equals(c.id)).ifPresent(o -> {
                throw ApiException.field("docNumber", "ya hay un cliente con ese documento: " + o.name);
            });
        }
        String docType = blank(r.docType());
        if (docType != null && !com.tiendaposs.fiscal.FiscalCodes.ID_TYPES.containsKey(docType)) {
            throw ApiException.field("docType", "tipo de documento no válido");
        }
        String dv = blank(r.dv());
        if ("NIT".equals(docType) && doc != null) {
            doc = com.tiendaposs.fiscal.Nit.digits(doc);
            Character expected = com.tiendaposs.fiscal.Nit.dv(doc);
            if (dv == null) throw ApiException.field("dv", "escribe el dígito de verificación (es " + expected + ")");
            if (!com.tiendaposs.fiscal.Nit.matches(doc, dv)) throw ApiException.field("dv", "no corresponde a ese NIT: debería ser " + expected);
        }
        List<String> resp = r.fiscalResponsibilities() == null ? List.of() : r.fiscalResponsibilities().stream().distinct().toList();
        for (String code : resp) {
            if (!com.tiendaposs.fiscal.FiscalCodes.RESPONSIBILITIES.containsKey(code)) throw ApiException.field("fiscalResponsibilities", "código no válido: " + code);
        }
        String municipality = blank(r.municipalityCode());
        if (municipality != null && !com.tiendaposs.fiscal.Municipalities.exists(municipality)) {
            throw ApiException.field("municipalityCode", "no es un código DANE de municipio válido");
        }
        c.tenantId = t;
        c.name = r.name().trim();
        c.docType = docType;
        c.docNumber = doc;
        c.dv = "NIT".equals(docType) ? dv : null;
        c.personType = blank(r.personType());
        c.invoiceEmail = blank(r.invoiceEmail());
        c.fiscalResponsibilities = resp.isEmpty() ? null : String.join(",", resp);
        c.municipalityCode = municipality;
        c.ivaResponsible = r.ivaResponsible();
        if (r.invoicePreference() != null) c.invoicePreference = r.invoicePreference();
        c.phone = blank(r.phone());
        c.email = blank(r.email());
        c.address = blank(r.address());
        c.notes = blank(r.notes());
        Customer saved = customers.save(c);
        audit.log(id == null ? "CLIENTE_CREADO" : "CLIENTE_EDITADO", "CUSTOMER", saved.id, (id == null ? "Creó" : "Editó") + " el cliente " + saved.name);
        return CustomerDto.of(saved);
    }

    // ---------------- Promociones ----------------

    public record PromotionDto(Long id, String name, String type, Long productId, String productName, Long categoryId,
                               String categoryName, BigDecimal percent, BigDecimal minQty, BigDecimal bundleQty,
                               BigDecimal bundlePrice, BigDecimal payQty, LocalDate startsOn, LocalDate endsOn, boolean active) {
    }

    public record PromotionRequest(
            @NotBlank @Size(max = 100, message = "máximo 100 caracteres") String name,
            @NotBlank @Pattern(regexp = "PORCENTAJE|PRECIO_POR_CANTIDAD|LLEVA_PAGA", message = "tipo no válido") String type,
            Long productId, Long categoryId,
            @DecimalMin(value = "0.01", message = "debe ser mayor que cero") @DecimalMax(value = "100", message = "máximo 100") BigDecimal percent,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal minQty,
            @DecimalMin(value = "1", message = "mínimo 1") BigDecimal bundleQty,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal bundlePrice,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal payQty,
            LocalDate startsOn, LocalDate endsOn, Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<PromotionDto> listPromotions(boolean onlyActive) {
        Long t = TenantContext.require();
        List<Promotion> list = onlyActive ? promotions.findByTenantIdAndActiveTrue(t) : promotions.findByTenantIdOrderByIdDesc(t);
        return list.stream().map(this::toDto).toList();
    }

    @Transactional
    public PromotionDto savePromotion(Long id, PromotionRequest r) {
        Long t = TenantContext.require();
        Promotion p = id == null ? new Promotion() : promotions.findByIdAndTenantId(id, t)
                .orElseThrow(() -> ApiException.notFound("Promoción no encontrada"));
        if ((r.productId() == null) == (r.categoryId() == null)) {
            throw ApiException.field("productId", "elige un producto o una categoría (uno de los dos)");
        }
        if (r.productId() != null && products.findByIdAndTenantId(r.productId(), t).isEmpty()) throw ApiException.field("productId", "el producto no existe");
        if (r.categoryId() != null && categories.findByIdAndTenantId(r.categoryId(), t).isEmpty()) throw ApiException.field("categoryId", "la categoría no existe");
        switch (r.type()) {
            case "PORCENTAJE" -> {
                if (r.percent() == null) throw ApiException.field("percent", "escribe el porcentaje");
            }
            case "PRECIO_POR_CANTIDAD" -> {
                if (r.bundleQty() == null || r.bundlePrice() == null) throw ApiException.field("bundleQty", "escribe la cantidad y el precio del grupo");
                if (r.bundleQty().compareTo(BigDecimal.valueOf(2)) < 0) throw ApiException.field("bundleQty", "el grupo debe ser de 2 o más unidades");
            }
            default -> {
                if (r.bundleQty() == null || r.payQty() == null) throw ApiException.field("bundleQty", "escribe cuántos lleva y cuántos paga");
                if (r.payQty().compareTo(r.bundleQty()) >= 0) throw ApiException.field("payQty", "debe pagar menos de los que lleva");
            }
        }
        if (r.startsOn() != null && r.endsOn() != null && r.endsOn().isBefore(r.startsOn())) {
            throw ApiException.field("endsOn", "la fecha final es anterior a la inicial");
        }
        p.tenantId = t;
        p.name = r.name().trim();
        p.type = r.type();
        p.productId = r.productId();
        p.categoryId = r.categoryId();
        p.percent = r.percent();
        p.minQty = r.minQty();
        p.bundleQty = r.bundleQty();
        p.bundlePrice = r.bundlePrice();
        p.payQty = r.payQty();
        p.startsOn = r.startsOn();
        p.endsOn = r.endsOn();
        if (r.active() != null) p.active = r.active();
        Promotion saved = promotions.save(p);
        audit.sensitive(id == null ? "PROMOCION_CREADA" : "PROMOCION_EDITADA", "PROMOTION", saved.id,
                (id == null ? "Creó" : "Editó") + " la promoción " + saved.name + (saved.active ? "" : " (inactiva)"));
        return toDto(saved);
    }

    @Transactional
    public void deletePromotion(Long id) {
        Promotion p = promotions.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Promoción no encontrada"));
        promotions.delete(p);
        audit.log("PROMOCION_ELIMINADA", "PROMOTION", p.id, "Eliminó la promoción " + p.name);
    }

    private PromotionDto toDto(Promotion p) {
        String productName = p.productId == null ? null : products.findById(p.productId).map(x -> x.name).orElse(null);
        String categoryName = p.categoryId == null ? null : categories.findById(p.categoryId).map(x -> x.name).orElse(null);
        return new PromotionDto(p.id, p.name, p.type, p.productId, productName, p.categoryId, categoryName, p.percent, p.minQty,
                p.bundleQty, p.bundlePrice, p.payQty, p.startsOn, p.endsOn, p.active);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
