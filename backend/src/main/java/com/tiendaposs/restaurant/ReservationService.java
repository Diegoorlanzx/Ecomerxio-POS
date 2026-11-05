package com.tiendaposs.restaurant;

import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.pos.AccountAccess;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.restaurant.RestaurantRepos.DiningTableRepository;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Reservas de mesa: la lista del día, el aviso en el plano y "llegaron" que abre la mesa. */
@Service
public class ReservationService {

    public interface TableReservationRepository extends JpaRepository<TableReservation, Long> {
        List<TableReservation> findByTenantIdAndReservedAtBetweenOrderByReservedAtAsc(Long tenantId, Instant from, Instant to);

        Optional<TableReservation> findByIdAndTenantId(Long id, Long tenantId);
    }

    public static final List<String> STATES = List.of("PENDIENTE", "CONFIRMADA", "LLEGO", "NO_LLEGO", "CANCELADA");
    /** Las que todavía ocupan la mesa en el plano. */
    static final Set<String> HOLDING = Set.of("PENDIENTE", "CONFIRMADA");

    private final TableReservationRepository reservations;
    private final DiningTableRepository tables;
    private final RestaurantService restaurant;
    private final SettingsService settings;
    private final AnalyticsService analytics;
    private final AuditService audit;

    public ReservationService(TableReservationRepository reservations, DiningTableRepository tables, RestaurantService restaurant,
                              SettingsService settings, AnalyticsService analytics, AuditService audit) {
        this.reservations = reservations;
        this.tables = tables;
        this.restaurant = restaurant;
        this.settings = settings;
        this.analytics = analytics;
        this.audit = audit;
    }

