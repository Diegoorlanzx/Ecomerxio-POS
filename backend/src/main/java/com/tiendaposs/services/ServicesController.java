package com.tiendaposs.services;

import com.tiendaposs.auth.RateLimiter;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.services.PublicBookingService.*;
import com.tiendaposs.services.ServiceOrderService.*;
import com.tiendaposs.services.ServicesService.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Fase 11 — Servicios: catálogo de servicios, profesionales, agenda, comisiones y órdenes de servicio técnico. */
@RestController
public class ServicesController {

    private final ServicesService services;
    private final ServiceOrderService orders;
    private final PublicBookingService booking;
    private final RateLimiter limiter;

    public ServicesController(ServicesService services, ServiceOrderService orders, PublicBookingService booking, RateLimiter limiter) {
        this.services = services;
        this.orders = orders;
        this.booking = booking;
        this.limiter = limiter;
    }

    // ---------- Catálogo y profesionales ----------

    @GetMapping("/api/services/catalog")
    public List<ServiceDto> catalog() {
        return services.services();
    }

    @PutMapping("/api/services/catalog/{productId}")
    public ServiceDto saveService(@PathVariable Long productId, @Valid @RequestBody ServiceSettingsRequest r) {
        return services.saveService(productId, r);
    }

    @GetMapping("/api/services/staff")
    public List<StaffDto> staff() {
        return services.staffList();
    }

    @PostMapping("/api/services/staff")
    public StaffDto createStaff(@Valid @RequestBody StaffRequest r) {
        return services.saveStaff(null, r);
    }

    @PutMapping("/api/services/staff/{id}")
    public StaffDto updateStaff(@PathVariable Long id, @Valid @RequestBody StaffRequest r) {
        return services.saveStaff(id, r);
    }

    @PutMapping("/api/services/booking")
    public BookingSettings saveBooking(@Valid @RequestBody BookingSettings r) {
        return services.saveBooking(r);
    }

    // ---------- Agenda ----------

    @GetMapping("/api/services/agenda")
    public AgendaDto agenda(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam(defaultValue = "1") int days) {
        return services.agenda(date, days);
    }

    @GetMapping("/api/services/slots")
    public List<SlotDto> slots(@RequestParam Long serviceId, @RequestParam(required = false) Long staffId,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return services.slots(serviceId, staffId, date);
    }

    @PostMapping("/api/services/appointments")
    public AppointmentDto createAppointment(@Valid @RequestBody AppointmentRequest r) {
        return services.createAppointment(r);
    }

    @PutMapping("/api/services/appointments/{id}")
    public AppointmentDto updateAppointment(@PathVariable Long id, @Valid @RequestBody AppointmentRequest r) {
        return services.updateAppointment(id, r);
    }

    @PostMapping("/api/services/appointments/{id}/status")
    public AppointmentDto appointmentStatus(@PathVariable Long id, @Valid @RequestBody ServicesService.StatusRequest r) {
        return services.setStatus(id, r);
    }

    @PostMapping("/api/services/appointments/{id}/reminder")
    public AppointmentDto reminder(@PathVariable Long id) {
        return services.markReminder(id);
    }

    @PostMapping("/api/services/appointments/{id}/charge")
    public SaleDto charge(@PathVariable Long id) {
        return services.charge(id);
    }

    // ---------- Comisiones ----------

    @GetMapping("/api/services/commissions")
    public CommissionReport commissions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return services.commissions(from, to);
    }

    @PostMapping("/api/services/commissions/pay")
    public CommissionReport pay(@Valid @RequestBody PayRequest r) {
        return services.pay(r);
    }

    // ---------- Órdenes de servicio ----------

    @GetMapping("/api/service-orders/history")
    public List<OrderSummary> orderHistory(@RequestParam String serial) {
        return orders.history(serial);
    }

    @GetMapping("/api/service-orders")
    public List<OrderSummary> orders(@RequestParam(required = false) String status) {
        return orders.list(status);
    }

    @GetMapping("/api/service-orders/{id}")
    public OrderDto order(@PathVariable Long id) {
        return orders.get(id);
    }

    @PostMapping("/api/service-orders")
    public OrderDto createOrder(@Valid @RequestBody OrderRequest r) {
        return orders.create(r);
    }

    @PutMapping("/api/service-orders/{id}")
    public OrderDto updateOrder(@PathVariable Long id, @Valid @RequestBody OrderRequest r) {
        return orders.update(id, r);
    }

    @PostMapping("/api/service-orders/{id}/status")
    public OrderDto orderStatus(@PathVariable Long id, @Valid @RequestBody ServiceOrderService.StatusRequest r) {
        return orders.setStatus(id, r);
    }

    @PostMapping("/api/service-orders/{id}/quote")
    public OrderDto decide(@PathVariable Long id, @Valid @RequestBody QuoteDecision r) {
        return orders.decide(id, r);
    }

    @PostMapping("/api/service-orders/{id}/parts")
    public OrderDto addPart(@PathVariable Long id, @Valid @RequestBody PartRequest r) {
        return orders.addPart(id, r);
    }

    @DeleteMapping("/api/service-orders/{id}/parts/{partId}")
    public OrderDto removePart(@PathVariable Long id, @PathVariable Long partId) {
        return orders.removePart(id, partId);
    }

    @PostMapping("/api/service-orders/{id}/deposits")
    public OrderDto deposit(@PathVariable Long id, @Valid @RequestBody DepositRequest r) {
        return orders.addDeposit(id, r);
    }

    @PostMapping("/api/service-orders/{id}/deliver")
    public DeliverResult deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest r) {
        return orders.deliver(id, r);
    }

    // ---------- Citas desde la tienda en línea (sin sesión) ----------

    @GetMapping("/api/public/stores/{slug}/booking")
    public BookingInfo bookingInfo(@PathVariable String slug) {
        return booking.info(slug);
    }

    @GetMapping("/api/public/stores/{slug}/booking/slots")
    public List<SlotDto> bookingSlots(@PathVariable String slug, @RequestParam Long serviceId, @RequestParam(required = false) Long staffId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return booking.slots(slug, serviceId, staffId, date);
    }

    @PostMapping("/api/public/stores/{slug}/booking")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDone book(@PathVariable String slug, @Valid @RequestBody BookingRequest r) {
        limiter.check("online-booking", AuditService.clientIp());
        return booking.book(slug, r);
    }
}
