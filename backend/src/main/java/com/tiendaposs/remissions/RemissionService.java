package com.tiendaposs.remissions;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleCalculator;
import com.tiendaposs.pos.SaleDtos.PaymentRequest;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.quotes.Quote;
import com.tiendaposs.quotes.QuoteLine;
import com.tiendaposs.quotes.QuoteRepos.QuoteLineRepository;
import com.tiendaposs.quotes.QuoteRepos.QuoteRepository;
import com.tiendaposs.remissions.RemissionRepos.RemissionLineRepository;
import com.tiendaposs.remissions.RemissionRepos.RemissionRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Remisiones: la mercancía se le entrega al cliente con un documento que firma quien recibe, y se factura después.
 *
 * <p>BORRADOR → ENTREGADA → FACTURADA, o ANULADA. Al entregar sale del inventario (movimiento REMISION, que la
 * contabilidad no registra como pérdida: el costo se reconoce al facturar). Lo que el cliente devuelve vuelve a entrar
 * y no se factura. Varias remisiones entregadas del mismo cliente se facturan en una sola venta, que no vuelve a
 * descontar el inventario; si esa venta se anula, las remisiones vuelven a quedar entregadas.</p>
 */
@Service
public class RemissionService {

    public static final String BORRADOR = "BORRADOR", ENTREGADA = "ENTREGADA", FACTURADA = "FACTURADA", ANULADA = "ANULADA";

    private final RemissionRepository remissions;
    private final RemissionLineRepository lines;
    private final QuoteRepository quotes;
    private final QuoteLineRepository quoteLines;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CustomerRepository customers;
    private final SaleRepository sales;
    private final SaleService saleService;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final Counters counters;
    private final AuditService audit;

