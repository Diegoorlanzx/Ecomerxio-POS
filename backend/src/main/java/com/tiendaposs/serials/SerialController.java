package com.tiendaposs.serials;

import com.tiendaposs.serials.SerialService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SerialController {

    private final SerialService service;

    public SerialController(SerialService service) {
        this.service = service;
    }

    @PostMapping("/products/{id}/serials")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public List<SerialDto> add(@PathVariable Long id, @Valid @RequestBody AddSerialsRequest r) {
        return service.add(id, r);
    }

    @GetMapping("/products/{id}/serials/available")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','INVENTARIO_VER','PRODUCTOS_VER')")
    public List<String> available(@PathVariable Long id) {
        return service.available(id);
    }

    @GetMapping("/serials")
    @PreAuthorize("hasAnyAuthority('POS_VENDER','POS_DEVOLUCION','INVENTARIO_VER','PRODUCTOS_VER')")
    public List<SerialDto> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long productId) {
        return service.search(q, productId);
    }

    @PostMapping("/serials/{id}/write-off")
    @PreAuthorize("hasAuthority('INVENTARIO_AJUSTAR')")
    public SerialDto writeOff(@PathVariable Long id, @Valid @RequestBody WriteOffRequest r) {
        return service.writeOff(id, r);
    }
}
