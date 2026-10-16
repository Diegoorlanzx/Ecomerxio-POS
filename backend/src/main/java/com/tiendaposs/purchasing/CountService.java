package com.tiendaposs.purchasing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Category;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.purchasing.PurchasingRepos.InventoryCountLineRepository;
import com.tiendaposs.purchasing.PurchasingRepos.InventoryCountRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Conteos físicos. Al abrir se guarda lo que dice el sistema de cada producto (teórico); quien
 * cuenta registra lo que encontró (a ciegas por defecto). Al cerrar, cada diferencia se ajusta
 * sobre el stock ACTUAL (así las ventas hechas durante el conteo no se pierden) y las
 * diferencias grandes quedan marcadas como inusuales.
 */
@Service
public class CountService {

    private final InventoryCountRepository counts;
    private final InventoryCountLineRepository lines;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CategoryRepository categories;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final Counters counters;
    private final AuditService audit;

    public CountService(InventoryCountRepository counts, InventoryCountLineRepository lines, ProductRepository products,
                        PresentationRepository presentations, CategoryRepository categories, InventoryService inventory,
                        SettingsService settings, Counters counters, AuditService audit) {
        this.counts = counts;
        this.lines = lines;
        this.products = products;
        this.presentations = presentations;
        this.categories = categories;
        this.inventory = inventory;
        this.settings = settings;
        this.counters = counters;
        this.audit = audit;
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String name, Long categoryId, Boolean blind, @Size(max = 500) String notes) {
    }

    public record CountLineDto(Long id, Long productId, String productName, String sku, String barcode, String unit,
                               BigDecimal theoretical, BigDecimal counted, BigDecimal difference, BigDecimal differenceValue,
                               String countedBy, Instant countedAt, boolean unusual) {
    }

    public record CountDto(Long id, long number, String name, String status, boolean blind, Long categoryId, String categoryName,
                           String createdBy, Instant createdAt, String closedBy, Instant closedAt, String notes, int totalLines,
                           int countedLines, int withDifference, int unusualLines, BigDecimal shrinkageValue, BigDecimal surplusValue,
                           List<CountLineDto> lines) {
    }

    public record EntryRequest(@NotNull Long productId, @NotNull @DecimalMin(value = "0", message = "no puede ser negativa") BigDecimal counted,
                               boolean add) {
    }

    @Transactional(readOnly = true)
    public List<CountDto> list() {
        return counts.findByTenantIdOrderByCreatedAtDesc(TenantContext.require()).stream().map(c -> toDto(c, false)).toList();
    }

    @Transactional(readOnly = true)
    public CountDto get(Long id) {
        return toDto(find(id), true);
    }

    @Transactional
    public CountDto create(CreateRequest r) {
        CurrentUser.require(Permission.INVENTARIO_CONTEO);
        Long t = TenantContext.require();
        List<Long> cats = null;
        if (r.categoryId() != null) {
            Category c = categories.findByIdAndTenantId(r.categoryId(), t).orElseThrow(() -> ApiException.field("categoryId", "la categoría no existe"));
            cats = new ArrayList<>(List.of(c.id));
            for (Category x : categories.findByTenantIdOrderBySortOrderAscNameAsc(t)) if (c.id.equals(x.parentId)) cats.add(x.id);
        }
        final List<Long> scope = cats;
        List<Product> ps = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").stream()
                .filter(p -> p.trackStock && (scope == null || (p.categoryId != null && scope.contains(p.categoryId)))).toList();
        if (ps.isEmpty()) throw ApiException.badRequest("No hay productos para contar con ese filtro");
        InventoryCount c = new InventoryCount();
        c.tenantId = t;
        c.number = counters.next(t, "CONTEO");
        c.name = r.name().trim();
        c.categoryId = r.categoryId();
        c.blind = r.blind() == null || r.blind();
        c.notes = r.notes() == null || r.notes().isBlank() ? null : r.notes().trim();
        c.createdBy = CurrentUser.get().fullName();
        c = counts.save(c);
        for (Product p : ps) {
            InventoryCountLine l = new InventoryCountLine();
            l.countId = c.id;
            l.productId = p.id;
            l.theoretical = p.stock;
            l.unitCost = p.cost;
            lines.save(l);
        }
        audit.log("CONTEO_ABIERTO", "COUNT", c.id, "Inició el conteo #" + c.number + " \"" + c.name + "\" con " + ps.size() + " productos");
        return toDto(c, true);
    }

