package com.tiendaposs.restaurant;

import com.tiendaposs.pos.SaleDtos.SaleDto;
import com.tiendaposs.restaurant.RestaurantService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Fase 10 — Restaurante: plano de mesas, platos con modificadores, comandas, precuenta, dividir/unir y propina. */
@RestController
@RequestMapping("/api/restaurant")
public class RestaurantController {

    private final RestaurantService restaurant;
    private final KitchenService kitchen;
    private final ReservationService reservations;

    public RestaurantController(RestaurantService restaurant, KitchenService kitchen, ReservationService reservations) {
        this.restaurant = restaurant;
        this.kitchen = kitchen;
        this.reservations = reservations;
    }

    // ---------- Plano ----------

    @GetMapping("/floor")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public FloorDto floor() {
        FloorDto f = restaurant.floor();
        return f.with(reservations.upcomingByTable(com.tiendaposs.tenant.TenantContext.require()));
    }

    // ---------- Reservas ----------

    @GetMapping("/reservations")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public List<ReservationService.ReservationDto> reservations(@RequestParam(required = false) java.time.LocalDate date) {
        return reservations.day(date);
    }

    @PostMapping("/reservations")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public ReservationService.ReservationDto createReservation(@Valid @RequestBody ReservationService.ReservationRequest r) {
        return reservations.save(null, r);
    }

    @PutMapping("/reservations/{id}")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public ReservationService.ReservationDto updateReservation(@PathVariable Long id, @Valid @RequestBody ReservationService.ReservationRequest r) {
        return reservations.save(id, r);
    }

    @PostMapping("/reservations/{id}/status")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public ReservationService.ReservationDto reservationStatus(@PathVariable Long id, @Valid @RequestBody ReservationService.StatusRequest r) {
        return reservations.setStatus(id, r);
    }

    @PostMapping("/reservations/{id}/seat")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto seat(@PathVariable Long id, @RequestBody(required = false) ReservationService.SeatRequest r) {
        return reservations.seat(id, r);
    }

    @PostMapping("/areas")
    public AreaDto createArea(@Valid @RequestBody AreaRequest r) {
        return restaurant.saveArea(null, r);
    }

    @PutMapping("/areas/{id}")
    public AreaDto updateArea(@PathVariable Long id, @Valid @RequestBody AreaRequest r) {
        return restaurant.saveArea(id, r);
    }

    @DeleteMapping("/areas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArea(@PathVariable Long id) {
        restaurant.deleteArea(id);
    }

    @PostMapping("/tables")
    public TableDto createTable(@Valid @RequestBody TableRequest r) {
        return restaurant.saveTable(null, r);
    }

    @PutMapping("/tables/{id}")
    public TableDto updateTable(@PathVariable Long id, @Valid @RequestBody TableRequest r) {
        return restaurant.saveTable(id, r);
    }

    @PostMapping("/tables/{id}/qr")
    public TableDto newQr(@PathVariable Long id) {
        return restaurant.newQr(id);
    }

    @DeleteMapping("/tables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTable(@PathVariable Long id) {
        restaurant.deleteTable(id);
    }

    @PostMapping("/tables/{id}/open")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto openTable(@PathVariable Long id, @RequestBody(required = false) @Valid OpenTableRequest r) {
        return restaurant.openTable(id, r);
    }

    // ---------- Cuentas de mesa ----------

    @PostMapping("/accounts/{id}/dishes")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto addDish(@PathVariable Long id, @Valid @RequestBody DishRequest r) {
        return restaurant.addDish(id, r);
    }

    @PostMapping("/accounts/{id}/send")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public KitchenService.SendResult send(@PathVariable Long id) {
        return kitchen.send(id);
    }

    @PostMapping("/accounts/{id}/deliver")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public Map<String, Integer> deliver(@PathVariable Long id) {
        return Map.of("delivered", kitchen.deliverReady(id));
    }

    @PostMapping("/accounts/{id}/prebill")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto prebill(@PathVariable Long id) {
        return restaurant.prebill(id);
    }

    @PostMapping("/accounts/{id}/move")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto move(@PathVariable Long id, @Valid @RequestBody MoveRequest r) {
        return restaurant.move(id, r);
    }

    @PostMapping("/accounts/{id}/merge")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto merge(@PathVariable Long id, @Valid @RequestBody MergeRequest r) {
        return restaurant.merge(id, r);
    }

    @PostMapping("/accounts/{id}/split")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto split(@PathVariable Long id, @Valid @RequestBody SplitRequest r) {
        return restaurant.split(id, r);
    }

    @PostMapping("/accounts/{id}/split-equal")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public SaleDto splitEqual(@PathVariable Long id, @Valid @RequestBody SplitEqualRequest r) {
        return restaurant.splitEqual(id, r);
    }

    @PostMapping("/accounts/{id}/checkout")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto checkout(@PathVariable Long id, @Valid @RequestBody CheckoutRequest r) {
        return restaurant.checkout(id, r);
    }

    // ---------- Cocina ----------

    @GetMapping("/kitchen")
    @PreAuthorize("hasAnyAuthority('COCINA_VER','POS_VENDER')")
    public List<KitchenService.TicketDto> kitchen(@RequestParam(required = false) String station) {
        return kitchen.board(station);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/kitchen/{id}/status")
    @PreAuthorize("hasAnyAuthority('COCINA_VER','POS_VENDER')")
    public KitchenService.TicketDto status(@PathVariable Long id, @RequestBody StatusRequest r) {
        return kitchen.setStatus(id, r.status());
    }

    public record TipSettings(boolean tipsEnabled, @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("30") java.math.BigDecimal tipSuggestedPct) {
    }

    /** Propina voluntaria: si se ofrece en el cobro y el porcentaje sugerido (siempre opcional para el cliente). */
    @PutMapping("/settings")
    public FloorDto saveSettings(@Valid @RequestBody TipSettings r) {
        restaurant.saveTipSettings(r.tipsEnabled(), r.tipSuggestedPct());
        return restaurant.floor();
    }

    // ---------- Modificadores y estaciones ----------

    @GetMapping("/modifiers")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','MESAS_ATENDER')")
    public List<GroupDto> modifiers() {
        return restaurant.modifiers();
    }

    @PostMapping("/modifiers")
    public GroupDto createGroup(@Valid @RequestBody GroupRequest r) {
        return restaurant.saveGroup(null, r);
    }

    @PutMapping("/modifiers/{id}")
    public GroupDto updateGroup(@PathVariable Long id, @Valid @RequestBody GroupRequest r) {
        return restaurant.saveGroup(id, r);
    }

    @DeleteMapping("/modifiers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable Long id) {
        restaurant.deleteGroup(id);
    }

    @GetMapping("/stations")
    public List<StationCategoryDto> stations() {
        return restaurant.stations();
    }

    @PutMapping("/stations")
    public List<StationCategoryDto> saveStations(@RequestBody StationsRequest r) {
        return restaurant.saveStations(r);
    }
}
