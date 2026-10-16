package com.tiendaposs.catalog;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.*;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;

/** Variantes (tallas, colores…), combos y etiquetas de báscula. */
@Service
public class AdvancedCatalogService {

    private final CatalogService catalog;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final ComboComponentRepository combos;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final AuditService audit;

    public AdvancedCatalogService(CatalogService catalog, ProductRepository products, PresentationRepository presentations,
                                  ComboComponentRepository combos, InventoryService inventory, SettingsService settings,
                                  AuditService audit) {
        this.catalog = catalog;
        this.products = products;
        this.presentations = presentations;
        this.combos = combos;
        this.inventory = inventory;
        this.settings = settings;
        this.audit = audit;
    }

    // =====================================================================
    // Variantes
    // =====================================================================

    /**
     * Guarda la matriz de variantes de un producto. La primera vez el producto pasa a ser "padre": deja de
     * venderse y de tener stock propio, y cada combinación se vuelve un producto hijo con su SKU, código,
     * precio, costo y stock. Las combinaciones que ya no vienen se descontinúan (no se borran: tienen historia).
     */
    @Transactional
    public ProductDto saveVariants(Long id, VariantsRequest r) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        ModulesService.check(st, "VARIANTES");
        Product parent = products.lockById(id, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if ("VARIANTE".equals(parent.productType)) throw ApiException.badRequest("Una variante no puede tener variantes");
        if ("COMBO".equals(parent.productType)) throw ApiException.badRequest("Un combo no puede tener variantes");
        if ("SIMPLE".equals(parent.productType)) {
            if (parent.stock.signum() != 0) {
                throw ApiException.badRequest("Deja el stock de " + parent.name + " en cero antes de dividirlo en variantes "
                        + "(después registras el stock de cada variante)");
            }
            if (combos.usedAsComponent(parent.id)) throw ApiException.badRequest(parent.name + " hace parte de un combo: usa una variante en el combo");
            parent.productType = "PADRE";
            parent.trackStock = false;
            parent.trackLots = false;
        }
        // Opciones: nombres y valores sin repetir.
        List<VariantOption> options = new ArrayList<>();
        Set<String> optNames = new HashSet<>();
        for (VariantOption o : r.options()) {
            String name = o.name().trim();
            if (!optNames.add(name.toLowerCase())) throw ApiException.field("options", "la opción " + name + " está repetida");
            List<String> values = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String v : o.values()) {
                String vv = v.trim();
                if (!seen.add(vv.toLowerCase())) throw ApiException.field("options", "el valor " + vv + " está repetido en " + name);
                values.add(vv);
            }
            options.add(new VariantOption(name, values));
        }
        parent.variantOptions = CatalogService.write(options);

