package com.tiendaposs.tenant;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Precios y topes de los planes, sin sesión: los muestran el registro del POS y la landing de Ecomerxio. */
@RestController
public class PublicPlansController {

    /** Topes en null = sin límite. */
    public record PublicPlan(String code, String name, long priceMonthly, long priceAnnual, Integer maxRegisters, Integer maxUsers) {
    }

    @GetMapping("/api/public/plans")
    public ResponseEntity<List<PublicPlan>> plans() {
        List<PublicPlan> body = Plans.all().stream().map(p -> new PublicPlan(p.code(), p.name(), p.priceMonthly(), p.priceAnnual(),
                p.maxRegisters() == Plans.UNLIMITED ? null : p.maxRegisters(), p.maxUsers() == Plans.UNLIMITED ? null : p.maxUsers())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(body);
    }
}
