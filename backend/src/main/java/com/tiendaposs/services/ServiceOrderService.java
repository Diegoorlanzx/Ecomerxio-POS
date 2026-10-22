package com.tiendaposs.services;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.pos.CashMovement;
import com.tiendaposs.pos.PosRepos.CashMovementRepository;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleDtos.AccountCheckoutRequest;
import com.tiendaposs.pos.SaleDtos.PaymentRequest;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.services.ServicesRepos.*;
import com.tiendaposs.settings.SettingsService;
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
import java.util.*;

/**
 * Órdenes de servicio técnico: se recibe el equipo con su estado, se diagnostica y cotiza, el cliente aprueba, se repara
 * con repuestos del inventario y se entrega cobrando mano de obra + repuestos menos lo que dejó de anticipo.
 */
@Service
public class ServiceOrderService {

    public static final List<String> FLOW = List.of("RECIBIDO", "DIAGNOSTICO", "ESPERANDO_APROBACION", "EN_REPARACION", "LISTO", "ENTREGADO", "CANCELADO");

    private final ServiceOrderRepository orders;
    private final ServiceOrderPartRepository parts;
    private final ServiceOrderDepositRepository deposits;
    private final ServiceOrderEventRepository events;
    private final StaffRepository staff;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CustomerRepository customers;
    private final CashMovementRepository cashMovements;
    private final ShiftService shifts;
    private final SaleService saleService;
    private final ServicesService services;
    private final SettingsService settings;
    private final Counters counters;
    private final AuditService audit;

    public ServiceOrderService(ServiceOrderRepository orders, ServiceOrderPartRepository parts, ServiceOrderDepositRepository deposits,
                               ServiceOrderEventRepository events, StaffRepository staff, ProductRepository products,
                               PresentationRepository presentations, CustomerRepository customers, CashMovementRepository cashMovements,
                               ShiftService shifts, SaleService saleService, ServicesService services, SettingsService settings,
                               Counters counters, AuditService audit) {
        this.orders = orders;
        this.parts = parts;
        this.deposits = deposits;
        this.events = events;
        this.staff = staff;
        this.products = products;
        this.presentations = presentations;
        this.customers = customers;
        this.cashMovements = cashMovements;
        this.shifts = shifts;
        this.saleService = saleService;
        this.services = services;
        this.settings = settings;
        this.counters = counters;
        this.audit = audit;
    }