    private Long tenant() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), "RESTAURANTE");
        AccountAccess.requireServe();
        return t;
    }

    public record ReservationDto(Long id, Long tableId, String tableName, String name, String phone, int people, Instant reservedAt,
                                 int durationMin, String notes, String status, Long saleId, String createdBy) {
    }

    public record ReservationRequest(@NotBlank(message = "escribe el nombre") @Size(max = 120) String name,
                                     @Pattern(regexp = "[0-9 +()-]{7,20}", message = "celular no válido") String phone,
                                     @NotNull @Min(value = 1, message = "mínimo 1 persona") @Max(value = 200) Integer people,
                                     @NotNull(message = "elige la fecha y la hora") Instant reservedAt,
                                     @Min(value = 15, message = "mínimo 15 minutos") @Max(value = 600) Integer durationMin,
                                     Long tableId, @Size(max = 300) String notes) {
    }

    /** Reservas de un día (hora local de la tienda). */
    @Transactional(readOnly = true)
    public List<ReservationDto> day(LocalDate date) {
        Long t = tenant();
        ZoneId z = analytics.zone(t);
        LocalDate d = date == null ? LocalDate.now(z) : date;
        Instant from = d.atStartOfDay(z).toInstant();
        Instant to = d.plusDays(1).atStartOfDay(z).toInstant();
        Map<Long, String> names = tableNames(t);
        return reservations.findByTenantIdAndReservedAtBetweenOrderByReservedAtAsc(t, from, to).stream().map(r -> toDto(r, names)).toList();
    }

    @Transactional
    public ReservationDto save(Long id, ReservationRequest r) {
        Long t = tenant();
        TableReservation x = id == null ? new TableReservation()
                : reservations.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Reserva no encontrada"));
        if (id != null && !HOLDING.contains(x.status)) throw ApiException.conflict("Esa reserva ya se cerró");
        if (r.tableId() != null) tables.findByIdAndTenantId(r.tableId(), t).filter(tb -> tb.active)
                .orElseThrow(() -> ApiException.field("tableId", "la mesa no existe"));
        int duration = r.durationMin() == null ? 90 : r.durationMin();
        if (id == null && r.reservedAt().isBefore(Instant.now().minus(Duration.ofMinutes(30)))) {
            throw ApiException.field("reservedAt", "esa hora ya pasó");
        }
        if (r.tableId() != null) {
            Instant start = r.reservedAt();
            Instant end = start.plus(Duration.ofMinutes(duration));
            for (TableReservation o : reservations.findByTenantIdAndReservedAtBetweenOrderByReservedAtAsc(t,
                    start.minus(Duration.ofHours(12)), end)) {
                if (Objects.equals(o.id, x.id) || !r.tableId().equals(o.tableId) || !HOLDING.contains(o.status)) continue;
                Instant oEnd = o.reservedAt.plus(Duration.ofMinutes(o.durationMin));
                if (o.reservedAt.isBefore(end) && oEnd.isAfter(start)) {
                    throw ApiException.field("tableId", "esa mesa ya está reservada a nombre de " + o.name + " a las "
                            + LocalTime.ofInstant(o.reservedAt, analytics.zone(t)).toString());
                }
            }
        }
        x.tenantId = t;
        x.name = r.name().trim();
        x.phone = r.phone() == null || r.phone().isBlank() ? null : r.phone().replaceAll("[^0-9+]", "");
        x.people = r.people();
        x.reservedAt = r.reservedAt();
        x.durationMin = duration;
        x.tableId = r.tableId();
        x.notes = r.notes() == null || r.notes().isBlank() ? null : r.notes().trim();
        if (id == null) x.createdBy = CurrentUser.get().fullName();
        x = reservations.save(x);
        audit.log(id == null ? "RESERVA_CREADA" : "RESERVA_EDITADA", "RESERVATION", x.id, (id == null ? "Reservó" : "Cambió la reserva de")
                + " " + x.name + " para " + x.people + " personas");
        return toDto(x, tableNames(t));
    }

    public record StatusRequest(@NotBlank String status) {
    }

    /** Confirmar, cancelar o marcar que no llegó. "LLEGO" se hace con {@link #seat}. */
    @Transactional
    public ReservationDto setStatus(Long id, StatusRequest r) {
        Long t = tenant();
        TableReservation x = reservations.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Reserva no encontrada"));
        String st = r.status().trim().toUpperCase();
        if (!STATES.contains(st) || "LLEGO".equals(st)) throw ApiException.field("status", "estado no válido");
        if ("LLEGO".equals(x.status)) throw ApiException.conflict("Esa reserva ya llegó y tiene la mesa abierta");
        x.status = st;
        audit.log("RESERVA_" + st, "RESERVATION", x.id, "Reserva de " + x.name + ": " + st.toLowerCase().replace('_', ' '));
        return toDto(x, tableNames(t));
    }

    public record SeatRequest(Long tableId) {
    }

    /** Llegó la reserva: se abre la mesa (la reservada o la que se elija ahora) con el número de personas. */
    @Transactional
    public SaleDto seat(Long id, SeatRequest r) {
        Long t = tenant();
        TableReservation x = reservations.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Reserva no encontrada"));
        if (!HOLDING.contains(x.status)) throw ApiException.conflict("Esa reserva ya se cerró");
        Long tableId = r != null && r.tableId() != null ? r.tableId() : x.tableId;
        if (tableId == null) throw ApiException.field("tableId", "elige la mesa donde se sientan");
        SaleDto acc = restaurant.openTable(tableId, new RestaurantService.OpenTableRequest(Math.min(x.people, 99)));
        x.tableId = tableId;
        x.status = "LLEGO";
        x.saleId = acc.id();
        audit.log("RESERVA_LLEGO", "RESERVATION", x.id, "Llegó la reserva de " + x.name + " (" + x.people + " personas)");
        return acc;
    }

    /** Próxima reserva de cada mesa entre ahora (con 30 min de gracia) y las próximas 3 horas, para el plano. */
    @Transactional(readOnly = true)
    public Map<Long, ReservationDto> upcomingByTable(Long t) {
        Instant now = Instant.now();
        Map<Long, String> names = tableNames(t);
        Map<Long, ReservationDto> out = new HashMap<>();
        for (TableReservation x : reservations.findByTenantIdAndReservedAtBetweenOrderByReservedAtAsc(t, now.minus(Duration.ofMinutes(30)),
                now.plus(Duration.ofHours(3)))) {
            if (x.tableId == null || !HOLDING.contains(x.status)) continue;
            out.putIfAbsent(x.tableId, toDto(x, names));
        }
        return out;
    }

    private Map<Long, String> tableNames(Long t) {
        Map<Long, String> m = new HashMap<>();
        tables.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(t).forEach(tb -> m.put(tb.id, tb.name));
        return m;
    }

    private static ReservationDto toDto(TableReservation x, Map<Long, String> names) {
        return new ReservationDto(x.id, x.tableId, x.tableId == null ? null : names.get(x.tableId), x.name, x.phone, x.people, x.reservedAt,
                x.durationMin, x.notes, x.status, x.saleId, x.createdBy);
    }
}
