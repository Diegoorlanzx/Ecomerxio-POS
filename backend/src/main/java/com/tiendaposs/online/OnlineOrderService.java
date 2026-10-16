package com.tiendaposs.online;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.*;
import com.tiendaposs.catalog.ComboComponent;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.notifications.NotificationService;
import com.tiendaposs.notifications.NotificationType;
import com.tiendaposs.online.OnlineRepos.*;
import com.tiendaposs.payments.PaymentRepos.PaymentAccountRepository;
import com.tiendaposs.pos.Customer;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.SaleDtos.PaymentRequest;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pedidos de la tienda en línea. Llegan como NUEVO; al aceptarlos se aparta el inventario; al entregarlos se
 * cobran como una venta normal del POS (caja, inventario, analítica y rentabilidad los ven igual que cualquier venta).
 */
@Service
public class OnlineOrderService {

    public static final String NUEVO = "NUEVO", ACEPTADO = "ACEPTADO", PREPARANDO = "PREPARANDO", LISTO = "LISTO",
            EN_CAMINO = "EN_CAMINO", ENTREGADO = "ENTREGADO", CANCELADO = "CANCELADO";
    public static final List<String> ACTIVE = List.of(NUEVO, ACEPTADO, PREPARANDO, LISTO, EN_CAMINO);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OnlineStoreService storeService;
    private final OnlineOrderRepository orders;
    private final OnlineOrderLineRepository lines;
    private final OnlineReservationRepository reservations;
    private final DeliveryZoneRepository zones;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final ComboComponentRepository combos;
    private final CustomerRepository customers;
    private final PaymentAccountRepository accounts;
    private final TenantRepository tenants;
    private final SettingsService settings;
    private final SaleService sales;
    private final Counters counters;
    private final AuditService audit;
    private final NotificationService notifications;

    public OnlineOrderService(OnlineStoreService storeService, OnlineOrderRepository orders, OnlineOrderLineRepository lines,
                              OnlineReservationRepository reservations, DeliveryZoneRepository zones, ProductRepository products,
                              PresentationRepository presentations, ComboComponentRepository combos, CustomerRepository customers,
                              PaymentAccountRepository accounts, TenantRepository tenants, SettingsService settings, SaleService sales,
                              Counters counters, AuditService audit, NotificationService notifications) {
        this.storeService = storeService;
        this.orders = orders;
        this.lines = lines;
        this.reservations = reservations;
        this.zones = zones;
        this.products = products;
        this.presentations = presentations;
        this.combos = combos;
        this.customers = customers;
        this.accounts = accounts;
        this.tenants = tenants;
        this.settings = settings;
        this.sales = sales;
        this.counters = counters;
        this.audit = audit;
        this.notifications = notifications;
    }

    // =====================================================================
    // Público: hacer y seguir un pedido
    // =====================================================================

    public record ItemRequest(@NotNull Long presentationId,
                              @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") @DecimalMax("999") BigDecimal quantity) {
    }

    public record OrderRequest(@NotBlank(message = "escribe tu nombre") @Size(max = 120) String name,
                               @NotBlank(message = "escribe tu celular") @Pattern(regexp = "[0-9 +()-]{7,20}", message = "celular no válido") String phone,
                               @Email(message = "correo no válido") @Size(max = 160) String email,
                               @NotBlank @Pattern(regexp = "DOMICILIO|RECOGER") String fulfillment,
                               Long zoneId, @Size(max = 300) String address, @Size(max = 500) String notes,
                               @NotBlank String payMethod,
                               @NotNull @Size(min = 1, max = 60, message = "agrega al menos un producto") List<@Valid ItemRequest> items) {
    }

