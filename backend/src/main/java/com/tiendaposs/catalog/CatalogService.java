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
    private final ComboComponentRepository combos;

    public CatalogService(CategoryRepository categories, ProductRepository products, PresentationRepository presentations,
                          PriceHistoryRepository priceHistory, InventoryService inventory, SettingsService settings,
                          Counters counters, AuditService audit, ComboComponentRepository combos) {
        this.combos = combos;
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
    public PageDto<ProductDto> search(String q, Long categoryId, String status, String stock, String level, int page, int size, String sort) {
        Long t = TenantContext.require();
        String like = q == null || q.isBlank() ? null : "%" + fold(q.trim().toLowerCase()) + "%";
        String code = q == null || q.isBlank() ? null : q.trim();
        List<Long> cats = categoryId == null ? List.of(-1L) : withChildren(t, categoryId);
        Sort s = switch (sort == null ? "" : sort) {
            case "stock" -> Sort.by("stock").ascending();
            case "recent" -> Sort.by("updatedAt").descending();
            default -> Sort.by("name").ascending();
        };
        boolean stockFilter = stock != null && !stock.isBlank();
        String lv = level == null || !Set.of("TOP", "ITEMS", "STOCK").contains(level) ? null : level;
        if (stockFilter) {
            // El estado de stock es calculado: se filtra en memoria sobre el resultado (catálogos de tienda de barrio).
            List<ProductDto> all = toDtos(products.search(t, categoryId == null, cats, blank(status), like, code, lv,
                    PageRequest.of(0, 5000, s)).getContent()).stream().filter(p -> p.stockStatus().equals(stock)).toList();
            int from = Math.min(page * size, all.size());
            List<ProductDto> slice = all.subList(from, Math.min(from + size, all.size()));
            return new PageDto<>(slice, all.size(), page, (int) Math.ceil(all.size() / (double) size));
        }
        Page<Product> res = products.search(t, categoryId == null, cats, blank(status), like, code, lv, PageRequest.of(page, size, s));
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
        if (initial.signum() > 0 && p.trackSerials) {
            throw ApiException.field("initialStock", "en productos con serial el stock entra registrando cada serial");
        }
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
        boolean hadLots = p.trackLots;
        boolean hadSerials = p.trackSerials;
        applyFields(p, r);
        if ("PADRE".equals(p.productType) || "COMBO".equals(p.productType)) {
            p.trackStock = false;
            p.trackLots = false;
            if ("COMBO".equals(p.productType)) p.trackSerials = false;
        }
        if (hadSerials != p.trackSerials && p.stock.signum() != 0) {
            throw ApiException.field("trackSerials", "deja el stock en cero antes de cambiar el control por serial");
        }
        if ("VARIANTE".equals(p.productType)) {
            Product parent = products.findById(p.parentId).orElseThrow();
            p.name = parent.name + " · " + p.variantLabel;
        }
        if ("PADRE".equals(p.productType)) syncChildren(p);
        if (!hadLots && p.trackLots) inventory.initLots(p);
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
        if ("PADRE".equals(p.productType)) products.findByParentIdOrderByIdAsc(p.id).forEach(c -> c.status = status);
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

    /** Lo que se edita en el padre lo heredan sus variantes (nombre, categoría, marca, unidad, impuestos, serial y garantía). */
    void syncChildren(Product parent) {
        for (Product c : products.findByParentIdOrderByIdAsc(parent.id)) {
            c.name = parent.name + " · " + c.variantLabel;
            c.categoryId = parent.categoryId;
            c.brand = parent.brand;
            c.description = parent.description;
            c.unit = parent.unit;
            c.taxName = parent.taxName;
            c.taxRate = parent.taxRate;
            c.fixedTax = parent.fixedTax;
            c.taxClass = parent.taxClass;
            c.warrantyMonths = parent.warrantyMonths;
            if (c.trackSerials != parent.trackSerials && c.stock.signum() == 0) c.trackSerials = parent.trackSerials;
        }
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
        // Con tarifa > 0 el producto es gravado: la clasificación solo aplica a tarifa 0.
        if (p.taxRate.signum() > 0) p.taxClass = null;
        else if (r.taxClass() != null) p.taxClass = r.taxClass().isBlank() ? null : r.taxClass();
        if (r.favorite() != null) p.favorite = r.favorite();
        if (r.trackLots() != null) p.trackLots = r.trackLots() && p.trackStock;
        if (p.trackLots && !s.hasModule("LOTES") && r.trackLots() != null) {
            com.tiendaposs.settings.ModulesService.check(s, "LOTES");
        }
        if (r.trackSerials() != null && r.trackSerials() != p.trackSerials) {
            if (r.trackSerials()) com.tiendaposs.settings.ModulesService.check(s, "SERIALES");
            p.trackSerials = r.trackSerials();
        }
        if (p.trackSerials) {
            if (!"UNIDAD".equals(p.unit)) throw ApiException.field("trackSerials", "solo los productos por unidad llevan serial");
            p.trackStock = true;
            p.trackLots = false;
        }
        if (r.warrantyMonths() != null) p.warrantyMonths = r.warrantyMonths();
        if (r.plu() != null) {
            String plu = blank(r.plu());
            if (plu != null) {
                plu = plu.replaceFirst("^0+(?=\\d)", "");
                String norm = plu;
                products.findByPlu(p.tenantId, norm).filter(o -> !o.id.equals(p.id)).ifPresent(o -> {
                    throw ApiException.field("plu", "el código de báscula " + norm + " ya lo usa " + o.name);
                });
            }
            p.plu = plu;
        }
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
        List<Long> parentIds = list.stream().filter(p -> "PADRE".equals(p.productType)).map(p -> p.id).toList();
        Map<Long, List<Product>> children = parentIds.isEmpty() ? Map.of() : products.findByParentIdIn(parentIds).stream()
                .sorted(Comparator.comparing((Product x) -> x.id))
                .collect(Collectors.groupingBy(x -> x.parentId));
        List<Long> comboIds = list.stream().filter(p -> "COMBO".equals(p.productType)).map(p -> p.id).toList();
        Map<Long, List<ComboComponent>> comps = comboIds.isEmpty() ? Map.of() : combos.findByComboIdIn(comboIds).stream()
                .sorted(Comparator.comparingInt((ComboComponent x) -> x.sortOrder).thenComparing(x -> x.id))
                .collect(Collectors.groupingBy(x -> x.comboId));
        Map<Long, Product> compProducts = new HashMap<>();
        products.findAllById(comps.values().stream().flatMap(List::stream).map(c -> c.componentId).toList())
                .forEach(x -> compProducts.put(x.id, x));
        Set<Long> parentRefs = list.stream().filter(p -> p.parentId != null).map(p -> p.parentId).collect(Collectors.toSet());
        Map<Long, String> parentNames = new HashMap<>();
        products.findAllById(parentRefs).forEach(x -> parentNames.put(x.id, x.name));
        List<Long> presIds = new ArrayList<>(list.stream().map(p -> p.id).toList());
        children.values().forEach(cs -> cs.forEach(c -> presIds.add(c.id)));
        Map<Long, List<Presentation>> pres = presentations.findByProductIdIn(presIds).stream()
                .sorted(Comparator.comparingInt((Presentation x) -> x.sortOrder).thenComparing(x -> x.id))
                .collect(Collectors.groupingBy(x -> x.productId));
        List<ProductDto> out = new ArrayList<>();
        for (Product p : list) {
            List<VariantDto> vs = null;
            BigDecimal stock = p.stock;
            String stockStatus = p.stockStatus();
            BigDecimal stockValue = p.stock.max(BigDecimal.ZERO).multiply(p.cost);
            if ("PADRE".equals(p.productType)) {
                List<Product> cs = children.getOrDefault(p.id, List.of());
                vs = cs.stream().map(c -> {
                    Presentation base = pres.getOrDefault(c.id, List.of()).stream().filter(x -> x.isBase).findFirst().orElse(null);
                    return new VariantDto(c.id, c.sku, c.variantLabel, readValues(c.variantValues), base == null ? null : base.id,
                            base == null ? null : base.barcode, base == null ? null : base.price, costs ? c.cost : null,
                            c.stock, c.stockStatus(), c.status);
                }).toList();
                List<Product> active = cs.stream().filter(c -> "ACTIVE".equals(c.status)).toList();
                stock = active.stream().map(c -> c.stock).reduce(BigDecimal.ZERO, BigDecimal::add);
                stockValue = active.stream().map(c -> c.stock.max(BigDecimal.ZERO).multiply(c.cost)).reduce(BigDecimal.ZERO, BigDecimal::add);
                stockStatus = active.isEmpty() || stock.signum() <= 0 ? "AGOTADO"
                        : active.stream().anyMatch(c -> !"NORMAL".equals(c.stockStatus()) && !"EXCESO".equals(c.stockStatus())) ? "BAJO" : "NORMAL";
            }
            List<ComponentDto> cds = null;
            if ("COMBO".equals(p.productType)) {
                cds = comps.getOrDefault(p.id, List.of()).stream().map(c -> {
                    Product x = compProducts.get(c.componentId);
                    return new ComponentDto(x.id, x.sku, x.name, x.unit, c.quantity, costs ? x.cost : null, x.stock, x.trackStock);
                }).toList();
            }
            List<PresentationDto> pds = pres.getOrDefault(p.id, List.of()).stream()
                    .map(x -> new PresentationDto(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, x.isBase, x.active,
                            costs ? margin(p, x, taxIncluded) : null))
                    .toList();
            out.add(new ProductDto(p.id, p.sku, p.name, p.description, p.brand, p.photo, p.categoryId,
                    p.categoryId == null ? null : catNames.get(p.categoryId), p.unit, p.trackStock, stock, stockStatus,
                    costs ? p.cost : null, costs ? p.lastCost : null, p.stockMin, p.stockMax, p.safetyStock, p.taxName,
                    p.taxRate, p.fixedTax, p.status, p.favorite, pds,
                    costs ? stockValue.setScale(2, RoundingMode.HALF_UP) : null,
                    p.trackLots, p.createdAt, p.updatedAt, p.productType, p.parentId,
                    p.parentId == null ? null : parentNames.get(p.parentId), p.variantLabel, readValues(p.variantValues),
                    readOptions(p.variantOptions), p.trackSerials, p.warrantyMonths, p.plu, vs, cds, p.reserved, p.onlineVisible, p.taxClass));
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
        // Los padres viajan sin presentaciones (no se venden): la caja agrupa sus variantes bajo ellos.
        return list.stream().map(p -> new PosProduct(p.id, p.sku, p.name, p.brand, "VARIANTE".equals(p.productType) ? null : p.photo,
                p.categoryId, p.unit, p.trackStock, p.stock.subtract(p.reserved), p.stockStatus(), p.taxName, p.taxRate, p.fixedTax, p.favorite,
                "PADRE".equals(p.productType) ? List.of() : pres.getOrDefault(p.id, List.of()).stream()
                        .map(x -> new PosPresentation(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, x.isBase)).toList(),
                p.productType, p.parentId, p.variantLabel, readValues(p.variantValues), readOptions(p.variantOptions),
                p.trackSerials, p.plu))
                .toList();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static Map<String, String> readValues(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    static List<VariantOption> readOptions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<VariantOption>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Quita tildes para buscar "aguila" y encontrar "Águila" (igual que translate() en la consulta). */
    static String fold(String s) {
        return s.replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n');
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
