package com.tiendaposs.billing;

import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

/** Suscripción de Ecomerxio POS: estado, pago con Wompi y verificación. Solo el dueño de la tienda paga. */
@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    public record StartRequest(String plan, String period) {
    }

    public record ConfirmRequest(String transactionId) {
    }

    @GetMapping
    public SubscriptionService.Status get() {
        requireOwner();
        return service.status(TenantContext.require());
    }

    @PostMapping("/checkout")
    public SubscriptionService.StartResult checkout(@RequestBody StartRequest req) {
        requireOwner();
        return service.start(TenantContext.require(), req.plan(), req.period());
    }

    @PostMapping("/confirm")
    public SubscriptionService.ConfirmResult confirm(@RequestBody ConfirmRequest req) {
        requireOwner();
        return service.confirm(TenantContext.require(), req.transactionId());
    }

    @PostMapping("/verify")
    public SubscriptionService.ConfirmResult verify() {
        requireOwner();
        return service.verifyOwn(TenantContext.require());
    }

    private static void requireOwner() {
        if (!CurrentUser.get().owner()) throw ApiException.forbidden("Solo el dueño de la tienda maneja la suscripción");
    }
}
