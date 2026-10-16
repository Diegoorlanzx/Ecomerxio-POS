package com.tiendaposs.online;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.auth.RateLimiter;
import com.tiendaposs.online.OnlineOrderService.*;
import com.tiendaposs.online.OnlineStoreService.*;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/** Rutas públicas (sin sesión) de la vitrina: catálogo, fotos, hacer y seguir un pedido. */
@RestController
@RequestMapping("/api/public")
public class PublicStoreController {

    private final OnlineStoreService store;
    private final OnlineOrderService orders;
    private final RateLimiter limiter;

    public PublicStoreController(OnlineStoreService store, OnlineOrderService orders, RateLimiter limiter) {
        this.store = store;
        this.orders = orders;
        this.limiter = limiter;
    }

    @GetMapping("/stores/{slug}")
    public ResponseEntity<PublicStore> catalog(@PathVariable String slug) {
        // Corto: las existencias y el horario cambian, pero varios clientes seguidos no recargan todo.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(20)).cachePublic()).body(store.publicStore(slug));
    }

    @GetMapping("/stores/{slug}/photo/{productId}")
    public ResponseEntity<byte[]> photo(@PathVariable String slug, @PathVariable Long productId) {
        return image(store.photo(slug, productId));
    }

    @GetMapping("/stores/{slug}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String slug) {
        return image(store.logo(slug));
    }

    private static ResponseEntity<byte[]> image(Image i) {
        // La URL lleva la versión (?v=): se puede guardar mucho tiempo.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .contentType(MediaType.parseMediaType(i.contentType())).body(i.bytes());
    }

    @PostMapping("/stores/{slug}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicOrderDto place(@PathVariable String slug, @Valid @RequestBody OrderRequest r) {
        limiter.check("online-order", AuditService.clientIp());
        return orders.place(slug, r);
    }

    @GetMapping("/orders/{token}")
    public PublicOrderDto track(@PathVariable String token) {
        return orders.track(token);
    }
}