    public record PartDto(Long id, Long presentationId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    public record DepositDto(Long id, BigDecimal amount, String method, String reference, String userName, Instant createdAt) {
    }

    public record EventDto(String status, String note, String userName, Instant createdAt) {
    }

    public record OrderSummary(Long id, long number, String customerName, String customerPhone, String device, String status,
                               String quoteStatus, String technicianName, BigDecimal total, BigDecimal deposits, LocalDate promisedAt,
                               Instant createdAt, Instant updatedAt) {
    }

    public record OrderDto(Long id, long number, Long customerId, String customerName, String customerPhone, String device, String brand,
                           String model, String serial, String problem, String accessories, String conditionNotes, String diagnosis,
                           BigDecimal laborPrice, String quoteStatus, String status, Long technicianId, String technicianName,
                           int warrantyDays, LocalDate promisedAt, LocalDate warrantyUntil, Long saleId, Instant deliveredAt,
                           String createdBy, Instant createdAt, List<PartDto> parts, List<DepositDto> deposits, List<EventDto> events,
                           BigDecimal partsTotal, BigDecimal total, BigDecimal depositsTotal, BigDecimal balance, String whatsappUrl) {
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> list(String status) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = services.tenant();
        List<OrderSummary> out = new ArrayList<>();
        for (ServiceOrder o : orders.findByTenantIdOrderByCreatedAtDesc(t)) {
            if (status != null && !status.isBlank()) {
                boolean open = !"ENTREGADO".equals(o.status) && !"CANCELADO".equals(o.status);
                if ("ABIERTAS".equals(status) ? !open : !status.equals(o.status)) continue;
            }
            BigDecimal[] tot = totals(o);
            out.add(new OrderSummary(o.id, o.number, o.customerName, o.customerPhone, o.device + (o.model == null ? "" : " " + o.model),
                    o.status, o.quoteStatus, techName(o), tot[1], tot[2], o.promisedAt, o.createdAt, o.updatedAt));
            if (out.size() >= 300) break;
        }
        return out;
    }

    /** [repuestos, total, anticipos]. */
    BigDecimal[] totals(ServiceOrder o) {
        BigDecimal p = parts.findByOrderIdOrderByIdAsc(o.id).stream().map(x -> x.unitPrice.multiply(x.quantity)).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal d = deposits.findByOrderIdOrderByIdAsc(o.id).stream().map(x -> x.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BigDecimal[]{p, p.add(o.laborPrice), d};
    }

    String techName(ServiceOrder o) {
        return o.technicianId == null ? null : staff.findById(o.technicianId).map(m -> m.name).orElse(null);
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        CurrentUser.require(Permission.POS_VENDER);
        return toDto(find(id));
    }

    ServiceOrder find(Long id) {
        return orders.findByIdAndTenantId(id, services.tenant()).orElseThrow(() -> ApiException.notFound("Orden no encontrada"));
    }

    OrderDto toDto(ServiceOrder o) {
        BigDecimal[] tot = totals(o);
        List<PartDto> ps = parts.findByOrderIdOrderByIdAsc(o.id).stream()
                .map(p -> new PartDto(p.id, p.presentationId, p.name, p.quantity, p.unitPrice, p.unitPrice.multiply(p.quantity).setScale(2, RoundingMode.HALF_UP))).toList();
        List<DepositDto> ds = deposits.findByOrderIdOrderByIdAsc(o.id).stream()
                .map(d -> new DepositDto(d.id, d.amount, d.method, d.reference, d.userName, d.createdAt)).toList();
        List<EventDto> es = events.findByOrderIdOrderByIdAsc(o.id).stream().map(e -> new EventDto(e.status, e.note, e.userName, e.createdAt)).toList();
        LocalDate until = o.deliveredAt == null ? null : LocalDate.ofInstant(o.deliveredAt, services.zone(o.tenantId)).plusDays(o.warrantyDays);
        BigDecimal balance = tot[1].subtract(tot[2]).max(BigDecimal.ZERO);
        return new OrderDto(o.id, o.number, o.customerId, o.customerName, o.customerPhone, o.device, o.brand, o.model, o.serial, o.problem,
                o.accessories, o.conditionNotes, o.diagnosis, o.laborPrice, o.quoteStatus, o.status, o.technicianId, techName(o), o.warrantyDays,
                o.promisedAt, until, o.saleId, o.deliveredAt, o.createdBy, o.createdAt, ps, ds, es, tot[0], tot[1], tot[2], balance,
                whatsapp(o, tot[1], balance));
    }

    /** Mensaje para el cliente según cómo va la orden (cotización para aprobar, equipo listo…). */
    String whatsapp(ServiceOrder o, BigDecimal total, BigDecimal balance) {
        String phone = ServicesService.waPhone(o.customerPhone);
        if (phone == null) return null;
        String store = services.storeName(o.tenantId);
        String eq = o.device + (o.model == null ? "" : " " + o.model);
        String msg = switch (o.status) {
            case "ESPERANDO_APROBACION" -> "Hola " + first(o.customerName) + ", ya revisamos tu " + eq + " (orden #" + o.number + "). "
                    + (o.diagnosis == null ? "" : "Diagnóstico: " + o.diagnosis + ". ") + "La reparación cuesta " + Money.fmt(total)
                    + ". ¿Nos confirmas si la hacemos? — " + store;
            case "LISTO" -> "Hola " + first(o.customerName) + ", tu " + eq + " (orden #" + o.number + ") ya está listo para recoger en " + store + ". "
                    + (balance.signum() > 0 ? "Saldo a pagar: " + Money.fmt(balance) + "." : "Ya está pago.");
            case "ENTREGADO" -> "Hola " + first(o.customerName) + ", gracias por confiar en " + store + ". Tu " + eq + " tiene garantía de "
                    + o.warrantyDays + " días con la orden #" + o.number + ".";
            default -> "Hola " + first(o.customerName) + ", recibimos tu " + eq + " en " + store + " con la orden #" + o.number
                    + ". Te avisamos por aquí cómo va.";
        };
        return "https://wa.me/" + phone + "?text=" + URLEncoder.encode(msg, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String first(String name) {
        return name.trim().split(" ")[0];
    }

    public record OrderRequest(Long customerId,
                               @Size(max = 120) String customerName,
                               @Size(max = 30) String customerPhone,
                               @NotBlank(message = "¿qué equipo es?") @Size(max = 120) String device,
                               @Size(max = 60) String brand,
                               @Size(max = 80) String model,
                               @Size(max = 80) String serial,
                               @NotBlank(message = "describe la falla") @Size(max = 1000) String problem,
                               @Size(max = 300) String accessories,
                               @Size(max = 500) String conditionNotes,
                               @Size(max = 1000) String diagnosis,
                               @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal laborPrice,
                               Long technicianId,
                               @Min(value = 0, message = "no puede ser negativo") @Max(value = 730, message = "máximo 2 años") Integer warrantyDays,
                               LocalDate promisedAt) {
    }

    @Transactional
    public OrderDto create(OrderRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = services.tenant();
        ServiceOrder o = new ServiceOrder();
        o.tenantId = t;
        o.number = counters.next(t, "ORDEN_SERVICIO");
        o.createdBy = CurrentUser.get().fullName();
        fill(t, o, r);
        o = orders.save(o);
        event(o, "RECIBIDO", "Equipo recibido" + (o.accessories == null ? "" : " · accesorios: " + o.accessories));
        audit.log("ORDEN_SERVICIO", "SERVICE_ORDER", o.id, "Recibió " + o.device + " de " + o.customerName + " (orden #" + o.number + ")");
        return toDto(o);
    }

    @Transactional
    public OrderDto update(Long id, OrderRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        fill(o.tenantId, o, r);
        o.updatedAt = Instant.now();
        return toDto(orders.save(o));
    }

    private void fill(Long t, ServiceOrder o, OrderRequest r) {
        String name = ServicesService.blank(r.customerName()), phone = ServicesService.blank(r.customerPhone());
        if (r.customerId() != null) {
            var c = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
            o.customerId = c.id;
            if (name == null) name = c.name;
            if (phone == null) phone = c.phone;
        }
        if (name == null) throw ApiException.field("customerName", "escribe el nombre del cliente");
        o.customerName = name;
        o.customerPhone = phone;
        o.device = r.device().trim();
        o.brand = ServicesService.blank(r.brand());
        o.model = ServicesService.blank(r.model());
        o.serial = ServicesService.blank(r.serial());
        o.problem = r.problem().trim();
        o.accessories = ServicesService.blank(r.accessories());
        o.conditionNotes = ServicesService.blank(r.conditionNotes());
        o.diagnosis = ServicesService.blank(r.diagnosis());
        if (r.laborPrice() != null) o.laborPrice = r.laborPrice().setScale(2, RoundingMode.HALF_UP);
        if (r.technicianId() != null) staff.findByIdAndTenantId(r.technicianId(), t).orElseThrow(() -> ApiException.field("technicianId", "ese técnico no existe"));
        o.technicianId = r.technicianId();
        if (r.warrantyDays() != null) o.warrantyDays = r.warrantyDays();
        o.promisedAt = r.promisedAt();
    }

    private static void open(ServiceOrder o) {
        if ("ENTREGADO".equals(o.status) || "CANCELADO".equals(o.status)) {
            throw ApiException.conflict("La orden #" + o.number + " ya está " + o.status.toLowerCase());
        }
    }

    private void event(ServiceOrder o, String status, String note) {
        ServiceOrderEvent e = new ServiceOrderEvent();
        e.tenantId = o.tenantId;
        e.orderId = o.id;
        e.status = status;
        e.note = note == null ? null : note.length() > 500 ? note.substring(0, 500) : note;
        e.userName = CurrentUser.get().fullName();
        events.save(e);
    }

    public record StatusRequest(@NotBlank String status, @Size(max = 500) String note) {
    }

    /** Cambia el estado. ESPERANDO_APROBACION deja la cotización pendiente; la entrega se hace con deliver (cobra). */
    @Transactional
    public OrderDto setStatus(Long id, StatusRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        String st = r.status().trim().toUpperCase();
        if (!FLOW.contains(st) || "ENTREGADO".equals(st)) throw ApiException.field("status", "estado no válido");
        if ("ESPERANDO_APROBACION".equals(st)) {
            if (o.diagnosis == null) throw ApiException.field("diagnosis", "escribe el diagnóstico antes de pedir aprobación");
            o.quoteStatus = "PENDIENTE";
        }
        o.status = st;
        o.updatedAt = Instant.now();
        orders.save(o);
        event(o, st, ServicesService.blank(r.note()));
        audit.log("ORDEN_SERVICIO_ESTADO", "SERVICE_ORDER", o.id, "Orden #" + o.number + ": " + st.toLowerCase().replace('_', ' '));
        return toDto(o);
    }

    public record QuoteDecision(boolean approved, @Size(max = 500) String note) {
    }

    /** El cliente aprobó (pasa a reparación) o rechazó la cotización (queda lista para devolver el equipo). */
    @Transactional
    public OrderDto decide(Long id, QuoteDecision r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        o.quoteStatus = r.approved() ? "APROBADA" : "RECHAZADA";
        o.status = r.approved() ? "EN_REPARACION" : "LISTO";
        o.updatedAt = Instant.now();
        orders.save(o);
        event(o, o.status, (r.approved() ? "El cliente aprobó la reparación" : "El cliente no aprobó la reparación: se devuelve el equipo")
                + (ServicesService.blank(r.note()) == null ? "" : " · " + r.note().trim()));
        return toDto(o);
    }

    public record PartRequest(@NotNull Long presentationId,
                              @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal quantity,
                              @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitPrice) {
    }

    @Transactional
    public OrderDto addPart(Long id, PartRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        Presentation pr = presentations.findByIdAndTenantId(r.presentationId(), o.tenantId).orElseThrow(() -> ApiException.field("presentationId", "el repuesto no existe"));
        Product p = products.findById(pr.productId).orElseThrow();
        ServiceOrderPart part = new ServiceOrderPart();
        part.tenantId = o.tenantId;
        part.orderId = o.id;
        part.presentationId = pr.id;
        part.name = p.name + ("Unidad".equals(pr.name) ? "" : " (" + pr.name + ")");
        part.quantity = r.quantity().setScale(3, RoundingMode.HALF_UP);
        part.unitPrice = (r.unitPrice() == null ? pr.price : r.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        parts.save(part);
        o.updatedAt = Instant.now();
        orders.save(o);
        return toDto(o);
    }

    @Transactional
    public OrderDto removePart(Long id, Long partId) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        ServiceOrderPart part = parts.findById(partId).filter(x -> x.orderId.equals(o.id)).orElseThrow(() -> ApiException.notFound("Repuesto no encontrado"));
        parts.delete(part);
        return toDto(o);
    }

    public record DepositRequest(@NotNull @DecimalMin(value = "1", message = "debe ser mayor que cero") BigDecimal amount,
                                 @NotBlank String method, @Size(max = 80) String reference) {
    }

    /**
     * Anticipo: el dinero entra hoy (en efectivo queda en la caja del turno) y al entregar se descuenta del cobro, sin
     * volver a contarse como ingreso.
     */
    @Transactional
    public OrderDto addDeposit(Long id, DepositRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        String m = r.method().trim().toUpperCase();
        if ("FIADO".equals(m) || !settings.forTenant(o.tenantId).paymentMethodList().contains(m)) throw ApiException.field("method", "medio de pago no válido");
        AuthUser me = CurrentUser.get();
        var shift = shifts.openShiftOfCurrentUser().orElse(null);
        if ("EFECTIVO".equals(m) && shift == null) throw ApiException.conflict("Abre tu turno de caja para recibir el anticipo en efectivo");
        ServiceOrderDeposit d = new ServiceOrderDeposit();
        d.tenantId = o.tenantId;
        d.orderId = o.id;
        d.amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        d.method = m;
        d.reference = ServicesService.blank(r.reference());
        d.shiftId = shift == null ? null : shift.id;
        d.userName = me.fullName();
        deposits.save(d);
        if ("EFECTIVO".equals(m)) {
            CashMovement cm = new CashMovement();
            cm.tenantId = o.tenantId;
            cm.shiftId = shift.id;
            cm.type = "ANTICIPO";
            cm.amount = d.amount;
            cm.reason = "Anticipo orden #" + o.number + " (" + o.customerName + ")";
            cm.userId = me.userId();
            cm.userName = me.fullName();
            cashMovements.save(cm);
        }
        event(o, o.status, "Anticipo de " + Money.fmt(d.amount) + " por " + m.toLowerCase());
        audit.log("ORDEN_ANTICIPO", "SERVICE_ORDER", o.id, "Anticipo de " + Money.fmt(d.amount) + " en la orden #" + o.number);
        return toDto(o);
    }

    public record DeliverRequest(List<@Valid PaymentRequest> payments, @Size(max = 500) String notes) {
    }

    public record DeliverResult(OrderDto order, SaleDto sale) {
    }

    /**
     * Entrega el equipo y cobra: mano de obra + repuestos (salen del inventario) menos los anticipos. La comisión de la
     * mano de obra queda a nombre del técnico.
     */
    @Transactional
    public DeliverResult deliver(Long id, DeliverRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        ServiceOrder o = find(id);
        open(o);
        if (!"LISTO".equals(o.status)) throw ApiException.conflict("Marca la orden como lista antes de entregarla");
        List<ServiceOrderPart> ps = parts.findByOrderIdOrderByIdAsc(o.id);
        BigDecimal[] tot = totals(o);
        SaleDto sale = null;
        if (tot[1].signum() > 0) {
            if (tot[2].compareTo(tot[1]) > 0) {
                throw ApiException.conflict("El anticipo (" + Money.fmt(tot[2]) + ") es mayor que el total (" + Money.fmt(tot[1]) + "): ajusta el valor de la orden");
            }
            Sale s = saleService.openServiceAccount("Orden #" + o.number + " · " + o.device, o.customerId, o.technicianId);
            for (ServiceOrderPart p : ps) saleService.addServiceLine(s.id, p.presentationId, p.quantity, p.unitPrice, null, null);
            if (o.laborPrice.signum() > 0) {
                saleService.addServiceLine(s.id, laborPresentation(o.tenantId).id, BigDecimal.ONE, o.laborPrice,
                        o.diagnosis == null ? o.device : o.device + ": " + o.diagnosis, o.technicianId);
            }
            List<PaymentRequest> pays = new ArrayList<>(r.payments() == null ? List.of() : r.payments());
            if (tot[2].signum() > 0) pays.add(new PaymentRequest("ANTICIPO", tot[2], "Orden #" + o.number));
            if (pays.isEmpty()) throw ApiException.field("payments", "registra el pago del saldo (" + Money.fmt(tot[1]) + ")");
            sale = saleService.checkoutAccountWithAdvance(s.id, new AccountCheckoutRequest(null, null, null, pays,
                    ServicesService.blank(r.notes()) == null ? "Orden de servicio #" + o.number : r.notes().trim(), null));
            o.saleId = sale.id();
        }
        o.status = "ENTREGADO";
        o.deliveredAt = Instant.now();
        o.updatedAt = o.deliveredAt;
        orders.save(o);
        event(o, "ENTREGADO", sale == null ? "Entregado sin cobro" : "Entregado y cobrado en la venta #" + sale.number());
        audit.log("ORDEN_ENTREGADA", "SERVICE_ORDER", o.id, "Entregó la orden #" + o.number + (sale == null ? "" : " por " + Money.fmt(sale.total())));
        return new DeliverResult(toDto(o), sale);
    }

    /** Producto interno de mano de obra (sin inventario); se crea la primera vez. */
    Presentation laborPresentation(Long t) {
        Product p = products.findBySku(t, LaborProduct.SKU).orElse(null);
        if (p == null) {
            p = new Product();
            p.tenantId = t;
            p.sku = LaborProduct.SKU;
            p.name = LaborProduct.NAME;
            p.trackStock = false;
            p.serviceMinutes = 0;
            p.onlineVisible = false;
            p = products.save(p);
            Presentation pr = new Presentation();
            pr.tenantId = t;
            pr.productId = p.id;
            pr.name = "Servicio";
            pr.price = BigDecimal.ZERO;
            pr.isBase = true;
            return presentations.save(pr);
        }
        return presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).get(0);
    }
}
