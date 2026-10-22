package com.tiendaposs.payroll;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 16F: liquidación con valores de 2025 (mínimo $1.423.500, auxilio $200.000, jornada de 220 horas). */
class PayrollCalculatorTest {

    final PayrollCalculator.Params p2025 = new PayrollCalculator.Params(new BigDecimal("1423500"), new BigDecimal("200000"),
            new BigDecimal("220"), new BigDecimal("80"), false);
    final PayrollCalculator.Worker minimum = new PayrollCalculator.Worker(new BigDecimal("1423500"), false, 1, LocalDate.of(2024, 1, 10), null);
    final YearMonth march = YearMonth.of(2025, 3);

    private static BigDecimal item(List<PayrollCalculator.Item> items, String key) {
        return items.stream().filter(i -> i.key().equals(key)).map(PayrollCalculator.Item::amount).findFirst().orElse(null);
    }

    @Test
    void aMinimumWageMonthWithOvertime() {
        var r = PayrollCalculator.calculate(minimum, p2025, march,
                List.of(new PayrollCalculator.Novelty("HORA", "1", BigDecimal.TEN, null, null, null)));
        assertThat(r.daysWorked()).isEqualTo(30);
        assertThat(item(r.earnings(), "suel")).isEqualByComparingTo("1423500");
        assertThat(item(r.earnings(), "tra")).isEqualByComparingTo("200000");
        assertThat(item(r.earnings(), "hora")).isEqualByComparingTo("80881"); // 10 h × (1.423.500 / 220) × 1,25
        assertThat(r.contributionBase()).isEqualByComparingTo("1504381");
        assertThat(item(r.deductions(), "salu")).isEqualByComparingTo("60175");
        assertThat(item(r.deductions(), "pens")).isEqualByComparingTo("60175");
        assertThat(item(r.deductions(), "dedu")).isNull();
        assertThat(r.netPay()).isEqualByComparingTo("1584031");
        assertThat(r.employer().get("Salud")).isEqualByComparingTo("127872");
        assertThat(r.employer().get("Pensión")).isEqualByComparingTo("180526");
        assertThat(r.employer().get("ARL")).isEqualByComparingTo("7853");
        assertThat(r.provisions().get("Cesantías")).isEqualByComparingTo("141975");
        assertThat(r.provisions().get("Intereses a las cesantías")).isEqualByComparingTo("17037");
        assertThat(r.provisions().get("Vacaciones")).isEqualByComparingTo("59360");
    }

    @Test
    void sickLeaveIsPaidAtLeastTheMinimumAndReducesTransportAid() {
        var r = PayrollCalculator.calculate(minimum, p2025, march,
                List.of(new PayrollCalculator.Novelty("INCAPACIDAD", "1", BigDecimal.valueOf(3), null, null, null)));
        assertThat(item(r.earnings(), "suel")).isEqualByComparingTo("1281150");
        assertThat(item(r.earnings(), "inca")).isEqualByComparingTo("142350"); // 2/3 del diario, pero no menos del mínimo diario
        assertThat(item(r.earnings(), "tra")).isEqualByComparingTo("180000");
    }

    @Test
    void unpaidLeaveExemptionAndSolidarityFund() {
        var high = new PayrollCalculator.Worker(new BigDecimal("6000000"), false, 2, LocalDate.of(2025, 3, 11), null);
        var exonerated = new PayrollCalculator.Params(p2025.minimumWage(), p2025.transportAid(), p2025.monthlyHours(), p2025.sundayPct(), true);
        var r = PayrollCalculator.calculate(high, exonerated, march,
                List.of(new PayrollCalculator.Novelty("LICENCIA", "3", BigDecimal.valueOf(5), null, null, null),
                        new PayrollCalculator.Novelty("PRESTAMO", null, null, null, new BigDecimal("100000"), "Anticipo")));
        assertThat(r.daysWorked()).isEqualTo(15);                       // entró el 11: 20 días de contrato, 5 sin pago
        assertThat(item(r.earnings(), "suel")).isEqualByComparingTo("3000000");
        assertThat(item(r.earnings(), "tra")).isNull();                  // gana más de 2 mínimos
        assertThat(item(r.deductions(), "dedu")).isNull();               // IBC 3.000.000 < 4 mínimos
        assertThat(item(r.deductions(), "deud")).isEqualByComparingTo("100000");
        assertThat(r.employer().get("Salud")).isZero();                  // exonerado bajo 10 mínimos
        assertThat(r.employer().get("SENA")).isZero();

        var full = PayrollCalculator.calculate(high, p2025, YearMonth.of(2025, 4), List.of());
        assertThat(item(full.deductions(), "dedu")).isEqualByComparingTo("60000"); // 1 % sobre 6.000.000 (≥ 4 mínimos)
    }
}
