package com.tiendaposs.catalog;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.BulkRequest;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Cambios sobre muchos productos a la vez (precios, mínimos, categoría, estado, impuesto). */
@Service
public class ProductBulkService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final PriceHistoryRepository priceHistory;
    private final CategoryRepository categories;
    private final AuditService audit;
    private final CatalogService catalog;

    public ProductBulkService(ProductRepository products, PresentationRepository presentations,
                              PriceHistoryRepository priceHistory, CategoryRepository categories, AuditService audit, CatalogService catalog) {
        this.catalog = catalog;
        this.products = products;
        this.presentations = presentations;
        this.priceHistory = priceHistory;
        this.categories = categories;
        this.audit = audit;
    }

    /** skipped = productos que no se pudieron cambiar, con el motivo (p. ej. llevan serial y deben ser por unidad). */
    public record BulkResult(int updated, List<String> skipped) {
    }

    @Transactional
    public BulkResult apply(BulkRequest r) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        String action = r.action();
        if (action.startsWith("PRECIO")) CurrentUser.require(Permission.PRODUCTOS_PRECIOS);
        else CurrentUser.require(Permission.PRODUCTOS_EDITAR);
        if (r.productIds().size() > 5000) throw ApiException.badRequest("Máximo 5.000 productos por operación");
        String reason = r.reason() == null || r.reason().isBlank() ? "Actualización masiva" : r.reason().trim();
        int count = 0;
        List<String> skipped = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (Long id : r.productIds()) {
            Product p = products.findByIdAndTenantId(id, t).orElse(null);
            if (p == null) continue;
            // La unidad la heredan las variantes del producto principal: se cambia en el principal.
            if ("UNIDAD".equals(action) && "VARIANTE".equals(p.productType) && p.parentId != null) {
                p = products.findByIdAndTenantId(p.parentId, t).orElse(p);
            }
            if (!done.add(p.id)) continue;
            switch (action) {
                case "PRECIO_PORCENTAJE", "PRECIO_FIJO" -> {
                    if (r.value() == null) throw ApiException.field("value", "escribe el valor");
                    if (action.equals("PRECIO_PORCENTAJE") && r.value().compareTo(BigDecimal.valueOf(-90)) < 0) {
                        throw ApiException.field("value", "no se puede bajar más de 90%");
                    }
                    if (action.equals("PRECIO_FIJO") && r.value().signum() < 0) throw ApiException.field("value", "no puede ser negativo");
                    for (Presentation x : presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id)) {
                        if (!x.active) continue;
                        // El precio fijo solo aplica a la unidad base; el porcentaje, a todas las presentaciones.
                        if (action.equals("PRECIO_FIJO") && !x.isBase) continue;
                        BigDecimal nv = action.equals("PRECIO_FIJO") ? r.value()
                                : x.price.multiply(HUNDRED.add(r.value())).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                        nv = roundTo(nv, r.roundTo());
                        if (nv.compareTo(x.price) == 0) continue;
                        log(p, x, x.price, nv, reason, me);
                        x.price = nv;
                        if (x.minPrice.compareTo(nv) > 0) x.minPrice = nv;
                    }
                }
                case "STOCK_MINIMO" -> p.stockMin = nonNeg(r.value());
                case "STOCK_MAXIMO" -> p.stockMax = nonNeg(r.value());
                case "IMPUESTO" -> {
                    BigDecimal v = nonNeg(r.value());
                    if (v.compareTo(HUNDRED) > 0) throw ApiException.field("value", "máximo 100");
                    p.taxRate = v;
                }
                case "CATEGORIA" -> {
                    if (r.categoryId() != null && categories.findByIdAndTenantId(r.categoryId(), t).isEmpty()) {
                        throw ApiException.field("categoryId", "la categoría no existe");
                    }
                    // Quitar la categoría = "Varios" (ningún producto queda suelto).
                    p.categoryId = r.categoryId() != null ? r.categoryId() : catalog.defaultCategoryId(t);
                }
                case "UNIDAD" -> {
                    if (r.unit() == null) throw ApiException.field("unit", "elige la unidad de medida");
                    if (p.trackSerials && !"UNIDAD".equals(r.unit())) {
                        skipped.add(p.name + ": lleva serial, solo puede venderse por unidad");
                        continue;
                    }
                    if (r.unit().equals(p.unit)) continue;
                    p.unit = r.unit();
                    if ("PADRE".equals(p.productType)) catalog.syncChildren(p);
                }
                case "ESTADO" -> {
                    if (!"ACTIVE".equals(r.status()) && !"DISCONTINUED".equals(r.status())) throw ApiException.field("status", "estado no válido");
                    p.status = r.status();
                }
                default -> throw ApiException.field("action", "acción no válida");
            }
            count++;
        }
        audit.sensitive("ACTUALIZACION_MASIVA", "PRODUCT", null, describe(r, count));
        return new BulkResult(count, skipped);
    }

    private void log(Product p, Presentation x, BigDecimal oldV, BigDecimal newV, String reason, AuthUser me) {
        PriceHistory h = new PriceHistory();
        h.tenantId = p.tenantId;
        h.productId = p.id;
        h.presentationId = x.id;
        h.field = "PRECIO";
        h.oldValue = oldV;
        h.newValue = newV;
        h.reason = reason;
        h.userId = me.userId();
        h.userName = me.fullName();
        h.createdAt = Instant.now();
        priceHistory.save(h);
    }

    private static BigDecimal roundTo(BigDecimal v, Integer step) {
        if (step == null || step <= 1) return v.setScale(2, RoundingMode.HALF_UP);
        BigDecimal s = BigDecimal.valueOf(step);
        return v.divide(s, 0, RoundingMode.HALF_UP).multiply(s).setScale(2);
    }

    private static BigDecimal nonNeg(BigDecimal v) {
        if (v == null || v.signum() < 0) throw ApiException.field("value", "escribe un valor de cero o más");
        return v;
    }

    private static String describe(BulkRequest r, int count) {
        Map<String, String> labels = Map.of("PRECIO_PORCENTAJE", "ajustó precios " + r.value() + "%",
                "PRECIO_FIJO", "fijó el precio en " + r.value(), "STOCK_MINIMO", "fijó stock mínimo " + r.value(),
                "STOCK_MAXIMO", "fijó stock máximo " + r.value(), "IMPUESTO", "fijó impuesto " + r.value() + "%",
                "CATEGORIA", "cambió la categoría", "ESTADO", "cambió el estado a " + r.status(),
                "UNIDAD", "cambió la unidad de medida a " + r.unit());
        return "Actualización masiva: " + labels.getOrDefault(r.action(), r.action()) + " en " + count
                + (count == 1 ? " producto" : " productos") + (r.reason() == null ? "" : ". Motivo: " + r.reason());
    }
}
