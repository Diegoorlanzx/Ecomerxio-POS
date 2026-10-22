package com.tiendaposs.services;

import com.tiendaposs.common.ApiException;
import com.tiendaposs.notifications.NotificationService;
import com.tiendaposs.notifications.NotificationType;
import com.tiendaposs.online.OnlineRepos.OnlineStoreRepository;
import com.tiendaposs.online.OnlineStore;
import com.tiendaposs.services.ServicesRepos.*;
import com.tiendaposs.settings.SettingsService;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Citas desde la tienda en línea: el cliente elige servicio, profesional (o "cualquiera"), día y hora libre. */
@Service
public class PublicBookingService {

    private final OnlineStoreRepository stores;
    private final StaffRepository staff;
    private final AppointmentRepository appointments;
    private final ServicesService services;
    private final SettingsService settings;
    private final NotificationService notifications;

    public PublicBookingService(OnlineStoreRepository stores, StaffRepository staff, AppointmentRepository appointments,
                                ServicesService services, SettingsService settings, NotificationService notifications) {
        this.stores = stores;
        this.staff = staff;
        this.appointments = appointments;
        this.services = services;
        this.settings = settings;
        this.notifications = notifications;
    }

    public record PublicStaff(Long id, String name, String color, List<Long> serviceIds) {
    }

    public record PublicService(Long id, String name, int minutes, java.math.BigDecimal price) {
    }

    public record BookingInfo(String storeName, List<PublicService> services, List<PublicStaff> staff, int maxDays) {
    }

    /** Tienda publicada, con el módulo de servicios y las citas en línea encendidas. */
    Long tenant(String slug) {
        OnlineStore s = stores.findBySlug(slug == null ? "" : slug.trim()).orElseThrow(() -> ApiException.notFound("Esta tienda no recibe citas en línea"));
        var st = settings.forTenant(s.tenantId);
        if (!s.enabled || !st.hasModule("SERVICIOS") || !st.bookingEnabled) throw ApiException.notFound("Esta tienda no recibe citas en línea");
        return s.tenantId;
    }

    @Transactional(readOnly = true)
    public BookingInfo info(String slug) {
        Long t = tenant(slug);
        List<PublicService> svcs = services.services(t, true).stream().filter(x -> x.minutes() > 0)
                .map(x -> new PublicService(x.productId(), x.name(), x.minutes(), x.price())).toList();
        List<PublicStaff> people = staff.findByTenantIdOrderBySortOrderAscIdAsc(t).stream().filter(m -> m.active && m.onlineBooking)
                .map(m -> new PublicStaff(m.id, m.name, m.color, ServicesService.ids(m.serviceIds))).toList();
        return new BookingInfo(services.storeName(t), svcs, people, 30);
    }

    @Transactional(readOnly = true)
    public List<ServicesService.SlotDto> slots(String slug, Long serviceId, Long staffId, LocalDate day) {
        Long t = tenant(slug);
        LocalDate today = LocalDate.now(services.zone(t));
        if (day.isBefore(today) || day.isAfter(today.plusDays(30))) return List.of();
        return services.slots(t, serviceId, staffId, day, true);
    }

    public record BookingRequest(@NotNull(message = "elige el servicio") Long serviceId, Long staffId,
                                 @NotNull(message = "elige el día") LocalDate date,
                                 @NotBlank(message = "elige la hora") @Pattern(regexp = "\\d{2}:\\d{2}", message = "hora no válida") String time,
                                 @NotBlank(message = "escribe tu nombre") @Size(max = 120) String name,
                                 @NotBlank(message = "escribe tu celular") @Size(min = 7, max = 30, message = "celular no válido") String phone,
                                 @Size(max = 300) String notes) {
    }

    public record BookingDone(Long id, String serviceName, String staffName, LocalDateTime start, String storeName) {
    }

    @Transactional
    public BookingDone book(String slug, BookingRequest r) {
        Long t = tenant(slug);
        ZoneId z = services.zone(t);
        LocalDateTime start = r.date().atTime(LocalTime.parse(r.time()));
        if (start.isBefore(LocalDateTime.now(z))) throw ApiException.field("time", "esa hora ya pasó");
        String phone = r.phone().replaceAll("[^0-9+]", "");
        Instant since = Instant.now().minus(Duration.ofHours(12));
        long recent = appointments.between(t, since, Instant.now().plus(Duration.ofDays(60))).stream()
                .filter(a -> phone.equals(a.customerPhone) && "ONLINE".equals(a.source) && a.createdAt.isAfter(since) && !"CANCELADA".equals(a.status)).count();
        if (recent >= 3) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Ya tienes varias citas pedidas. Escríbele a la tienda si necesitas otra.");
        // Sin profesional elegido: el primero que esté libre a esa hora.
        Long staffId = r.staffId();
        if (staffId == null) {
            staffId = services.slots(t, r.serviceId(), null, r.date(), true).stream().filter(s -> s.time().equals(r.time()))
                    .findFirst().map(s -> s.staffIds().get(0)).orElseThrow(() -> ApiException.conflict("Esa hora ya no está libre. Elige otra."));
        }
        StaffMember m = staff.findByIdAndTenantId(staffId, t).filter(x -> x.active && x.onlineBooking)
                .orElseThrow(() -> ApiException.field("staffId", "ese profesional no recibe citas en línea"));
        Appointment a = new Appointment();
        a.tenantId = t;
        a.source = "ONLINE";
        a.createdBy = "Tienda en línea";
        services.fill(t, a, new ServicesService.AppointmentRequest(m.id, r.serviceId(), null, r.name().trim(), phone, start, null, null, r.notes()), true);
        a = appointments.save(a);
        String when = start.format(DateTimeFormatter.ofPattern("dd/MM")) + " " + ServicesService.hora(start.toLocalTime());
        notifications.notifyNow(t, NotificationType.CITA_EN_LINEA, "Cita en línea: " + a.customerName + " · " + when,
                a.serviceName + " con " + m.name + ". Celular " + phone + (a.notes == null ? "" : ". Nota: " + a.notes) + ".",
                "/agenda?fecha=" + r.date(), "CITA_EN_LINEA:" + a.id);
        return new BookingDone(a.id, a.serviceName, m.name, start, services.storeName(t));
    }
}
