package com.tiendaposs.purchasing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.purchasing.PurchasingRepos.*;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Proveedores: datos, catálogo con precios por proveedor, cumplimiento, saldos y devoluciones. */
@Service
public class SupplierService {

    private static final Set<String> DAYS = Set.of("LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM");

    private final SupplierRepository suppliers;
    private final SupplierProductRepository supplierProducts;
    private final SupplierPriceHistoryRepository priceHistory;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository orderLines;
    private final GoodsReceiptRepository receipts;
    private final SupplierReturnRepository returns;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final AuditService audit;

    public SupplierService(SupplierRepository suppliers, SupplierProductRepository supplierProducts,
                           SupplierPriceHistoryRepository priceHistory, PurchaseOrderRepository orders,
                           PurchaseOrderLineRepository orderLines, GoodsReceiptRepository receipts,
                           SupplierReturnRepository returns, ProductRepository products, InventoryService inventory,
                           SettingsService settings, AuditService audit) {
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.priceHistory = priceHistory;
        this.orders = orders;
        this.orderLines = orderLines;
        this.receipts = receipts;
        this.returns = returns;
        this.products = products;
        this.inventory = inventory;
        this.settings = settings;
        this.audit = audit;
    }

    // ---------------- DTOs ----------------

    public record SupplierRequest(
            @NotBlank @Size(max = 120, message = "máximo 120 caracteres") String name,
            @Size(max = 160) String legalName, @Size(max = 30) String taxId, @Size(max = 120) String contactName,
            @Size(max = 30) String phone, @Size(max = 30) String whatsapp,
            @Email(message = "no es un correo válido") String email, @Size(max = 200) String address,
            @Size(max = 80) String city, @Size(max = 200) String paymentTerms,
            @Min(value = 0, message = "no puede ser negativo") @Max(value = 365, message = "máximo 365") Integer creditDays,
            String orderDays, String deliveryDays,
            @Min(value = 0, message = "no puede ser negativo") @Max(value = 120, message = "máximo 120") Integer leadTimeDays,
            @Size(max = 500) String notes, Boolean active,
            @Size(max = 10) String docType, @Size(max = 1, message = "es un solo dígito") String dv,
            @Pattern(regexp = "NATURAL|JURIDICA", message = "elige persona natural o jurídica") String personType,
            @Pattern(regexp = "^$|[0-9]{5}", message = "son 5 dígitos (código DANE del municipio)") String municipalityCode,
            Boolean invoicesElectronically,
            /** Para proponer retenciones (Fase 14). */ Boolean ivaResponsible, Boolean incomeTaxDeclarant, Boolean selfWithholder,
            Boolean simpleRegime, Boolean greatContributor) {
    }

    public record Metrics(int orders, int receipts, BigDecimal purchasedTotal, BigDecimal balance,
                          BigDecimal avgLeadDays, BigDecimal effectiveLeadDays, BigDecimal onTimePct,
                          BigDecimal fillRatePct, int productCount, Instant lastPurchaseAt) {
    }

    public record SupplierDto(Long id, String name, String legalName, String taxId, String contactName, String phone,
                              String whatsapp, String email, String address, String city, String paymentTerms,
                              int creditDays, String orderDays, String deliveryDays, Integer leadTimeDays, String notes,
                              boolean active, Metrics metrics, String docType, String dv, String personType,
                              String municipalityCode, boolean invoicesElectronically,
                              /** Lo que le falta para emitirle documento soporte (vacío si no aplica o está completo). */
                              List<String> supportDocumentMissing, Boolean ivaResponsible, boolean incomeTaxDeclarant,
                              boolean selfWithholder, boolean simpleRegime, boolean greatContributor) {
    }

    public record CatalogItemRequest(
            @NotNull Long productId,
            @NotBlank @Size(max = 60) String packName,
            @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal packFactor,
            @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal costPerPack,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal minPacks,
            @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal multiplePacks,
            @Size(max = 60) String supplierSku,
            Boolean isPrimary) {
    }

