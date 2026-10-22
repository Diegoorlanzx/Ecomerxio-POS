package com.tiendaposs.purchasing;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.MailService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.purchasing.PurchasingRepos.*;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Órdenes de compra (borrador → por aprobar → aprobada/enviada → parcial → recibida) y recepción de
 * mercancía con o sin orden. Recibir suma inventario al costo real, recalcula el costo promedio,
 * actualiza el precio del proveedor y deja el total como cuenta por pagar.
 */
@Service
public class PurchaseService {

    public static final Set<String> OPEN = Set.of("APROBADA", "PARCIAL");

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository receiptLines;
    private final SupplierProductRepository supplierProducts;
    private final SupplierService suppliers;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final TenantRepository tenants;
    private final MailService mail;
    private final Counters counters;
    private final AuditService audit;

    private final com.tiendaposs.serials.SerialService serialService;
    private final com.tiendaposs.einvoice.EInvoiceService einvoice;

    /** Fase 14: comprobante contable de cada operación. */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.tiendaposs.accounting.AccountingService accounting;

    public PurchaseService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines, GoodsReceiptRepository receipts,
                           GoodsReceiptLineRepository receiptLines, SupplierProductRepository supplierProducts,
                           SupplierService suppliers, ProductRepository products, InventoryService inventory,
                           TenantRepository tenants, MailService mail, Counters counters, AuditService audit,
                           com.tiendaposs.serials.SerialService serialService, com.tiendaposs.einvoice.EInvoiceService einvoice) {
        this.serialService = serialService;
        this.einvoice = einvoice;
        this.orders = orders;
        this.lines = lines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.supplierProducts = supplierProducts;
        this.suppliers = suppliers;
        this.products = products;
        this.inventory = inventory;
        this.tenants = tenants;
        this.mail = mail;
        this.counters = counters;
        this.audit = audit;
    }

    // ---------------- DTOs ----------------

    public record LineRequest(@NotNull Long productId, @Size(max = 60) String packName,
                              @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal packFactor,
                              @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal packs,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal costPerPack,
                              @Size(max = 300) String reason) {
    }

    public record OrderRequest(@NotNull Long supplierId, @NotNull @Size(min = 1, message = "agrega al menos un producto") List<@Valid LineRequest> lines,
                               LocalDate expectedDate, @Size(max = 500) String notes, String source) {
    }

    public record LineDto(Long id, Long productId, String productName, String sku, String packName, BigDecimal packFactor,
                          BigDecimal packsOrdered, BigDecimal unitsOrdered, BigDecimal costPerPack, BigDecimal subtotal,
                          BigDecimal unitsReceived, BigDecimal unitsRejected, BigDecimal unitsPending, String reason,
                          BigDecimal currentStock) {
    }

    public record ReceiptSummary(Long id, long number, String invoiceNumber, BigDecimal total, Boolean onTime,
                                 String receivedBy, Instant receivedAt) {
    }

    public record OrderDto(Long id, long number, Long supplierId, String supplierName, String status, String source,
                           LocalDate expectedDate, boolean late, String notes, BigDecimal estimatedTotal, BigDecimal receivedTotal,
                           String createdBy, Instant createdAt, String approvedBy, Instant approvedAt, Instant sentAt, String sentVia,
                           Instant receivedAt, String cancelReason, List<LineDto> lines, List<ReceiptSummary> receipts) {
    }

    public record ReceiveLine(Long orderLineId, Long productId,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitsReceived,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitsRejected,
                              @Pattern(regexp = "FALTANTE|INCORRECTO|DANADO", message = "motivo no válido") String rejectReason,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitCost,
                              @Size(max = 60) String lotCode, LocalDate expiryDate,
                              /** Uno por unidad recibida en productos con serial. */ List<@Size(max = 80) String> serials) {
    }

    public record ReceiveRequest(@NotNull @Size(min = 1, message = "registra al menos un producto") List<@Valid ReceiveLine> lines,
                                 @Size(max = 60) String invoiceNumber, @Size(max = 500) String notes,
                                 /** Solo para recepción directa (sin orden). */ Long supplierId) {
    }

    public record SendResult(String message, String whatsappUrl, String mailtoUrl, boolean emailed) {
    }

    // ---------------- Órdenes ----------------

    @Transactional(readOnly = true)
    public List<OrderDto> list(String status, Long supplierId) {
        Long t = TenantContext.require();
        List<PurchaseOrder> list = orders.search(t, status == null || status.isBlank() ? null : status, supplierId);
        return list.stream().map(o -> toDto(o, false)).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        return toDto(find(id), true);
    }

    @Transactional
    public OrderDto create(OrderRequest r) {
        Long t = TenantContext.require();
        Supplier s = suppliers.find(r.supplierId());
        if (!s.active) throw ApiException.badRequest("El proveedor está inactivo");
        PurchaseOrder o = new PurchaseOrder();
        o.tenantId = t;
        o.number = counters.next(t, "COMPRA");
        o.supplierId = s.id;
        o.source = "SUGERIDA".equals(r.source()) ? "SUGERIDA" : "MANUAL";
        o.expectedDate = r.expectedDate();
        o.notes = blank(r.notes());
        o.createdBy = CurrentUser.get().fullName();
        o = orders.save(o);
        replaceLines(o, r.lines());
        audit.log("COMPRA_CREADA", "PURCHASE", o.id, "Creó la orden de compra #" + o.number + " para " + s.name
                + " por " + Money.fmt(o.estimatedTotal) + ("SUGERIDA".equals(o.source) ? " (desde sugerencias)" : ""));
        return toDto(o, true);
    }

    /** Editar: en borrador o por aprobar (el dueño puede ajustar cantidades antes de aprobar). */
    @Transactional
    public OrderDto update(Long id, OrderRequest r) {
        PurchaseOrder o = find(id);
        if (!o.status.equals("BORRADOR") && !o.status.equals("POR_APROBAR")) {
            throw ApiException.badRequest("Solo se pueden editar órdenes en borrador o por aprobar");
        }
        if (o.status.equals("POR_APROBAR")) CurrentUser.require(Permission.COMPRAS_APROBAR);
        if (!o.supplierId.equals(r.supplierId())) throw ApiException.badRequest("No se puede cambiar el proveedor de una orden");
        o.expectedDate = r.expectedDate();
        o.notes = blank(r.notes());
        replaceLines(o, r.lines());
        audit.log("COMPRA_EDITADA", "PURCHASE", o.id, "Editó la orden de compra #" + o.number + " (" + Money.fmt(o.estimatedTotal) + ")");
        return toDto(o, true);
    }

    private void replaceLines(PurchaseOrder o, List<LineRequest> reqs) {
        Long t = o.tenantId;
        lines.deleteByOrderId(o.id);
        lines.flush();
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LineRequest r : reqs) {
            if (!seen.add(r.productId())) throw ApiException.field("lines", "un producto está repetido en la orden");
            Product p = products.findByIdAndTenantId(r.productId(), t).orElseThrow(() -> ApiException.field("lines", "un producto no existe"));
            SupplierProduct sp = supplierProducts.findBySupplierIdAndProductId(o.supplierId, p.id).orElse(null);
            PurchaseOrderLine l = new PurchaseOrderLine();
            l.orderId = o.id;
            l.productId = p.id;
            l.packName = blank(r.packName()) != null ? r.packName().trim() : sp != null ? sp.packName : "Unidad";
            l.packFactor = r.packFactor() != null ? r.packFactor() : sp != null ? sp.packFactor : BigDecimal.ONE;
            l.packsOrdered = r.packs().setScale(3, RoundingMode.HALF_UP);
            l.costPerPack = r.costPerPack() != null ? r.costPerPack() : sp != null ? sp.costPerPack : p.lastCost.multiply(l.packFactor);
            l.costPerPack = l.costPerPack.setScale(2, RoundingMode.HALF_UP);
            l.reason = blank(r.reason());
            lines.save(l);
            total = total.add(l.costPerPack.multiply(l.packsOrdered));
        }
        o.estimatedTotal = total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Pasa el borrador a aprobación; si quien envía puede aprobar, queda aprobada de una vez. */
    @Transactional
    public OrderDto submit(Long id) {
        PurchaseOrder o = find(id);
        if (!o.status.equals("BORRADOR")) throw ApiException.badRequest("La orden no está en borrador");
        o.submittedAt = Instant.now();
        if (CurrentUser.get().can(Permission.COMPRAS_APROBAR)) {
            approveInternal(o);
        } else {
            o.status = "POR_APROBAR";
            audit.log("COMPRA_POR_APROBAR", "PURCHASE", o.id, "Envió a aprobación la orden #" + o.number + " por " + Money.fmt(o.estimatedTotal));
        }
        return toDto(o, true);
    }

    @Transactional
    public OrderDto approve(Long id) {
        CurrentUser.require(Permission.COMPRAS_APROBAR);
        PurchaseOrder o = find(id);
        if (!o.status.equals("POR_APROBAR") && !o.status.equals("BORRADOR")) throw ApiException.badRequest("La orden no está pendiente de aprobación");
        approveInternal(o);
        return toDto(o, true);
    }

    private void approveInternal(PurchaseOrder o) {
        if (lines.findByOrderIdOrderByIdAsc(o.id).isEmpty()) throw ApiException.badRequest("La orden no tiene productos");
        o.status = "APROBADA";
        o.approvedBy = CurrentUser.get().fullName();
        o.approvedAt = Instant.now();
        if (o.expectedDate == null) {
            Supplier s = suppliers.find(o.supplierId);
            BigDecimal lead = suppliers.effectiveLead(s, suppliers.metrics(s).avgLeadDays());
            o.expectedDate = LocalDate.now(zone(o.tenantId)).plusDays(lead.setScale(0, RoundingMode.UP).longValue());
        }
        audit.sensitive("COMPRA_APROBADA", "PURCHASE", o.id, "Aprobó la orden de compra #" + o.number + " por " + Money.fmt(o.estimatedTotal));
    }

    @Transactional
    public OrderDto cancel(Long id, String reason) {
        PurchaseOrder o = find(id);
        if (o.status.equals("RECIBIDA") || o.status.equals("CANCELADA")) throw ApiException.badRequest("La orden ya está cerrada");
        if (o.status.equals("PARCIAL")) CurrentUser.require(Permission.COMPRAS_APROBAR);
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "explica por qué se cancela");
        o.status = "CANCELADA";
        o.cancelledAt = Instant.now();
        o.cancelReason = reason.trim();
        audit.sensitive("COMPRA_CANCELADA", "PURCHASE", o.id, "Canceló la orden #" + o.number + ". Motivo: " + o.cancelReason);
        return toDto(o, true);
    }

    /** Texto del pedido para enviar por WhatsApp o correo (y lo envía por correo si hay servidor de correo). */
    @Transactional
    public SendResult send(Long id, String via) {
        PurchaseOrder o = find(id);
        if (!OPEN.contains(o.status)) throw ApiException.badRequest("Aprueba la orden antes de enviarla");
        Supplier s = suppliers.find(o.supplierId);
        Tenant t = tenants.findById(o.tenantId).orElseThrow();
        StringBuilder sb = new StringBuilder();
        sb.append("Hola").append(s.contactName != null ? " " + s.contactName : "").append(", le escribimos de ").append(t.getName())
                .append(". Pedido #").append(o.number).append(":\n");
        Map<Long, Product> ps = productsOf(lines.findByOrderIdOrderByIdAsc(o.id));
        for (PurchaseOrderLine l : lines.findByOrderIdOrderByIdAsc(o.id)) {
            Product p = ps.get(l.productId);
            sb.append("• ").append(plain(l.packsOrdered)).append(" ").append(l.packName).append(" — ").append(p == null ? "" : p.name).append("\n");
        }
        if (o.expectedDate != null) sb.append("Lo necesitamos para el ").append(o.expectedDate).append(".\n");
        if (t.getAddress() != null) sb.append("Entrega: ").append(t.getAddress()).append(t.getCity() != null ? ", " + t.getCity() : "").append(".\n");
        sb.append("Gracias.");
        String msg = sb.toString();
        String wa = s.whatsapp == null ? null : "https://wa.me/" + phone(s.whatsapp) + "?text=" + URLEncoder.encode(msg, StandardCharsets.UTF_8);
        String mailto = s.email == null ? null : "mailto:" + s.email + "?subject=" + URLEncoder.encode("Pedido #" + o.number + " - " + t.getName(), StandardCharsets.UTF_8).replace("+", "%20")
                + "&body=" + URLEncoder.encode(msg, StandardCharsets.UTF_8).replace("+", "%20");
        boolean emailed = false;
        if ("EMAIL".equals(via)) {
            if (s.email == null) throw ApiException.badRequest("El proveedor no tiene correo registrado");
            mail.send(s.email, "Pedido #" + o.number + " - " + t.getName(), msg);
            emailed = true;
        }
        if ("WHATSAPP".equals(via) && s.whatsapp == null) throw ApiException.badRequest("El proveedor no tiene WhatsApp registrado");
        o.sentAt = Instant.now();
        o.sentVia = via == null ? "MANUAL" : via;
        audit.log("COMPRA_ENVIADA", "PURCHASE", o.id, "Envió la orden #" + o.number + " a " + s.name + " por " + o.sentVia.toLowerCase());
        return new SendResult(msg, wa, mailto, emailed);
    }

    // ---------------- Recepción ----------------

    /** Recibe mercancía de una orden (parcial o total), con faltantes/incorrectos, costo real, lote y vencimiento. */
    @Transactional
    public OrderDto receive(Long orderId, ReceiveRequest r) {
        CurrentUser.require(Permission.COMPRAS_RECIBIR);
        PurchaseOrder o = find(orderId);
        accounting.onGoodsReceipt(receiveOrder(o, r));
        return toDto(o, true);
    }

    /** La recepción de una orden; también la usa la importación de facturas de proveedores. */
    GoodsReceipt receiveOrder(PurchaseOrder o, ReceiveRequest r) {
        if (!OPEN.contains(o.status)) throw ApiException.badRequest("Solo se reciben órdenes aprobadas o parciales");
        Map<Long, PurchaseOrderLine> byId = lines.findByOrderIdOrderByIdAsc(o.id).stream().collect(Collectors.toMap(l -> l.id, Function.identity()));
        GoodsReceipt rec = newReceipt(o.tenantId, o.supplierId, o.id, r);
        LocalDate today = LocalDate.now(zone(o.tenantId));
        rec.onTime = o.expectedDate == null ? null : !today.isAfter(o.expectedDate);
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiveLine rl : r.lines()) {
            PurchaseOrderLine l = byId.get(rl.orderLineId());
            if (l == null) throw ApiException.field("lines", "una línea no pertenece a la orden");
            BigDecimal got = nz(rl.unitsReceived());
            BigDecimal bad = nz(rl.unitsRejected());
            if (got.signum() == 0 && bad.signum() == 0) continue;
            BigDecimal unitCost = rl.unitCost() != null ? rl.unitCost() : l.costPerPack.divide(l.packFactor, 4, RoundingMode.HALF_UP);
            total = total.add(receiveLine(rec, l.id, l.productId, got, bad, rl.rejectReason(), unitCost, rl.lotCode(), rl.expiryDate(), o.supplierId, rl.serials()));
            l.unitsReceived = l.unitsReceived.add(got);
            l.unitsRejected = l.unitsRejected.add(bad);
        }
        if (total.signum() == 0 && r.lines().stream().allMatch(x -> nz(x.unitsReceived()).signum() == 0)) {
            if (r.lines().stream().allMatch(x -> nz(x.unitsRejected()).signum() == 0)) throw ApiException.badRequest("No registraste ninguna cantidad");
        }
        rec.total = total.setScale(2, RoundingMode.HALF_UP);
        receipts.save(rec);
        o.receivedTotal = o.receivedTotal.add(rec.total);
        boolean complete = byId.values().stream().allMatch(l -> l.unitsReceived.add(l.unitsRejected).compareTo(l.unitsOrdered()) >= 0);
        o.status = complete ? "RECIBIDA" : "PARCIAL";
        if (complete) o.receivedAt = Instant.now();
        BigDecimal rejected = byId.values().stream().map(l -> l.unitsRejected).reduce(BigDecimal.ZERO, BigDecimal::add);
        audit.log("MERCANCIA_RECIBIDA", "PURCHASE", o.id, "Recibió mercancía de la orden #" + o.number + " por " + Money.fmt(rec.total)
                + (complete ? " (completa)" : " (parcial)") + (rejected.signum() > 0 ? ", con " + plain(rejected) + " unidades faltantes o rechazadas" : ""));
        einvoice.onReceipt(rec, suppliers.find(o.supplierId));
        return rec;
    }

    /** Recepción directa sin orden (el proveedor llegó con mercancía). Las cantidades van en unidades base. */
    @Transactional
    public ReceiptSummary receiveDirect(ReceiveRequest r) {
        CurrentUser.require(Permission.COMPRAS_RECIBIR);
        GoodsReceipt rec = receiveWithoutOrder(r);
        accounting.onGoodsReceipt(rec);
        return new ReceiptSummary(rec.id, rec.number, rec.invoiceNumber, rec.total, rec.onTime, rec.receivedBy, rec.receivedAt);
    }

    GoodsReceipt receiveWithoutOrder(ReceiveRequest r) {
        if (r.supplierId() == null) throw ApiException.field("supplierId", "elige el proveedor");
        Supplier s = suppliers.find(r.supplierId());
        GoodsReceipt rec = newReceipt(s.tenantId, s.id, null, r);
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiveLine rl : r.lines()) {
            if (rl.productId() == null) throw ApiException.field("lines", "elige el producto");
            BigDecimal got = nz(rl.unitsReceived());
            if (got.signum() <= 0) continue;
            Product p = products.findByIdAndTenantId(rl.productId(), s.tenantId).orElseThrow(() -> ApiException.field("lines", "un producto no existe"));
            BigDecimal unitCost = rl.unitCost() != null ? rl.unitCost()
                    : supplierProducts.findBySupplierIdAndProductId(s.id, p.id).map(SupplierProduct::unitCost).orElse(p.lastCost);
            total = total.add(receiveLine(rec, null, p.id, got, nz(rl.unitsRejected()), rl.rejectReason(), unitCost, rl.lotCode(), rl.expiryDate(), s.id, rl.serials()));
        }
        if (total.signum() == 0) throw ApiException.badRequest("No registraste ninguna cantidad");
        rec.total = total.setScale(2, RoundingMode.HALF_UP);
        receipts.save(rec);
        audit.log("MERCANCIA_RECIBIDA", "RECEIPT", rec.id, "Recibió mercancía de " + s.name + " sin orden por " + Money.fmt(rec.total));
        einvoice.onReceipt(rec, s);
        return rec;
    }

    private GoodsReceipt newReceipt(Long tenantId, Long supplierId, Long orderId, ReceiveRequest r) {
        Supplier s = suppliers.find(supplierId);
        GoodsReceipt rec = new GoodsReceipt();
        rec.tenantId = tenantId;
        rec.number = counters.next(tenantId, "RECEPCION");
        rec.supplierId = supplierId;
        rec.orderId = orderId;
        rec.invoiceNumber = blank(r.invoiceNumber());
        rec.notes = blank(r.notes());
        rec.receivedBy = CurrentUser.get().fullName();
        rec.dueDate = LocalDate.now(zone(tenantId)).plusDays(s.creditDays);
        return receipts.save(rec);
    }

    /** Suma al inventario lo bueno al costo real y actualiza el costo del proveedor. Devuelve el valor recibido. */
    private BigDecimal receiveLine(GoodsReceipt rec, Long orderLineId, Long productId, BigDecimal got, BigDecimal bad,
                                   String rejectReason, BigDecimal unitCost, String lotCode, LocalDate expiry, Long supplierId,
                                   List<String> serials) {
        if (bad.signum() > 0 && rejectReason == null) throw ApiException.field("lines", "indica por qué no se recibieron unidades");
        if (expiry != null && expiry.isBefore(LocalDate.now(zone(rec.tenantId)))) {
            throw ApiException.field("lines", "la fecha de vencimiento ya pasó: no recibas mercancía vencida");
        }
        Product p = products.lockById(productId, rec.tenantId).orElseThrow();
        GoodsReceiptLine gl = new GoodsReceiptLine();
        gl.receiptId = rec.id;
        gl.orderLineId = orderLineId;
        gl.productId = productId;
        gl.unitsReceived = got;
        gl.unitsRejected = bad;
        gl.rejectReason = bad.signum() > 0 ? rejectReason : null;
        gl.unitCost = unitCost.setScale(4, RoundingMode.HALF_UP);
        gl.lotCode = blank(lotCode);
        gl.expiryDate = expiry;
        if (!p.sellable() || "COMBO".equals(p.productType)) {
            throw ApiException.field("lines", p.name + (p.sellable() ? " es un combo: recibe sus componentes" : ": recibe cada variante (talla, color…)"));
        }
        if (p.trackSerials && got.signum() > 0) {
            serialService.receive(p, serials, gl.unitCost, rec.id, got);
            gl.serials = com.tiendaposs.serials.SerialService.join(com.tiendaposs.serials.SerialService.normalize(serials));
        }
        receiptLines.save(gl);
        if (got.signum() > 0) {
            if (p.trackStock) {
                inventory.applyLocked(p, new InventoryService.Change(p.id, got, InventoryService.COMPRA, "RECEIPT", rec.id,
                        "Recepción #" + rec.number + (rec.invoiceNumber != null ? " · factura " + rec.invoiceNumber : ""), gl.unitCost, false),
                        new InventoryService.LotInfo(lotCode, expiry, rec.id));
            } else {
                p.lastCost = gl.unitCost;
            }
            supplierProducts.findBySupplierIdAndProductId(supplierId, productId).ifPresent(sp -> {
                BigDecimal old = sp.unitCost();
                if (old.compareTo(gl.unitCost) != 0) {
                    sp.costPerPack = gl.unitCost.multiply(sp.packFactor).setScale(2, RoundingMode.HALF_UP);
                    sp.updatedAt = Instant.now();
                    suppliers.logPrice(sp, old, gl.unitCost, "COMPRA", rec.id);
                }
            });
        }
        return got.multiply(gl.unitCost);
    }

    // ---------------- Consultas ----------------

    /** Órdenes aprobadas que pasaron su fecha prevista sin llegar completas. */
    @Transactional(readOnly = true)
    public List<OrderDto> late() {
        Long t = TenantContext.require();
        LocalDate today = LocalDate.now(zone(t));
        return orders.findByTenantIdAndStatusIn(t, OPEN).stream()
                .filter(o -> o.expectedDate != null && o.expectedDate.isBefore(today)).map(o -> toDto(o, false)).toList();
    }

    /** Unidades ya pedidas que no han llegado, por producto (para no volverlas a pedir). */
    public Map<Long, BigDecimal> onOrder(Long tenantId) {
        List<Long> ids = orders.findByTenantIdAndStatusIn(tenantId, OPEN).stream().map(o -> o.id).toList();
        Map<Long, BigDecimal> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (PurchaseOrderLine l : lines.findByOrderIdIn(ids)) {
            BigDecimal pending = l.unitsOrdered().subtract(l.unitsReceived).subtract(l.unitsRejected);
            if (pending.signum() > 0) out.merge(l.productId, pending, BigDecimal::add);
        }
        return out;
    }

    PurchaseOrder find(Long id) {
        return orders.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Orden de compra no encontrada"));
    }

    private OrderDto toDto(PurchaseOrder o, boolean detail) {
        Supplier s = suppliers.find(o.supplierId);
        List<PurchaseOrderLine> ls = lines.findByOrderIdOrderByIdAsc(o.id);
        Map<Long, Product> ps = productsOf(ls);
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER) || CurrentUser.get().can(Permission.COMPRAS_VER);
        List<LineDto> lds = ls.stream().map(l -> {
            Product p = ps.get(l.productId);
            BigDecimal pending = l.unitsOrdered().subtract(l.unitsReceived).subtract(l.unitsRejected).max(BigDecimal.ZERO);
            return new LineDto(l.id, l.productId, p == null ? "?" : p.name, p == null ? null : p.sku, l.packName, l.packFactor, l.packsOrdered,
                    l.unitsOrdered(), costs ? l.costPerPack : null, costs ? l.costPerPack.multiply(l.packsOrdered).setScale(2, RoundingMode.HALF_UP) : null,
                    l.unitsReceived, l.unitsRejected, pending, l.reason, p == null ? null : p.stock);
        }).toList();
        List<ReceiptSummary> rs = detail ? receipts.findByOrderIdOrderByReceivedAtAsc(o.id).stream()
                .map(r -> new ReceiptSummary(r.id, r.number, r.invoiceNumber, costs ? r.total : null, r.onTime, r.receivedBy, r.receivedAt)).toList()
                : List.of();
        boolean late = OPEN.contains(o.status) && o.expectedDate != null && o.expectedDate.isBefore(LocalDate.now(zone(o.tenantId)));
        return new OrderDto(o.id, o.number, s.id, s.name, o.status, o.source, o.expectedDate, late, o.notes,
                costs ? o.estimatedTotal : null, costs ? o.receivedTotal : null, o.createdBy, o.createdAt, o.approvedBy, o.approvedAt,
                o.sentAt, o.sentVia, o.receivedAt, o.cancelReason, lds, rs);
    }

    private Map<Long, Product> productsOf(List<PurchaseOrderLine> ls) {
        return products.findAllById(ls.stream().map(l -> l.productId).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));
    }

    private ZoneId zone(Long tenantId) {
        return ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static String phone(String raw) {
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 10 && digits.startsWith("3") ? "57" + digits : digits;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
