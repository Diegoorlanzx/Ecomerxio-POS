package com.tiendaposs.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Redondeo de dinero según la moneda: el peso colombiano y otros no usan centavos en la práctica. */
public final class Money {

    private static final Set<String> NO_DECIMALS = Set.of("COP", "CLP", "PYG", "ARS");

    private Money() {
    }

    public static int scale(String currency) {
        return currency != null && NO_DECIMALS.contains(currency) ? 0 : 2;
    }

    public static BigDecimal round(BigDecimal v, int scale) {
        return (v == null ? BigDecimal.ZERO : v).setScale(scale, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static String fmt(BigDecimal v) {
        BigDecimal x = nz(v).setScale(0, RoundingMode.HALF_UP);
        String s = x.abs().toPlainString().replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
        return (x.signum() < 0 ? "-$" : "$") + s;
    }
}