    public record CatalogItemDto(Long id, Long supplierId, String supplierName, Long productId, String productName,
                                 String sku, String packName, BigDecimal packFactor, BigDecimal costPerPack,
                                 BigDecimal unitCost, BigDecimal minPacks, BigDecimal multiplePacks, String supplierSku,
                                 boolean isPrimary, BigDecimal productStock, Instant updatedAt) {
    }

    public record PriceHistoryDto(Long productId, String productName, BigDecimal oldCostPerUnit, BigDecimal newCostPerUnit,
                                  String source, String userName, Instant createdAt) {
    }

    public record ReturnRequest(@NotNull Long productId,
                                @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity,
                                @NotBlank(message = "explica el motivo") @Size(max = 300) String reason) {
    }

    public record ReturnDto(Long id, Long productId, String productName, BigDecimal quantity, BigDecimal unitCost,
                            String reason, String userName, Instant createdAt) {
    }

    // ---------------- Proveedores ----------------

    @Transactional(readOnly = true)
    public List<SupplierDto> list() {
        return suppliers.findByTenantIdOrderByNameAsc(TenantContext.require()).stream().map(s -> toDto(s, metrics(s))).toList();
    }

    @Transactional(readOnly = true)
    public SupplierDto get(Long id) {
        Supplier s = find(id);
        return toDto(s, metrics(s));
    }

    @Transactional
    public SupplierDto save(Long id, SupplierRequest r) {
        Long t = TenantContext.require();
        if (suppliers.nameTaken(t, r.name().trim(), id)) throw ApiException.field("name", "ya existe un proveedor con ese nombre");
        Supplier s = id == null ? new Supplier() : find(id);
        s.tenantId = t;
        s.name = r.name().trim();
        s.legalName = blank(r.legalName());
        s.taxId = blank(r.taxId());
        s.contactName = blank(r.contactName());
        s.phone = blank(r.phone());
        s.whatsapp = blank(r.whatsapp());
        s.email = blank(r.email());
        s.address = blank(r.address());
        s.city = blank(r.city());
        s.paymentTerms = blank(r.paymentTerms());
        s.creditDays = r.creditDays() == null ? 0 : r.creditDays();
        s.orderDays = days("orderDays", r.orderDays());
        s.deliveryDays = days("deliveryDays", r.deliveryDays());
        s.leadTimeDays = r.leadTimeDays();
        s.notes = blank(r.notes());
        if (r.active() != null) s.active = r.active();
        if (r.docType() != null && !r.docType().isBlank()) {
            if (!com.tiendaposs.fiscal.FiscalCodes.ID_TYPES.containsKey(r.docType())) throw ApiException.field("docType", "tipo de documento no válido");
            s.docType = r.docType();
        }
        s.dv = blank(r.dv());
        if ("NIT".equals(s.docType) && s.taxId != null && s.dv != null && !com.tiendaposs.fiscal.Nit.matches(s.taxId, s.dv)) {
            throw ApiException.field("dv", "el dígito de verificación no corresponde al NIT");
        }
        s.personType = blank(r.personType());
        s.municipalityCode = blank(r.municipalityCode());
        if (r.invoicesElectronically() != null) s.invoicesElectronically = r.invoicesElectronically();
        s.ivaResponsible = r.ivaResponsible();
        if (r.incomeTaxDeclarant() != null) s.incomeTaxDeclarant = r.incomeTaxDeclarant();
        if (r.selfWithholder() != null) s.selfWithholder = r.selfWithholder();
        if (r.simpleRegime() != null) s.simpleRegime = r.simpleRegime();
        if (r.greatContributor() != null) s.greatContributor = r.greatContributor();
        s = suppliers.save(s);
        audit.log(id == null ? "PROVEEDOR_CREADO" : "PROVEEDOR_EDITADO", "SUPPLIER", s.id, (id == null ? "Creó" : "Editó") + " el proveedor " + s.name);
        return toDto(s, metrics(s));
    }

    public Supplier find(Long id) {
        return suppliers.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Proveedor no encontrado"));
    }

    // ---------------- Catálogo del proveedor ----------------

    @Transactional(readOnly = true)
    public List<CatalogItemDto> catalog(Long supplierId) {
        Supplier s = find(supplierId);
        return toItems(supplierProducts.findBySupplierId(s.id));
    }

