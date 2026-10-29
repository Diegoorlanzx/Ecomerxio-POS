package com.tiendaposs.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Aplica las promociones vigentes a toda la venta. Reglas:
 * <ul>
 *   <li>PORCENTAJE: % sobre la línea (si cumple la cantidad mínima, cuando la hay). Aplica a cualquier presentación.</li>
 *   <li>PRECIO_POR_CANTIDAD: cada grupo de N unidades base cuesta $X (p. ej. 3 cervezas por $10.000). Solo unidad base.</li>
 *   <li>LLEVA_PAGA: lleva N, paga M (p. ej. 3x2). Solo unidad base.</li>
 *   <li>COMPRA_LLEVA: por cada N unidades del producto (o categoría) que compra, M unidades de OTRO producto salen con
 *   el % de descuento (100 = gratis). Ej.: compra una gaseosa y lleva un ponqué a mitad de precio.</li>
 * </ul>
 * Cada línea lleva una sola promoción: primero las de "compra y lleva" (las más específicas) y luego, en las líneas que
 * quedan libres, la que más ahorra. La promoción puede ser de un producto o de una categoría (incluye subcategorías).
 * <p>
 * Cupo: una promoción con {@code usageLimit} solo se aplica las veces que le quedan. Una "vez" es una unidad con
 * descuento (PORCENTAJE), un paquete (PRECIO_POR_CANTIDAD, LLEVA_PAGA) o un combo (COMPRA_LLEVA). Cuando se acaba, la
 * venta sigue a precio normal. La copia en la caja (frontend/src/lib/cart.ts) hace exactamente lo mismo.
 */
