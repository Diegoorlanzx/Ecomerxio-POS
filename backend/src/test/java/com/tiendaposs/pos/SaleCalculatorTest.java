package com.tiendaposs.pos;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleCalculatorTest {

    private static SaleCalculator.LineInput line(String price, String qty, String pct, String tax) {
        return new SaleCalculator.LineInput(new BigDecimal(price), new BigDecimal(qty), BigDecimal.ONE, BigDecimal.ZERO,
                new BigDecimal(pct), new BigDecimal(tax), BigDecimal.ZERO);
    }

    @Test
    void taxIncludedIsExtractedAndTotalIsWhatCustomerPays() {
        var r = SaleCalculator.calculate(List.of(line("11900", "1", "0", "19")), true, null, null, 0);
        assertThat(r.total()).isEqualByComparingTo("11900");
        assertThat(r.taxTotal()).isEqualByComparingTo("1900");
    }

    @Test
    void taxExcludedIsAddedOnTop() {
        var r = SaleCalculator.calculate(List.of(line("10000", "2", "0", "19")), false, null, null, 0);
        assertThat(r.subtotal()).isEqualByComparingTo("20000");
        assertThat(r.taxTotal()).isEqualByComparingTo("3800");
        assertThat(r.total()).isEqualByComparingTo("23800");
    }

    @Test
    void saleDiscountIsProratedExactly() {
        var r = SaleCalculator.calculate(List.of(line("3333", "1", "0", "0"), line("3333", "1", "0", "0"),
                line("3334", "1", "0", "0")), true, null, new BigDecimal("1000"), 0);
        assertThat(r.discountTotal()).isEqualByComparingTo("1000");
        assertThat(r.total()).isEqualByComparingTo("9000");
        BigDecimal sum = r.lines().stream().map(SaleCalculator.LineResult::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1000");
    }

    @Test
    void promoThenLineDiscountAndFixedTax() {
        // 6 cervezas a 3.500 con promo de 3.500 de descuento y 10% manual sobre el resto; impuesto fijo de 50 por unidad.
        var in = new SaleCalculator.LineInput(new BigDecimal("3500"), new BigDecimal("6"), BigDecimal.ONE,
                new BigDecimal("3500"), new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("50"));
        var r = SaleCalculator.calculate(List.of(in), true, null, null, 0);
        // bruto 21.000 - promo 3.500 = 17.500; -10% = 1.750 -> 15.750
        assertThat(r.total()).isEqualByComparingTo("15750");
        assertThat(r.taxTotal()).isEqualByComparingTo("300");
    }

    @Test
    void decimalQuantitiesForWeight() {
        var r = SaleCalculator.calculate(List.of(line("12000", "0.750", "0", "0")), true, null, null, 0);
        assertThat(r.total()).isEqualByComparingTo("9000");
    }

    @Test
    void discountCannotExceedSale() {
        assertThatThrownBy(() -> SaleCalculator.calculate(List.of(line("1000", "1", "0", "0")), true, null, new BigDecimal("2000"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promotionsRules() {
        Promotion threeForTwo = new Promotion();
        threeForTwo.type = "LLEVA_PAGA";
        threeForTwo.bundleQty = new BigDecimal("3");
        threeForTwo.payQty = new BigDecimal("2");
        assertThat(PromotionEngine.discount(threeForTwo, BigDecimal.ONE, new BigDecimal("3000"), new BigDecimal("7"), 0))
                .isEqualByComparingTo("6000");
        Promotion bundle = new Promotion();
        bundle.type = "PRECIO_POR_CANTIDAD";
        bundle.bundleQty = new BigDecimal("3");
        bundle.bundlePrice = new BigDecimal("10000");
        assertThat(PromotionEngine.discount(bundle, BigDecimal.ONE, new BigDecimal("3500"), new BigDecimal("4"), 0))
                .isEqualByComparingTo("500");
        // No aplica a presentaciones (caja x24).
        assertThat(PromotionEngine.discount(bundle, new BigDecimal("24"), new BigDecimal("80000"), new BigDecimal("1"), 0))
                .isEqualByComparingTo("0");
    }
}
