package com.tiendaposs.online;

import com.tiendaposs.online.OnlineOrderService.*;
import com.tiendaposs.online.OnlineStoreService.*;
import com.tiendaposs.pos.SaleDtos.SaleDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Tienda en línea vista por el personal: configuración, publicación y bandeja de pedidos. */
@RestController
@RequestMapping("/api")
public class OnlineController {

    private final OnlineStoreService store;
    private final OnlineOrderService orders;

    public OnlineController(OnlineStoreService store, OnlineOrderService orders) {
        this.store = store;
        this.orders = orders;
    }

    @GetMapping("/online-store")
    public ConfigDto config() {
        return store.config();
    }

    @PutMapping("/online-store")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ConfigDto update(@Valid @RequestBody ConfigRequest r) {
        return store.update(r);
    }

    @GetMapping("/online-store/products")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_VER','PRODUCTOS_EDITAR')")
    public List<PublishItem> products() {
        return store.publishList();
    }

    @PutMapping("/online-store/products")
    @PreAuthorize("hasAnyAuthority('PRODUCTOS_EDITAR','PRODUCTOS_PRECIOS')")
    public Map<String, Integer> publish(@RequestBody List<@Valid PublishRequest> r) {
        return Map.of("changed", store.publish(r));
    }

    @GetMapping("/online-orders")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public List<OrderDto> list(@RequestParam(required = false) String scope) {
        return orders.list(scope);
    }

    @GetMapping("/online-orders/counts")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public Counts counts() {
        return orders.counts();
    }

    @GetMapping("/online-orders/{id}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public OrderDto get(@PathVariable Long id) {
        return orders.get(id);
    }

    @PostMapping("/online-orders/{id}/accept")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public OrderDto accept(@PathVariable Long id) {
        return orders.accept(id);
    }

    @PostMapping("/online-orders/{id}/status/{to}")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public OrderDto advance(@PathVariable Long id, @PathVariable String to) {
        return orders.advance(id, to);
    }

    @PostMapping("/online-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public OrderDto cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest r) {
        return orders.cancel(id, r);
    }

    @PostMapping("/online-orders/{id}/deliver")
    @PreAuthorize("hasAuthority('POS_VENDER')")
    public SaleDto deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest r) {
        return orders.deliver(id, r);
    }
}