public final class PromotionEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PromotionEngine() {
    }

    /** Una línea de la venta: {@code eligible} = admite promoción (sin precio digitado a mano ni marcada "sin promo"). */
    public record LineView(Long productId, List<Long> categoryChain, BigDecimal factor, BigDecimal unitPrice, BigDecimal qty,
                           boolean eligible) {
    }

    public record Applied(Promotion promo, BigDecimal discount) {
    }

    /** Promoción de cada línea (null = ninguna) y cuántas veces se usó cada promoción en esta venta. */
    public record Result(List<Applied> lines, Map<Long, BigDecimal> uses) {
    }

    private record Pick(BigDecimal discount, BigDecimal uses) {
    }

    public static Result apply(List<Promotion> promos, List<LineView> lines, LocalDate today, int scale) {
        List<Promotion> live = promos.stream().filter(p -> p.active && (p.startsOn == null || !today.isBefore(p.startsOn))
                && (p.endsOn == null || !today.isAfter(p.endsOn))).sorted(Comparator.comparing(p -> p.id == null ? 0L : p.id)).toList();
        Map<Long, BigDecimal> left = new HashMap<>();
        for (Promotion p : live) if (p.usageLimit != null) left.put(p.id, remaining(p));
        Applied[] out = new Applied[lines.size()];
        Map<Long, BigDecimal> uses = new LinkedHashMap<>();

        // 1) Compra X, lleva Y.
        for (Promotion p : live) {
            if (!"COMPRA_LLEVA".equals(p.type) || !validReward(p)) continue;
            BigDecimal bought = BigDecimal.ZERO;
            for (LineView l : lines) {
                if (l.eligible() && !p.rewardProductId.equals(l.productId()) && matches(p, l)) bought = bought.add(l.qty().multiply(l.factor()));
            }
            BigDecimal combos = bought.divide(p.bundleQty, 0, RoundingMode.DOWN);
            if (left.containsKey(p.id)) combos = combos.min(left.get(p.id).setScale(0, RoundingMode.DOWN));
            BigDecimal toGive = combos.multiply(p.rewardQty);
            BigDecimal given = BigDecimal.ZERO;
            for (int i = 0; i < lines.size() && given.compareTo(toGive) < 0; i++) {
                LineView l = lines.get(i);
                if (out[i] != null || !l.eligible() || !p.rewardProductId.equals(l.productId()) || l.factor().compareTo(BigDecimal.ONE) != 0) continue;
                BigDecimal units = l.qty().min(toGive.subtract(given));
                BigDecimal d = units.multiply(l.unitPrice()).multiply(p.percent).divide(HUNDRED, scale, RoundingMode.HALF_UP);
                if (d.signum() <= 0) continue;
                out[i] = new Applied(p, d);
                given = given.add(units);
            }
            if (given.signum() > 0) use(p, given.divide(p.rewardQty, 0, RoundingMode.UP), left, uses);
        }

        // 2) En cada línea libre, la promoción de un solo producto que más ahorra.
        for (int i = 0; i < lines.size(); i++) {
            LineView l = lines.get(i);
            if (out[i] != null || !l.eligible()) continue;
            Promotion bestPromo = null;
            Pick best = null;
            for (Promotion p : live) {
                if ("COMPRA_LLEVA".equals(p.type) || !matches(p, l)) continue;
                Pick k = pick(p, l.factor(), l.unitPrice(), l.qty(), scale, left.get(p.id));
                if (k.discount().signum() > 0 && (best == null || k.discount().compareTo(best.discount()) > 0)) {
                    best = k;
                    bestPromo = p;
                }
            }
            if (bestPromo != null) {
                out[i] = new Applied(bestPromo, best.discount());
                use(bestPromo, best.uses(), left, uses);
            }
        }
        return new Result(Arrays.asList(out), uses);
    }

    /** Copia suelta (no la guarda JPA) para calcular con otro cupo. */
    static Promotion copy(Promotion p) {
        Promotion c = new Promotion();
        c.id = p.id;
        c.tenantId = p.tenantId;
        c.name = p.name;
        c.type = p.type;
        c.productId = p.productId;
        c.categoryId = p.categoryId;
        c.percent = p.percent;
        c.minQty = p.minQty;
        c.bundleQty = p.bundleQty;
        c.bundlePrice = p.bundlePrice;
        c.payQty = p.payQty;
        c.rewardProductId = p.rewardProductId;
        c.rewardQty = p.rewardQty;
        c.usageLimit = p.usageLimit;
        c.usedCount = p.usedCount;
        c.startsOn = p.startsOn;
        c.endsOn = p.endsOn;
        c.active = p.active;
        return c;
    }

    /** Lo que le queda de cupo (null = sin límite). */
    public static BigDecimal remaining(Promotion p) {
        if (p.usageLimit == null) return null;
        BigDecimal r = p.usageLimit.subtract(p.usedCount == null ? BigDecimal.ZERO : p.usedCount);
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }

    private static void use(Promotion p, BigDecimal n, Map<Long, BigDecimal> left, Map<Long, BigDecimal> uses) {
        uses.merge(p.id, n, BigDecimal::add);
        left.computeIfPresent(p.id, (k, v) -> v.subtract(n));
    }

    private static boolean matches(Promotion p, LineView l) {
        return (p.productId != null && p.productId.equals(l.productId()))
                || (p.productId == null && p.categoryId != null && l.categoryChain().contains(p.categoryId));
    }

    private static boolean validReward(Promotion p) {
        return p.rewardProductId != null && p.bundleQty != null && p.bundleQty.signum() > 0 && p.rewardQty != null
                && p.rewardQty.signum() > 0 && p.percent != null && p.percent.signum() > 0;
    }

    /** Descuento de una promoción de un solo producto sobre una línea, sin cupo. */
    static BigDecimal discount(Promotion p, BigDecimal factor, BigDecimal unitPrice, BigDecimal qty, int scale) {
        return pick(p, factor, unitPrice, qty, scale, null).discount();
    }

    /** Descuento y veces usadas, sin pasarse de {@code left} (null = sin límite). */
    private static Pick pick(Promotion p, BigDecimal factor, BigDecimal unitPrice, BigDecimal qty, int scale, BigDecimal left) {
        Pick none = new Pick(BigDecimal.ZERO, BigDecimal.ZERO);
        if (left != null && left.signum() <= 0) return none;
        BigDecimal gross = unitPrice.multiply(qty);
        boolean base = factor.compareTo(BigDecimal.ONE) == 0;
        switch (p.type) {
            case "PORCENTAJE" -> {
                if (p.percent == null) return none;
                BigDecimal units = qty.multiply(factor);
                if (p.minQty != null && units.compareTo(p.minQty) < 0) return none;
                if (left == null || left.compareTo(units) >= 0) {
                    return new Pick(gross.multiply(p.percent).divide(HUNDRED, scale, RoundingMode.HALF_UP), units);
                }
                // Solo le quedan algunas unidades: el descuento va sobre esas.
                return new Pick(gross.multiply(p.percent).multiply(left).divide(HUNDRED.multiply(units), scale, RoundingMode.HALF_UP), left);
            }
            case "PRECIO_POR_CANTIDAD" -> {
                if (!base || p.bundleQty == null || p.bundlePrice == null || p.bundleQty.signum() <= 0) return none;
                BigDecimal groups = cap(qty.divide(p.bundleQty, 0, RoundingMode.DOWN), left);
                BigDecimal saving = unitPrice.multiply(p.bundleQty).subtract(p.bundlePrice);
                return saving.signum() <= 0 ? none : new Pick(groups.multiply(saving).setScale(scale, RoundingMode.HALF_UP), groups);
            }
            case "LLEVA_PAGA" -> {
                if (!base || p.bundleQty == null || p.payQty == null || p.payQty.compareTo(p.bundleQty) >= 0) return none;
                BigDecimal groups = cap(qty.divide(p.bundleQty, 0, RoundingMode.DOWN), left);
                BigDecimal free = groups.multiply(p.bundleQty.subtract(p.payQty));
                return new Pick(free.multiply(unitPrice).setScale(scale, RoundingMode.HALF_UP), groups);
            }
            default -> {
                return none;
            }
        }
    }

    private static BigDecimal cap(BigDecimal groups, BigDecimal left) {
        return left == null ? groups : groups.min(left.setScale(0, RoundingMode.DOWN));
    }
}