    public record LineDto(Long id, Long productId, Long presentationId, String productName, String presentationName, BigDecimal factor,
                          BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record PayInfo(String method, String label, String holder, String accountRef, String qrImage) {
    }

    public record PublicOrderDto(String token, Long number, String status, String storeName, String storeSlug, String customerName,
                                 String fulfillment, String zoneName, String address, String payMethod, String payLabel,
                                 BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, List<LineDto> lines,
                                 List<PayInfo> payInfo, String whatsappUrl, int prepMinutes, Instant createdAt, Instant acceptedAt,
                                 Instant readyAt, Instant dispatchedAt, Instant deliveredAt, Instant cancelledAt, String cancelReason) {
    }

    @Transactional
    public PublicOrderDto place(String slug, OrderRequest r) {
        OnlineStore s = storeService.published(slug);
        Long t = s.tenantId;
        if (!"PEDIDOS".equals(s.mode)) throw ApiException.badRequest("Esta tienda muestra su carta, pero no recibe pedidos en línea");
        var os = storeService.openState(s, storeService.zone(t));
        if (!os.open()) throw ApiException.conflict(os.text());
        if (!s.payMethodList().contains(r.payMethod())) throw ApiException.field("payMethod", "elige una forma de pago de la lista");
        String phone = r.phone().replaceAll("[^0-9+]", "");
        // Freno simple a pedidos repetidos desde el mismo celular.
        if (orders.recentByPhone(t, phone, Instant.now().minus(10, ChronoUnit.MINUTES)).stream().filter(o -> ACTIVE.contains(o.status)).count() >= 3) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Ya tienes varios pedidos en curso. Escríbele a la tienda si necesitas algo más.");
        }
        StoreSettings st = settings.forTenant(t);
        DeliveryZone zone = null;
        if ("DOMICILIO".equals(r.fulfillment())) {
            if (!s.deliveryEnabled) throw ApiException.field("fulfillment", "esta tienda no hace domicilios");
            if (CatalogBlank.of(r.address()) == null) throw ApiException.field("address", "escribe la dirección de entrega");
            List<DeliveryZone> active = zones.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().filter(z -> z.active).toList();
            if (!active.isEmpty()) {
                if (r.zoneId() == null) throw ApiException.field("zoneId", "elige tu barrio o zona");
                zone = active.stream().filter(z -> z.id.equals(r.zoneId())).findFirst()
                        .orElseThrow(() -> ApiException.field("zoneId", "no llegamos a esa zona"));
            }
        } else if (!s.pickupEnabled) {
            throw ApiException.field("fulfillment", "esta tienda solo entrega a domicilio");
        }

        Map<Long, Product> byId = new HashMap<>();
        products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").forEach(p -> byId.put(p.id, p));
        Map<Long, List<ComboComponent>> comps = combos.findByTenantId(t).stream().collect(Collectors.groupingBy(c -> c.comboId));
        Map<Long, BigDecimal> need = new HashMap<>();
        List<OnlineOrderLine> ls = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int order = 0;
        for (ItemRequest it : r.items()) {
            Presentation pr = presentations.findByIdAndTenantId(it.presentationId(), t).filter(x -> x.active)
                    .orElseThrow(() -> ApiException.badRequest("Un producto del carrito ya no está disponible. Actualiza la página."));
            Product p = byId.get(pr.productId);
            Product parent = p == null || p.parentId == null ? null : byId.get(p.parentId);
            if (p == null || !p.onlineVisible || p.trackSerials || !p.sellable() || (parent != null && !parent.onlineVisible)) {
                throw ApiException.badRequest("Un producto del carrito ya no está disponible. Actualiza la página.");
            }
            if ("UNIDAD".equals(p.unit) && pr.factor.stripTrailingZeros().scale() <= 0 && it.quantity().stripTrailingZeros().scale() > 0) {
                throw ApiException.badRequest(p.name + " se vende por unidades enteras");
            }
            BigDecimal price = pr.onlinePrice != null ? pr.onlinePrice : pr.price;
            OnlineOrderLine l = new OnlineOrderLine();
            l.tenantId = t;
            l.productId = p.id;
            l.presentationId = pr.id;
            l.productName = p.name;
            l.presentationName = pr.name;
            l.factor = pr.factor;
            l.quantity = it.quantity().setScale(3, RoundingMode.HALF_UP);
            l.unitPrice = price;
            l.lineTotal = price.multiply(l.quantity).setScale(2, RoundingMode.HALF_UP);
            l.sortOrder = order++;
            ls.add(l);
            subtotal = subtotal.add(l.lineTotal);
            addNeed(p, l.quantity.multiply(l.factor), byId, comps, need);
        }
        if (!st.allowNegativeStock) {
            for (var e : need.entrySet()) {
                Product p = byId.get(e.getKey());
                BigDecimal avail = p.stock.subtract(p.reserved);
                if (avail.compareTo(e.getValue()) < 0) {
                    throw ApiException.conflict(avail.signum() <= 0 ? p.name + " se agotó. Quítalo del carrito."
                            : "De " + p.name + " solo quedan " + plain(avail) + ". Ajusta la cantidad.");
                }
            }
        }
        BigDecimal min = s.minOrder.max(zone == null ? BigDecimal.ZERO : zone.minOrder);
        if (subtotal.compareTo(min) < 0) throw ApiException.badRequest("El pedido mínimo es " + Money.fmt(min));

        OnlineOrder o = new OnlineOrder();
        o.tenantId = t;
        o.number = counters.next(t, "PEDIDO_ONLINE");
        o.token = token();
        o.customerName = r.name().trim();
        o.phone = phone;
        o.email = CatalogBlank.of(r.email());
        o.fulfillment = r.fulfillment();
        o.zoneId = zone == null ? null : zone.id;
        o.zoneName = zone == null ? null : zone.name;
        o.address = "DOMICILIO".equals(r.fulfillment()) ? r.address().trim() : null;
        o.notes = CatalogBlank.of(r.notes());
        o.payMethod = r.payMethod();
        o.subtotal = subtotal;
        o.deliveryFee = zone == null || !"DOMICILIO".equals(r.fulfillment()) ? BigDecimal.ZERO : zone.fee;
        o.total = o.subtotal.add(o.deliveryFee);
        o.customerId = customerFor(t, o).id;
        o = orders.save(o);
        for (OnlineOrderLine l : ls) {
            l.orderId = o.id;
            lines.save(l);
        }
        audit.logInCurrentTx(t, null, o.customerName, "PEDIDO_ONLINE", "Pedido en línea #" + o.number + " de " + o.customerName
                + " por " + Money.fmt(o.total) + ("DOMICILIO".equals(o.fulfillment) ? " (domicilio)" : " (recoge en tienda)"));
        notifications.notifyNow(t, NotificationType.PEDIDO_ONLINE, "Pedido en línea #" + o.number + " · " + Money.fmt(o.total),
                o.customerName + " · " + ("DOMICILIO".equals(o.fulfillment) ? "domicilio a " + o.address : "recoge en la tienda")
                        + " · " + OnlineStoreService.PAY_LABELS.getOrDefault(o.payMethod, o.payMethod), "/pedidos", "PEDIDO_ONLINE:" + o.id);
        return toPublic(o, s);
    }