    /** Proveedores que venden un producto, del más barato por unidad al más caro (comparación). */
    @Transactional(readOnly = true)
    public List<CatalogItemDto> forProduct(Long productId) {
        Product p = products.findByIdAndTenantId(productId, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        return toItems(supplierProducts.findByProductId(p.id)).stream()
                .sorted(Comparator.comparing(CatalogItemDto::unitCost)).toList();
    }

    @Transactional
    public CatalogItemDto saveItem(Long supplierId, CatalogItemRequest r) {
        Long t = TenantContext.require();
        Supplier s = find(supplierId);
        Product p = products.findByIdAndTenantId(r.productId(), t).orElseThrow(() -> ApiException.field("productId", "el producto no existe"));
        SupplierProduct sp = supplierProducts.findBySupplierIdAndProductId(s.id, p.id).orElseGet(SupplierProduct::new);
        BigDecimal oldUnit = sp.id == null ? null : sp.unitCost();
        sp.tenantId = t;
        sp.supplierId = s.id;
        sp.productId = p.id;
        sp.packName = r.packName().trim();
        sp.packFactor = r.packFactor().setScale(3, RoundingMode.HALF_UP);
        sp.costPerPack = r.costPerPack().setScale(2, RoundingMode.HALF_UP);
        sp.minPacks = r.minPacks() == null ? BigDecimal.ONE : r.minPacks();
        sp.multiplePacks = r.multiplePacks() == null ? BigDecimal.ONE : r.multiplePacks();
        sp.supplierSku = blank(r.supplierSku());
        sp.updatedAt = Instant.now();
        boolean wantsPrimary = Boolean.TRUE.equals(r.isPrimary()) || supplierProducts.findByProductId(p.id).stream().noneMatch(x -> x.isPrimary && !Objects.equals(x.id, sp.id));
        if (wantsPrimary) clearPrimary(p.id, sp.id);
        sp.isPrimary = wantsPrimary;
        SupplierProduct saved = supplierProducts.save(sp);
        if (oldUnit == null || oldUnit.compareTo(saved.unitCost()) != 0) {
            logPrice(saved, oldUnit, saved.unitCost(), "MANUAL", null);
        }
        audit.log("PROVEEDOR_PRODUCTO", "SUPPLIER", s.id, "Actualizó " + p.name + " en el catálogo de " + s.name);
        return toItems(List.of(saved)).get(0);
    }

    @Transactional
    public void setPrimary(Long itemId) {
        SupplierProduct sp = supplierProducts.findByIdAndTenantId(itemId, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("No encontrado"));
        clearPrimary(sp.productId, sp.id);
        sp.isPrimary = true;
        supplierProducts.saveAndFlush(sp);
    }

    @Transactional
    public void removeItem(Long itemId) {
        SupplierProduct sp = supplierProducts.findByIdAndTenantId(itemId, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("No encontrado"));
        supplierProducts.delete(sp);
        if (sp.isPrimary) {
            supplierProducts.flush();
            supplierProducts.findByProductId(sp.productId).stream().min(Comparator.comparing(SupplierProduct::unitCost))
                    .ifPresent(other -> other.isPrimary = true);
        }
    }

    private void clearPrimary(Long productId, Long exceptId) {
        for (SupplierProduct other : supplierProducts.findByProductId(productId)) {
            if (other.isPrimary && !Objects.equals(other.id, exceptId)) {
                other.isPrimary = false;
                supplierProducts.saveAndFlush(other);
            }
        }
    }

    public void logPrice(SupplierProduct sp, BigDecimal oldUnit, BigDecimal newUnit, String source, Long ref) {
        AuthUser u = CurrentUser.getOrNull();
        SupplierPriceHistory h = new SupplierPriceHistory();
        h.tenantId = sp.tenantId;
        h.supplierId = sp.supplierId;
        h.productId = sp.productId;
        h.oldCostPerUnit = oldUnit;
        h.newCostPerUnit = newUnit;
        h.source = source;
        h.referenceId = ref;
        h.userName = u == null ? null : u.fullName();
        priceHistory.save(h);
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryDto> priceHistory(Long supplierId) {
        Supplier s = find(supplierId);
        List<SupplierPriceHistory> list = priceHistory.findTop200BySupplierIdOrderByCreatedAtDesc(s.id);
        Map<Long, String> names = productNames(list.stream().map(h -> h.productId).toList());
        return list.stream().map(h -> new PriceHistoryDto(h.productId, names.get(h.productId), h.oldCostPerUnit, h.newCostPerUnit,
                h.source, h.userName, h.createdAt)).toList();
    }

    // ---------------- Devoluciones al proveedor ----------------

    @Transactional
    public ReturnDto returnToSupplier(Long supplierId, ReturnRequest r) {
        Long t = TenantContext.require();
        CurrentUser.require(Permission.INVENTARIO_AJUSTAR);
        Supplier s = find(supplierId);
        Product p = products.lockById(r.productId(), t).orElseThrow(() -> ApiException.field("productId", "el producto no existe"));
        BigDecimal unitCost = supplierProducts.findBySupplierIdAndProductId(s.id, p.id).map(SupplierProduct::unitCost).orElse(p.cost);
        inventory.applyLocked(p, new InventoryService.Change(p.id, r.quantity().negate(), InventoryService.DEVOLUCION_PROVEEDOR,
                "SUPPLIER", s.id, "Devolución a " + s.name + ": " + r.reason().trim(), null, false));
        SupplierReturn ret = new SupplierReturn();
        ret.tenantId = t;
        ret.supplierId = s.id;
        ret.productId = p.id;
        ret.quantity = r.quantity();
        ret.unitCost = unitCost;
        ret.reason = r.reason().trim();
        ret.userName = CurrentUser.get().fullName();
        ret = returns.save(ret);
        audit.sensitive("DEVOLUCION_PROVEEDOR", "SUPPLIER", s.id, "Devolvió " + r.quantity().stripTrailingZeros().toPlainString() + " x "
                + p.name + " a " + s.name + ". Motivo: " + ret.reason);
        return new ReturnDto(ret.id, p.id, p.name, ret.quantity, ret.unitCost, ret.reason, ret.userName, ret.createdAt);
    }

    @Transactional(readOnly = true)
    public List<ReturnDto> returns(Long supplierId) {
        Supplier s = find(supplierId);
        List<SupplierReturn> list = returns.findBySupplierIdOrderByCreatedAtDesc(s.id);
        Map<Long, String> names = productNames(list.stream().map(x -> x.productId).toList());
        return list.stream().map(x -> new ReturnDto(x.id, x.productId, names.get(x.productId), x.quantity, x.unitCost, x.reason,
                x.userName, x.createdAt)).toList();
    }

    // ---------------- Métricas ----------------

    /** Cumplimiento: tiempo de entrega medido, % a tiempo, % de lo pedido que llegó, saldo pendiente. */
    public Metrics metrics(Supplier s) {
        List<PurchaseOrder> os = orders.findBySupplierIdOrderByCreatedAtDesc(s.id);
        List<GoodsReceipt> rs = receipts.findBySupplierIdOrderByReceivedAtDesc(s.id);
        BigDecimal purchased = rs.stream().map(r -> r.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> leadDays = new ArrayList<>();
        for (PurchaseOrder o : os) {
            Instant start = o.sentAt != null ? o.sentAt : o.approvedAt;
            if (start == null) continue;
            rs.stream().filter(r -> o.id.equals(r.orderId)).map(r -> r.receivedAt).min(Comparator.naturalOrder())
                    .ifPresent(first -> leadDays.add(Math.max(0, Duration.between(start, first).toHours()) / 24));
            if (leadDays.size() >= 10) break;
        }
        BigDecimal avgLead = leadDays.isEmpty() ? null
                : BigDecimal.valueOf(leadDays.stream().mapToLong(Long::longValue).average().orElse(0)).setScale(1, RoundingMode.HALF_UP);
        long timed = rs.stream().filter(r -> r.onTime != null).count();
        BigDecimal onTime = timed == 0 ? null : BigDecimal.valueOf(rs.stream().filter(r -> Boolean.TRUE.equals(r.onTime)).count() * 100.0 / timed)
                .setScale(1, RoundingMode.HALF_UP);
        List<Long> closedIds = os.stream().filter(o -> o.status.equals("RECIBIDA") || o.status.equals("PARCIAL")).map(o -> o.id).toList();
        BigDecimal ordered = BigDecimal.ZERO, received = BigDecimal.ZERO;
        if (!closedIds.isEmpty()) {
            for (PurchaseOrderLine l : orderLines.findByOrderIdIn(closedIds)) {
                ordered = ordered.add(l.unitsOrdered());
                received = received.add(l.unitsReceived);
            }
        }
        BigDecimal fill = ordered.signum() == 0 ? null : received.multiply(BigDecimal.valueOf(100)).divide(ordered, 1, RoundingMode.HALF_UP);
        return new Metrics(os.size(), rs.size(), purchased, receipts.balance(s.id), avgLead, effectiveLead(s, avgLead), onTime, fill,
                supplierProducts.findBySupplierId(s.id).size(), rs.isEmpty() ? null : rs.get(0).receivedAt);
    }

    /** Tiempo de entrega que usa la reposición: el configurado, si no el medido, si no el de la tienda. */
    public BigDecimal effectiveLead(Supplier s, BigDecimal measured) {
        if (s.leadTimeDays != null) return BigDecimal.valueOf(s.leadTimeDays);
        if (measured != null) return measured;
        return BigDecimal.valueOf(settings.forTenant(s.tenantId).defaultLeadDays);
    }

    // ---------------- utilidades ----------------

    List<CatalogItemDto> toItems(List<SupplierProduct> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, Product> ps = products.findAllById(list.stream().map(x -> x.productId).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));
        Map<Long, String> sn = suppliers.findAllById(list.stream().map(x -> x.supplierId).distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.id, s -> s.name));
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER) || CurrentUser.get().can(Permission.COMPRAS_VER);
        return list.stream().map(x -> {
            Product p = ps.get(x.productId);
            return new CatalogItemDto(x.id, x.supplierId, sn.get(x.supplierId), x.productId, p == null ? "?" : p.name, p == null ? null : p.sku,
                    x.packName, x.packFactor, costs ? x.costPerPack : null, costs ? x.unitCost() : null, x.minPacks, x.multiplePacks,
                    x.supplierSku, x.isPrimary, p == null ? null : p.stock, x.updatedAt);
        }).sorted(Comparator.comparing(CatalogItemDto::productName)).toList();
    }

