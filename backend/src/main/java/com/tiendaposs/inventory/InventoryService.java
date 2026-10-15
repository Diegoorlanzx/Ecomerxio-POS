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

    /** Tipos de movimiento. Los de entrada suman, los de salida restan. */
    public static final String VENTA = "VENTA", ANULACION = "ANULACION", DEVOLUCION = "DEVOLUCION",
            AJUSTE_ENTRADA = "AJUSTE_ENTRADA", AJUSTE_SALIDA = "AJUSTE_SALIDA", INICIAL = "INICIAL",
            IMPORTACION = "IMPORTACION", CORRECCION = "CORRECCION", COMPRA = "COMPRA", DANADO = "DANADO";

    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final PriceHistoryRepository priceHistory;
    private final SettingsService settings;

    public InventoryService(ProductRepository products, StockMovementRepository movements,
                            PriceHistoryRepository priceHistory, SettingsService settings) {
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
        BigDecimal qty = c.quantity().setScale(3, RoundingMode.HALF_UP);
        if (qty.signum() == 0) throw ApiException.badRequest("La cantidad no puede ser cero");
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