    /** Registra lo contado de un producto. {@code add} suma (contar por partes: nevera + bodega). */
    @Transactional
    public CountLineDto enter(Long id, EntryRequest r) {
        CurrentUser.require(Permission.INVENTARIO_CONTEO);
        InventoryCount c = find(id);
        if (!"ABIERTO".equals(c.status)) throw ApiException.badRequest("El conteo ya está cerrado");
        InventoryCountLine l = lines.findByCountIdAndProductId(c.id, r.productId())
                .orElseThrow(() -> ApiException.badRequest("Ese producto no hace parte de este conteo"));
        l.counted = r.add() && l.counted != null ? l.counted.add(r.counted()) : r.counted();
        l.countedBy = CurrentUser.get().fullName();
        l.countedAt = Instant.now();
        return lineDtos(c, List.of(l)).get(0);
    }

    /** Cierra: ajusta las diferencias. Lo no contado se deja como estaba (no se asume cero). */
    @Transactional
    public CountDto close(Long id) {
        CurrentUser.require(Permission.INVENTARIO_AJUSTAR);
        InventoryCount c = find(id);
        if (!"ABIERTO".equals(c.status)) throw ApiException.badRequest("El conteo ya está cerrado");
        StoreSettings st = settings.forTenant(c.tenantId);
        int adjusted = 0, unusual = 0;
        BigDecimal net = BigDecimal.ZERO;
        for (InventoryCountLine l : lines.findByCountIdOrderByIdAsc(c.id)) {
            if (l.counted == null) continue;
            BigDecimal diff = l.counted.subtract(l.theoretical);
            if (diff.signum() == 0) continue;
            l.unusual = isUnusual(diff, l.theoretical, l.unitCost, st);
            if (l.unusual) unusual++;
            Product p = products.lockById(l.productId, c.tenantId).orElseThrow();
            // Lo que tiene serial se cuadra registrando o dando de baja cada serial (queda la diferencia a la vista).
            if (p.trackSerials) continue;
            inventory.applyLocked(p, new InventoryService.Change(p.id, diff, InventoryService.CONTEO, "COUNT", c.id,
                    "Conteo físico #" + c.number + ": sistema " + plain(l.theoretical) + ", contado " + plain(l.counted), null, true));
            net = net.add(diff.multiply(l.unitCost));
            adjusted++;
        }
        c.status = "CERRADO";
        c.closedBy = CurrentUser.get().fullName();
        c.closedAt = Instant.now();
        String msg = "Cerró el conteo #" + c.number + ": " + adjusted + " productos ajustados, valor neto " + Money.fmt(net)
                + (unusual > 0 ? ", " + unusual + " diferencias inusuales" : "");
        if (unusual > 0) audit.sensitive("CONTEO_CERRADO", "COUNT", c.id, msg);
        else audit.log("CONTEO_CERRADO", "COUNT", c.id, msg);
        return toDto(c, true);
    }

    @Transactional
    public CountDto cancel(Long id) {
        InventoryCount c = find(id);
        if (!"ABIERTO".equals(c.status)) throw ApiException.badRequest("El conteo ya está cerrado");
        c.status = "ANULADO";
        c.closedBy = CurrentUser.get().fullName();
        c.closedAt = Instant.now();
        audit.log("CONTEO_ANULADO", "COUNT", c.id, "Anuló el conteo #" + c.number + " sin ajustar inventario");
        return toDto(c, false);
    }