    private Map<Long, String> productNames(Collection<Long> ids) {
        return products.findAllById(new HashSet<>(ids)).stream().collect(Collectors.toMap(p -> p.id, p -> p.name));
    }

    private SupplierDto toDto(Supplier s, Metrics m) {
        return new SupplierDto(s.id, s.name, s.legalName, s.taxId, s.contactName, s.phone, s.whatsapp, s.email, s.address, s.city,
                s.paymentTerms, s.creditDays, s.orderDays, s.deliveryDays, s.leadTimeDays, s.notes, s.active, m,
                s.docType, s.dv, s.personType, s.municipalityCode, s.invoicesElectronically,
                s.invoicesElectronically ? List.of() : supportDocumentMissing(s), s.ivaResponsible, s.incomeTaxDeclarant, s.selfWithholder,
                s.simpleRegime, s.greatContributor);
    }

    /** Datos del proveedor que exige el documento soporte. */
    public static List<String> supportDocumentMissing(Supplier s) {
        List<String> m = new ArrayList<>();
        if (s.taxId == null) m.add("número de documento");
        else if ("NIT".equals(s.docType) && !com.tiendaposs.fiscal.Nit.matches(s.taxId, s.dv)) m.add("dígito de verificación correcto");
        if (s.personType == null) m.add("tipo de persona");
        if (s.address == null) m.add("dirección");
        if (s.municipalityCode == null) m.add("municipio (código DANE)");
        return m;
    }

    private static String days(String field, String v) {
        if (v == null || v.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String d : v.toUpperCase().split(",")) {
            String x = d.trim().replace("É", "E").replace("Á", "A");
            if (x.isEmpty()) continue;
            if (!DAYS.contains(x)) throw ApiException.field(field, "usa LUN, MAR, MIE, JUE, VIE, SAB, DOM separados por coma");
            if (!out.contains(x)) out.add(x);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