        List<Product> existing = products.findByParentIdOrderByIdAsc(parent.id);
        Map<Long, Product> byId = new HashMap<>();
        Map<String, Product> byLabel = new HashMap<>();
        for (Product c : existing) {
            byId.put(c.id, c);
            byLabel.put(c.variantLabel.toLowerCase(), c);
        }
        Presentation parentBase = presentations.findByProductIdOrderBySortOrderAscIdAsc(parent.id).stream()
                .filter(x -> x.isBase).findFirst().orElse(null);
        String unitName = parentBase == null ? "Unidad" : parentBase.name;
        Set<String> labels = new HashSet<>();
        Set<Long> kept = new HashSet<>();
        int created = 0, updated = 0;
        for (VariantRow row : r.variants()) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (VariantOption o : options) {
                String v = row.values().entrySet().stream().filter(e -> e.getKey().trim().equalsIgnoreCase(o.name()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                String match = v == null ? null : o.values().stream().filter(x -> x.equalsIgnoreCase(v.trim())).findFirst().orElse(null);
                if (match == null) throw ApiException.field("variants", "a una variante le falta " + o.name() + " (o trae un valor que no está en la lista)");
                values.put(o.name(), match);
            }
            String label = String.join(" / ", values.values());
            if ((parent.name + " · " + label).length() > 160) throw ApiException.field("variants", "el nombre de la variante " + label + " es muy largo");
            if (!labels.add(label.toLowerCase())) throw ApiException.field("variants", "la variante " + label + " está repetida");
            Product child = row.id() != null ? byId.get(row.id()) : byLabel.get(label.toLowerCase());
            if (row.id() != null && child == null) throw ApiException.field("variants", "una variante no pertenece a este producto");
            boolean active = row.active() == null || row.active();
            if (child == null) {
                if (!active) continue;
                String sku = CatalogService.blank(row.sku());
                if (sku == null) sku = variantSku(t, parent.sku, values.values());
                ProductRequest req = new ProductRequest(sku, parent.name + " · " + label, parent.description, parent.brand, null,
                        parent.categoryId, parent.unit, true, row.cost() == null ? parent.cost : row.cost(), row.initialStock(),
                        parent.stockMin, parent.stockMax, parent.safetyStock, parent.taxName, parent.taxRate, parent.fixedTax, false,
                        List.of(new PresentationRequest(null, unitName, BigDecimal.ONE, CatalogService.blank(row.barcode()), row.price(),
                                parentBase == null ? null : parentBase.minPrice, true, true)),
                        "Variante nueva", false, parent.trackSerials, parent.warrantyMonths, null);
                ProductDto dto = catalog.create(req);
                child = products.findById(dto.id()).orElseThrow();
                created++;
            } else {
                Presentation base = presentations.findByProductIdOrderBySortOrderAscIdAsc(child.id).stream()
                        .filter(x -> x.isBase).findFirst().orElseThrow();
                List<PresentationRequest> pres = new ArrayList<>();
                for (Presentation x : presentations.findByProductIdOrderBySortOrderAscIdAsc(child.id)) {
                    if (!x.active && !x.isBase) continue;
                    pres.add(x.id.equals(base.id)
                            ? new PresentationRequest(x.id, x.name, x.factor, CatalogService.blank(row.barcode()), row.price(), x.minPrice, true, true)
                            : new PresentationRequest(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, false, x.active));
                }
                BigDecimal cost = row.cost() != null && CurrentUser.get().can(Permission.COSTOS_VER) ? row.cost() : null;
                String sku = CatalogService.blank(row.sku()) == null ? child.sku : row.sku().trim();
                catalog.update(child.id, new ProductRequest(sku, child.name, child.description, child.brand, child.photo,
                        child.categoryId, child.unit, child.trackStock, cost, null, child.stockMin, child.stockMax, child.safetyStock,
                        child.taxName, child.taxRate, child.fixedTax, child.favorite, pres, "Edición de variantes", child.trackLots,
                        null, null, null));
                String wanted = active ? "ACTIVE" : "DISCONTINUED";
                if (!wanted.equals(child.status)) child.status = wanted;
                if (row.initialStock() != null && row.initialStock().signum() > 0) {
                    throw ApiException.field("variants", "el stock de una variante existente se cambia con un ajuste de inventario");
                }
                updated++;
            }
            child.productType = "VARIANTE";
            child.parentId = parent.id;
            child.variantLabel = label;
            child.variantValues = CatalogService.write(values);
            child.name = parent.name + " · " + label;
            kept.add(child.id);
        }
        int discontinued = 0;
        for (Product c : existing) {
            if (!kept.contains(c.id) && "ACTIVE".equals(c.status)) {
                c.status = "DISCONTINUED";
                discontinued++;
            }
        }
        if (kept.isEmpty()) throw ApiException.field("variants", "deja al menos una variante activa");
        audit.log("VARIANTES", "PRODUCT", parent.id, "Variantes de " + parent.name + ": " + created + " nuevas, " + updated
                + " actualizadas" + (discontinued > 0 ? ", " + discontinued + " descontinuadas" : ""));
        return catalog.get(parent.id);
    }

