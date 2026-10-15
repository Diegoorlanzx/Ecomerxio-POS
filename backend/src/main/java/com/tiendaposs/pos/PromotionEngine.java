package com.tiendaposs.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Elige y calcula la mejor promoción vigente para una línea. Reglas:
 * <ul>
 *   <li>PORCENTAJE: % sobre la línea (si cumple la cantidad mínima, cuando la hay). Aplica a cualquier presentación.</li>
 *   <li>PRECIO_POR_CANTIDAD: cada grupo de N unidades base cuesta $X (p. ej. 3 cervezas por $10.000). Solo unidad base.</li>
 *   <li>LLEVA_PAGA: lleva N, paga M (p. ej. 3x2). Solo unidad base.</li>
 * </ul>
 * La promoción puede ser de un producto o de una categoría (incluye sus subcategorías).
 */
public final class PromotionEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PromotionEngine() {
    }

    public record Applied(Promotion promo, BigDecimal discount) {
    }

    public static Applied best(List<Promotion> promos, Long productId, List<Long> categoryChain, BigDecimal factor,
                               BigDecimal unitPrice, BigDecimal qty, LocalDate today, int scale) {
        Applied best = null;
        for (Promotion p : promos) {
            if (!p.active) continue;
            if (p.startsOn != null && today.isBefore(p.startsOn)) continue;
            if (p.endsOn != null && today.isAfter(p.endsOn)) continue;
            boolean matches = (p.productId != null && p.productId.equals(productId))
                    || (p.productId == null && p.categoryId != null && categoryChain.contains(p.categoryId));
            if (!matches) continue;
            BigDecimal d = discount(p, factor, unitPrice, qty, scale);
            if (d.signum() > 0 && (best == null || d.compareTo(best.discount()) > 0)) best = new Applied(p, d);
        }
        return best;
    }

    static BigDecimal discount(Promotion p, BigDecimal factor, BigDecimal unitPrice, BigDecimal qty, int scale) {
        BigDecimal gross = unitPrice.multiply(qty);
        boolean base = factor.compareTo(BigDecimal.ONE) == 0;
        switch (p.type) {
            case "PORCENTAJE" -> {
                if (p.percent == null) return BigDecimal.ZERO;
                if (p.minQty != null && qty.multiply(factor).compareTo(p.minQty) < 0) return BigDecimal.ZERO;
                return gross.multiply(p.percent).divide(HUNDRED, scale, RoundingMode.HALF_UP);
            }
            case "PRECIO_POR_CANTIDAD" -> {
                if (!base || p.bundleQty == null || p.bundlePrice == null || p.bundleQty.signum() <= 0) return BigDecimal.ZERO;
                BigDecimal groups = qty.divide(p.bundleQty, 0, RoundingMode.DOWN);
                BigDecimal normal = unitPrice.multiply(p.bundleQty);
                BigDecimal saving = normal.subtract(p.bundlePrice);
                return saving.signum() <= 0 ? BigDecimal.ZERO : groups.multiply(saving).setScale(scale, RoundingMode.HALF_UP);
            }
            case "LLEVA_PAGA" -> {
                if (!base || p.bundleQty == null || p.payQty == null || p.payQty.compareTo(p.bundleQty) >= 0) return BigDecimal.ZERO;
                BigDecimal groups = qty.divide(p.bundleQty, 0, RoundingMode.DOWN);
                BigDecimal free = groups.multiply(p.bundleQty.subtract(p.payQty));
                return free.multiply(unitPrice).setScale(scale, RoundingMode.HALF_UP);
            }
            default -> {
                return BigDecimal.ZERO;
            }
        }
    }
}