    public RemissionService(RemissionRepository remissions, RemissionLineRepository lines, QuoteRepository quotes,
                            QuoteLineRepository quoteLines, ProductRepository products, PresentationRepository presentations,
                            CustomerRepository customers, SaleRepository sales, SaleService saleService,
                            InventoryService inventory, SettingsService settings, TenantRepository tenants, Counters counters,
                            AuditService audit) {
        this.remissions = remissions;
        this.lines = lines;
        this.quotes = quotes;
        this.quoteLines = quoteLines;
        this.products = products;
        this.presentations = presentations;
        this.customers = customers;
        this.sales = sales;
        this.saleService = saleService;
        this.inventory = inventory;
        this.settings = settings;
        this.tenants = tenants;
        this.counters = counters;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record LineRequest(Long presentationId,
                              @Size(max = 200, message = "máximo 200 caracteres") String description,
                              @NotNull(message = "es obligatoria") @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitPrice,
                              @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal discountPct,
                              /** Solo en líneas libres: el IVA lo trae el producto en las demás. */
                              @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal taxRate) {
    }

    public record RemissionRequest(
            Long customerId,
            @Size(max = 160, message = "máximo 160 caracteres") String customerName,
            @Size(max = 10) String customerDocType,
            @Size(max = 30, message = "máximo 30 caracteres") String customerDoc,
            @Size(max = 30, message = "máximo 30 caracteres") String customerPhone,
            @Size(max = 200, message = "máximo 200 caracteres") String deliveryAddress,
            @Size(max = 1000, message = "máximo 1000 caracteres") String notes,
            Boolean showPrices,
            @NotNull @Size(min = 1, message = "agrega al menos un producto") List<@Valid LineRequest> lines) {
    }

    public record LineDto(Long id, Long productId, Long presentationId, String description, String presentationName,
                          BigDecimal factor, BigDecimal quantity, BigDecimal returnedQty, BigDecimal pendingQty,
                          BigDecimal unitPrice, BigDecimal discountPct, String taxName, BigDecimal taxRate, BigDecimal fixedTax,
                          BigDecimal gross, BigDecimal discount, BigDecimal tax, BigDecimal lineTotal, boolean free) {
        static LineDto of(RemissionLine l) {
            return new LineDto(l.id, l.productId, l.presentationId, l.description, l.presentationName, l.factor, l.quantity,
                    l.returnedQty, l.pending(), l.unitPrice, l.discountPct, l.taxName, l.taxRate, l.fixedTax, l.gross, l.discount, l.tax,
                    l.lineTotal, l.presentationId == null);
        }
    }

    public record RemissionDto(Long id, long number, String code, String status, Long customerId, String customerName,
                               String customerDocType, String customerDoc, String customerPhone, String deliveryAddress,
                               LocalDate issueDate, String notes, boolean showPrices, BigDecimal subtotal,
                               BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total, BigDecimal pendingTotal,
                               boolean pricesIncludeTax, Long quoteId, String quoteCode, Long saleId, Long saleNumber,
                               List<String> invoicedWith, String createdBy, Instant createdAt, Instant deliveredAt,
                               String deliveredBy, String receivedBy, String receivedDoc, Instant invoicedAt,
                               Instant cancelledAt, String cancelReason, List<LineDto> lines, StoreInfo store) {
    }

    /** Encabezado del documento impreso. */
    public record StoreInfo(String name, String legalName, String taxId, String phone, String email, String address, String city,
                            String logo, String currency) {
    }

    public record SummaryDto(Long id, String code, String status, Long customerId, String customerName, String customerDoc,
                             BigDecimal total, BigDecimal pendingTotal, LocalDate issueDate, Instant deliveredAt,
                             String createdBy, Long saleNumber) {
    }

    // ------------------------------------------------------------------ Consultas

    public List<SummaryDto> list(String status, Long customerId, String q) {
        CurrentUser.require(Permission.REMISIONES_VER);
        Long t = TenantContext.require();
        String term = q == null ? "" : q.trim().toLowerCase();
        String exact = term.replaceAll("^rem-0*", "");
        StoreSettings st = settings.forTenant(t);
        return remissions.search(t, status == null ? "" : status.trim().toUpperCase(), customerId,
                        term.isEmpty() ? "" : "%" + term + "%", exact, PageRequest.of(0, 300)).stream()
                .map(r -> new SummaryDto(r.id, r.code(), r.status, r.customerId, r.customerName, r.customerDoc, r.total,
                        pendingTotal(r, lines.findByRemissionIdOrderBySortOrderAscIdAsc(r.id), st), r.issueDate, r.deliveredAt,
                        r.createdBy, saleNumber(r.saleId)))
                .toList();
    }

    public RemissionDto get(Long id) {
        CurrentUser.require(Permission.REMISIONES_VER);
        return toDto(find(id));
    }

    // ------------------------------------------------------------------ Crear y editar

    @Transactional
    public RemissionDto create(RemissionRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Long t = TenantContext.require();
        Remission x = new Remission();
        x.tenantId = t;
        x.status = BORRADOR;
        x.issueDate = today(t);
        x.createdBy = CurrentUser.get().fullName();
        x.number = counters.next(t, "REMISION");
        fill(x, r);
        x = remissions.save(x);
        saveLines(x, r.lines());
        audit.log("REMISION_CREADA", "REMISSION", x.id, "Creó la remisión " + x.code() + " para " + x.customerName + " por " + Money.fmt(x.total));
        return toDto(x);
    }

    @Transactional
    public RemissionDto update(Long id, RemissionRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Remission x = find(id);
        if (!BORRADOR.equals(x.status)) throw ApiException.conflict("La remisión ya está " + statusLabel(x.status) + ": no se puede editar");
        fill(x, r);
        lines.deleteByRemissionId(x.id);
        lines.flush();
        saveLines(x, r.lines());
        audit.log("REMISION_EDITADA", "REMISSION", x.id, "Editó la remisión " + x.code());
        return toDto(x);
    }

    /** Arma la remisión con lo cotizado (misma gente, mismos precios). La cotización queda como "despachada con remisión". */
    @Transactional
    public RemissionDto fromQuote(Long quoteId) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Long t = TenantContext.require();
        Quote q = quotes.findByIdAndTenantId(quoteId, t).orElseThrow(() -> ApiException.notFound("Cotización no encontrada"));
        if (!q.isQuote()) throw ApiException.badRequest("Solo una cotización se pasa a remisión");
        if (!Set.of("BORRADOR", "ENVIADA", "ACEPTADA").contains(q.status)) {
            throw ApiException.conflict("La cotización ya no se puede despachar (está " + q.status.toLowerCase() + ")");
        }
        if (q.customerName == null) throw ApiException.badRequest("La cotización no tiene cliente");
        Remission x = new Remission();
        x.tenantId = t;
        x.status = BORRADOR;
        x.issueDate = today(t);
        x.createdBy = CurrentUser.get().fullName();
        x.number = counters.next(t, "REMISION");
        x.customerId = q.customerId;
        x.customerName = q.customerName;
        x.customerDocType = q.customerDocType;
        x.customerDoc = q.customerDoc;
        x.customerPhone = q.customerPhone;
        x.deliveryAddress = q.customerAddress;
        x.notes = q.notes;
        x.quoteId = q.id;
        x = remissions.save(x);
        List<LineRequest> reqs = new ArrayList<>();
        for (QuoteLine l : quoteLines.findByQuoteIdOrderBySortOrderAscIdAsc(q.id)) {
            reqs.add(new LineRequest(l.presentationId, l.description, l.quantity, l.unitPrice, l.discountPct,
                    l.presentationId == null ? l.taxRate : null));
        }
        // Los precios ya los autorizó quien cotizó: no se vuelven a validar contra los permisos de quien remite.
        saveLines(x, reqs, true);
        q.status = "REMITIDA";
        quotes.save(q);
        audit.log("REMISION_CREADA", "REMISSION", x.id, "Creó la remisión " + x.code() + " desde la cotización " + q.code());
        return toDto(x);
    }