    /** Diferencia inusual: supera el % configurado del teórico o el valor configurado. */
    public static boolean isUnusual(BigDecimal diff, BigDecimal theoretical, BigDecimal unitCost, StoreSettings st) {
        BigDecimal value = diff.abs().multiply(unitCost);
        if (st.unusualDiffValue.signum() > 0 && value.compareTo(st.unusualDiffValue) >= 0) return true;
        if (theoretical.signum() <= 0) return diff.abs().compareTo(BigDecimal.valueOf(5)) >= 0;
        BigDecimal pct = diff.abs().multiply(BigDecimal.valueOf(100)).divide(theoretical.abs(), 2, RoundingMode.HALF_UP);
        return st.unusualDiffPct.signum() > 0 && pct.compareTo(st.unusualDiffPct) > 0 && diff.abs().compareTo(BigDecimal.ONE) > 0;
    }

    private InventoryCount find(Long id) {
        return counts.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Conteo no encontrado"));
    }

    private CountDto toDto(InventoryCount c, boolean withLines) {
        List<InventoryCountLine> ls = lines.findByCountIdOrderByIdAsc(c.id);
        List<CountLineDto> dtos = lineDtos(c, ls);
        int counted = (int) dtos.stream().filter(d -> d.counted() != null).count();
        int withDiff = (int) dtos.stream().filter(d -> d.difference() != null && d.difference().signum() != 0).count();
        int unusual = (int) dtos.stream().filter(CountLineDto::unusual).count();
        boolean seeCost = CurrentUser.get().can(Permission.COSTOS_VER);
        BigDecimal shrink = BigDecimal.ZERO, surplus = BigDecimal.ZERO;
        for (CountLineDto d : dtos) {
            if (d.differenceValue() == null) continue;
            if (d.differenceValue().signum() < 0) shrink = shrink.add(d.differenceValue().negate());
            else surplus = surplus.add(d.differenceValue());
        }
        String catName = c.categoryId == null ? null : categories.findById(c.categoryId).map(x -> x.name).orElse(null);
        return new CountDto(c.id, c.number, c.name, c.status, c.blind, c.categoryId, catName, c.createdBy, c.createdAt, c.closedBy,
                c.closedAt, c.notes, ls.size(), counted, withDiff, unusual, seeCost ? shrink : null, seeCost ? surplus : null,
                withLines ? dtos : List.of());
    }

    private List<CountLineDto> lineDtos(InventoryCount c, List<InventoryCountLine> ls) {
        if (ls.isEmpty()) return List.of();
        Map<Long, Product> ps = products.findAllById(ls.stream().map(l -> l.productId).toList()).stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));
        Map<Long, String> codes = new HashMap<>();
        presentations.findByProductIdIn(ps.keySet()).stream().filter(x -> x.isBase && x.barcode != null).forEach(x -> codes.put(x.productId, x.barcode));
        StoreSettings st = settings.forTenant(c.tenantId);
        boolean seeCost = CurrentUser.get().can(Permission.COSTOS_VER);
        // A ciegas: mientras está abierto, quien cuenta no ve el teórico (salvo quien puede ajustar).
        boolean hide = c.blind && "ABIERTO".equals(c.status) && !CurrentUser.get().can(Permission.INVENTARIO_AJUSTAR);
        return ls.stream().map(l -> {
            Product p = ps.get(l.productId);
            BigDecimal diff = l.counted == null ? null : l.counted.subtract(l.theoretical);
            boolean unusual = "CERRADO".equals(c.status) ? l.unusual : diff != null && diff.signum() != 0 && isUnusual(diff, l.theoretical, l.unitCost, st);
            return new CountLineDto(l.id, l.productId, p == null ? "?" : p.name, p == null ? null : p.sku, codes.get(l.productId),
                    p == null ? null : p.unit, hide ? null : l.theoretical, l.counted, hide ? null : diff,
                    hide || diff == null || !seeCost ? null : diff.multiply(l.unitCost).setScale(2, RoundingMode.HALF_UP),
                    l.countedBy, l.countedAt, !hide && unusual);
        }).toList();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
