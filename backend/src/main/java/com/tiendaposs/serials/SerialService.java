package com.tiendaposs.serials;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Seriales e IMEI: cada unidad entra con su serial, sale en una venta concreta (con su garantía) y vuelve
 * al stock si se anula o se devuelve en buen estado. El stock del producto es igual a sus seriales EN_STOCK.
 */
@Service
public class SerialService {

    public static final String EN_STOCK = "EN_STOCK", VENDIDO = "VENDIDO", DEFECTUOSO = "DEFECTUOSO";

    public interface ProductSerialRepository extends JpaRepository<ProductSerial, Long> {
        @Query("select s from ProductSerial s where s.tenantId = ?1 and s.productId = ?2 and lower(s.serial) = lower(?3)")
        Optional<ProductSerial> find(Long tenantId, Long productId, String serial);

        List<ProductSerial> findByProductIdAndStatusOrderBySerialAsc(Long productId, String status);

        List<ProductSerial> findBySaleIdOrderByIdAsc(Long saleId);

        List<ProductSerial> findBySaleLineIdOrderByIdAsc(Long saleLineId);

        Optional<ProductSerial> findByIdAndTenantId(Long id, Long tenantId);

        @Query("select s from ProductSerial s where s.tenantId = ?1 and (lower(s.serial) like ?2 or lower(coalesce(s.customerName, '')) like ?2) order by s.updatedAt desc")
        List<ProductSerial> search(Long tenantId, String like, org.springframework.data.domain.Pageable page);

        @Query("select s from ProductSerial s where s.tenantId = ?1 and s.productId = ?2 order by s.updatedAt desc")
        List<ProductSerial> ofProduct(Long tenantId, Long productId, org.springframework.data.domain.Pageable page);
    }

    public record SerialDto(Long id, Long productId, String productName, String serial, String status, BigDecimal unitCost,
                            Long saleId, Long saleNumber, Long customerId, String customerName, Instant soldAt,
                            LocalDate warrantyUntil, boolean inWarranty, String notes, Instant createdAt, Instant updatedAt) {
    }

    public record AddSerialsRequest(@NotNull @Size(min = 1, max = 500, message = "entre 1 y 500 seriales") List<@NotBlank @Size(max = 80) String> serials,
                                    @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitCost,
                                    @NotBlank(message = "explica de dónde vienen") @Size(max = 300) String reason) {
    }

    public record WriteOffRequest(@NotBlank(message = "explica el motivo") @Size(max = 300) String reason) {
    }

    private final ProductSerialRepository serials;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final AuditService audit;
    private final jakarta.persistence.EntityManager em;

    public SerialService(ProductSerialRepository serials, ProductRepository products, InventoryService inventory,
                         SettingsService settings, AuditService audit, jakarta.persistence.EntityManager em) {
        this.serials = serials;
        this.products = products;
        this.inventory = inventory;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    /** Limpia y valida la lista: sin vacíos ni repetidos. */
    public static List<String> normalize(List<String> list) {
        if (list == null) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : list) {
            if (s == null || s.isBlank()) continue;
            String v = s.trim().toUpperCase();
            if (v.length() > 80) throw ApiException.badRequest("El serial " + v.substring(0, 20) + "… es muy largo");
            if (!seen.add(v)) throw ApiException.badRequest("El serial " + v + " está repetido");
            out.add(v);
        }
        return out;
    }

    public static String join(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(",", list);
    }

    public static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    // ---------------- Entradas ----------------

