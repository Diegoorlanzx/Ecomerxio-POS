package com.tiendaposs.counter;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Mostrador: pantalla de pedidos del día por número de turno. */
@RestController
@RequestMapping("/api/counter/orders")
@PreAuthorize("hasAuthority('POS_VENDER')")
public class CounterController {

    private final CounterService counter;

    public CounterController(CounterService counter) {
        this.counter = counter;
    }

    public record StatusRequest(String status) {
    }

    @GetMapping
    public List<CounterService.CounterOrder> today() {
        return counter.today();
    }

    @PostMapping("/{saleId}/status")
    public List<CounterService.CounterOrder> status(@PathVariable Long saleId, @RequestBody StatusRequest r) {
        counter.setStatus(saleId, r.status());
        return counter.today();
    }
}
