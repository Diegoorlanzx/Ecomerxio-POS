package com.tiendaposs.services;

import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.PresentationRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Presentation;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.finance.FinanceService;
import com.tiendaposs.pos.PosRepos.CustomerRepository;
import com.tiendaposs.pos.PosRepos.SaleRepository;
import com.tiendaposs.pos.Sale;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.pos.SaleService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.services.ServicesRepos.*;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 11 — Servicios (peluquería, barbería, spa, taller): servicios con duración, profesionales con horario, agenda
 * de citas sin cruces, recordatorio por WhatsApp, cobro en la caja (servicio + productos) y comisiones por
 * profesional que se liquidan como gasto de nómina.
 */
@Service
public class ServicesService {

    public static final List<String> APPOINTMENT_STATUS = List.of("AGENDADA", "CONFIRMADA", "ATENDIDA", "NO_ASISTIO", "CANCELADA");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] DAYS = {"", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
    private static final String[] MONTHS = {"", "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
            "octubre", "noviembre", "diciembre"};

    private final StaffRepository staff;
    private final StaffHourRepository hours;
    private final AppointmentRepository appointments;
    private final StaffPayoutRepository payouts;
    private final ProductRepository products;
    private final PresentationRepository presentations;
    private final CustomerRepository customers;
    private final SaleRepository sales;
    private final SaleService saleService;
    private final FinanceService finance;
    private final AnalyticsService analytics;
    private final SettingsService settings;
    private final AuditService audit;
    private final EntityManager em;

    public ServicesService(StaffRepository staff, StaffHourRepository hours, AppointmentRepository appointments, StaffPayoutRepository payouts,
                           ProductRepository products, PresentationRepository presentations, CustomerRepository customers,
                           SaleRepository sales, SaleService saleService, FinanceService finance, AnalyticsService analytics,
                           SettingsService settings, AuditService audit, EntityManager em) {
        this.staff = staff;
        this.hours = hours;
        this.appointments = appointments;
        this.payouts = payouts;
        this.products = products;
        this.presentations = presentations;
        this.customers = customers;
        this.sales = sales;
        this.saleService = saleService;
        this.finance = finance;
        this.analytics = analytics;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    Long tenant() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), "SERVICIOS");
        return t;
    }

    /** Órdenes con anticipo: las usan los servicios técnicos (módulo SERVICIOS) y lavandería, taller, panadería u óptica (ORDENES). */
    Long ordersTenant() {
        Long t = TenantContext.require();
        var st = settings.forTenant(t);
        if (!st.hasModule("ORDENES")) ModulesService.check(st, "SERVICIOS");
        return t;
    }

    ZoneId zone(Long t) {
        return analytics.zone(t);
    }

    // =====================================================================
    // Catálogo de servicios
    // =====================================================================

    public record ServiceDto(Long productId, Long presentationId, String name, int minutes, BigDecimal price, BigDecimal commissionPct,
                             Long categoryId, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<ServiceDto> services() {
        return services(tenant(), false);
    }

    List<ServiceDto> services(Long t, boolean onlyActive) {
        List<ServiceDto> out = new ArrayList<>();
        for (Product p : em.createQuery("select p from Product p where p.tenantId = :t and p.serviceMinutes is not null", Product.class)
                .setParameter("t", t).getResultList()) {
            if (LaborProduct.SKU.equals(p.sku)) continue;
            boolean active = "ACTIVE".equals(p.status);
            if (onlyActive && !active) continue;
            Presentation base = presentations.findByProductIdOrderBySortOrderAscIdAsc(p.id).stream().filter(x -> x.active)
                    .filter(x -> x.isBase).findFirst().orElse(null);
            if (base == null) continue;
            out.add(new ServiceDto(p.id, base.id, p.name, p.serviceMinutes, base.price, p.commissionPct, p.categoryId, active));
        }
        out.sort(Comparator.comparing(ServiceDto::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public record ServiceSettingsRequest(@Min(value = 5, message = "mínimo 5 minutos") @Max(value = 720, message = "máximo 12 horas") Integer minutes,
                                         @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal commissionPct) {
    }

    /** Marca un producto como servicio (con su duración) o lo vuelve producto normal (minutes null). */
    @Transactional
    public ServiceDto saveService(Long productId, ServiceSettingsRequest r) {
        CurrentUser.require(Permission.PRODUCTOS_EDITAR);
        Long t = tenant();
        Product p = products.findByIdAndTenantId(productId, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        p.serviceMinutes = r.minutes();
        p.commissionPct = r.commissionPct() == null ? null : r.commissionPct().setScale(2, RoundingMode.HALF_UP);
        if (r.minutes() != null) p.trackStock = false; // un servicio no tiene existencias
        p.updatedAt = Instant.now();
        products.save(p);
        audit.log("SERVICIO_GUARDADO", "PRODUCT", p.id, r.minutes() == null ? p.name + " ya no es un servicio" : p.name + ": servicio de " + r.minutes() + " min");
        return services(t, false).stream().filter(s -> s.productId().equals(p.id)).findFirst().orElse(null);
    }

    // =====================================================================
    // Profesionales y horarios
    // =====================================================================

    public record HourDto(int weekday, String start, String end) {
    }

    public record StaffDto(Long id, Long userId, String name, String phone, String color, BigDecimal commissionServicePct,
                           BigDecimal commissionProductPct, List<Long> serviceIds, boolean onlineBooking, int sortOrder, boolean active,
                           List<HourDto> hours) {
    }

    public record HourRequest(@Min(1) @Max(7) int weekday,
                              @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}", message = "hora no válida") String start,
                              @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}", message = "hora no válida") String end) {
    }

    public record StaffRequest(@NotBlank(message = "escribe el nombre") @Size(max = 80) String name,
                               @Size(max = 30) String phone,
                               @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "color no válido") String color,
                               Long userId,
                               @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal commissionServicePct,
                               @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal commissionProductPct,
                               List<Long> serviceIds,
                               Boolean onlineBooking,
                               Integer sortOrder,
                               Boolean active,
                               List<@Valid HourRequest> hours) {
    }

    @Transactional(readOnly = true)
    public List<StaffDto> staffList() {
        Long t = tenant();
        return staff.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().map(this::toDto).toList();
    }

    StaffDto toDto(StaffMember m) {
        List<HourDto> hs = hours.findByStaffIdOrderByWeekdayAscStartTimeAsc(m.id).stream()
                .map(h -> new HourDto(h.weekday, h.startTime.format(HM), h.endTime.format(HM))).toList();
        return new StaffDto(m.id, m.userId, m.name, m.phone, m.color, m.commissionServicePct, m.commissionProductPct, ids(m.serviceIds),
                m.onlineBooking, m.sortOrder, m.active, hs);
    }

    static List<Long> ids(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(Long::valueOf).toList();
    }

    @Transactional
    public StaffDto saveStaff(Long id, StaffRequest r) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = tenant();
        StaffMember m = id == null ? new StaffMember() : staff.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Profesional no encontrado"));
        m.tenantId = t;
        m.name = r.name().trim();
        m.phone = blank(r.phone());
        if (r.color() != null) m.color = r.color();
        m.userId = r.userId();
        m.commissionServicePct = pct(r.commissionServicePct());
        m.commissionProductPct = pct(r.commissionProductPct());
        Set<Long> valid = services(t, false).stream().map(ServiceDto::productId).collect(Collectors.toSet());
        m.serviceIds = r.serviceIds() == null || r.serviceIds().isEmpty() ? null
                : r.serviceIds().stream().filter(valid::contains).map(String::valueOf).collect(Collectors.joining(","));
        if (r.onlineBooking() != null) m.onlineBooking = r.onlineBooking();
        if (r.sortOrder() != null) m.sortOrder = r.sortOrder();
        if (r.active() != null) m.active = r.active();
        m = staff.save(m);
        if (r.hours() != null) {
            List<StaffHour> hs = new ArrayList<>();
            for (HourRequest h : r.hours()) {
                LocalTime a = LocalTime.parse(h.start()), b = LocalTime.parse(h.end());
                if (!b.isAfter(a)) throw ApiException.field("hours", "la hora de salida del " + DAYS[h.weekday()] + " debe ser después de la de entrada");
                for (StaffHour o : hs) {
                    if (o.weekday == h.weekday() && a.isBefore(o.endTime) && b.isAfter(o.startTime)) {
                        throw ApiException.field("hours", "dos franjas del " + DAYS[h.weekday()] + " se cruzan");
                    }
                }
                StaffHour sh = new StaffHour();
                sh.tenantId = t;
                sh.staffId = m.id;
                sh.weekday = h.weekday();
                sh.startTime = a;
                sh.endTime = b;
                hs.add(sh);
            }
            hours.deleteByStaffId(m.id);
            hours.flush();
            hours.saveAll(hs);
        }
        audit.log(id == null ? "PROFESIONAL_CREADO" : "PROFESIONAL_EDITADO", "STAFF", m.id, m.name);
        return toDto(m);
    }

    private static BigDecimal pct(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Franjas de un día. Sin horario configurado se asume lunes a sábado de 8 a. m. a 6 p. m. */
    List<LocalTime[]> workingRanges(StaffMember m, List<StaffHour> all, LocalDate day) {
        List<StaffHour> mine = all.stream().filter(h -> h.staffId.equals(m.id)).toList();
        int wd = day.getDayOfWeek().getValue();
        if (mine.isEmpty()) return wd == 7 ? List.of() : List.<LocalTime[]>of(new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(18, 0)});
        return mine.stream().filter(h -> h.weekday == wd).sorted(Comparator.comparing(h -> h.startTime))
                .map(h -> new LocalTime[]{h.startTime, h.endTime}).toList();
    }

    boolean doesService(StaffMember m, Long serviceId) {
        List<Long> s = ids(m.serviceIds);
        return s.isEmpty() || s.contains(serviceId);
    }

    // =====================================================================
    // Agenda
    // =====================================================================

    public record AppointmentDto(Long id, Long staffId, String staffName, String staffColor, Long customerId, String customerName,
                                 String customerPhone, Long serviceId, String serviceName, BigDecimal price, LocalDateTime start,
                                 LocalDateTime end, int minutes, String status, String source, String notes, Long saleId, String saleStatus,
                                 Instant reminderSentAt, String whatsappUrl, String createdBy) {
    }

    public record StaffDay(Long id, String name, String color, List<HourDto> ranges) {
    }

    public record AgendaDto(LocalDate date, List<StaffDay> staff, List<AppointmentDto> appointments, int slotMinutes, boolean bookingEnabled,
                            String bookingUrlSlug) {
    }

    @Transactional(readOnly = true)
    public AgendaDto agenda(LocalDate date, int days) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        ZoneId z = zone(t);
        LocalDate d = date == null ? LocalDate.now(z) : date;
        int span = Math.max(1, Math.min(days, 7));
        List<StaffHour> all = hours.findByTenantId(t);
        List<StaffDay> people = staff.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().filter(m -> m.active)
                .map(m -> new StaffDay(m.id, m.name, m.color, workingRanges(m, all, d).stream()
                        .map(r -> new HourDto(d.getDayOfWeek().getValue(), r[0].format(HM), r[1].format(HM))).toList()))
                .toList();
        List<AppointmentDto> list = appointments.between(t, d.atStartOfDay(z).toInstant(), d.plusDays(span).atStartOfDay(z).toInstant())
                .stream().map(a -> toDto(a, z)).toList();
        var st = settings.forTenant(t);
        Object slug = em.createNativeQuery("select slug from online_stores where tenant_id = :t and enabled").setParameter("t", t)
                .getResultStream().findFirst().orElse(null);
        return new AgendaDto(d, people, list, st.bookingSlotMinutes, st.bookingEnabled, slug == null ? null : slug.toString());
    }

    AppointmentDto toDto(Appointment a, ZoneId z) {
        StaffMember m = staff.findById(a.staffId).orElse(null);
        String saleStatus = a.saleId == null ? null : sales.findById(a.saleId).map(s -> s.status).orElse(null);
        LocalDateTime start = LocalDateTime.ofInstant(a.startAt, z), end = LocalDateTime.ofInstant(a.endAt, z);
        return new AppointmentDto(a.id, a.staffId, m == null ? "" : m.name, m == null ? "#1e66f5" : m.color, a.customerId, a.customerName,
                a.customerPhone, a.serviceId, a.serviceName, a.price, start, end, (int) Duration.between(a.startAt, a.endAt).toMinutes(),
                a.status, a.source, a.notes, a.saleId, saleStatus, a.reminderSentAt, reminderUrl(a, m, start), a.createdBy);
    }

    /** Mensaje de recordatorio por WhatsApp (se abre en el celular de la tienda y se envía con un toque). */
    String reminderUrl(Appointment a, StaffMember m, LocalDateTime start) {
        String phone = waPhone(a.customerPhone);
        if (phone == null) return null;
        String store = storeName(a.tenantId);
        String msg = "Hola " + a.customerName.split(" ")[0] + ", te recordamos tu cita en " + store + " el " + DAYS[start.getDayOfWeek().getValue()]
                + " " + start.getDayOfMonth() + " de " + MONTHS[start.getMonthValue()] + " a las " + hora(start.toLocalTime())
                + ": " + a.serviceName + (m == null ? "" : " con " + m.name) + ". Si no puedes venir, avísanos por aquí. ¡Te esperamos!";
        return "https://wa.me/" + phone + "?text=" + URLEncoder.encode(msg, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String hora(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return h + (t.getMinute() == 0 ? "" : ":" + String.format("%02d", t.getMinute())) + (t.getHour() < 12 ? " a. m." : " p. m.");
    }

    static String waPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 10) digits = "57" + digits;
        return digits.length() < 10 ? null : digits;
    }

    public record AppointmentRequest(@NotNull(message = "elige el profesional") Long staffId,
                                     @NotNull(message = "elige el servicio") Long serviceId,
                                     Long customerId,
                                     @Size(max = 120) String customerName,
                                     @Size(max = 30) String customerPhone,
                                     @NotNull(message = "elige la hora") LocalDateTime start,
                                     @Min(value = 5, message = "mínimo 5 minutos") @Max(value = 720, message = "máximo 12 horas") Integer minutes,
                                     @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price,
                                     @Size(max = 500) String notes) {
    }

    @Transactional
    public AppointmentDto createAppointment(AppointmentRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        Appointment a = new Appointment();
        a.tenantId = t;
        a.createdBy = CurrentUser.get().fullName();
        fill(t, a, r, false);
        a = appointments.save(a);
        audit.log("CITA_AGENDADA", "APPOINTMENT", a.id, "Agendó " + a.serviceName + " para " + a.customerName);
        return toDto(a, zone(t));
    }

    @Transactional
    public AppointmentDto updateAppointment(Long id, AppointmentRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        Appointment a = find(t, id);
        if (a.saleId != null) throw ApiException.conflict("La cita ya se pasó a la caja: no se puede cambiar");
        fill(t, a, r, false);
        audit.log("CITA_EDITADA", "APPOINTMENT", a.id, "Cambió la cita de " + a.customerName);
        return toDto(appointments.save(a), zone(t));
    }

    /** Llena y valida una cita: el profesional hace el servicio, está en su horario (en línea) y no se cruza con otra. */
    void fill(Long t, Appointment a, AppointmentRequest r, boolean strictHours) {
        StaffMember m = staff.findByIdAndTenantId(r.staffId(), t).filter(x -> x.active).orElseThrow(() -> ApiException.field("staffId", "ese profesional no existe"));
        ServiceDto svc = services(t, true).stream().filter(s -> s.productId().equals(r.serviceId())).findFirst()
                .orElseThrow(() -> ApiException.field("serviceId", "ese servicio no existe"));
        if (!doesService(m, svc.productId())) throw ApiException.field("staffId", m.name + " no hace " + svc.name());
        String name = blank(r.customerName());
        String phone = blank(r.customerPhone());
        if (r.customerId() != null) {
            var c = customers.findByIdAndTenantId(r.customerId(), t).orElseThrow(() -> ApiException.field("customerId", "el cliente no existe"));
            a.customerId = c.id;
            if (name == null) name = c.name;
            if (phone == null) phone = c.phone;
        } else {
            a.customerId = null;
        }
        if (name == null) throw ApiException.field("customerName", "escribe el nombre del cliente");
        ZoneId z = zone(t);
        int minutes = r.minutes() == null ? svc.minutes() : r.minutes();
        Instant start = r.start().atZone(z).toInstant();
        Instant end = start.plus(Duration.ofMinutes(minutes));
        if (strictHours) {
            LocalDate day = r.start().toLocalDate();
            LocalTime a0 = r.start().toLocalTime(), a1 = a0.plusMinutes(minutes);
            boolean fits = workingRanges(m, hours.findByTenantId(t), day).stream().anyMatch(x -> !a0.isBefore(x[0]) && !a1.isAfter(x[1]) && a1.isAfter(a0));
            if (!fits) throw ApiException.field("start", m.name + " no atiende a esa hora");
        }
        for (Appointment o : appointments.busy(m.id, start, end)) {
            if (o.id.equals(a.id)) continue;
            LocalTime s0 = LocalDateTime.ofInstant(o.startAt, z).toLocalTime(), s1 = LocalDateTime.ofInstant(o.endAt, z).toLocalTime();
            throw ApiException.conflict(m.name + " ya tiene una cita de " + s0.format(HM) + " a " + s1.format(HM) + " (" + o.customerName + ")");
        }
        a.staffId = m.id;
        a.serviceId = svc.productId();
        a.serviceName = svc.name();
        a.price = r.price() == null ? svc.price() : r.price().setScale(2, RoundingMode.HALF_UP);
        a.customerName = name;
        a.customerPhone = phone;
        a.startAt = start;
        a.endAt = end;
        a.notes = blank(r.notes());
    }

    Appointment find(Long t, Long id) {
        return appointments.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Cita no encontrada"));
    }

    public record StatusRequest(@NotBlank String status, @Size(max = 300) String note) {
    }

    @Transactional
    public AppointmentDto setStatus(Long id, StatusRequest r) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        Appointment a = find(t, id);
        String st = r.status().trim().toUpperCase();
        if (!APPOINTMENT_STATUS.contains(st)) throw ApiException.field("status", "estado no válido");
        if (a.saleId != null && !"ATENDIDA".equals(st)) {
            Sale s = sales.findById(a.saleId).orElse(null);
            if (s != null && "PAGADA".equals(s.status)) throw ApiException.conflict("La cita ya se cobró");
        }
        a.status = st;
        if (r.note() != null && !r.note().isBlank()) a.notes = (a.notes == null ? "" : a.notes + " · ") + r.note().trim();
        audit.log("CITA_" + st, "APPOINTMENT", a.id, "Cita de " + a.customerName + ": " + st.toLowerCase().replace('_', ' '));
        return toDto(appointments.save(a), zone(t));
    }

    /** Se abrió el recordatorio por WhatsApp: queda marcado para no mandarlo dos veces. */
    @Transactional
    public AppointmentDto markReminder(Long id) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        Appointment a = find(t, id);
        a.reminderSentAt = Instant.now();
        if ("AGENDADA".equals(a.status)) a.status = "AGENDADA";
        return toDto(appointments.save(a), zone(t));
    }

    /**
     * Pasa la cita a la caja: abre una cuenta con el servicio a nombre del profesional; ahí se agregan los productos que
     * lleve el cliente y se cobra todo junto. La cita queda atendida.
     */
    @Transactional
    public SaleDto charge(Long id) {
        CurrentUser.require(Permission.POS_VENDER);
        Long t = tenant();
        Appointment a = find(t, id);
        if ("CANCELADA".equals(a.status)) throw ApiException.conflict("La cita está cancelada");
        if (a.saleId != null) {
            Sale s = sales.findById(a.saleId).orElse(null);
            if (s != null && "ABIERTA".equals(s.status)) return saleService.get(s.id);
            if (s != null && "PAGADA".equals(s.status)) throw ApiException.conflict("La cita ya se cobró (venta #" + s.number + ")");
        }
        StaffMember m = staff.findById(a.staffId).orElseThrow();
        Presentation pr = presentations.findByProductIdOrderBySortOrderAscIdAsc(a.serviceId).stream().filter(x -> x.isBase).findFirst()
                .orElseThrow(() -> ApiException.conflict("El servicio ya no tiene precio"));
        Sale s = saleService.openServiceAccount("Cita: " + a.customerName.split(" ")[0] + " · " + m.name.split(" ")[0], a.customerId, m.id);
        saleService.addServiceLine(s.id, pr.id, BigDecimal.ONE, a.price, null, m.id);
        a.saleId = s.id;
        a.status = "ATENDIDA";
        appointments.save(a);
        audit.log("CITA_A_CAJA", "APPOINTMENT", a.id, "Pasó a la caja la cita de " + a.customerName + " (" + a.serviceName + ")");
        return saleService.get(s.id);
    }

    // =====================================================================
    // Cupos libres (agenda y tienda en línea)
    // =====================================================================

    public record SlotDto(String time, List<Long> staffIds) {
    }

    /** Horas libres de un día para un servicio, con quién puede atender en cada una. */
    List<SlotDto> slots(Long t, Long serviceId, Long staffId, LocalDate day, boolean onlineOnly) {
        ZoneId z = zone(t);
        ServiceDto svc = services(t, true).stream().filter(s -> s.productId().equals(serviceId)).findFirst()
                .orElseThrow(() -> ApiException.field("serviceId", "ese servicio no existe"));
        int step = Math.max(5, settings.forTenant(t).bookingSlotMinutes);
        LocalDateTime now = LocalDateTime.now(z).plusMinutes(onlineOnly ? 30 : 0);
        List<StaffHour> all = hours.findByTenantId(t);
        TreeMap<LocalTime, List<Long>> out = new TreeMap<>();
        for (StaffMember m : staff.findByTenantIdOrderBySortOrderAscIdAsc(t)) {
            if (!m.active || (staffId != null && !m.id.equals(staffId)) || !doesService(m, serviceId) || (onlineOnly && !m.onlineBooking)) continue;
            List<Appointment> busy = appointments.busy(m.id, day.atStartOfDay(z).toInstant(), day.plusDays(1).atStartOfDay(z).toInstant());
            for (LocalTime[] r : workingRanges(m, all, day)) {
                for (LocalTime s = r[0]; !s.plusMinutes(svc.minutes()).isAfter(r[1]) && s.isAfter(r[0].minusMinutes(1)); s = s.plusMinutes(step)) {
                    LocalDateTime start = day.atTime(s);
                    if (start.isBefore(now)) continue;
                    Instant a = start.atZone(z).toInstant(), b = a.plus(Duration.ofMinutes(svc.minutes()));
                    boolean free = busy.stream().noneMatch(x -> x.startAt.isBefore(b) && x.endAt.isAfter(a));
                    if (free) out.computeIfAbsent(s, k -> new ArrayList<>()).add(m.id);
                    if (s.plusMinutes(step).isBefore(s)) break; // cruza la medianoche
                }
            }
        }
        return out.entrySet().stream().map(e -> new SlotDto(e.getKey().format(HM), e.getValue())).toList();
    }

    @Transactional(readOnly = true)
    public List<SlotDto> slots(Long serviceId, Long staffId, LocalDate day) {
        CurrentUser.require(Permission.POS_VENDER);
        return slots(tenant(), serviceId, staffId, day, false);
    }

    // =====================================================================
    // Comisiones y liquidación
    // =====================================================================

    public record CommissionLine(LocalDate date, long saleNumber, String item, boolean service, BigDecimal base, BigDecimal pct,
                                 BigDecimal commission) {
    }

    public record StaffCommission(Long staffId, String name, String color, BigDecimal serviceBase, BigDecimal serviceCommission,
                                  BigDecimal productBase, BigDecimal productCommission, BigDecimal total, int services,
                                  BigDecimal paid, Long payoutId, List<CommissionLine> lines) {
    }

    public record PayoutDto(Long id, Long staffId, String staffName, LocalDate from, LocalDate to, BigDecimal base, BigDecimal amount,
                            String paidBy, Instant createdAt) {
    }

    public record CommissionReport(LocalDate from, LocalDate to, List<StaffCommission> staff, BigDecimal total, List<PayoutDto> payouts) {
    }

    /**
     * Comisión por línea cobrada: servicios con la comisión propia del servicio o la del profesional; productos con la
     * de productos del profesional. La base es lo cobrado sin impuestos y sin lo devuelto.
     */
    @Transactional(readOnly = true)
    public CommissionReport commissions(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.FINANZAS_VER);
        Long t = tenant();
        ZoneId z = zone(t);
        LocalDate today = LocalDate.now(z);
        LocalDate f = from != null ? from : (today.getDayOfMonth() <= 15 ? today.withDayOfMonth(1) : today.withDayOfMonth(16));
        LocalDate tt = to != null ? to : (f.getDayOfMonth() == 1 ? f.withDayOfMonth(15) : f.withDayOfMonth(f.lengthOfMonth()));
        if (tt.isBefore(f)) throw ApiException.field("to", "la fecha final es antes de la inicial");
        Map<Long, StaffMember> people = staff.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().collect(Collectors.toMap(m -> m.id, m -> m, (a, b) -> a, LinkedHashMap::new));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        select coalesce(l.staff_id, s.staff_id), cast(s.completed_at at time zone :tz as date), s.number, l.product_name,
                               p.service_minutes is not null, p.commission_pct,
                               (l.line_total - l.tax) * (l.quantity - l.returned_qty) / nullif(l.quantity, 0)
                        from sale_lines l join sales s on s.id = l.sale_id join products p on p.id = l.product_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and coalesce(l.staff_id, s.staff_id) is not null
                          and s.completed_at >= :f and s.completed_at < :tt
                        order by s.completed_at""")
                .setParameter("tz", z.getId()).setParameter("t", t)
                .setParameter("f", f.atStartOfDay(z).toInstant()).setParameter("tt", tt.plusDays(1).atStartOfDay(z).toInstant())
                .getResultList();
        Map<Long, List<CommissionLine>> byStaff = new HashMap<>();
        for (Object[] r : rows) {
            StaffMember m = people.get(((Number) r[0]).longValue());
            if (m == null) continue;
            boolean service = Boolean.TRUE.equals(r[4]);
            BigDecimal pct = service ? (r[5] != null ? dec(r[5]) : m.commissionServicePct) : m.commissionProductPct;
            BigDecimal base = dec(r[6]).setScale(2, RoundingMode.HALF_UP);
            BigDecimal com = base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            byStaff.computeIfAbsent(m.id, k -> new ArrayList<>()).add(new CommissionLine(localDate(r[1]), ((Number) r[2]).longValue(),
                    (String) r[3], service, base, pct, com));
        }
        List<StaffPayout> paidList = payouts.findByTenantIdOrderByCreatedAtDesc(t);
        List<StaffCommission> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (StaffMember m : people.values()) {
            List<CommissionLine> ls = byStaff.getOrDefault(m.id, List.of());
            if (ls.isEmpty() && !m.active) continue;
            BigDecimal sb = BigDecimal.ZERO, sc = BigDecimal.ZERO, pb = BigDecimal.ZERO, pc = BigDecimal.ZERO;
            int n = 0;
            for (CommissionLine l : ls) {
                if (l.service()) {
                    sb = sb.add(l.base());
                    sc = sc.add(l.commission());
                    n++;
                } else {
                    pb = pb.add(l.base());
                    pc = pc.add(l.commission());
                }
            }
            StaffPayout po = paidList.stream().filter(p -> p.staffId.equals(m.id) && p.periodFrom.equals(f) && p.periodTo.equals(tt)).findFirst().orElse(null);
            BigDecimal tot = sc.add(pc);
            total = total.add(tot);
            out.add(new StaffCommission(m.id, m.name, m.color, sb, sc, pb, pc, tot, n, po == null ? BigDecimal.ZERO : po.amount,
                    po == null ? null : po.id, ls));
        }
        List<PayoutDto> pays = paidList.stream().limit(30).map(p -> new PayoutDto(p.id, p.staffId,
                people.containsKey(p.staffId) ? people.get(p.staffId).name : "", p.periodFrom, p.periodTo, p.baseAmount, p.amount, p.paidBy, p.createdAt)).toList();
        return new CommissionReport(f, tt, out, total, pays);
    }

    public record PayRequest(@NotNull Long staffId, @NotNull LocalDate from, @NotNull LocalDate to,
                             @NotBlank @Pattern(regexp = "CAJA|BANCO|OTRO", message = "elige de dónde sale el dinero") String paidFrom,
                             String method, @Size(max = 80) String reference) {
    }

    /** Liquida las comisiones de un profesional en el período: queda un gasto de "Nómina" en finanzas. */
    @Transactional
    public CommissionReport pay(PayRequest r) {
        CurrentUser.require(Permission.FINANZAS_GASTOS);
        Long t = tenant();
        CommissionReport rep = commissions(r.from(), r.to());
        StaffCommission sc = rep.staff().stream().filter(x -> x.staffId().equals(r.staffId())).findFirst()
                .orElseThrow(() -> ApiException.field("staffId", "ese profesional no existe"));
        if (sc.payoutId() != null) throw ApiException.conflict("Las comisiones de " + sc.name() + " de ese período ya se pagaron");
        if (sc.total().signum() <= 0) throw ApiException.conflict(sc.name() + " no tiene comisiones en ese período");
        Number cat = (Number) em.createNativeQuery("select id from finance_categories where tenant_id = :t and kind = 'GASTO' and name = 'Nómina'")
                .setParameter("t", t).getResultStream().findFirst().orElse(null);
        String desc = "Comisiones de " + sc.name() + " del " + r.from() + " al " + r.to();
        String method = r.method() == null || r.method().isBlank() ? ("CAJA".equals(r.paidFrom()) ? "EFECTIVO" : "TRANSFERENCIA") : r.method();
        var entry = finance.createEntry(new FinanceService.EntryRequest("GASTO", cat == null ? null : cat.longValue(), desc, sc.total(),
                null, method, r.paidFrom(), r.reference()));
        StaffPayout p = new StaffPayout();
        p.tenantId = t;
        p.staffId = sc.staffId();
        p.periodFrom = rep.from();
        p.periodTo = rep.to();
        p.baseAmount = sc.serviceBase().add(sc.productBase());
        p.amount = sc.total();
        p.financeEntryId = entry.id();
        p.paidBy = CurrentUser.get().fullName();
        payouts.save(p);
        audit.log("COMISIONES_PAGADAS", "STAFF", sc.staffId(), desc + ": " + Money.fmt(sc.total()));
        return commissions(r.from(), r.to());
    }

    // =====================================================================
    // Ajustes de la agenda en línea
    // =====================================================================

    public record BookingSettings(boolean bookingEnabled, @Min(value = 5, message = "mínimo 5") @Max(value = 120, message = "máximo 120") int slotMinutes) {
    }

    @Transactional
    public BookingSettings saveBooking(BookingSettings r) {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        Long t = tenant();
        var st = settings.forTenant(t);
        st.bookingEnabled = r.bookingEnabled();
        st.bookingSlotMinutes = r.slotMinutes();
        em.merge(st);
        audit.log("AGENDA_EN_LINEA", "SETTINGS", t, r.bookingEnabled() ? "Activó las citas desde la tienda en línea" : "Apagó las citas en línea");
        return r;
    }

    // ---------------------------------------------------------------------

    String storeName(Long t) {
        return String.valueOf(em.createNativeQuery("select name from tenants where id = :t").setParameter("t", t).getSingleResult());
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }

    static LocalDate localDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate() : o instanceof LocalDate l ? l : LocalDate.parse(String.valueOf(o));
    }
}
