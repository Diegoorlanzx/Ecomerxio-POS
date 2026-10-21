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

    public RestaurantController(RestaurantService restaurant, KitchenService kitchen) {
        this.restaurant = restaurant;
        this.kitchen = kitchen;
    }

    // ---------- Plano ----------

    @GetMapping("/floor")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public FloorDto floor() {
        return restaurant.floor();
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

    @DeleteMapping("/tables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTable(@PathVariable Long id) {
        restaurant.deleteTable(id);
    }

    @PostMapping("/tables/{id}/open")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto openTable(@PathVariable Long id, @RequestBody(required = false) @Valid OpenTableRequest r) {
        return restaurant.openTable(id, r);
    }

    // ---------- Cuentas de mesa ----------

    @PostMapping("/accounts/{id}/dishes")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto addDish(@PathVariable Long id, @Valid @RequestBody DishRequest r) {
        return restaurant.addDish(id, r);
    }

    @PostMapping("/accounts/{id}/send")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public KitchenService.SendResult send(@PathVariable Long id) {
        return kitchen.send(id);
    }

    @PostMapping("/accounts/{id}/deliver")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public Map<String, Integer> deliver(@PathVariable Long id) {
        return Map.of("delivered", kitchen.deliverReady(id));
    }

    @PostMapping("/accounts/{id}/prebill")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto prebill(@PathVariable Long id) {
        return restaurant.prebill(id);
    }

    @PostMapping("/accounts/{id}/move")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto move(@PathVariable Long id, @Valid @RequestBody MoveRequest r) {
        return restaurant.move(id, r);
    }

    @PostMapping("/accounts/{id}/merge")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto merge(@PathVariable Long id, @Valid @RequestBody MergeRequest r) {
        return restaurant.merge(id, r);
    }

    @PostMapping("/accounts/{id}/split")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto split(@PathVariable Long id, @Valid @RequestBody SplitRequest r) {
        return restaurant.split(id, r);
    }

    @PostMapping("/accounts/{id}/checkout")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto checkout(@PathVariable Long id, @Valid @RequestBody CheckoutRequest r) {
        return restaurant.checkout(id, r);
    }

    // ---------- Cocina ----------

    @GetMapping("/kitchen")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<KitchenService.TicketDto> kitchen(@RequestParam(required = false) String station) {
        return kitchen.board(station);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/kitchen/{id}/status")
    @PreAuthorize("hasAuthority('POS_VENDER')")
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
    @PreAuthorize("hasAuthority('POS_VENDER')")
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