    /** Entrada manual (inventario inicial, traslado, compra sin orden): crea los seriales y suma el stock. */
    @Transactional
    public List<SerialDto> add(Long productId, AddSerialsRequest r) {
        Long t = TenantContext.require();
        CurrentUser.require(Permission.INVENTARIO_AJUSTAR);
        ModulesService.check(settings.forTenant(t), "SERIALES");
        Product p = products.lockById(productId, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        if (!p.trackSerials) throw ApiException.badRequest(p.name + " no se controla por serial");
        List<String> list = normalize(r.serials());
        if (list.isEmpty()) throw ApiException.field("serials", "escribe al menos un serial");
        BigDecimal cost = r.unitCost() != null && CurrentUser.get().can(Permission.COSTOS_VER) ? r.unitCost() : p.cost;
        List<ProductSerial> created = create(p, list, cost, null);
        inventory.applyLocked(p, new InventoryService.Change(p.id, BigDecimal.valueOf(list.size()), InventoryService.ENTRADA_SERIAL,
                "SERIAL", null, r.reason().trim() + " (" + preview(list) + ")", cost, false));
        audit.sensitive("SERIALES_ENTRADA", "PRODUCT", p.id, "Registró " + list.size() + " seriales de " + p.name + ": " + preview(list)
                + ". Motivo: " + r.reason().trim());
        return created.stream().map(x -> toDto(x, p.name, null)).toList();
    }

    /** Recepción de compra: crea los seriales recibidos (el stock lo suma la recepción). */
    @Transactional
    public void receive(Product p, List<String> list, BigDecimal unitCost, Long receiptId, BigDecimal unitsReceived) {
        List<String> clean = normalize(list);
        if (unitsReceived.stripTrailingZeros().scale() > 0 || clean.size() != unitsReceived.intValue()) {
            throw ApiException.field("lines", p.name + ": registra un serial por cada unidad recibida (" + plain(unitsReceived)
                    + " unidades, " + clean.size() + " seriales)");
        }
        create(p, clean, unitCost, receiptId);
    }

    private List<ProductSerial> create(Product p, List<String> list, BigDecimal cost, Long receiptId) {
        List<ProductSerial> out = new ArrayList<>();
        for (String s : list) {
            serials.find(p.tenantId, p.id, s).ifPresent(x -> {
                throw ApiException.field("serials", "el serial " + s + " ya está registrado (" + label(x.status) + ")");
            });
            ProductSerial x = new ProductSerial();
            x.tenantId = p.tenantId;
            x.productId = p.id;
            x.serial = s;
            x.unitCost = cost == null ? BigDecimal.ZERO : cost.setScale(4, RoundingMode.HALF_UP);
            x.receiptId = receiptId;
            out.add(serials.save(x));
        }
        return out;
    }

    // ---------------- Ventas ----------------

    /**
     * Marca vendidos los seriales de una línea. Deben ser tantos como unidades y estar disponibles. Sin internet
     * la venta nunca se rechaza: lo que no cuadre deja la marca SERIAL_INVALIDO para revisión.
     */
    public void sell(Product p, Long saleId, Long saleLineId, BigDecimal units, List<String> list, Long customerId,
                     String customerName, Instant soldAt, LocalDate today, boolean offline, Set<String> flags) {
        List<String> clean = normalize(list);
        if (units.stripTrailingZeros().scale() > 0 || clean.size() != units.intValue()) {
            if (!offline) {
                throw ApiException.badRequest(p.name + ": escanea o escribe el serial de cada unidad (" + plain(units)
                        + " unidades, " + clean.size() + " seriales)");
            }
            flags.add("SERIAL_INVALIDO");
        }
        for (String s : clean) {
            ProductSerial x = serials.find(p.tenantId, p.id, s).orElse(null);
            if (x == null || !EN_STOCK.equals(x.status)) {
                if (!offline) {
                    throw ApiException.badRequest(x == null ? "El serial " + s + " no está registrado en " + p.name
                            : "El serial " + s + " no está disponible (" + label(x.status) + ")");
                }
                flags.add("SERIAL_INVALIDO");
                continue;
            }
            x.status = VENDIDO;
            x.saleId = saleId;
            x.saleLineId = saleLineId;
            x.customerId = customerId;
            x.customerName = customerName;
            x.soldAt = soldAt;
            x.warrantyUntil = p.warrantyMonths > 0 ? today.plusMonths(p.warrantyMonths) : null;
        }
    }

    /** Anulación: todo lo de la venta vuelve al stock. */
    public void voidSale(Long saleId) {
        for (ProductSerial x : serials.findBySaleIdOrderByIdAsc(saleId)) {
            if (!VENDIDO.equals(x.status)) continue;
            backToStock(x);
        }
    }

    /** Devolución: los seriales deben ser de esa línea; en buen estado vuelven al stock, si no quedan como defectuosos. */
    public void returned(Product p, Long saleLineId, BigDecimal units, List<String> list, boolean restock) {
        List<String> clean = normalize(list);
        if (units.stripTrailingZeros().scale() > 0 || clean.size() != units.intValue()) {
            throw ApiException.badRequest(p.name + ": indica el serial de cada unidad devuelta (" + plain(units) + " unidades, "
                    + clean.size() + " seriales)");
        }
        for (String s : clean) {
            ProductSerial x = serials.find(p.tenantId, p.id, s)
                    .filter(y -> saleLineId.equals(y.saleLineId) && VENDIDO.equals(y.status))
                    .orElseThrow(() -> ApiException.badRequest("El serial " + s + " no se vendió en esta venta"));
            if (restock) {
                backToStock(x);
            } else {
                x.status = DEFECTUOSO;
                x.notes = "Devuelto en mal estado";
            }
        }
    }

    private void backToStock(ProductSerial x) {
        x.status = EN_STOCK;
        x.saleId = null;
        x.saleLineId = null;
        x.customerId = null;
        x.customerName = null;
        x.soldAt = null;
        x.warrantyUntil = null;
    }

    // ---------------- Bajas y consultas ----------------

    @Transactional
    public SerialDto writeOff(Long id, WriteOffRequest r) {
        Long t = TenantContext.require();
        CurrentUser.require(Permission.INVENTARIO_AJUSTAR);
        ProductSerial x = serials.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Serial no encontrado"));
        if (!EN_STOCK.equals(x.status)) throw ApiException.badRequest("Solo se da de baja un serial que está en stock");
        Product p = products.lockById(x.productId, t).orElseThrow();
        x.status = DEFECTUOSO;
        x.notes = r.reason().trim();
        inventory.applyLocked(p, new InventoryService.Change(p.id, BigDecimal.ONE.negate(), InventoryService.BAJA_SERIAL, "SERIAL", x.id,
                "Baja del serial " + x.serial + ": " + x.notes, null, true));
        audit.sensitive("SERIAL_BAJA", "PRODUCT", p.id, "Dio de baja el serial " + x.serial + " de " + p.name + ". Motivo: " + x.notes);
        return toDto(x, p.name, null);
    }

    @Transactional(readOnly = true)
    public List<String> available(Long productId) {
        Product p = products.findByIdAndTenantId(productId, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        return serials.findByProductIdAndStatusOrderBySerialAsc(p.id, EN_STOCK).stream().map(x -> x.serial).toList();
    }

    /** Garantías: busca por serial o por cliente; o lista los de un producto. */
    @Transactional(readOnly = true)
    public List<SerialDto> search(String q, Long productId) {
        Long t = TenantContext.require();
        List<ProductSerial> list;
        if (productId != null) {
            list = serials.ofProduct(t, productId, org.springframework.data.domain.PageRequest.of(0, 500));
        } else {
            if (q == null || q.trim().length() < 3) throw ApiException.field("q", "escribe al menos 3 caracteres del serial o del cliente");
            list = serials.search(t, "%" + q.trim().toLowerCase() + "%", org.springframework.data.domain.PageRequest.of(0, 50));
        }
        Map<Long, String> names = new HashMap<>();
        products.findAllById(list.stream().map(x -> x.productId).distinct().toList()).forEach(p -> names.put(p.id, p.name));
        Map<Long, Long> numbers = saleNumbers(list.stream().map(x -> x.saleId).filter(Objects::nonNull).distinct().toList());
        return list.stream().map(x -> toDto(x, names.get(x.productId), x.saleId == null ? null : numbers.get(x.saleId))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> saleNumbers(List<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<Object[]> rows = em.createNativeQuery("select id, number from sales where id in (:ids)").setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put(((Number) r[0]).longValue(), r[1] == null ? null : ((Number) r[1]).longValue());
        return out;
    }

    private SerialDto toDto(ProductSerial x, String productName, Long saleNumber) {
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        LocalDate today = LocalDate.now();
        return new SerialDto(x.id, x.productId, productName, x.serial, x.status, costs ? x.unitCost : null, x.saleId, saleNumber,
                x.customerId, x.customerName, x.soldAt, x.warrantyUntil,
                x.warrantyUntil != null && !x.warrantyUntil.isBefore(today), x.notes, x.createdAt, x.updatedAt);
    }

    private static String label(String status) {
        return switch (status) {
            case VENDIDO -> "vendido";
            case DEFECTUOSO -> "defectuoso";
            default -> "en stock";
        };
    }

    private static String preview(List<String> list) {
        return list.size() <= 5 ? String.join(", ", list) : String.join(", ", list.subList(0, 5)) + " y " + (list.size() - 5) + " más";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