    /** SKU de la variante: SKU del padre + iniciales de cada valor ("CAM01-M-ROJ"), sin repetir. */
    private String variantSku(Long t, String parentSku, Collection<String> values) {
        StringBuilder b = new StringBuilder(parentSku);
        for (String v : values) {
            String clean = Normalizer.normalize(v, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            b.append('-').append(clean, 0, Math.min(clean.length(), 4));
        }
        String base = b.length() > 36 ? b.substring(0, 36) : b.toString();
        String sku = base;
        int n = 2;
        while (products.skuTaken(t, sku, null)) sku = base + "-" + n++;
        return sku;
    }

    // =====================================================================
    // Combos
    // =====================================================================

    /** Define qué lleva un combo. El combo no tiene stock propio: al venderlo sale cada componente del inventario. */
    @Transactional
    public ProductDto saveComponents(Long id, ComponentsRequest r) {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), "COMBOS");
        Product combo = products.lockById(id, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if (!"SIMPLE".equals(combo.productType) && !"COMBO".equals(combo.productType)) {
            throw ApiException.badRequest("Un producto con variantes o una variante no puede ser combo");
        }
        if ("SIMPLE".equals(combo.productType)) {
            if (combo.stock.signum() != 0) throw ApiException.badRequest("Deja el stock de " + combo.name + " en cero antes de volverlo combo");
            if (combo.trackSerials) throw ApiException.badRequest("Un producto con serial no puede ser combo");
            if (combos.usedAsComponent(combo.id)) throw ApiException.badRequest(combo.name + " ya es componente de otro combo");
        }
        Set<Long> seen = new HashSet<>();
        List<ComboComponent> list = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        List<String> detail = new ArrayList<>();
        int order = 0;
        for (ComponentRequest cr : r.components()) {
            if (!seen.add(cr.productId())) throw ApiException.field("components", "un producto está repetido en el combo");
            if (cr.productId().equals(combo.id)) throw ApiException.field("components", "un combo no puede contenerse a sí mismo");
            Product p = products.findByIdAndTenantId(cr.productId(), t).orElseThrow(() -> ApiException.field("components", "un producto no existe"));
            if ("PADRE".equals(p.productType)) throw ApiException.field("components", "elige la variante de " + p.name + " (talla, color…)");
            if ("COMBO".equals(p.productType)) throw ApiException.field("components", p.name + " ya es un combo: no se pueden anidar");
            if (p.trackSerials) throw ApiException.field("components", p.name + " lleva serial: véndelo por separado");
            if ("UNIDAD".equals(p.unit) && cr.quantity().stripTrailingZeros().scale() > 0) {
                throw ApiException.field("components", p.name + " va en unidades enteras");
            }
            ComboComponent c = new ComboComponent();
            c.tenantId = t;
            c.comboId = combo.id;
            c.componentId = p.id;
            c.quantity = cr.quantity().setScale(3, RoundingMode.HALF_UP);
            c.sortOrder = order++;
            list.add(c);
            cost = cost.add(p.cost.multiply(c.quantity));
            detail.add(cr.quantity().stripTrailingZeros().toPlainString() + " x " + p.name);
        }
        combos.deleteByComboId(combo.id);
        combos.flush();
        combos.saveAll(list);
        combo.productType = "COMBO";
        combo.trackStock = false;
        combo.trackLots = false;
        combo.cost = cost.setScale(4, RoundingMode.HALF_UP);
        combo.lastCost = combo.cost;
        audit.log("COMBO", "PRODUCT", combo.id, "Combo " + combo.name + ": " + String.join(", ", detail));
        return catalog.get(combo.id);
    }