    private void addNeed(Product p, BigDecimal baseQty, Map<Long, Product> byId, Map<Long, List<ComboComponent>> comps, Map<Long, BigDecimal> need) {
        if ("COMBO".equals(p.productType)) {
            for (ComboComponent c : comps.getOrDefault(p.id, List.of())) {
                Product x = byId.get(c.componentId);
                if (x != null && x.trackStock) need.merge(x.id, c.quantity.multiply(baseQty), BigDecimal::add);
            }
        } else if (p.trackStock) {
            need.merge(p.id, baseQty, BigDecimal::add);
        }
    }

    /** El cliente queda registrado (por su celular) para la próxima vez y para el historial. */
    private Customer customerFor(Long t, OnlineOrder o) {
        Customer c = customers.findFirstByTenantIdAndPhoneOrderByIdAsc(t, o.phone).orElse(null);
        if (c == null) {
            c = new Customer();
            c.tenantId = t;
            c.name = o.customerName;
            c.phone = o.phone;
            c.email = o.email;
            c.address = o.address;
            return customers.save(c);
        }
        if (c.email == null && o.email != null) c.email = o.email;
        if (o.address != null) c.address = o.address;
        return c;
    }

    @Transactional(readOnly = true)
    public PublicOrderDto track(String token) {
        OnlineOrder o = orders.findByToken(token == null ? "" : token).orElseThrow(() -> ApiException.notFound("No encontramos ese pedido"));
        OnlineStore s = storeService.storeOf(o.tenantId);
        return toPublic(o, s);
    }

