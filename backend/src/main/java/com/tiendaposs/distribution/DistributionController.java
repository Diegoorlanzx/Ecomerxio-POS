package com.tiendaposs.distribution;

import com.tiendaposs.distribution.DistributionService.*;
import com.tiendaposs.distribution.PresaleService.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Fase 12 — Distribución: listas de precios, rutas, preventa, alistamiento, despachos, entregas y reportes. */
@RestController
@RequestMapping("/api/distribution")
public class DistributionController {

    private final DistributionService distribution;
    private final PresaleService presale;

    public DistributionController(DistributionService distribution, PresaleService presale) {
        this.distribution = distribution;
        this.presale = presale;
    }

    // ---------- Configuración ----------

    @GetMapping("/price-lists")
    public List<PriceListDto> priceLists() {
        return distribution.priceLists();
    }

    @PostMapping("/price-lists")
    public PriceListDto createPriceList(@Valid @RequestBody PriceListRequest r) {
        return distribution.savePriceList(null, r);
    }

    @PutMapping("/price-lists/{id}")
    public PriceListDto updatePriceList(@PathVariable Long id, @Valid @RequestBody PriceListRequest r) {
        return distribution.savePriceList(id, r);
    }

    @GetMapping("/routes")
    public List<RouteDto> routes() {
        return distribution.routes();
    }

    @PostMapping("/routes")
    public RouteDto createRoute(@Valid @RequestBody RouteRequest r) {
        return distribution.saveRoute(null, r);
    }

    @PutMapping("/routes/{id}")
    public RouteDto updateRoute(@PathVariable Long id, @Valid @RequestBody RouteRequest r) {
        return distribution.saveRoute(id, r);
    }

    @GetMapping("/customers")
    public List<RouteCustomer> customers() {
        return distribution.routeCustomers();
    }

    @PutMapping("/customers/{id}")
    public RouteCustomer assign(@PathVariable Long id, @RequestBody CustomerRouteRequest r) {
        return distribution.assign(id, r);
    }

    // ---------- Vendedor ----------

    @GetMapping("/seller")
    public SellerData seller() {
        return distribution.sellerData();
    }

    @PostMapping("/orders")
    public OrderDto createOrder(@Valid @RequestBody OrderRequest r) {
        return presale.create(r);
    }

    @PostMapping("/visits")
    public Map<String, Object> visit(@Valid @RequestBody VisitRequest r) {
        return presale.visitWithoutOrder(r);
    }

    // ---------- Pedidos, alistamiento y despacho ----------

    @GetMapping("/orders")
    public List<OrderSummary> orders(@RequestParam(required = false) String status) {
        return presale.list(status);
    }

    @GetMapping("/orders/{id}")
    public OrderDto order(@PathVariable Long id) {
        return presale.get(id);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderDto cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest r) {
        return presale.cancel(id, r);
    }

    @GetMapping("/pick-list")
    public PickList pickList(@RequestParam String ids) {
        return presale.pickList(Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList());
    }

    @PostMapping("/pick")
    public PickList pick(@Valid @RequestBody IdsRequest r) {
        return presale.pick(r);
    }

    @GetMapping("/dispatches")
    public List<DispatchDto> dispatches(@RequestParam(required = false) String status) {
        return presale.dispatches(status);
    }

    @GetMapping("/dispatches/{id}")
    public DispatchDto dispatch(@PathVariable Long id) {
        return presale.dispatch(id);
    }

    @PostMapping("/dispatches")
    public DispatchDto createDispatch(@Valid @RequestBody DispatchRequest r) {
        return presale.dispatch(r);
    }

    @PostMapping("/orders/{id}/deliver")
    public OrderDto deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest r) {
        return presale.deliver(id, r);
    }

    @PostMapping("/dispatches/{id}/close")
    public DispatchDto close(@PathVariable Long id, @Valid @RequestBody CloseRequest r) {
        return presale.close(id, r);
    }

    // ---------- Reportes ----------

    @GetMapping("/report")
    public Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return distribution.report(from, to);
    }
}
