package com.tiendaposs.tenant;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Planes de Ecomerxio POS. Los precios y topes viven en la tabla `plans` y se editan desde el Super Admin de Ecomerxio
 * ({@link PlanCatalog} los carga aquí al arrancar y tras cada cambio); la landing de Ecomerxio los lee de
 * `/api/public/plans`. Cada tienda conserva el precio con que entró a su plan ({@link Tenant#assignPlan}).
 * Los topes de cajas y usuarios activos los hace cumplir {@link PlanLimits}.
 */
public final class Plans {

    public static final int TRIAL_DAYS = 15;
    /** Sin límite. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    public record Plan(String code, String name, long priceMonthly, long priceAnnual, int maxRegisters, int maxUsers) {
    }

    /** Los de la migración V42: sirven solo si la tabla todavía no se ha podido leer. */
    static final List<Plan> DEFAULTS = List.of(
            new Plan("TIENDA", "Tienda", 39_900, 399_000, 1, 3),
            new Plan("NEGOCIO", "Negocio", 69_900, 699_000, 3, 10),
            new Plan("PRO", "Pro", 119_900, 1_199_000, UNLIMITED, UNLIMITED));

    /** Plan por defecto de quien se registra sin elegir: el recomendado. */
    public static final String DEFAULT = "NEGOCIO";

    private static volatile List<Plan> current = DEFAULTS;

    private Plans() {
    }

    public static List<Plan> all() {
        return current;
    }

    static void replace(List<Plan> plans) {
        if (!plans.isEmpty()) current = List.copyOf(plans);
    }

    public static Optional<Plan> find(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return current.stream().filter(p -> p.code().equals(c)).findFirst();
    }

    public static Plan of(String code) {
        return find(code).orElseGet(() -> find(DEFAULT).orElseThrow());
    }

    /** Lo que paga esta tienda por el plan: su precio guardado si es su plan actual y ya pagó alguna vez; en prueba o
     * en otro plan, el del catálogo de hoy (el precio se congela con el primer pago, no al registrarse). */
    public static long price(Tenant t, Plan p, boolean annual) {
        Long kept = annual ? t.getPlanPriceAnnual() : t.getPlanPriceMonthly();
        if (p.code().equals(t.getPlan()) && kept != null && t.getPaidUntil() != null) return kept;
        return annual ? p.priceAnnual() : p.priceMonthly();
    }
}