    /** Vuelve un combo a producto normal (sin componentes). */
    @Transactional
    public ProductDto removeComponents(Long id) {
        Long t = TenantContext.require();
        Product combo = products.lockById(id, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if (!"COMBO".equals(combo.productType)) throw ApiException.badRequest(combo.name + " no es un combo");
        combos.deleteByComboId(combo.id);
        combo.productType = "SIMPLE";
        combo.trackStock = true;
        audit.log("COMBO", "PRODUCT", combo.id, "Dejó de ser combo: " + combo.name);
        return catalog.get(combo.id);
    }

    /** Lo que descuenta un combo por cada unidad vendida: componente y cantidad en unidades base. */
    public record Part(Product product, BigDecimal quantity) {
    }

    public List<Part> parts(Product combo) {
        if (!"COMBO".equals(combo.productType)) return List.of();
        List<Part> out = new ArrayList<>();
        for (ComboComponent c : combos.findByComboIdOrderBySortOrderAscIdAsc(combo.id)) {
            out.add(new Part(products.findById(c.componentId).orElseThrow(), c.quantity));
        }
        return out;
    }

    /** Costo de una presentación: en un combo, la suma del costo vigente de sus componentes. */
    public BigDecimal unitCost(Product p, BigDecimal factor) {
        BigDecimal base = p.cost;
        if ("COMBO".equals(p.productType)) {
            base = parts(p).stream().map(x -> x.product().cost.multiply(x.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return base.multiply(factor).setScale(4, RoundingMode.HALF_UP);
    }

    // =====================================================================
    // Báscula
    // =====================================================================

    /** Dígito de control EAN-13 de los primeros 12 dígitos. */
    public static int ean13Check(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = first12.charAt(i) - '0';
            sum += i % 2 == 0 ? d : d * 3;
        }
        return (10 - sum % 10) % 10;
    }

    /** Lee una etiqueta de balanza (EAN-13 con prefijo 20-29): producto por PLU y peso o precio impreso. */
    @Transactional(readOnly = true)
    public ScaleCodeDto parseScale(String raw) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        ModulesService.check(st, "BASCULA");
        String code = raw == null ? "" : raw.trim();
        if (!code.matches("\\d{13}")) throw ApiException.badRequest("Una etiqueta de balanza tiene 13 números");
        if (!Arrays.asList(st.scalePrefixes.split(",")).contains(code.substring(0, 2))) {
            throw ApiException.badRequest("El código " + code + " no empieza por un prefijo de balanza (" + st.scalePrefixes + ")");
        }
        if (ean13Check(code.substring(0, 12)) != code.charAt(12) - '0') {
            throw ApiException.badRequest("El código " + code + " está mal leído (dígito de control). Vuelve a escanearlo");
        }
        int n = st.scalePluDigits;
        String plu = code.substring(2, 2 + n).replaceFirst("^0+(?=\\d)", "");
        BigDecimal value = new BigDecimal(code.substring(2 + n, 12));
        Product p = products.findByPlu(t, plu).orElseThrow(() -> ApiException.notFound("Ningún producto tiene el código de báscula " + plu));
        Presentation base = presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream().filter(x -> x.isBase && x.active)
                .findFirst().orElseThrow(() -> ApiException.badRequest(p.name + " no tiene precio"));
        BigDecimal qty, total;
        if ("PRECIO".equals(st.scaleMode)) {
            total = value;
            qty = base.price.signum() == 0 ? BigDecimal.ONE : value.divide(base.price, 3, RoundingMode.HALF_UP);
        } else {
            qty = switch (p.unit) {
                case "KILOGRAMO", "LIBRA", "LITRO", "METRO" -> value.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
                default -> value;
            };
            total = qty.multiply(base.price).setScale(0, RoundingMode.HALF_UP);
        }
        if (qty.signum() <= 0) throw ApiException.badRequest("La etiqueta no trae cantidad");
        return new ScaleCodeDto(code, plu, p.id, p.name, base.id, qty, base.price, total, st.scaleMode);
    }
}
