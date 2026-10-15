package com.tiendaposs.catalog;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.*;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_PHOTO_CHARS = 400_000;

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final PriceHistoryRepository priceHistory;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final Counters counters;
    private final AuditService audit;

    public CatalogService(CategoryRepository categories, ProductRepository products, PresentationRepository presentations,
                          PriceHistoryRepository priceHistory, InventoryService inventory, SettingsService settings,
                          Counters counters, AuditService audit) {
        this.categories = categories;
        this.products = products;
        this.presentations = presentations;
        this.priceHistory = priceHistory;
        this.inventory = inventory;
        this.settings = settings;
        this.counters = counters;
        this.audit = audit;
    }

    // ---------------- Categorías ----------------

    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        Long t = TenantContext.require();
        return categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .map(c -> new CategoryDto(c.id, c.parentId, c.name, c.icon, c.sortOrder, c.active, products.countByCategoryId(c.id)))
                .toList();
    }

    @Transactional
    public CategoryDto saveCategory(Long id, CategoryRequest r) {
        Long t = TenantContext.require();
        Category c = id == null ? new Category() : findCategory(id);
        if (r.parentId() != null) {
            Category parent = findCategory(r.parentId());
            if (id != null && (parent.id.equals(id) || isDescendant(parent, id))) {
                throw ApiException.field("parentId", "una categoría no puede estar dentro de sí misma");
            }
            if (parent.parentId != null) throw ApiException.field("parentId", "solo se permiten dos niveles: categoría y subcategoría");
        }
        categories.findByName(t, r.name().trim(), r.parentId()).filter(o -> !o.id.equals(c.id)).ifPresent(o -> {
            throw ApiException.field("name", "ya existe una categoría con ese nombre");
        });
        c.tenantId = t;
        c.parentId = r.parentId();
        c.name = r.name().trim();
        c.icon = blank(r.icon());
        if (r.sortOrder() != null) c.sortOrder = r.sortOrder();
        if (r.active() != null) c.active = r.active();
        Category saved = categories.save(c);
        audit.log(id == null ? "CATEGORIA_CREADA" : "CATEGORIA_EDITADA", "CATEGORY", saved.id,
                (id == null ? "Creó" : "Editó") + " la categoría " + saved.name);
        return new CategoryDto(saved.id, saved.parentId, saved.name, saved.icon, saved.sortOrder, saved.active,
                products.countByCategoryId(saved.id));
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category c = findCategory(id);
        if (products.countByCategoryId(c.id) > 0) {
            throw ApiException.badRequest("La categoría tiene productos. Muévelos a otra categoría o desactívala.");
        }
        if (categories.countChildren(c.id) > 0) throw ApiException.badRequest("Primero elimina o mueve sus subcategorías.");
        categories.delete(c);
        audit.log("CATEGORIA_ELIMINADA", "CATEGORY", c.id, "Eliminó la categoría " + c.name);
    }

    private boolean isDescendant(Category candidate, Long ancestorId) {
        Long p = candidate.parentId;
        int guard = 0;
        while (p != null && guard++ < 10) {
            if (p.equals(ancestorId)) return true;
            p = categories.findById(p).map(x -> x.parentId).orElse(null);
        }
        return false;
    }

    private Category findCategory(Long id) {
        return categories.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> ApiException.field("categoryId", "la categoría no existe"));
    }

    /** La categoría y sus subcategorías (para filtrar "Cervezas" e incluir "Cervezas nacionales"). */
    private List<Long> withChildren(Long tenantId, Long categoryId) {
        List<Long> ids = new ArrayList<>(List.of(categoryId));
        for (Category c : categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId)) {
            if (categoryId.equals(c.parentId)) ids.add(c.id);
        }
        return ids;
    }

    // ---------------- Productos ----------------

    @Transactional(readOnly = true)
    public PageDto<ProductDto> search(String q, Long categoryId, String status, String stock, int page, int size, String sort) {
        Long t = TenantContext.require();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String code = q == null || q.isBlank() ? null : q.trim();
        List<Long> cats = categoryId == null ? List.of(-1L) : withChildren(t, categoryId);
        Sort s = switch (sort == null ? "" : sort) {
            case "stock" -> Sort.by("stock").ascending();
            case "recent" -> Sort.by("updatedAt").descending();
            default -> Sort.by("name").ascending();
        };
        boolean stockFilter = stock != null && !stock.isBlank();
        if (stockFilter) {
            // El estado de stock es calculado: se filtra en memoria sobre el resultado (catálogos de tienda de barrio).
            List<Product> all = products.search(t, categoryId == null, cats, blank(status), like, code,
                    PageRequest.of(0, 5000, s)).getContent().stream().filter(p -> p.stockStatus().equals(stock)).toList();
            int from = Math.min(page * size, all.size());
            List<Product> slice = all.subList(from, Math.min(from + size, all.size()));
            return new PageDto<>(toDtos(slice), all.size(), page, (int) Math.ceil(all.size() / (double) size));
        }
        Page<Product> res = products.search(t, categoryId == null, cats, blank(status), like, code, PageRequest.of(page, size, s));
        return new PageDto<>(toDtos(res.getContent()), res.getTotalElements(), res.getNumber(), res.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProductDto get(Long id) {
        return toDtos(List.of(findProduct(id))).get(0);
    }

    @Transactional(readOnly = true)
    public ProductDto byBarcode(String code) {
        Long t = TenantContext.require();
        Presentation pr = presentations.findByBarcode(t, code.trim())
                .orElseThrow(() -> ApiException.notFound("No hay ningún producto con el código " + code));
        return get(pr.productId);
    }

    @Transactional
    public ProductDto create(ProductRequest r) {
        Long t = TenantContext.require();
        Product p = new Product();
        p.tenantId = t;
        String sku = blank(r.sku());
        if (sku == null) {
            do {
                sku = "P" + String.format("%05d", counters.next(t, "SKU"));
            } while (products.skuTaken(t, sku, null));
        } else if (products.skuTaken(t, sku, null)) {
            throw ApiException.field("sku", "ya existe un producto con ese SKU");
        }
        p.sku = sku;
        applyFields(p, r);
        BigDecimal cost = r.cost() == null ? BigDecimal.ZERO : r.cost();
        p.cost = cost.setScale(4, RoundingMode.HALF_UP);
        p.lastCost = p.cost;
        p = products.save(p);
        savePresentations(p, r.presentations(), true, null);
        BigDecimal initial = r.initialStock() == null ? BigDecimal.ZERO : r.initialStock();
        if (initial.signum() > 0 && p.trackStock) {
            inventory.applyLocked(p, new InventoryService.Change(p.id, initial, InventoryService.INICIAL, "PRODUCT", p.id,
                    "Existencia inicial", null, false));
        }
        audit.log("PRODUCTO_CREADO", "PRODUCT", p.id, "Creó el producto " + p.name + " (" + p.sku + ")");
        return get(p.id);
    }

    @Transactional
    public ProductDto update(Long id, ProductRequest r) {
        Long t = TenantContext.require();
        Product p = findProduct(id);
        String sku = blank(r.sku());
        if (sku != null && !sku.equalsIgnoreCase(p.sku)) {
            if (products.skuTaken(t, sku, p.id)) throw ApiException.field("sku", "ya existe un producto con ese SKU");
            p.sku = sku;
        }
        boolean wasTracking = p.trackStock;
        applyFields(p, r);
        if (wasTracking && !p.trackStock && p.stock.signum() != 0) {
            throw ApiException.field("trackStock", "deja el stock en cero antes de dejar de controlarlo");
        }
        if (r.cost() != null && r.cost().setScale(4, RoundingMode.HALF_UP).compareTo(p.cost) != 0) {
            CurrentUser.require(Permission.COSTOS_VER);
            logPrice(p, null, "COSTO", p.cost, r.cost(), blank(r.priceChangeReason()) == null ? "Edición manual" : r.priceChangeReason());
            p.cost = r.cost().setScale(4, RoundingMode.HALF_UP);
        }
        savePresentations(p, r.presentations(), false, blank(r.priceChangeReason()));
        audit.log("PRODUCTO_EDITADO", "PRODUCT", p.id, "Editó el producto " + p.name);
        return get(p.id);
    }

    @Transactional
    public ProductDto setStatus(Long id, String status) {
        Product p = findProduct(id);
        p.status = status;
        audit.log("PRODUCTO_ESTADO", "PRODUCT", p.id,
                ("ACTIVE".equals(status) ? "Reactivó " : "Descontinuó ") + "el producto " + p.name);
        return get(p.id);
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryDto> priceHistory(Long productId) {
        Product p = findProduct(productId);
        Map<Long, String> names = presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream()
                .collect(Collectors.toMap(x -> x.id, x -> x.name));
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        return priceHistory.findTop100ByProductIdAndTenantIdOrderByCreatedAtDesc(p.id, p.tenantId).stream()
                .filter(h -> costs || !"COSTO".equals(h.field))
                .map(h -> new PriceHistoryDto(h.id, h.presentationId, h.presentationId == null ? null : names.get(h.presentationId),
                        h.field, h.oldValue, h.newValue, h.reason, h.userName, h.createdAt))
                .toList();
    }

    private void applyFields(Product p, ProductRequest r) {
        if (r.categoryId() != null) findCategory(r.categoryId());
        p.categoryId = r.categoryId();
        p.name = r.name().trim();
        p.description = blank(r.description());
        p.brand = blank(r.brand());
        String photo = blank(r.photo());
        if (photo != null) {
            if (!photo.matches("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$")) {
                throw ApiException.field("photo", "debe ser una imagen PNG, JPG o WEBP");
            }
            if (photo.length() > MAX_PHOTO_CHARS) throw ApiException.field("photo", "la foto es muy pesada (máx. 300 KB)");
        }
        p.photo = photo;
        p.unit = r.unit();
        if (r.trackStock() != null) p.trackStock = r.trackStock();
        p.stockMin = nz(r.stockMin());
        p.stockMax = nz(r.stockMax());
        p.safetyStock = nz(r.safetyStock());
        if (p.stockMax.signum() > 0 && p.stockMax.compareTo(p.stockMin) < 0) {
            throw ApiException.field("stockMax", "no puede ser menor que el stock mínimo");
        }
        var s = settings.forTenant(p.tenantId);
        p.taxName = blank(r.taxName()) == null ? s.defaultTaxName : r.taxName().trim();
        p.taxRate = r.taxRate() == null ? s.defaultTaxRate : r.taxRate();
        p.fixedTax = nz(r.fixedTax());
        if (r.favorite() != null) p.favorite = r.favorite();
    }

    /**
     * Sincroniza las presentaciones: actualiza las que traen id, crea las nuevas y desactiva las que
     * ya no vienen (no se borran: hay ventas que las referencian). Exactamente una es la base (factor 1).
     */
    private void savePresentations(Product p, List<PresentationRequest> reqs, boolean creating, String reason) {
        Set<Long> requestedIds = reqs.stream().map(PresentationRequest::id).filter(Objects::nonNull).collect(Collectors.toSet());
        long bases = reqs.stream().filter(PresentationRequest::isBase).count();
        if (bases != 1) throw ApiException.field("presentations", "marca exactamente una presentación como la unidad base");
        Set<String> names = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (PresentationRequest pr : reqs) {
            if (pr.isBase() && pr.factor().compareTo(BigDecimal.ONE) != 0) {
                throw ApiException.field("presentations", "la unidad base debe tener factor 1");
            }
            if (!names.add(pr.name().trim().toLowerCase())) throw ApiException.field("presentations", "hay dos presentaciones con el nombre " + pr.name());
            String code = blank(pr.barcode());
            if (code != null) {
                if (!codes.add(code)) throw ApiException.field("presentations", "el código " + code + " está repetido");
                presentations.findByBarcode(p.tenantId, code)
                        .filter(o -> !Objects.equals(o.id, pr.id()))
                        .filter(o -> !(o.productId.equals(p.id) && !requestedIds.contains(o.id)))
                        .ifPresent(o -> {
                    String other = products.findById(o.productId).map(x -> x.name).orElse("otro producto");
                    throw ApiException.field("presentations", "el código " + code + " ya lo usa " + other);
                });
            }
            if (pr.minPrice() != null && pr.minPrice().compareTo(pr.price()) > 0) {
                throw ApiException.field("presentations", "el precio mínimo de " + pr.name() + " es mayor que su precio");
            }
        }
        Map<Long, Presentation> existing = presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream()
                .collect(Collectors.toMap(x -> x.id, Function.identity()));
        // Primero se retiran las que ya no vienen (liberan su código de barras); no se borran: hay ventas que las usan.
        for (Presentation old : existing.values()) {
            if (!requestedIds.contains(old.id) && (old.active || old.barcode != null)) {
                old.active = false;
                old.isBase = false;
                old.barcode = null;
                presentations.saveAndFlush(old);
            }
        }
        AuthUser me = CurrentUser.get();
        int order = 0;
        for (PresentationRequest pr : reqs) {
            Presentation x;
            if (pr.id() != null) {
                x = existing.get(pr.id());
                if (x == null) throw ApiException.field("presentations", "una presentación no pertenece a este producto");
                if (x.price.compareTo(pr.price()) != 0 || x.minPrice.compareTo(nz(pr.minPrice())) != 0) {
                    if (!me.can(Permission.PRODUCTOS_PRECIOS)) throw ApiException.forbidden("No tienes permiso para cambiar precios.");
                }
                if (x.price.compareTo(pr.price()) != 0) logPrice(p, x.id, "PRECIO", x.price, pr.price(), reason == null ? "Edición manual" : reason);
                if (x.minPrice.compareTo(nz(pr.minPrice())) != 0) logPrice(p, x.id, "PRECIO_MINIMO", x.minPrice, nz(pr.minPrice()), reason == null ? "Edición manual" : reason);
                if (x.factor.compareTo(pr.factor()) != 0 && !creating) {
                    audit.sensitive("PRESENTACION_FACTOR", "PRODUCT", p.id, "Cambió el factor de " + p.name + " / " + x.name
                            + ": " + x.factor.stripTrailingZeros().toPlainString() + " → " + pr.factor().stripTrailingZeros().toPlainString());
                }
            } else {
                x = new Presentation();
                x.tenantId = p.tenantId;
                x.productId = p.id;
            }
            x.name = pr.name().trim();
            x.factor = pr.factor().setScale(3, RoundingMode.HALF_UP);
            x.barcode = blank(pr.barcode());
            x.price = pr.price().setScale(2, RoundingMode.HALF_UP);
            x.minPrice = nz(pr.minPrice()).setScale(2, RoundingMode.HALF_UP);
            x.isBase = pr.isBase();
            x.active = pr.active() == null || pr.active() || pr.isBase();
            x.sortOrder = order++;
            x = presentations.save(x);
            if (pr.id() == null && !creating) logPrice(p, x.id, "PRECIO", null, x.price, "Nueva presentación");
        }
    }

    private void logPrice(Product p, Long presentationId, String field, BigDecimal oldV, BigDecimal newV, String reason) {
        AuthUser u = CurrentUser.getOrNull();
        PriceHistory h = new PriceHistory();
        h.tenantId = p.tenantId;
        h.productId = p.id;
        h.presentationId = presentationId;
        h.field = field;
        h.oldValue = oldV;
        h.newValue = newV;
        h.reason = reason;
        h.userId = u == null ? null : u.userId();
        h.userName = u == null ? null : u.fullName();
        h.createdAt = Instant.now();
        priceHistory.save(h);
    }

    public Product findProduct(Long id) {
        return products.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
    }

    // ---------------- Conversión a DTO ----------------

    public List<ProductDto> toDtos(List<Product> list) {
        if (list.isEmpty()) return List.of();
        Long t = list.get(0).tenantId;
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        boolean taxIncluded = settings.forTenant(t).pricesIncludeTax;
        Map<Long, String> catNames = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .collect(Collectors.toMap(c -> c.id, c -> c.name));
        Map<Long, List<Presentation>> pres = presentations.findByProductIdIn(list.stream().map(p -> p.id).toList()).stream()
                .sorted(Comparator.comparingInt((Presentation x) -> x.sortOrder).thenComparing(x -> x.id))
                .collect(Collectors.groupingBy(x -> x.productId));
        List<ProductDto> out = new ArrayList<>();
        for (Product p : list) {
            List<PresentationDto> pds = pres.getOrDefault(p.id, List.of()).stream()
                    .map(x -> new PresentationDto(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, x.isBase, x.active,
                            costs ? margin(p, x, taxIncluded) : null))
                    .toList();
            out.add(new ProductDto(p.id, p.sku, p.name, p.description, p.brand, p.photo, p.categoryId,
                    p.categoryId == null ? null : catNames.get(p.categoryId), p.unit, p.trackStock, p.stock, p.stockStatus(),
                    costs ? p.cost : null, costs ? p.lastCost : null, p.stockMin, p.stockMax, p.safetyStock, p.taxName,
                    p.taxRate, p.fixedTax, p.status, p.favorite, pds,
                    costs ? p.stock.max(BigDecimal.ZERO).multiply(p.cost).setScale(2, RoundingMode.HALF_UP) : null,
                    p.createdAt, p.updatedAt));
        }
        return out;
    }

    /** Margen sobre el precio neto (sin impuestos) de la presentación. */
    public static BigDecimal margin(Product p, Presentation x, boolean taxIncluded) {
        BigDecimal net = x.price;
        if (taxIncluded) {
            net = net.subtract(p.fixedTax.multiply(x.factor));
            net = net.divide(BigDecimal.ONE.add(p.taxRate.divide(HUNDRED, 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
        }
        if (net.signum() <= 0) return null;
        BigDecimal cost = p.cost.multiply(x.factor);
        return net.subtract(cost).multiply(HUNDRED).divide(net, 1, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<PosProduct> posCatalog() {
        Long t = TenantContext.require();
        List<Product> list = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE");
        Map<Long, List<Presentation>> pres = presentations.findByTenantId(t).stream().filter(x -> x.active)
                .sorted(Comparator.comparingInt((Presentation x) -> x.sortOrder).thenComparing(x -> x.id))
                .collect(Collectors.groupingBy(x -> x.productId));
        return list.stream().map(p -> new PosProduct(p.id, p.sku, p.name, p.brand, p.photo, p.categoryId, p.unit, p.trackStock,
                p.stock, p.stockStatus(), p.taxName, p.taxRate, p.fixedTax, p.favorite,
                pres.getOrDefault(p.id, List.of()).stream()
                        .map(x -> new PosPresentation(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, x.isBase)).toList()))
                .toList();
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
