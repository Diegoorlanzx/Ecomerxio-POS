package com.tiendaposs.inventory;

import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.PriceHistory;
import com.tiendaposs.catalog.CatalogRepos.PriceHistoryRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.settings.SettingsService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * Único punto que cambia existencias. Cada cambio deja un movimiento con el stock resultante,
 * bloquea la fila del producto (dos cajas vendiendo lo mismo no se pisan) y, en las entradas
 * con costo, recalcula el costo según el método de la tienda (promedio ponderado o último).
 */
@Service
public class InventoryService {

    public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
        @Query("""
                select m from StockMovement m where m.tenantId = :tenantId
                  and (:productId is null or m.productId = :productId)
                  and (:type is null or m.type = :type)
                  and (:userId is null or m.userId = :userId)
                order by m.createdAt desc, m.id desc""")
        Page<StockMovement> search(Long tenantId, Long productId, String type, Long userId, Pageable pageable);
    }

    public interface ProductLotRepository extends JpaRepository<ProductLot, Long> {
        /** Primero los que vencen antes; los sin fecha al final (FEFO). */
        @Query("select l from ProductLot l where l.productId = ?1 and l.remaining > 0 order by case when l.expiryDate is null then 1 else 0 end, l.expiryDate, l.id")
        java.util.List<ProductLot> available(Long productId);

        @Query("select l from ProductLot l where l.tenantId = ?1 and l.remaining > 0 and l.expiryDate is not null and l.expiryDate <= ?2 order by l.expiryDate")
        java.util.List<ProductLot> expiringBefore(Long tenantId, java.time.LocalDate date);

        java.util.Optional<ProductLot> findByIdAndTenantId(Long id, Long tenantId);
    }

    /** Lote a registrar en una entrada (código y vencimiento opcionales). */
    public record LotInfo(String lotCode, java.time.LocalDate expiryDate, Long receiptId) {
    }

    /** Tipos de movimiento. Los de entrada suman, los de salida restan. */
    public static final String VENTA = "VENTA", ANULACION = "ANULACION", DEVOLUCION = "DEVOLUCION",
            AJUSTE_ENTRADA = "AJUSTE_ENTRADA", AJUSTE_SALIDA = "AJUSTE_SALIDA", INICIAL = "INICIAL",
            IMPORTACION = "IMPORTACION", CORRECCION = "CORRECCION", COMPRA = "COMPRA", DANADO = "DANADO",
            CONTEO = "CONTEO", DEVOLUCION_PROVEEDOR = "DEVOLUCION_PROVEEDOR",
            ENTRADA_SERIAL = "ENTRADA_SERIAL", BAJA_SERIAL = "BAJA_SERIAL";

    /** En productos con serial el stock solo cambia junto con sus seriales (entradas, ventas, devoluciones, bajas). */
    private static final java.util.Set<String> SERIAL_TYPES = java.util.Set.of(VENTA, ANULACION, DEVOLUCION, COMPRA, ENTRADA_SERIAL, BAJA_SERIAL);

    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final PriceHistoryRepository priceHistory;
    private final SettingsService settings;
    private final ProductLotRepository lots;

    public InventoryService(ProductRepository products, StockMovementRepository movements,
                            PriceHistoryRepository priceHistory, SettingsService settings, ProductLotRepository lots) {
        this.lots = lots;
        this.products = products;
        this.movements = movements;
        this.priceHistory = priceHistory;
        this.settings = settings;
    }

    public record Change(Long productId, BigDecimal quantity, String type, String referenceType, Long referenceId,
                         String reason, BigDecimal unitCost, boolean allowNegative) {
    }

    /** Aplica un cambio de existencias (cantidad con signo, en unidades base). Debe correr dentro de una transacción. */
    @Transactional
    public StockMovement apply(Long tenantId, Change c) {
        Product p = products.lockById(c.productId(), tenantId)
                .orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        return applyLocked(p, c);
    }

    @Transactional
    public StockMovement applyLocked(Product p, Change c) {
        return applyLocked(p, c, null);
    }

    /**
     * Igual que {@link #applyLocked(Product, Change)} y además, en productos con lotes: una entrada
     * crea un lote (con su código y vencimiento si vienen) y una salida consume primero los lotes que
     * vencen antes. Una salida de un lote específico (dar de baja un vencido) se pide con {@code lotId}.
     */
    @Transactional
    public StockMovement applyLocked(Product p, Change c, LotInfo lot) {
        StockMovement m = applyStock(p, c);
        if (p.trackLots) {
            if (m.quantity.signum() > 0) {
                ProductLot l = new ProductLot();
                l.tenantId = p.tenantId;
                l.productId = p.id;
                l.lotCode = lot == null ? null : blankToNull(lot.lotCode());
                l.expiryDate = lot == null ? null : lot.expiryDate();
                l.receiptId = lot == null ? null : lot.receiptId();
                l.quantity = m.quantity;
                l.remaining = m.quantity;
                lots.save(l);
            } else {
                consumeFefo(p.id, m.quantity.negate());
            }
        }
        return m;
    }

    /** Al activar lotes en un producto con existencias, lo que hay queda como un lote sin fecha. */
    @Transactional
    public void initLots(Product p) {
        if (p.stock.signum() <= 0 || !lots.available(p.id).isEmpty()) return;
        ProductLot l = new ProductLot();
        l.tenantId = p.tenantId;
        l.productId = p.id;
        l.quantity = p.stock;
        l.remaining = p.stock;
        lots.save(l);
    }

    /** Da de baja todo lo que queda de un lote (vencido o dañado). */
    @Transactional
    public StockMovement writeOffLot(Long tenantId, Long lotId, String reason) {
        ProductLot l = lots.findByIdAndTenantId(lotId, tenantId).orElseThrow(() -> ApiException.notFound("Lote no encontrado"));
        if (l.remaining.signum() <= 0) throw ApiException.badRequest("Ese lote ya no tiene existencias");
        Product p = products.lockById(l.productId, tenantId).orElseThrow();
        BigDecimal qty = l.remaining;
        l.remaining = BigDecimal.ZERO;
        return applyStock(p, new Change(p.id, qty.negate(), DANADO, "LOTE", l.id, reason, null, true));
    }

    private void consumeFefo(Long productId, BigDecimal qty) {
        BigDecimal left = qty;
        for (ProductLot l : lots.available(productId)) {
            if (left.signum() <= 0) break;
            BigDecimal take = l.remaining.min(left);
            l.remaining = l.remaining.subtract(take);
            left = left.subtract(take);
        }
    }

    public java.util.List<ProductLot> lotsOf(Long productId) {
        return lots.available(productId);
    }

    public java.util.List<ProductLot> expiringBefore(Long tenantId, java.time.LocalDate date) {
        return lots.expiringBefore(tenantId, date);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private StockMovement applyStock(Product p, Change c) {
        BigDecimal qty = c.quantity().setScale(3, RoundingMode.HALF_UP);
        if (qty.signum() == 0) throw ApiException.badRequest("La cantidad no puede ser cero");
        if (p.trackSerials && !SERIAL_TYPES.contains(c.type())) {
            throw ApiException.badRequest(p.name + " se controla por serial: registra la entrada o la baja de cada serial");
        }
        if ("PADRE".equals(p.productType) || "COMBO".equals(p.productType)) {
            throw ApiException.badRequest(p.name + (("PADRE".equals(p.productType)) ? " agrupa variantes: el stock va en cada variante"
                    : " es un combo: su stock es el de sus componentes"));
        }
        BigDecimal newStock = p.stock.add(qty);
        if (p.trackStock && qty.signum() < 0 && newStock.signum() < 0 && !c.allowNegative()
                && !settings.forTenant(p.tenantId).allowNegativeStock) {
            throw new ApiException(HttpStatus.CONFLICT, "No hay suficiente stock de " + p.name,
                    Map.of("producto", p.name, "disponible", p.stock.stripTrailingZeros().toPlainString(),
                            "solicitado", qty.negate().stripTrailingZeros().toPlainString()));
        }
        if (qty.signum() > 0 && c.unitCost() != null && c.unitCost().signum() >= 0) {
            updateCost(p, qty, c.unitCost());
        }
        if (p.trackStock) p.stock = newStock;
        AuthUser u = CurrentUser.getOrNull();
        StockMovement m = new StockMovement();
        m.tenantId = p.tenantId;
        m.productId = p.id;
        m.type = c.type();
        m.quantity = qty;
        m.stockAfter = p.stock;
        m.unitCost = c.unitCost() != null ? c.unitCost() : p.cost;
        m.referenceType = c.referenceType();
        m.referenceId = c.referenceId();
        m.reason = c.reason();
        m.userId = u == null ? null : u.userId();
        m.userName = u == null ? null : u.fullName();
        return movements.save(m);
    }

    private void updateCost(Product p, BigDecimal qty, BigDecimal unitCost) {
        BigDecimal old = p.cost;
        String method = settings.forTenant(p.tenantId).costMethod;
        if ("ULTIMO".equals(method) || p.stock.signum() <= 0) {
            p.cost = unitCost.setScale(4, RoundingMode.HALF_UP);
        } else {
            BigDecimal value = p.stock.multiply(p.cost).add(qty.multiply(unitCost));
            p.cost = value.divide(p.stock.add(qty), 4, RoundingMode.HALF_UP);
        }
        p.lastCost = unitCost.setScale(4, RoundingMode.HALF_UP);
        if (old.compareTo(p.cost) != 0) {
            AuthUser u = CurrentUser.getOrNull();
            PriceHistory h = new PriceHistory();
            h.tenantId = p.tenantId;
            h.productId = p.id;
            h.field = "COSTO";
            h.oldValue = old;
            h.newValue = p.cost;
            h.reason = "Entrada de mercancía (" + method.toLowerCase() + ")";
            h.userId = u == null ? null : u.userId();
            h.userName = u == null ? null : u.fullName();
            h.createdAt = Instant.now();
            priceHistory.save(h);
        }
    }

    public Page<StockMovement> search(Long tenantId, Long productId, String type, Long userId, Pageable pageable) {
        return movements.search(tenantId, productId, type, userId, pageable);
    }
}
