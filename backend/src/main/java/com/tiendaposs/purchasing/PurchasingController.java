package com.tiendaposs.purchasing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.purchasing.CountService.*;
import com.tiendaposs.purchasing.InsightsService.*;
import com.tiendaposs.purchasing.PurchaseService.*;
import com.tiendaposs.purchasing.SupplierService.*;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Proveedores, compras, recepción, conteos físicos e inventario inteligente. */
@RestController
@RequestMapping("/api")
public class PurchasingController {

    private final SupplierService suppliers;
    private final PurchaseService purchases;
    private final CountService counts;
    private final InsightsService insights;
    private final InventoryService inventory;
    private final AuditService audit;

    public PurchasingController(SupplierService suppliers, PurchaseService purchases, CountService counts, InsightsService insights,
                                InventoryService inventory, AuditService audit) {
        this.suppliers = suppliers;
        this.purchases = purchases;
        this.counts = counts;
        this.insights = insights;
        this.inventory = inventory;
        this.audit = audit;
    }

    // ---------- Proveedores ----------

    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public List<SupplierDto> suppliers() {
        return suppliers.list();
    }

    @GetMapping("/suppliers/{id}")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public SupplierDto supplier(@PathVariable Long id) {
        return suppliers.get(id);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROVEEDORES_EDITAR')")
    public SupplierDto createSupplier(@Valid @RequestBody SupplierRequest r) {
        return suppliers.save(null, r);
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('PROVEEDORES_EDITAR')")
    public SupplierDto updateSupplier(@PathVariable Long id, @Valid @RequestBody SupplierRequest r) {
        return suppliers.save(id, r);
    }

    @GetMapping("/suppliers/{id}/catalog")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public List<CatalogItemDto> catalog(@PathVariable Long id) {
        return suppliers.catalog(id);
    }

    @PostMapping("/suppliers/{id}/catalog")
    @PreAuthorize("hasAuthority('PROVEEDORES_EDITAR')")
    public CatalogItemDto saveItem(@PathVariable Long id, @Valid @RequestBody CatalogItemRequest r) {
        return suppliers.saveItem(id, r);
    }

    @PostMapping("/supplier-products/{itemId}/primary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PROVEEDORES_EDITAR')")
    public void setPrimary(@PathVariable Long itemId) {
        suppliers.setPrimary(itemId);
    }

    @DeleteMapping("/supplier-products/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PROVEEDORES_EDITAR')")
    public void removeItem(@PathVariable Long itemId) {
        suppliers.removeItem(itemId);
    }

    /** Proveedores de un producto, del más barato al más caro. */
    @GetMapping("/products/{id}/suppliers")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER','PRODUCTOS_VER')")
    public List<CatalogItemDto> productSuppliers(@PathVariable Long id) {
        return suppliers.forProduct(id);
    }

    @GetMapping("/suppliers/{id}/price-history")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public List<SupplierService.PriceHistoryDto> supplierPrices(@PathVariable Long id) {
        return suppliers.priceHistory(id);
    }

    @GetMapping("/suppliers/{id}/orders")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public List<OrderDto> supplierOrders(@PathVariable Long id) {
        return purchases.list(null, id);
    }

    @GetMapping("/suppliers/{id}/returns")
    @PreAuthorize("hasAnyAuthority('PROVEEDORES_VER','COMPRAS_VER')")
    public List<SupplierService.ReturnDto> supplierReturns(@PathVariable Long id) {
        return suppliers.returns(id);
    }

    @PostMapping("/suppliers/{id}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public SupplierService.ReturnDto returnToSupplier(@PathVariable Long id, @Valid @RequestBody SupplierService.ReturnRequest r) {
        return suppliers.returnToSupplier(id, r);
    }

    // ---------- Órdenes de compra ----------

    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('COMPRAS_VER')")
    public List<OrderDto> orders(@RequestParam(required = false) String status, @RequestParam(required = false) Long supplierId) {
        return purchases.list(status, supplierId);
    }

    @GetMapping("/purchases/{id}")
    @PreAuthorize("hasAnyAuthority('COMPRAS_VER','COMPRAS_RECIBIR')")
    public OrderDto order(@PathVariable Long id) {
        return purchases.get(id);
    }

    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPRAS_CREAR')")
    public OrderDto create(@Valid @RequestBody OrderRequest r) {
        return purchases.create(r);
    }

    @PutMapping("/purchases/{id}")
    @PreAuthorize("hasAnyAuthority('COMPRAS_CREAR','COMPRAS_APROBAR')")
    public OrderDto update(@PathVariable Long id, @Valid @RequestBody OrderRequest r) {
        return purchases.update(id, r);
    }

    @PostMapping("/purchases/{id}/submit")
    @PreAuthorize("hasAuthority('COMPRAS_CREAR')")
    public OrderDto submit(@PathVariable Long id) {
        return purchases.submit(id);
    }

    @PostMapping("/purchases/{id}/approve")
    @PreAuthorize("hasAuthority('COMPRAS_APROBAR')")
    public OrderDto approve(@PathVariable Long id) {
        return purchases.approve(id);
    }

    public record CancelRequest(String reason) {
    }

    @PostMapping("/purchases/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('COMPRAS_CREAR','COMPRAS_APROBAR')")
    public OrderDto cancel(@PathVariable Long id, @RequestBody CancelRequest r) {
        return purchases.cancel(id, r.reason());
    }

    public record SendRequest(String via) {
    }

    @PostMapping("/purchases/{id}/send")
    @PreAuthorize("hasAnyAuthority('COMPRAS_CREAR','COMPRAS_APROBAR')")
    public SendResult send(@PathVariable Long id, @RequestBody SendRequest r) {
        return purchases.send(id, r.via() == null ? "MANUAL" : r.via().toUpperCase());
    }

    @PostMapping("/purchases/{id}/receive")
    @PreAuthorize("hasAuthority('COMPRAS_RECIBIR')")
    public OrderDto receive(@PathVariable Long id, @Valid @RequestBody ReceiveRequest r) {
        return purchases.receive(id, r);
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPRAS_RECIBIR')")
    public ReceiptSummary receiveDirect(@Valid @RequestBody ReceiveRequest r) {
        return purchases.receiveDirect(r);
    }

    @GetMapping("/purchases/late")
    @PreAuthorize("hasAnyAuthority('COMPRAS_VER','ALERTAS_VER')")
    public List<OrderDto> late() {
        return purchases.late();
    }

    @GetMapping("/purchases/suggestions")
    @PreAuthorize("hasAnyAuthority('COMPRAS_VER','COMPRAS_CREAR')")
    public Suggestions suggestions() {
        return insights.suggestions();
    }

    // ---------- Conteos físicos ----------

    @GetMapping("/counts")
    @PreAuthorize("hasAnyAuthority('INVENTARIO_CONTEO','INVENTARIO_VER')")
    public List<CountDto> counts() {
        return counts.list();
    }

    @GetMapping("/counts/{id}")
    @PreAuthorize("hasAnyAuthority('INVENTARIO_CONTEO','INVENTARIO_VER')")
    public CountDto count(@PathVariable Long id) {
        return counts.get(id);
    }

    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVENTARIO_CONTEO')")
    public CountDto createCount(@Valid @RequestBody CreateRequest r) {
        return counts.create(r);
    }

    @PostMapping("/counts/{id}/entries")
    @PreAuthorize("hasAuthority('INVENTARIO_CONTEO')")
    public CountLineDto enter(@PathVariable Long id, @Valid @RequestBody EntryRequest r) {
        return counts.enter(id, r);
    }

    @PostMapping("/counts/{id}/close")
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public CountDto closeCount(@PathVariable Long id) {
        return counts.close(id);
    }

    @PostMapping("/counts/{id}/cancel")
    @PreAuthorize("hasAuthority('INVENTARIO_CONTEO')")
    public CountDto cancelCount(@PathVariable Long id) {
        return counts.cancel(id);
    }

    // ---------- Inventario inteligente ----------

    @GetMapping("/inventory/insights")
    @PreAuthorize("hasAuthority('INVENTARIO_VER')")
    public Insights insights() {
        return insights.insights();
    }

    @GetMapping("/inventory/alerts")
    @PreAuthorize("hasAnyAuthority('INVENTARIO_VER','ALERTAS_VER','COMPRAS_VER')")
    public List<Alert> alerts() {
        return insights.alerts();
    }

    @GetMapping("/inventory/expiring")
    @PreAuthorize("hasAuthority('INVENTARIO_VER')")
    public List<LotDto> expiring(@RequestParam(required = false) Integer days) {
        return insights.expiring(days);
    }

    @GetMapping("/products/{id}/lots")
    @PreAuthorize("hasAuthority('INVENTARIO_VER')")
    public List<LotDto> lots(@PathVariable Long id) {
        return insights.lotsOf(id);
    }

    public record WriteOffRequest(@NotBlank(message = "explica el motivo") String reason) {
    }

    /** Da de baja un lote vencido o dañado (sale del inventario). */
    @PostMapping("/lots/{id}/write-off")
    @Transactional
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public void writeOff(@PathVariable Long id, @Valid @RequestBody WriteOffRequest r) {
        var m = inventory.writeOffLot(TenantContext.require(), id, r.reason().trim());
        audit.sensitive("LOTE_BAJA", "PRODUCT", m.productId, "Dio de baja " + m.quantity.negate().stripTrailingZeros().toPlainString()
                + " unidades de un lote. Motivo: " + r.reason().trim());
    }

    @GetMapping("/inventory/adjustments-by-user")
    @PreAuthorize("hasAnyAuthority('INVENTARIO_VER','AUDITORIA_VER')")
    public List<AdjustmentByUser> adjustmentsByUser(@RequestParam(required = false) Integer days) {
        if (days != null && (days < 1 || days > 365)) throw ApiException.badRequest("Rango de días no válido");
        return insights.adjustmentsByUser(days);
    }
}