    private PublicOrderDto toPublic(OnlineOrder o, OnlineStore s) {
        Tenant tenant = tenants.findById(o.tenantId).orElseThrow();
        List<LineDto> ls = lineDtos(o);
        List<PayInfo> info = List.of();
        if (!"CONTRA_ENTREGA".equals(o.payMethod)) {
            info = accounts.findByTenantIdOrderByIdAsc(o.tenantId).stream().filter(a -> a.active && a.method.equals(o.payMethod))
                    .map(a -> new PayInfo(a.method, a.label, a.holder, a.accountRef, a.qrImage)).toList();
        }
        return new PublicOrderDto(o.token, o.number, o.status, tenant.getName(), s.slug, o.customerName, o.fulfillment, o.zoneName,
                o.address, o.payMethod, OnlineStoreService.PAY_LABELS.getOrDefault(o.payMethod, o.payMethod), o.subtotal, o.deliveryFee,
                o.total, ls, info, whatsappUrl(s, tenant, o, ls), s.prepMinutes, o.createdAt, o.acceptedAt, o.readyAt, o.dispatchedAt,
                o.deliveredAt, o.cancelledAt, o.cancelReason);
    }

    /** Resumen del pedido para enviarlo también por WhatsApp a la tienda. */
    private String whatsappUrl(OnlineStore s, Tenant tenant, OnlineOrder o, List<LineDto> ls) {
        String number = Optional.ofNullable(s.whatsapp).or(() -> Optional.ofNullable(tenant.getWhatsapp())).orElse(null);
        if (number == null) return null;
        String digits = number.replaceAll("\\D", "");
        if (digits.length() == 10) digits = "57" + digits;
        StringBuilder b = new StringBuilder("Hola, hice el pedido #" + o.number + " en la tienda en línea:\n");
        for (LineDto l : ls) {
            b.append("• ").append(plain(l.quantity())).append(" x ").append(l.productName())
                    .append("Unidad".equals(l.presentationName()) || "Kilo".equals(l.presentationName()) ? "" : " (" + l.presentationName() + ")")
                    .append(" = ").append(Money.fmt(l.lineTotal())).append('\n');
        }
        if (o.deliveryFee.signum() > 0) b.append("Domicilio: ").append(Money.fmt(o.deliveryFee)).append('\n');
        b.append("Total: ").append(Money.fmt(o.total)).append('\n');
        b.append("DOMICILIO".equals(o.fulfillment) ? "Entregar en: " + o.address + (o.zoneName == null ? "" : " (" + o.zoneName + ")") : "Recojo en la tienda").append('\n');
        b.append("Pago: ").append(OnlineStoreService.PAY_LABELS.getOrDefault(o.payMethod, o.payMethod)).append('\n');
        b.append("A nombre de ").append(o.customerName);
        return "https://wa.me/" + digits + "?text=" + URLEncoder.encode(b.toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    // =====================================================================
    // Personal de la tienda: bandeja de pedidos
    // =====================================================================

    public record OrderDto(Long id, Long number, String token, String status, Long customerId, String customerName, String phone,
                           String email, String fulfillment, String zoneName, String address, String notes, String payMethod,
                           String payLabel, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, Long saleId,
                           String cancelReason, String handledBy, List<LineDto> lines, Instant createdAt, Instant acceptedAt,
                           Instant readyAt, Instant dispatchedAt, Instant deliveredAt, Instant cancelledAt) {
    }

    public record Counts(long nuevos, long activos) {
    }

    public record CancelRequest(@NotBlank(message = "escribe el motivo") @Size(max = 300) String reason) {
    }

    public record DeliverRequest(@NotNull @Size(min = 1) List<@Valid PaymentRequest> payments) {
    }

    @Transactional(readOnly = true)
    public List<OrderDto> list(String scope) {
        Long t = TenantContext.require();
        List<OnlineOrder> list = "HISTORIAL".equals(scope)
                ? orders.findTop100ByTenantIdAndStatusInOrderByCreatedAtDesc(t, List.of(ENTREGADO, CANCELADO))
                : orders.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, ACTIVE);
        return list.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Counts counts() {
        Long t = TenantContext.require();
        return new Counts(orders.countByTenantIdAndStatus(t, NUEVO), orders.findByTenantIdAndStatusInOrderByCreatedAtAsc(t, ACTIVE).size());
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        return toDto(find(id));
    }

    /** Aceptar: aparta las existencias para que el mostrador no venda lo que ya es del cliente. */
    @Transactional
    public OrderDto accept(Long id) {
        OnlineOrder o = find(id);
        if (!NUEVO.equals(o.status)) throw ApiException.badRequest("El pedido ya fue " + label(o.status).toLowerCase());
        StoreSettings st = settings.forTenant(o.tenantId);
        Map<Long, BigDecimal> need = new HashMap<>();
        Map<Long, Product> byId = new HashMap<>();
        Map<Long, List<ComboComponent>> comps = combos.findByTenantId(o.tenantId).stream().collect(Collectors.groupingBy(c -> c.comboId));
        for (OnlineOrderLine l : lines.findByOrderIdOrderBySortOrderAscIdAsc(o.id)) {
            Product p = products.findById(l.productId).orElseThrow();
            byId.put(p.id, p);
            if ("COMBO".equals(p.productType)) {
                comps.getOrDefault(p.id, List.of()).forEach(c -> products.findById(c.componentId).ifPresent(x -> byId.put(x.id, x)));
            }
            addNeed(p, l.quantity.multiply(l.factor), byId, comps, need);
        }
        for (var e : need.entrySet()) {
            Product p = products.lockById(e.getKey(), o.tenantId).orElseThrow();
            BigDecimal avail = p.stock.subtract(p.reserved);
            if (!st.allowNegativeStock && avail.compareTo(e.getValue()) < 0) {
                throw ApiException.conflict("No alcanza " + p.name + ": se necesitan " + plain(e.getValue()) + " y hay " + plain(avail.max(BigDecimal.ZERO))
                        + " disponibles. Llama al cliente para cambiarlo o cancela el pedido.");
            }
            p.reserved = p.reserved.add(e.getValue());
            OnlineReservation r = new OnlineReservation();
            r.tenantId = o.tenantId;
            r.orderId = o.id;
            r.productId = p.id;
            r.quantity = e.getValue();
            reservations.save(r);
        }
        o.status = ACEPTADO;
        o.acceptedAt = Instant.now();
        o.handledBy = CurrentUser.get().fullName();
        audit.log("PEDIDO_ACEPTADO", "ONLINE_ORDER", o.id, "Aceptó el pedido en línea #" + o.number + " de " + o.customerName);
        return toDto(o);
    }

    @Transactional
    public OrderDto advance(Long id, String to) {
        OnlineOrder o = find(id);
        Set<String> from = switch (to) {
            case PREPARANDO -> Set.of(ACEPTADO);
            case LISTO -> Set.of(ACEPTADO, PREPARANDO);
            case EN_CAMINO -> Set.of(ACEPTADO, PREPARANDO, LISTO);
            default -> throw ApiException.badRequest("Estado no válido");
        };
        if (!from.contains(o.status)) throw ApiException.badRequest("El pedido está " + label(o.status).toLowerCase() + ": no puede pasar a " + label(to).toLowerCase());
        if (EN_CAMINO.equals(to) && !"DOMICILIO".equals(o.fulfillment)) throw ApiException.badRequest("Este pedido se recoge en la tienda");
        o.status = to;
        if (LISTO.equals(to)) o.readyAt = Instant.now();
        if (EN_CAMINO.equals(to)) o.dispatchedAt = Instant.now();
        return toDto(o);
    }

    @Transactional
    public OrderDto cancel(Long id, CancelRequest r) {
        OnlineOrder o = find(id);
        if (!ACTIVE.contains(o.status)) throw ApiException.badRequest("El pedido ya fue " + label(o.status).toLowerCase());
        release(o);
        o.status = CANCELADO;
        o.cancelledAt = Instant.now();
        o.cancelReason = r.reason().trim();
        o.handledBy = CurrentUser.get().fullName();
        audit.log("PEDIDO_CANCELADO", "ONLINE_ORDER", o.id, "Canceló el pedido en línea #" + o.number + ". Motivo: " + o.cancelReason);
        return toDto(o);
    }

    /** Entregar y cobrar: se libera lo apartado y se registra la venta (descuenta el inventario como cualquier venta). */
    @Transactional
    public SaleDto deliver(Long id, DeliverRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        OnlineOrder o = find(id);
        if (!ACTIVE.contains(o.status)) throw ApiException.badRequest("El pedido ya fue " + label(o.status).toLowerCase());
        release(o);
        List<SaleService.OnlineLine> items = lines.findByOrderIdOrderBySortOrderAscIdAsc(o.id).stream()
                .map(l -> new SaleService.OnlineLine(l.presentationId, l.quantity, l.unitPrice)).toList();
        SaleDto sale = sales.checkoutOnline(o.number, items, o.deliveryFee, o.customerId, r.payments(), "ONLINE-" + o.id);
        o.saleId = sale.id();
        o.status = ENTREGADO;
        o.deliveredAt = Instant.now();
        o.handledBy = CurrentUser.get().fullName();
        audit.log("PEDIDO_ENTREGADO", "ONLINE_ORDER", o.id, "Entregó y cobró el pedido en línea #" + o.number + ": venta #" + sale.number());
        return sale;
    }

    private void release(OnlineOrder o) {
        for (OnlineReservation r : reservations.findByOrderId(o.id)) {
            products.lockById(r.productId, o.tenantId).ifPresent(p -> p.reserved = p.reserved.subtract(r.quantity).max(BigDecimal.ZERO));
        }
        reservations.deleteByOrderId(o.id);
    }

    private OnlineOrder find(Long id) {
        return orders.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Pedido no encontrado"));
    }

    private List<LineDto> lineDtos(OnlineOrder o) {
        return lines.findByOrderIdOrderBySortOrderAscIdAsc(o.id).stream().map(l -> new LineDto(l.id, l.productId, l.presentationId,
                l.productName, l.presentationName, l.factor, l.quantity, l.unitPrice, l.lineTotal)).toList();
    }

    private OrderDto toDto(OnlineOrder o) {
        return new OrderDto(o.id, o.number, o.token, o.status, o.customerId, o.customerName, o.phone, o.email, o.fulfillment, o.zoneName,
                o.address, o.notes, o.payMethod, OnlineStoreService.PAY_LABELS.getOrDefault(o.payMethod, o.payMethod), o.subtotal,
                o.deliveryFee, o.total, o.saleId, o.cancelReason, o.handledBy, lineDtos(o), o.createdAt, o.acceptedAt, o.readyAt,
                o.dispatchedAt, o.deliveredAt, o.cancelledAt);
    }

    static String label(String status) {
        return switch (status) {
            case NUEVO -> "Nuevo";
            case ACEPTADO -> "Aceptado";
            case PREPARANDO -> "En preparación";
            case LISTO -> "Listo";
            case EN_CAMINO -> "En camino";
            case ENTREGADO -> "Entregado";
            default -> "Cancelado";
        };
    }

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** Texto vacío → null. */
    static final class CatalogBlank {
        static String of(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }
}
