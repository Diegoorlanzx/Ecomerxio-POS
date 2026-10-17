package com.tiendaposs.tenant;

import java.util.List;
import java.util.Locale;

/**
 * Planes de Ecomerxio POS. Fuente única de precios y límites; la landing de Ecomerxio
 * (ecommerce-platform, `lib/plans.ts` → POS_PLANS) muestra los mismos datos y debe ir en sync.
 * Los límites de cajas y usuarios todavía no se hacen cumplir: eso llega con el cobro de la suscripción.
 */
public final class Plans {

    public static final int TRIAL_DAYS = 15;
    /** Sin límite. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    public record Plan(String code, String name, long priceMonthly, long priceAnnual, int maxRegisters, int maxUsers) {
    }

    public static final List<Plan> ALL = List.of(
            new Plan("TIENDA", "Tienda", 39_900, 399_000, 1, 3),
            new Plan("NEGOCIO", "Negocio", 69_900, 699_000, 3, 10),
            new Plan("PRO", "Pro", 119_900, 1_199_000, UNLIMITED, UNLIMITED));

    /** Plan por defecto de quien se registra sin elegir: el recomendado. */
    public static final String DEFAULT = "NEGOCIO";

    private Plans() {
    }

    public static Plan of(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return ALL.stream().filter(p -> p.code().equals(c)).findFirst()
                .orElseGet(() -> ALL.stream().filter(p -> p.code().equals(DEFAULT)).findFirst().orElseThrow());
    }
}
