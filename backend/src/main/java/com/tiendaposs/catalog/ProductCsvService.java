package com.tiendaposs.catalog;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.*;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Importar/exportar el catálogo en CSV (abre directo en Excel: separador ";" y BOM UTF-8).
 * Columnas: ver {@link #HEADERS}. Las presentaciones extra van en una columna con el formato
 * "Nombre:factor:precio:código|Nombre:factor:precio:código".
 */
@Service
public class ProductCsvService {

    public static final List<String> HEADERS = List.of("sku", "nombre", "categoria", "subcategoria", "marca", "unidad",
            "codigo_barras", "precio", "precio_minimo", "costo", "stock", "stock_minimo", "stock_maximo", "stock_seguridad",
            "impuesto", "tasa_impuesto", "impuesto_fijo", "estado", "presentaciones");

    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CategoryRepository categories;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final AuditService audit;
    private final TransactionTemplate rowTx;

    public ProductCsvService(ProductRepository products, PresentationRepository presentations, CategoryRepository categories,
                             CatalogService catalog, InventoryService inventory, AuditService audit,
                             PlatformTransactionManager txManager) {
        this.rowTx = new TransactionTemplate(txManager);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.products = products;
        this.presentations = presentations;
        this.categories = categories;
        this.catalog = catalog;
        this.inventory = inventory;
        this.audit = audit;
    }

    @Transactional
    public String export() {
        Long t = TenantContext.require();
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        Map<Long, Category> cats = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream()
                .collect(Collectors.toMap(c -> c.id, c -> c));
        Map<Long, List<Presentation>> pres = presentations.findByTenantId(t).stream().filter(p -> p.active)
                .sorted(Comparator.comparingInt((Presentation p) -> p.sortOrder))
                .collect(Collectors.groupingBy(p -> p.productId));
        StringBuilder sb = new StringBuilder("﻿").append(String.join(";", HEADERS)).append("\r\n");
        for (Product p : products.findByTenantIdOrderByNameAsc(t)) {
            List<Presentation> list = pres.getOrDefault(p.id, List.of());
            Presentation base = list.stream().filter(x -> x.isBase).findFirst().orElse(null);
            Category c = p.categoryId == null ? null : cats.get(p.categoryId);
            Category parent = c != null && c.parentId != null ? cats.get(c.parentId) : null;
            String extra = list.stream().filter(x -> !x.isBase)
                    .map(x -> x.name + ":" + num(x.factor) + ":" + num(x.price) + ":" + (x.barcode == null ? "" : x.barcode))
                    .collect(Collectors.joining("|"));
            List<String> row = List.of(p.sku, p.name, parent != null ? parent.name : (c == null ? "" : c.name),
                    parent != null ? c.name : "", nn(p.brand), p.unit, base == null || base.barcode == null ? "" : base.barcode,
                    base == null ? "" : num(base.price), base == null ? "" : num(base.minPrice), costs ? num(p.cost) : "",
                    num(p.stock), num(p.stockMin), num(p.stockMax), num(p.safetyStock), p.taxName, num(p.taxRate),
                    num(p.fixedTax), "ACTIVE".equals(p.status) ? "activo" : "descontinuado", extra);
            sb.append(row.stream().map(ProductCsvService::cell).collect(Collectors.joining(";"))).append("\r\n");
        }
        audit.log("CATALOGO_EXPORTADO", "PRODUCT", null, "Exportó el catálogo de productos");
        return sb.toString();
    }

    /** Crea o actualiza por SKU. Cada fila va en su propia transacción: una fila con error no deja nada a medias
     * ni detiene las demás; los errores se informan al final. */
    public ImportResult importCsv(String csv) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) throw ApiException.badRequest("El archivo está vacío");
        char sep = lines.get(0).chars().filter(ch -> ch == ';').count() >= lines.get(0).chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<String> header = parse(lines.get(0), sep).stream().map(h -> h.trim().toLowerCase()).toList();
        if (!header.contains("nombre")) throw ApiException.badRequest("Falta la columna \"nombre\". Descarga la plantilla para ver el formato.");
        if (lines.size() > 5001) throw ApiException.badRequest("Máximo 5.000 productos por archivo");
        int created = 0, updated = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = parse(lines.get(i), sep);
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) row.put(header.get(c), cells.get(c).trim());
            try {
                Boolean isNew = rowTx.execute(st -> importRow(t, me, row));
                if (Boolean.TRUE.equals(isNew)) created++;
                else updated++;
            } catch (ApiException e) {
                String detail = e.getDetails() == null ? "" : " (" + e.getDetails().values().iterator().next() + ")";
                errors.add("Fila " + (i + 1) + ": " + e.getMessage() + detail);
            } catch (NumberFormatException e) {
                errors.add("Fila " + (i + 1) + ": hay un número mal escrito");
            }
            if (errors.size() > 200) {
                errors.add("…se detuvo el reporte de errores");
                break;
            }
        }
        final int c1 = created, u1 = updated, e1 = errors.size();
        rowTx.executeWithoutResult(st -> audit.sensitive("CATALOGO_IMPORTADO", "PRODUCT", null,
                "Importó productos: " + c1 + " nuevos, " + u1 + " actualizados, " + e1 + " con error"));
        return new ImportResult(created, updated, errors);
    }

    private boolean importRow(Long t, AuthUser me, Map<String, String> r) {
        String name = r.getOrDefault("nombre", "").replaceFirst("^'(?=[-=+@])", "");
        if (name.isBlank()) throw ApiException.badRequest("el nombre está vacío");
        String sku = r.getOrDefault("sku", "");
        Product existing = sku.isBlank() ? null : products.findBySku(t, sku).orElse(null);
        Long categoryId = resolveCategory(t, r.get("categoria"), r.get("subcategoria"));
        BigDecimal price = dec(r.get("precio"));
        List<PresentationRequest> pres = new ArrayList<>();
        List<Presentation> current = existing == null ? List.of()
                : presentations.findByProductIdOrderBySortOrderAscIdAsc(existing.id).stream().filter(x -> x.active).toList();
        Presentation curBase = current.stream().filter(x -> x.isBase).findFirst().orElse(null);
        if (price == null && curBase == null) throw ApiException.badRequest("falta el precio");
        String baseCode = r.containsKey("codigo_barras") ? emptyNull(r.get("codigo_barras")) : (curBase == null ? null : curBase.barcode);
        pres.add(new PresentationRequest(curBase == null ? null : curBase.id, curBase == null ? "Unidad" : curBase.name,
                BigDecimal.ONE, baseCode, price != null ? price : curBase.price,
                dec(r.get("precio_minimo")) != null ? dec(r.get("precio_minimo")) : (curBase == null ? BigDecimal.ZERO : curBase.minPrice),
                true, true));
        String extra = r.get("presentaciones");
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split("\\|")) {
                String[] f = part.split(":", -1);
                if (f.length < 3) throw ApiException.badRequest("presentación mal escrita: " + part);
                Presentation match = current.stream().filter(x -> !x.isBase && x.name.equalsIgnoreCase(f[0].trim())).findFirst().orElse(null);
                pres.add(new PresentationRequest(match == null ? null : match.id, f[0].trim(), new BigDecimal(f[1].trim().replace(',', '.')),
                        f.length > 3 ? emptyNull(f[3]) : null, new BigDecimal(f[2].trim().replace(',', '.')), BigDecimal.ZERO, false, true));
            }
        } else {
            for (Presentation x : current) {
                if (!x.isBase) pres.add(new PresentationRequest(x.id, x.name, x.factor, x.barcode, x.price, x.minPrice, false, true));
            }
        }
        String unit = emptyNull(r.get("unidad"));
        unit = unit == null ? (existing == null ? "UNIDAD" : existing.unit) : unit.toUpperCase();
        BigDecimal cost = dec(r.get("costo"));
        if (cost != null && !me.can(Permission.COSTOS_VER)) cost = null;
        BigDecimal stock = dec(r.get("stock"));
        var req = new ProductRequest(sku.isBlank() ? null : sku, name, existing == null ? null : existing.description,
                emptyNull(r.get("marca")) != null ? r.get("marca") : (existing == null ? null : existing.brand),
                existing == null ? null : existing.photo, categoryId != null ? categoryId : (existing == null ? null : existing.categoryId),
                unit, existing == null || existing.trackStock, cost, existing == null ? stock : null,
                orElse(dec(r.get("stock_minimo")), existing == null ? null : existing.stockMin),
                orElse(dec(r.get("stock_maximo")), existing == null ? null : existing.stockMax),
                orElse(dec(r.get("stock_seguridad")), existing == null ? null : existing.safetyStock),
                emptyNull(r.get("impuesto")) != null ? r.get("impuesto") : (existing == null ? null : existing.taxName),
                orElse(dec(r.get("tasa_impuesto")), existing == null ? null : existing.taxRate),
                orElse(dec(r.get("impuesto_fijo")), existing == null ? null : existing.fixedTax),
                existing == null ? null : existing.favorite, pres, "Importación de archivo");
        if (existing == null) {
            ProductDto dto = catalog.create(req);
            applyStatus(dto.id(), r.get("estado"));
            return true;
        }
        catalog.update(existing.id, req);
        applyStatus(existing.id, r.get("estado"));
        if (stock != null && existing.trackStock && stock.compareTo(existing.stock) != 0) {
            CurrentUser.require(Permission.INVENTARIO_AJUSTAR);
            inventory.apply(t, new InventoryService.Change(existing.id, stock.subtract(existing.stock), InventoryService.IMPORTACION,
                    "IMPORT", null, "Existencia actualizada por importación", null, true));
        }
        return false;
    }

    private void applyStatus(Long id, String estado) {
        if (estado == null || estado.isBlank()) return;
        String s = estado.trim().toLowerCase();
        String st = s.startsWith("desc") || s.equals("inactivo") || s.equals("discontinued") ? "DISCONTINUED" : "ACTIVE";
        Product p = catalog.findProduct(id);
        if (!st.equals(p.status)) p.status = st;
    }

    private Long resolveCategory(Long t, String name, String sub) {
        if (name == null || name.isBlank()) return null;
        Category parent = categories.findByName(t, name.trim(), null).orElseGet(() -> {
            Category c = new Category();
            c.tenantId = t;
            c.name = name.trim();
            return categories.save(c);
        });
        if (sub == null || sub.isBlank()) return parent.id;
        return categories.findByName(t, sub.trim(), parent.id).orElseGet(() -> {
            Category c = new Category();
            c.tenantId = t;
            c.parentId = parent.id;
            c.name = sub.trim();
            return categories.save(c);
        }).id;
    }

    // ---------------- utilidades CSV ----------------

    static List<String> parse(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static String cell(String v) {
        if (v == null) return "";
        if (v.contains(";") || v.contains("\"") || v.contains("\n")) return "\"" + v.replace("\"", "\"\"") + "\"";
        // Evita que Excel interprete el contenido como fórmula.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) return "'" + v;
        return v;
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : v.setScale(Math.max(0, Math.min(v.stripTrailingZeros().scale(), 4)), RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.isBlank()) return null;
        String x = s.trim().replace("$", "").replace(" ", "");
        // "12.500" (miles con punto) vs "12,5" / "12.5" (decimales).
        if (x.matches("\\d{1,3}(\\.\\d{3})+")) x = x.replace(".", "");
        else if (x.matches("\\d{1,3}(,\\d{3})+")) x = x.replace(",", "");
        x = x.replace(',', '.');
        BigDecimal v = new BigDecimal(x);
        if (v.signum() < 0) throw ApiException.badRequest("hay un valor negativo");
        return v;
    }

    private static BigDecimal orElse(BigDecimal v, BigDecimal fallback) {
        return v != null ? v : fallback;
    }

    private static String emptyNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
