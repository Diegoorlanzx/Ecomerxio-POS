package com.tiendaposs.distribution;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.distribution.DistributionRepos.*;
import com.tiendaposs.pos.CashMovement;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.Payment;
import com.tiendaposs.pos.PosRepos.CashMovementRepository;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.PosRepos.PaymentRepository;
import com.tiendaposs.pos.SaleDtos.PaymentRequest;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Preventa → alistamiento → despacho → entrega → cierre de ruta. Los pedidos se toman en el celular del vendedor
 * (también sin internet: llegan con su clientRef y la hora en que se tomaron) y el cierre de la ruta cuadra el
 * dinero que trae el conductor contra lo cobrado en las entregas.
 */
@Service
public class PresaleService {

    static final Set<String> FINAL = Set.of("ENTREGADO", "PARCIAL", "DEVUELTO", "ANULADO");

    private final PresaleOrderRepository orders;
    private final PresaleOrderLineRepository lines;
    private final RouteVisitRepository visits;
    private final DispatchRepository dispatches;
    private final SalesRouteRepository routes;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final PaymentRepository payments;
    private final CashMovementRepository cashMovements;
    private final ShiftService shifts;
    private final SaleService saleService;
    private final DistributionService distribution;
    private final Counters counters;
    private final AuditService audit;

    public PresaleService(PresaleOrderRepository orders, PresaleOrderLineRepository lines, RouteVisitRepository visits, DispatchRepository dispatches,
                          SalesRouteRepository routes, CustomerRepository customers, ProductRepository products, PresentationRepository presentations,
                          PaymentRepository payments, CashMovementRepository cashMovements, ShiftService shifts, SaleService saleService,
                          DistributionService distribution, Counters counters, AuditService audit) {
        this.orders = orders;
        this.lines = lines;
        this.visits = visits;
        this.dispatches = dispatches;
        this.routes = routes;
        this.customers = customers;
        this.products = products;
        this.presentations = presentations;
        this.payments = payments;
        this.cashMovements = cashMovements;
        this.shifts = shifts;
        this.saleService = saleService;
        this.distribution = distribution;
        this.counters = counters;
        this.audit = audit;
    }

    // =====================================================================
    // Pedidos de preventa
    // =====================================================================

    public record LineDto(Long id, Long presentationId, Long productId, String productName, String presentationName, BigDecimal quantity,
                          BigDecimal unitPrice, BigDecimal deliveredQty, BigDecimal lineTotal) {
    }

    public record OrderDto(Long id, long number, Long customerId, String customerName, String customerPhone, String customerAddress,
                           String customerDoc, Long routeId, String routeName, String sellerName, String status, String notes, BigDecimal total,
                           Instant takenAt, Instant syncedAt, boolean offline, Instant pickedAt, Long dispatchId, Instant deliveredAt,
                           Long saleId, BigDecimal deliveredTotal, BigDecimal collectedTotal, BigDecimal creditTotal, String returnReason,
                           List<LineDto> lines) {
    }

    public record OrderLineRequest(@NotNull Long presentationId,
                                   @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal quantity) {
    }

    public record OrderRequest(@Size(max = 80) String clientRef, @NotNull(message = "elige el cliente") Long customerId,
                               @NotNull @Size(min = 1, message = "agrega al menos un producto") List<@Valid OrderLineRequest> lines,
                               @Size(max = 500) String notes, Instant takenAt, boolean offline) {
    }

