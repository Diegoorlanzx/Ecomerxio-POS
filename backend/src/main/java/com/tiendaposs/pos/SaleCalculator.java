package com.tiendaposs.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Cálculo puro de una venta (sin base de datos): promociones y descuentos por línea, descuento de
 * venta repartido, impuesto porcentual (incluido o no en el precio) e impuesto fijo por unidad.
 * El servidor recalcula siempre; nunca confía en los totales que manda la pantalla.
 *
 * <p>Invariantes: total = subtotal − descuentos (precios con impuesto incluido) o
 * subtotal − descuentos + impuestos (sin incluir); la suma de lo repartido del descuento de venta es
 * exactamente el descuento pedido. Valores al cliente redondeados a la escala de la moneda
 * (0 en COP); el impuesto incluido se informa con 2 decimales.</p>
 *
 * <p>Adaptado del calculador del POS de Ecomerxio (cantidades decimales, promociones e impuesto fijo).</p>
 */
public final class SaleCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SaleCalculator() {
    }

    /**
     * @param unitPrice     precio cobrado por unidad de la presentación
     * @param quantity      cantidad en unidades de la presentación (puede ser decimal: venta por peso)
     * @param factor        unidades base por presentación (para el impuesto fijo)
     * @param promoDiscount descuento de promoción ya calculado para la línea (monto)
     * @param discountPct   descuento manual de la línea, en %, sobre lo que queda tras la promoción
     * @param fixedTax      impuesto fijo por unidad base
     */
    public record LineInput(BigDecimal unitPrice, BigDecimal quantity, BigDecimal factor, BigDecimal promoDiscount,
                            BigDecimal discountPct, BigDecimal taxRate, BigDecimal fixedTax) {
    }

    public record LineResult(BigDecimal gross, BigDecimal discount, BigDecimal tax, BigDecimal lineTotal) {
    }

    public record Result(List<LineResult> lines, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal,
                         BigDecimal total, BigDecimal saleDiscount) {
    }

    public static Result calculate(List<LineInput> inputs, boolean pricesIncludeTax, BigDecimal saleDiscountPct,
                                   BigDecimal saleDiscountAmount, int scale) {
        int n = inputs.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] lineDisc = new BigDecimal[n];
        BigDecimal[] after = new BigDecimal[n];
        BigDecimal sumAfter = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            gross[i] = in.unitPrice().multiply(in.quantity()).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal promo = nz(in.promoDiscount()).setScale(scale, RoundingMode.HALF_UP).min(gross[i]);
            BigDecimal rest = gross[i].subtract(promo);
            BigDecimal manual = rest.multiply(nz(in.discountPct())).divide(HUNDRED, scale, RoundingMode.HALF_UP);
            lineDisc[i] = promo.add(manual);
            after[i] = gross[i].subtract(lineDisc[i]);
            sumAfter = sumAfter.add(after[i]);
        }
        BigDecimal saleDisc = saleDiscount(saleDiscountPct, saleDiscountAmount, sumAfter, scale);
        BigDecimal[] share = prorate(saleDisc, after, sumAfter);

        List<LineResult> lines = new ArrayList<>(n);
        BigDecimal subtotal = BigDecimal.ZERO, discountTotal = BigDecimal.ZERO, taxTotal = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            BigDecimal discount = lineDisc[i].add(share[i]);
            BigDecimal net = gross[i].subtract(discount);
            BigDecimal rate = nz(in.taxRate());
            BigDecimal fixed = nz(in.fixedTax()).multiply(nz(in.factor()).signum() == 0 ? BigDecimal.ONE : in.factor())
                    .multiply(in.quantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax, lineTotal;
            if (pricesIncludeTax) {
                BigDecimal taxable = net.subtract(fixed).max(BigDecimal.ZERO);
                BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 10, RoundingMode.HALF_UP));
                BigDecimal pct = taxable.subtract(taxable.divide(divisor, 2, RoundingMode.HALF_UP));
                tax = pct.add(fixed.min(net)).setScale(2, RoundingMode.HALF_UP);
                lineTotal = net;
            } else {
                tax = net.multiply(rate).divide(HUNDRED, scale, RoundingMode.HALF_UP).add(fixed).setScale(scale, RoundingMode.HALF_UP);
                lineTotal = net.add(tax);
            }
            lines.add(new LineResult(s2(gross[i]), s2(discount), s2(tax), s2(lineTotal)));
            subtotal = subtotal.add(gross[i]);
            discountTotal = discountTotal.add(discount);
            taxTotal = taxTotal.add(tax);
            total = total.add(lineTotal);
        }
        return new Result(lines, s2(subtotal), s2(discountTotal), s2(taxTotal), s2(total), s2(saleDisc));
    }

    /** % efectivo de descuento manual de la venta (para validar contra el límite del cajero). */
    public static BigDecimal effectivePct(BigDecimal discount, BigDecimal base) {
        if (base.signum() == 0) return BigDecimal.ZERO;
        return discount.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal saleDiscount(BigDecimal pct, BigDecimal amount, BigDecimal base, int scale) {
        if (pct != null && pct.signum() != 0 && amount != null && amount.signum() != 0) {
            throw new IllegalArgumentException("El descuento de la venta es un porcentaje o un monto, no ambos");
        }
        BigDecimal v = BigDecimal.ZERO;
        if (pct != null && pct.signum() != 0) {
            if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) throw new IllegalArgumentException("El porcentaje de descuento debe estar entre 0 y 100");
            v = base.multiply(pct).divide(HUNDRED, scale, RoundingMode.HALF_UP);
        } else if (amount != null && amount.signum() != 0) {
            if (amount.signum() < 0) throw new IllegalArgumentException("El descuento no puede ser negativo");
            v = amount.setScale(scale, RoundingMode.HALF_UP);
        }
        if (v.compareTo(base) > 0) throw new IllegalArgumentException("El descuento no puede superar el valor de la venta");
        return v;
    }

    /** Reparte {@code amount} en proporción a {@code weights}; la línea de mayor peso absorbe el residuo. */
    private static BigDecimal[] prorate(BigDecimal amount, BigDecimal[] weights, BigDecimal sum) {
        BigDecimal[] r = new BigDecimal[weights.length];
        for (int i = 0; i < r.length; i++) r[i] = BigDecimal.ZERO;
        if (amount.signum() == 0 || sum.signum() == 0) return r;
        int biggest = 0;
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < weights.length; i++) {
            r[i] = amount.multiply(weights[i]).divide(sum, amount.scale(), RoundingMode.DOWN);
            assigned = assigned.add(r[i]);
            if (weights[i].compareTo(weights[biggest]) > 0) biggest = i;
        }
        r[biggest] = r[biggest].add(amount.subtract(assigned));
        return r;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal s2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