    private void fill(Remission x, RemissionRequest r) {
        Long t = x.tenantId;
        Customer cu = null;
        if (r.customerId() != null) {
            cu = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
        }
        x.customerId = cu == null ? null : cu.id;
        x.customerName = pick(r.customerName(), cu == null ? null : cu.name);
        if (x.customerName == null) throw ApiException.field("customerName", "escribe a quién se le entrega la mercancía");
        x.customerDocType = pick(r.customerDocType(), cu == null ? null : cu.docType);
        x.customerDoc = pick(r.customerDoc(), cu == null ? null : cu.docNumber);
        x.customerPhone = pick(r.customerPhone(), cu == null ? null : cu.phone);
        x.deliveryAddress = pick(r.deliveryAddress(), cu == null ? null : cu.address);
        x.notes = blank(r.notes());
        x.showPrices = r.showPrices() == null || r.showPrices();
    }

    private void saveLines(Remission x, List<LineRequest> reqs) {
        saveLines(x, reqs, false);
    }

    /** Arma las líneas desde el catálogo (o libres), valida precios con los permisos de quien remite y recalcula. */
    private void saveLines(Remission x, List<LineRequest> reqs, boolean pricesAuthorized) {
        StoreSettings st = settings.forTenant(x.tenantId);
        Set<String> perms = CurrentUser.get().permissions();
        List<RemissionLine> out = new ArrayList<>();
        int order = 0;
        for (LineRequest r : reqs) {
            RemissionLine l = new RemissionLine();
            l.tenantId = x.tenantId;
            l.remissionId = x.id;
            l.quantity = r.quantity().setScale(3, RoundingMode.HALF_UP);
            l.discountPct = r.discountPct() == null ? BigDecimal.ZERO : r.discountPct();
            l.sortOrder = order++;
            if (r.presentationId() != null) {
                Presentation pr = presentations.findByIdAndTenantId(r.presentationId(), x.tenantId)
                        .orElseThrow(() -> ApiException.badRequest("Un producto de la remisión ya no existe"));
                Product p = products.findByIdAndTenantId(pr.productId, x.tenantId).orElseThrow();
                if (!p.sellable()) throw ApiException.badRequest("Elige la variante de " + p.name + " (talla, color…)");
                if (!pr.active || !"ACTIVE".equals(p.status)) throw ApiException.badRequest(p.name + " (" + pr.name + ") ya no se vende");
                if (p.trackSerials) throw ApiException.badRequest(p.name + " lleva serial o IMEI: véndelo desde la caja para elegir cuál se entrega");
                if ("COMBO".equals(p.productType)) throw ApiException.badRequest(p.name + " es un combo: agrega sus productos uno por uno");
                l.productId = p.id;
                l.presentationId = pr.id;
                l.description = blank(r.description()) != null ? r.description().trim()
                        : p.name + (p.variantLabel == null ? "" : " " + p.variantLabel);
                l.presentationName = pr.name;
                l.factor = pr.factor;
                l.unitPrice = (r.unitPrice() == null ? pr.price : r.unitPrice()).setScale(2, RoundingMode.HALF_UP);
                l.taxName = p.taxName;
                l.taxRate = p.taxRate == null ? BigDecimal.ZERO : p.taxRate;
                l.fixedTax = p.fixedTax == null ? BigDecimal.ZERO : p.fixedTax;
                if (!pricesAuthorized) checkPrice(st, perms, l, pr);
            } else {
                if (blank(r.description()) == null) throw ApiException.badRequest("Describe la línea libre (por ejemplo: \"Flete\")");
                if (r.unitPrice() == null) throw ApiException.badRequest("Ponle precio a \"" + r.description().trim() + "\"");
                l.description = r.description().trim();
                l.unitPrice = r.unitPrice().setScale(2, RoundingMode.HALF_UP);
                l.taxName = st.defaultTaxName == null ? "IVA" : st.defaultTaxName;
                l.taxRate = r.taxRate() == null ? BigDecimal.ZERO : r.taxRate();
                if (!pricesAuthorized && l.discountPct.signum() > 0 && !perms.contains(Permission.POS_DESCUENTO.name())) {
                    throw ApiException.forbidden("No tienes permiso para dar descuentos");
                }
            }
            out.add(l);
        }
        SaleCalculator.Result res = calculate(x, out, l -> l.quantity, st);
        for (int i = 0; i < out.size(); i++) {
            var lr = res.lines().get(i);
            RemissionLine l = out.get(i);
            l.gross = lr.gross();
            l.discount = lr.discount();
            l.tax = lr.tax();
            l.lineTotal = lr.lineTotal();
        }
        x.subtotal = res.subtotal();
        x.discountTotal = res.discountTotal();
        x.taxTotal = res.taxTotal();
        x.total = res.total();
        x.pricesIncludeTax = st.pricesIncludeTax;
        lines.saveAll(out);
        remissions.save(x);
    }