    /** Toma un pedido. Con el mismo clientRef devuelve el que ya llegó (el celular puede reenviarlo al volver la señal). */
    @Transactional
    public OrderDto create(OrderRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        String ref = blank(r.clientRef());
        if (ref != null) {
            Optional<PresaleOrder> dup = orders.findByTenantIdAndClientRef(t, ref);
            if (dup.isPresent()) return toDto(dup.get());
        }
        Customer c = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
        AuthUser me = CurrentUser.get();
        PresaleOrder o = new PresaleOrder();
        o.tenantId = t;
        o.number = counters.next(t, "PREVENTA");
        o.clientRef = ref;
        o.customerId = c.id;
        o.customerName = c.name;
        o.routeId = c.routeId;
        o.sellerUserId = me.userId();
        o.sellerName = me.fullName();
        o.notes = blank(r.notes());
        o.offline = r.offline();
        Instant now = Instant.now();
        // La hora del celular solo se acepta si es razonable (no del futuro ni de hace más de una semana).
        o.takenAt = r.takenAt() != null && !r.takenAt().isAfter(now) && r.takenAt().isAfter(now.minusSeconds(7 * 86400)) ? r.takenAt() : now;
        o.syncedAt = now;
        // Llegó tarde desde la cola del celular: se tomó sin conexión aunque el equipo creyera tener señal.
        if (o.takenAt.isBefore(now.minusSeconds(120))) o.offline = true;
        o = orders.save(o);
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLineRequest lr : r.lines()) {
            Presentation pr = presentations.findByIdAndTenantId(lr.presentationId(), t).orElseThrow(() -> ApiException.field("lines", "un producto del pedido no existe"));
            Product p = products.findById(pr.productId).orElseThrow();
            PresaleOrderLine l = new PresaleOrderLine();
            l.tenantId = t;
            l.orderId = o.id;
            l.presentationId = pr.id;
            l.productId = p.id;
            l.productName = p.name;
            l.presentationName = pr.name;
            l.quantity = lr.quantity().setScale(3, RoundingMode.HALF_UP);
            l.unitPrice = distribution.price(t, c.priceListId, pr, l.quantity);
            l.lineTotal = l.unitPrice.multiply(l.quantity).setScale(2, RoundingMode.HALF_UP);
            total = total.add(l.lineTotal);
            lines.save(l);
        }
        o.total = total;
        orders.save(o);
        visit(t, o.routeId, c.id, "PEDIDO", null, o.id, ref == null ? null : ref + ":visita", LocalDate.ofInstant(o.takenAt, distribution.zone(t)));
        audit.log("PREVENTA", "PRESALE", o.id, "Pedido #" + o.number + " de " + c.name + " por " + Money.fmt(total) + (o.offline ? " (tomado sin internet)" : ""));
        return toDto(o);
    }

    public record VisitRequest(@Size(max = 80) String clientRef, @NotNull Long customerId,
                               @NotBlank @Pattern(regexp = "NO_PIDIO|CERRADO", message = "resultado no válido") String result,
                               @Size(max = 300) String reason, LocalDate date) {
    }

    /** Visita sin pedido: el cliente no pidió o el negocio estaba cerrado (cuenta para la efectividad de la ruta). */
    @Transactional
    public Map<String, Object> visitWithoutOrder(VisitRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        Customer c = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
        LocalDate today = LocalDate.now(distribution.zone(t));
        LocalDate d = r.date() == null || r.date().isAfter(today) ? today : r.date();
        RouteVisit v = visit(t, c.routeId, c.id, r.result(), blank(r.reason()), null, blank(r.clientRef()), d);
        return Map.of("id", v.id, "result", v.result);
    }

    private RouteVisit visit(Long t, Long routeId, Long customerId, String result, String reason, Long orderId, String ref, LocalDate date) {
        if (ref != null) {
            Optional<RouteVisit> dup = visits.findByTenantIdAndClientRef(t, ref);
            if (dup.isPresent()) return dup.get();
        }
        AuthUser me = CurrentUser.get();
        RouteVisit v = new RouteVisit();
        v.tenantId = t;
        v.routeId = routeId;
        v.customerId = customerId;
        v.sellerUserId = me.userId();
        v.sellerName = me.fullName();
        v.visitDate = date;
        v.result = result;
        v.reason = reason;
        v.orderId = orderId;
        v.clientRef = ref;
        return visits.save(v);
    }

    public record OrderSummary(Long id, long number, String customerName, String routeName, String sellerName, String status, BigDecimal total,
                               BigDecimal deliveredTotal, int items, Instant takenAt, boolean offline, Long dispatchId) {
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> list(String status) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        Map<Long, String> routeNames = routes.findByTenantIdOrderByNameAsc(t).stream().collect(Collectors.toMap(r -> r.id, r -> r.name));
        List<PresaleOrder> os = status == null || status.isBlank() ? orders.findByTenantIdOrderByTakenAtDesc(t)
                : orders.findByTenantIdAndStatusInOrderByTakenAtAsc(t, Arrays.asList(status.toUpperCase().split(",")));
        Map<Long, List<PresaleOrderLine>> ls = lines.findByOrderIdIn(os.stream().map(o -> o.id).toList()).stream().collect(Collectors.groupingBy(l -> l.orderId));
        return os.stream().limit(500).map(o -> new OrderSummary(o.id, o.number, o.customerName, o.routeId == null ? null : routeNames.get(o.routeId),
                o.sellerName, o.status, o.total, o.deliveredTotal, ls.getOrDefault(o.id, List.of()).size(), o.takenAt, o.offline, o.dispatchId)).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        CurrentUser.require(Permission.POS_VENDER);
        return toDto(find(id));
    }

    PresaleOrder find(Long id) {
        return orders.findByIdAndTenantId(id, distribution.tenant()).orElseThrow(() -> ApiException.notFound("Pedido no encontrado"));
    }

    OrderDto toDto(PresaleOrder o) {
        Customer c = customers.findById(o.customerId).orElse(null);
        String route = o.routeId == null ? null : routes.findById(o.routeId).map(r -> r.name).orElse(null);
        List<LineDto> ls = lines.findByOrderIdOrderByIdAsc(o.id).stream().map(l -> new LineDto(l.id, l.presentationId, l.productId, l.productName,
                l.presentationName, l.quantity, l.unitPrice, l.deliveredQty, l.lineTotal)).toList();
        return new OrderDto(o.id, o.number, o.customerId, o.customerName, c == null ? null : c.phone, c == null ? null : c.address,
                c == null ? null : c.docNumber, o.routeId, route, o.sellerName, o.status, o.notes, o.total, o.takenAt, o.syncedAt, o.offline,
                o.pickedAt, o.dispatchId, o.deliveredAt, o.saleId, o.deliveredTotal, o.collectedTotal, o.creditTotal, o.returnReason, ls);
    }

    public record CancelRequest(@NotBlank(message = "escribe el motivo") @Size(max = 300) String reason) {
    }

    @Transactional
    public OrderDto cancel(Long id, CancelRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        PresaleOrder o = find(id);
        if (!"PENDIENTE".equals(o.status) && !"ALISTADO".equals(o.status)) throw ApiException.conflict("Solo se anula un pedido que no ha salido a ruta");
        o.status = "ANULADO";
        o.returnReason = r.reason().trim();
        orders.save(o);
        audit.log("PREVENTA_ANULADA", "PRESALE", o.id, "Anuló el pedido #" + o.number + ": " + o.returnReason);
        return toDto(o);
    }

    // =====================================================================
    // Alistamiento (consolidado para la bodega)
    // =====================================================================

    public record PickLine(Long presentationId, String productName, String presentationName, BigDecimal quantity, BigDecimal stockUnits,
                           BigDecimal factor, boolean shortage) {
    }

    public record PickList(List<OrderSummary> orders, List<PickLine> lines, BigDecimal total) {
    }

    public record IdsRequest(@NotNull @Size(min = 1, message = "elige al menos un pedido") List<Long> orderIds) {
    }

    /** Consolidado de lo que hay que alistar: cuánto de cada producto, contra lo que hay en bodega. */
    @Transactional(readOnly = true)
    public PickList pickList(List<Long> ids) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        List<PresaleOrder> os = ids.stream().map(this::find).toList();
        Map<Long, BigDecimal> qty = new LinkedHashMap<>();
        Map<Long, PresaleOrderLine> sample = new HashMap<>();
        for (PresaleOrderLine l : lines.findByOrderIdIn(ids)) {
            qty.merge(l.presentationId, l.quantity, BigDecimal::add);
            sample.putIfAbsent(l.presentationId, l);
        }
        List<PickLine> out = new ArrayList<>();
        for (var e : qty.entrySet()) {
            PresaleOrderLine l = sample.get(e.getKey());
            Presentation pr = presentations.findByIdAndTenantId(e.getKey(), t).orElse(null);
            Product p = products.findById(l.productId).orElse(null);
            BigDecimal factor = pr == null ? BigDecimal.ONE : pr.factor;
            BigDecimal stock = p == null ? BigDecimal.ZERO : p.stock.subtract(p.reserved == null ? BigDecimal.ZERO : p.reserved);
            boolean shortage = p != null && p.trackStock && stock.compareTo(e.getValue().multiply(factor)) < 0;
            out.add(new PickLine(e.getKey(), l.productName, l.presentationName, e.getValue(), stock, factor, shortage));
        }
        out.sort(Comparator.comparing(PickLine::productName, String.CASE_INSENSITIVE_ORDER));
        Map<Long, String> routeNames = routes.findByTenantIdOrderByNameAsc(t).stream().collect(Collectors.toMap(r -> r.id, r -> r.name));
        List<OrderSummary> sums = os.stream().map(o -> new OrderSummary(o.id, o.number, o.customerName, o.routeId == null ? null : routeNames.get(o.routeId),
                o.sellerName, o.status, o.total, o.deliveredTotal, 0, o.takenAt, o.offline, o.dispatchId)).toList();
        return new PickList(sums, out, os.stream().map(o -> o.total).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Transactional
    public PickList pick(IdsRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        for (Long id : r.orderIds()) {
            PresaleOrder o = find(id);
            if (!"PENDIENTE".equals(o.status)) throw ApiException.conflict("El pedido #" + o.number + " ya está " + o.status.toLowerCase());
            o.status = "ALISTADO";
            o.pickedAt = Instant.now();
            orders.save(o);
        }
        audit.log("PREVENTA_ALISTADA", "PRESALE", null, "Alistó " + r.orderIds().size() + " pedidos");
        return pickList(r.orderIds());
    }

    // =====================================================================
    // Despacho, entrega y cierre de ruta
    // =====================================================================

    public record DispatchRequest(@NotNull @Size(min = 1, message = "elige al menos un pedido") List<Long> orderIds,
                                  @NotBlank(message = "¿quién lleva el pedido?") @Size(max = 120) String driverName,
                                  @Size(max = 60) String vehicle, Long routeId) {
    }

    public record MethodTotal(String method, BigDecimal amount) {
    }

    public record DispatchDto(Long id, long number, Long routeId, String routeName, String driverName, String vehicle, String status,
                              String createdBy, Instant createdAt, Instant closedAt, String closedBy, BigDecimal cashExpected,
                              BigDecimal cashCounted, BigDecimal difference, String notes, List<OrderDto> orders, BigDecimal total,
                              BigDecimal delivered, BigDecimal returned, BigDecimal credit, List<MethodTotal> collected, int pending) {
    }

    @Transactional
    public DispatchDto dispatch(DispatchRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        if (r.routeId() != null) routes.findByIdAndTenantId(r.routeId(), t).orElseThrow(() -> ApiException.field("routeId", "esa ruta no existe"));
        Dispatch d = new Dispatch();
        d.tenantId = t;
        d.number = counters.next(t, "DESPACHO");
        d.routeId = r.routeId();
        d.driverName = r.driverName().trim();
        d.vehicle = blank(r.vehicle());
        d.createdBy = CurrentUser.get().fullName();
        d = dispatches.save(d);
        for (Long id : r.orderIds()) {
            PresaleOrder o = find(id);
            if (!"ALISTADO".equals(o.status)) throw ApiException.conflict("El pedido #" + o.number + " no está alistado");
            o.status = "DESPACHADO";
            o.dispatchId = d.id;
            orders.save(o);
        }
        audit.log("DESPACHO", "DISPATCH", d.id, "Despacho #" + d.number + " con " + r.orderIds().size() + " pedidos (" + d.driverName + ")");
        return toDto(d);
    }

    public record DeliverLine(@NotNull Long lineId, @NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal deliveredQty) {
    }

    public record DeliverRequest(List<@Valid DeliverLine> lines, List<@Valid PaymentRequest> payments, @Size(max = 300) String returnReason,
                                 @Size(max = 80) String clientRef) {
    }

    /**
     * Entrega en la tienda del cliente: lo entregado se vuelve una venta (sale del inventario), lo cobrado queda para el
     * cuadre de la ruta y lo que no se paga queda en la cartera del cliente (fiado). Sin nada entregado = devuelto.
     */
    @Transactional
    public OrderDto deliver(Long id, DeliverRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        PresaleOrder o = find(id);
        if (!"DESPACHADO".equals(o.status)) throw ApiException.conflict("El pedido #" + o.number + " no está en ruta");
        Long t = o.tenantId;
        Map<Long, BigDecimal> qty = new HashMap<>();
        if (r.lines() != null) r.lines().forEach(l -> qty.put(l.lineId(), l.deliveredQty()));
        List<PresaleOrderLine> ls = lines.findByOrderIdOrderByIdAsc(o.id);
        List<SaleService.QuoteLineInput> items = new ArrayList<>();
        BigDecimal delivered = BigDecimal.ZERO;
        boolean partial = false;
        for (PresaleOrderLine l : ls) {
            BigDecimal q = qty.getOrDefault(l.id, l.quantity).setScale(3, RoundingMode.HALF_UP);
            if (q.compareTo(l.quantity) > 0) throw ApiException.field("lines", "de " + l.productName + " se pidieron " + l.quantity.stripTrailingZeros().toPlainString());
            if (q.compareTo(l.quantity) < 0) partial = true;
            l.deliveredQty = q;
            lines.save(l);
            if (q.signum() > 0) {
                // Con entrega parcial se respeta el precio pactado en el pedido (no se recalcula el escalón de volumen).
                items.add(new SaleService.QuoteLineInput(l.presentationId, null, q, l.unitPrice, null, null, null));
                delivered = delivered.add(l.unitPrice.multiply(q).setScale(2, RoundingMode.HALF_UP));
            }
        }
        if (partial && blank(r.returnReason()) == null) throw ApiException.field("returnReason", "¿por qué no se entregó todo?");
        o.deliveredAt = Instant.now();
        o.returnReason = blank(r.returnReason());
        if (items.isEmpty()) {
            o.status = "DEVUELTO";
            o.deliveredTotal = BigDecimal.ZERO;
            orders.save(o);
            audit.log("PREVENTA_DEVUELTA", "PRESALE", o.id, "Pedido #" + o.number + " devuelto: " + o.returnReason);
            return toDto(o);
        }
        List<PaymentRequest> pays = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (PaymentRequest p : r.payments() == null ? List.<PaymentRequest>of() : r.payments()) {
            if ("FIADO".equalsIgnoreCase(p.method())) continue;
            pays.add(p);
            paid = paid.add(p.amount());
        }
        // El total de la venta puede diferir por centavos de impuestos: lo que falte (o todo si no pagó) queda a crédito.
        BigDecimal credit = delivered.subtract(paid).max(BigDecimal.ZERO);
        if (credit.signum() > 0) {
            if (!distribution.settingsCredit(t)) throw ApiException.field("payments", "falta cobrar " + Money.fmt(credit) + " (el fiado está apagado)");
            pays.add(new PaymentRequest("FIADO", credit, "Pedido #" + o.number));
        }
        SaleDto sale = saleService.checkoutRoute("Pedido #" + o.number + " (despacho)", items, o.customerId, pays,
                blank(r.clientRef()) == null ? "PREVENTA-" + o.id : r.clientRef());
        o.saleId = sale.id();
        o.deliveredTotal = sale.total();
        o.collectedTotal = sale.total().subtract(credit).max(BigDecimal.ZERO);
        o.creditTotal = credit;
        o.status = partial ? "PARCIAL" : "ENTREGADO";
        orders.save(o);
        audit.log("PREVENTA_ENTREGADA", "PRESALE", o.id, "Entregó el pedido #" + o.number + (partial ? " (parcial)" : "") + " por " + Money.fmt(sale.total()));
        return toDto(o);
    }

    @Transactional(readOnly = true)
    public List<DispatchDto> dispatches(String status) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = distribution.tenant();
        return dispatches.findByTenantIdOrderByCreatedAtDesc(t).stream().filter(d -> status == null || status.isBlank() || status.equalsIgnoreCase(d.status))
                .limit(100).map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DispatchDto dispatch(Long id) {
        CurrentUser.require(Permission.POS_VENDER);
        return toDto(findDispatch(id));
    }

    Dispatch findDispatch(Long id) {
        return dispatches.findByIdAndTenantId(id, distribution.tenant()).orElseThrow(() -> ApiException.notFound("Despacho no encontrado"));
    }

    DispatchDto toDto(Dispatch d) {
        List<PresaleOrder> os = orders.findByDispatchIdOrderByIdAsc(d.id);
        Map<String, BigDecimal> byMethod = new TreeMap<>();
        for (PresaleOrder o : os) {
            if (o.saleId == null) continue;
            for (Payment p : payments.findBySaleIdOrderByIdAsc(o.saleId)) {
                if ("FIADO".equals(p.method) || "RECHAZADO".equals(p.status)) continue;
                byMethod.merge(p.method, p.amount, BigDecimal::add);
            }
        }
        BigDecimal total = os.stream().map(o -> o.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delivered = os.stream().map(o -> o.deliveredTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = os.stream().map(o -> o.creditTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returned = os.stream().filter(o -> o.deliveredAt != null).map(o -> o.total.subtract(o.deliveredTotal)).filter(x -> x.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int pending = (int) os.stream().filter(o -> "DESPACHADO".equals(o.status)).count();
        String route = d.routeId == null ? null : routes.findById(d.routeId).map(r -> r.name).orElse(null);
        BigDecimal expected = d.cashExpected != null ? d.cashExpected : byMethod.getOrDefault("EFECTIVO", BigDecimal.ZERO);
        return new DispatchDto(d.id, d.number, d.routeId, route, d.driverName, d.vehicle, d.status, d.createdBy, d.createdAt, d.closedAt, d.closedBy,
                expected, d.cashCounted, d.difference, d.notes, os.stream().map(this::toDto).toList(), total, delivered, returned, credit,
                byMethod.entrySet().stream().map(e -> new MethodTotal(e.getKey(), e.getValue())).toList(), pending);
    }

    public record CloseRequest(@NotNull @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal cashCounted,
                               @Size(max = 500) String notes, boolean returnPending) {
    }

    /**
     * Cierre de ruta: el conductor entrega el efectivo; se compara con lo cobrado en efectivo en las entregas. Si quien
     * cierra tiene la caja abierta, el efectivo entra a su turno como "Recaudo de ruta".
     */
    @Transactional
    public DispatchDto close(Long id, CloseRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Dispatch d = findDispatch(id);
        if (!"EN_RUTA".equals(d.status)) throw ApiException.conflict("El despacho #" + d.number + " ya se cerró");
        List<PresaleOrder> pending = orders.findByDispatchIdOrderByIdAsc(d.id).stream().filter(o -> "DESPACHADO".equals(o.status)).toList();
        if (!pending.isEmpty()) {
            if (!r.returnPending()) {
                throw ApiException.conflict("Faltan " + pending.size() + " pedidos por registrar: "
                        + pending.stream().map(o -> "#" + o.number + " " + o.customerName).collect(Collectors.joining(", ")));
            }
            for (PresaleOrder o : pending) {
                o.status = "DEVUELTO";
                o.deliveredAt = Instant.now();
                o.returnReason = "No se entregó en la ruta";
                orders.save(o);
            }
        }
        DispatchDto now = toDto(d);
        BigDecimal expected = now.collected().stream().filter(m -> "EFECTIVO".equals(m.method())).map(MethodTotal::amount).findFirst().orElse(BigDecimal.ZERO);
        AuthUser me = CurrentUser.get();
        d.status = "CERRADO";
        d.closedAt = Instant.now();
        d.closedBy = me.fullName();
        d.cashExpected = expected;
        d.cashCounted = r.cashCounted().setScale(2, RoundingMode.HALF_UP);
        d.difference = d.cashCounted.subtract(expected);
        d.notes = blank(r.notes());
        dispatches.save(d);
        var shift = shifts.openShiftOfCurrentUser().orElse(null);
        if (shift != null && d.cashCounted.signum() > 0) {
            CashMovement cm = new CashMovement();
            cm.tenantId = d.tenantId;
            cm.shiftId = shift.id;
            cm.type = "RECAUDO_RUTA";
            cm.amount = d.cashCounted;
            cm.reason = "Recaudo del despacho #" + d.number + " (" + d.driverName + ")";
            cm.userId = me.userId();
            cm.userName = me.fullName();
            cashMovements.save(cm);
        }
        audit.log("DESPACHO_CERRADO", "DISPATCH", d.id, "Cerró el despacho #" + d.number + ": esperado " + Money.fmt(expected) + ", entregó "
                + Money.fmt(d.cashCounted) + (d.difference.signum() == 0 ? " (cuadra)" : ", diferencia " + Money.fmt(d.difference)));
        return toDto(d);
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