    /** Las mismas reglas de la caja: descuento, descuento especial y precio mínimo según los permisos de quien remite. */
    private static void checkPrice(StoreSettings st, Set<String> perms, RemissionLine l, Presentation pr) {
        boolean below = l.unitPrice.compareTo(pr.price) < 0;
        if (!below && l.discountPct.signum() == 0) return;
        BigDecimal effective = l.unitPrice.multiply(BigDecimal.valueOf(100).subtract(l.discountPct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal implied = pr.price.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.ONE.subtract(effective.divide(pr.price, 6, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100));
        if (!perms.contains(Permission.POS_DESCUENTO.name())) {
            throw ApiException.forbidden("No tienes permiso para dar descuentos ni bajar precios (" + l.description + ")");
        }
        if (implied.compareTo(st.maxDiscountPct) > 0 && !perms.contains(Permission.POS_DESCUENTO_ESPECIAL.name())) {
            throw ApiException.forbidden("El descuento de " + l.description + " supera el máximo de "
                    + st.maxDiscountPct.stripTrailingZeros().toPlainString() + "%: debe hacerlo alguien con permiso de descuento especial");
        }
        if (pr.minPrice != null && pr.minPrice.signum() > 0 && effective.compareTo(pr.minPrice) < 0
                && !perms.contains(Permission.POS_PRECIO_ESPECIAL.name())) {
            throw ApiException.forbidden(l.description + " queda por debajo del precio mínimo (" + Money.fmt(pr.minPrice) + ")");
        }
    }

    /** Totales con el mismo motor de la caja, sobre la cantidad que se pida (entregada o pendiente por facturar). */
    private SaleCalculator.Result calculate(Remission x, List<RemissionLine> ls, java.util.function.Function<RemissionLine, BigDecimal> qty,
                                            StoreSettings st) {
        int scale = Money.scale(tenants.findById(x.tenantId).map(Tenant::getCurrency).orElse("COP"));
        List<SaleCalculator.LineInput> in = ls.stream().map(l -> new SaleCalculator.LineInput(l.unitPrice, qty.apply(l), l.factor,
                BigDecimal.ZERO, l.discountPct, l.taxRate, l.fixedTax)).toList();
        return SaleCalculator.calculate(in, st.pricesIncludeTax, null, BigDecimal.ZERO, scale);
    }

    /** Valor de lo que falta por facturar: lo entregado menos lo devuelto (cero si ya se facturó o se anuló). */
    private BigDecimal pendingTotal(Remission x, List<RemissionLine> ls, StoreSettings st) {
        if (!ENTREGADA.equals(x.status)) return BigDecimal.ZERO;
        List<RemissionLine> open = ls.stream().filter(l -> l.pending().signum() > 0).toList();
        return open.isEmpty() ? BigDecimal.ZERO : calculate(x, open, RemissionLine::pending, st).total();
    }

    // ------------------------------------------------------------------ Entregar, devolver y anular

    public record DeliverRequest(@Size(max = 160, message = "máximo 160 caracteres") String receivedBy,
                                 @Size(max = 30, message = "máximo 30 caracteres") String receivedDoc) {
    }

    /** La mercancía sale con el cliente: se descuenta del inventario y la remisión ya no se edita. */
    @Transactional
    public RemissionDto deliver(Long id, DeliverRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Remission x = find(id);
        if (!BORRADOR.equals(x.status)) throw ApiException.conflict("La remisión ya está " + statusLabel(x.status));
        for (RemissionLine l : lines.findByRemissionIdOrderBySortOrderAscIdAsc(x.id)) {
            move(x, l, l.quantity.negate(), InventoryService.REMISION, "Remisión " + x.code() + " a " + x.customerName);
        }
        x.status = ENTREGADA;
        x.deliveredAt = Instant.now();
        x.deliveredBy = CurrentUser.get().fullName();
        x.receivedBy = blank(r.receivedBy());
        x.receivedDoc = blank(r.receivedDoc());
        remissions.save(x);
        audit.log("REMISION_ENTREGADA", "REMISSION", x.id, "Entregó la remisión " + x.code() + " a " + x.customerName
                + (x.receivedBy == null ? "" : " (recibió " + x.receivedBy + ")") + " por " + Money.fmt(x.total));
        return toDto(x);
    }

    public record ReturnLine(@NotNull Long lineId,
                             @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity) {
    }

    public record ReturnRequest(@NotNull @Size(min = 1, message = "elige qué devolvió el cliente") List<@Valid ReturnLine> lines,
                                @NotBlank(message = "explica por qué lo devolvió") @Size(max = 300) String reason) {
    }

    /** El cliente devolvió parte de lo remitido (antes de facturar): vuelve al inventario y no se le cobra. */
    @Transactional
    public RemissionDto returnItems(Long id, ReturnRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Remission x = find(id);
        if (!ENTREGADA.equals(x.status)) {
            throw ApiException.conflict(FACTURADA.equals(x.status) ? "La remisión ya se facturó: registra la devolución desde la venta"
                    : "La remisión está " + statusLabel(x.status));
        }
        Map<Long, RemissionLine> byId = lines.findByRemissionIdOrderBySortOrderAscIdAsc(x.id).stream()
                .collect(Collectors.toMap(l -> l.id, l -> l));
        List<String> what = new ArrayList<>();
        for (ReturnLine rl : r.lines()) {
            RemissionLine l = byId.get(rl.lineId());
            if (l == null) throw ApiException.badRequest("Una línea no es de esta remisión");
            BigDecimal q = rl.quantity().setScale(3, RoundingMode.HALF_UP);
            if (q.compareTo(l.pending()) > 0) {
                throw ApiException.badRequest("De " + l.description + " solo quedan " + l.pending().stripTrailingZeros().toPlainString() + " por devolver");
            }
            move(x, l, q, InventoryService.REMISION_REINGRESO, "Devolución de la remisión " + x.code() + ": " + r.reason().trim());
            l.returnedQty = l.returnedQty.add(q);
            lines.save(l);
            what.add(q.stripTrailingZeros().toPlainString() + " " + l.description);
        }
        remissions.save(x);
        audit.log("REMISION_DEVOLUCION", "REMISSION", x.id, "El cliente devolvió de la remisión " + x.code() + ": "
                + String.join(", ", what) + ". Motivo: " + r.reason().trim());
        return toDto(x);
    }

    public record CancelRequest(@NotBlank(message = "explica por qué se anula") @Size(max = 300) String reason) {
    }

    /** Anula: si ya se había entregado, lo que el cliente no devolvió antes vuelve al inventario (la mercancía regresó). */
    @Transactional
    public RemissionDto cancel(Long id, CancelRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Remission x = find(id);
        if (FACTURADA.equals(x.status)) throw ApiException.conflict("La remisión ya se facturó: anula o devuelve la venta");
        if (ANULADA.equals(x.status)) throw ApiException.conflict("La remisión ya está anulada");
        if (ENTREGADA.equals(x.status)) {
            for (RemissionLine l : lines.findByRemissionIdOrderBySortOrderAscIdAsc(x.id)) {
                if (l.pending().signum() <= 0) continue;
                move(x, l, l.pending(), InventoryService.REMISION_REINGRESO, "Anulación de la remisión " + x.code());
            }
        }
        x.status = ANULADA;
        x.cancelledAt = Instant.now();
        x.cancelReason = r.reason().trim();
        if (x.quoteId != null) {
            // La cotización vuelve a estar disponible para despacharse o cobrarse.
            quotes.findByIdAndTenantId(x.quoteId, x.tenantId).filter(q -> "REMITIDA".equals(q.status)).ifPresent(q -> {
                q.status = "ACEPTADA";
                quotes.save(q);
            });
        }
        remissions.save(x);
        audit.log("REMISION_ANULADA", "REMISSION", x.id, "Anuló la remisión " + x.code() + ". Motivo: " + x.cancelReason);
        return toDto(x);
    }

    /** Mueve el inventario de una línea (las libres, los servicios y lo que no controla existencias no mueven nada). */
    private void move(Remission x, RemissionLine l, BigDecimal units, String type, String reason) {
        if (l.productId == null) return;
        Product p = products.lockById(l.productId, x.tenantId).orElse(null);
        if (p == null || !p.trackStock) return;
        inventory.applyLocked(p, new InventoryService.Change(p.id, units.multiply(l.factor), type, "REMISSION", x.id, reason, null, false));
    }

    // ------------------------------------------------------------------ Facturar

    public record InvoiceRequest(@NotNull @Size(min = 1, message = "elige al menos una remisión") List<Long> remissionIds,
                                 @NotNull @Size(min = 1, message = "registra al menos un pago") List<@Valid PaymentRequest> payments,
                                 Boolean electronicInvoice) {
    }

    /**
     * Factura en una sola venta una o varias remisiones entregadas del mismo cliente (por ejemplo, el corte del mes),
     * por lo que quedó en manos del cliente. Se cobra en la caja; con "Fiado" queda en su cartera.
     */
    @Transactional
    public SaleDto invoice(InvoiceRequest r) {
        CurrentUser.require(Permission.REMISIONES_CREAR);
        Long t = TenantContext.require();
        List<Remission> list = new ArrayList<>();
        for (Long id : new TreeSet<>(r.remissionIds())) list.add(find(id));
        for (Remission x : list) {
            if (!ENTREGADA.equals(x.status)) throw ApiException.conflict("La remisión " + x.code() + " está " + statusLabel(x.status) + ": solo se facturan las entregadas");
        }
        Remission first = list.get(0);
        for (Remission x : list) {
            boolean same = first.customerId != null ? first.customerId.equals(x.customerId)
                    : x.customerId == null && first.customerName.equalsIgnoreCase(x.customerName);
            if (!same) throw ApiException.badRequest("Las remisiones son de clientes distintos: factúralas por separado");
        }
        boolean invoice = Boolean.TRUE.equals(r.electronicInvoice());
        if (invoice && first.customerId == null) {
            throw ApiException.badRequest("Para la factura electrónica la remisión debe tener un cliente registrado (con su documento)");
        }
        List<SaleService.QuoteLineInput> items = new ArrayList<>();
        for (Remission x : list) {
            for (RemissionLine l : lines.findByRemissionIdOrderBySortOrderAscIdAsc(x.id)) {
                if (l.pending().signum() <= 0) continue;
                items.add(new SaleService.QuoteLineInput(l.presentationId, l.description, l.pending(), l.unitPrice, l.discountPct,
                        l.taxName, l.taxRate));
            }
        }
        if (items.isEmpty()) throw ApiException.badRequest("El cliente devolvió todo: no queda nada por facturar. Anula la remisión.");
        String codes = list.stream().map(Remission::code).collect(Collectors.joining(", "));
        // Idempotente ante un doble clic; cambia si la venta se anula y se vuelve a facturar (sube la versión).
        String ids = list.stream().map(x -> String.valueOf(x.id)).collect(Collectors.joining("."));
        if (ids.length() > 40) ids = first.id + "-" + list.size() + "-" + Integer.toHexString(ids.hashCode());
        String ref = "REM-" + ids + "-v" + list.stream().mapToInt(x -> x.version).sum();
        SaleDto sale = saleService.checkoutRemissions((list.size() == 1 ? "Remisión " : "Remisiones ") + codes, items,
                first.customerId, r.payments(), ref, invoice);
        Instant now = Instant.now();
        for (Remission x : list) {
            x.status = FACTURADA;
            x.saleId = sale.id();
            x.invoicedAt = now;
            remissions.save(x);
        }
        audit.log("REMISION_FACTURADA", "SALE", sale.id(), "Facturó " + (list.size() == 1 ? "la remisión " : "las remisiones ") + codes
                + " de " + first.customerName + ": venta #" + sale.number() + " por " + Money.fmt(sale.total()));
        return sale;
    }

    /** La venta que las facturó se anuló: vuelven a quedar entregadas (la mercancía sigue con el cliente). */
    @Transactional
    public void onSaleVoided(Sale s) {
        for (Remission x : remissions.findByTenantIdAndSaleIdOrderByNumberAsc(s.tenantId, s.id)) {
            x.status = ENTREGADA;
            x.saleId = null;
            x.invoicedAt = null;
            remissions.save(x);
            audit.log("REMISION_REABIERTA", "REMISSION", x.id, "La remisión " + x.code() + " vuelve a estar por facturar: se anuló la venta #" + s.number);
        }
    }

    // ------------------------------------------------------------------ utilidades

    private Remission find(Long id) {
        return remissions.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Remisión no encontrada"));
    }

    private Long saleNumber(Long saleId) {
        return saleId == null ? null : sales.findById(saleId).map(s -> s.number).orElse(null);
    }

    RemissionDto toDto(Remission x) {
        List<RemissionLine> ls = lines.findByRemissionIdOrderBySortOrderAscIdAsc(x.id);
        StoreSettings st = settings.forTenant(x.tenantId);
        String quoteCode = x.quoteId == null ? null : quotes.findById(x.quoteId).map(Quote::code).orElse(null);
        List<String> with = x.saleId == null ? List.of() : remissions.findByTenantIdAndSaleIdOrderByNumberAsc(x.tenantId, x.saleId).stream()
                .filter(o -> !o.id.equals(x.id)).map(Remission::code).toList();
        return new RemissionDto(x.id, x.number, x.code(), x.status, x.customerId, x.customerName, x.customerDocType, x.customerDoc,
                x.customerPhone, x.deliveryAddress, x.issueDate, x.notes, x.showPrices, x.subtotal, x.discountTotal, x.taxTotal,
                x.total, pendingTotal(x, ls, st), x.pricesIncludeTax, x.quoteId, quoteCode, x.saleId, saleNumber(x.saleId), with,
                x.createdBy, x.createdAt, x.deliveredAt, x.deliveredBy, x.receivedBy, x.receivedDoc, x.invoicedAt, x.cancelledAt,
                x.cancelReason, ls.stream().map(LineDto::of).toList(), storeInfo(x.tenantId));
    }

    private StoreInfo storeInfo(Long tenantId) {
        Tenant t = tenants.findById(tenantId).orElseThrow();
        String logo = t.getLogo() != null && t.getLogo().length() < 300_000 ? t.getLogo() : null;
        return new StoreInfo(t.getName(), t.getLegalName(), t.getTaxId(), t.getPhone(), t.getEmail(), t.getAddress(), t.getCity(), logo,
                t.getCurrency());
    }

    private LocalDate today(Long tenantId) {
        return LocalDate.now(ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota")));
    }

    static String statusLabel(String s) {
        return switch (s) {
            case BORRADOR -> "en borrador";
            case ENTREGADA -> "entregada";
            case FACTURADA -> "facturada";
            case ANULADA -> "anulada";
            default -> s.toLowerCase();
        };
    }

    private static String pick(String a, String b) {
        return blank(a) != null ? a.trim() : blank(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
