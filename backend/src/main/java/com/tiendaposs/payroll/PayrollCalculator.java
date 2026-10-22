package com.tiendaposs.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase 16F: liquidación mensual de un empleado (Colombia). Sin base de datos: recibe el empleado, los parámetros del
 * año y las novedades, y devuelve devengados, deducciones, aportes del empleador y provisiones con los códigos de la
 * nómina electrónica. Las tarifas son las generales; un contador debe revisarlas (docs/contador) antes de venderse.
 * <ul>
 *   <li>Mes comercial de 30 días; hora ordinaria = salario / horas de la jornada mensual del año.</li>
 *   <li>Auxilio de transporte si el salario es hasta 2 mínimos (no integral), por días trabajados.</li>
 *   <li>IBC = devengos salariales (integral: 70 %), mínimo un salario mínimo proporcional, máximo 25.</li>
 *   <li>Empleado: salud 4 %, pensión 4 %, fondo de solidaridad 1 % a 2 % desde 4 mínimos.</li>
 *   <li>Empleador: salud 8,5 %, pensión 12 %, ARL por clase, caja 4 %, ICBF 3 %, SENA 2 % (exonerados de salud, ICBF
 *       y SENA bajo 10 mínimos si la tienda lo marca).</li>
 *   <li>Provisiones: cesantías 8,33 %, intereses 12 % de la cesantía del mes, prima 8,33 % (con auxilio de transporte),
 *       vacaciones 4,17 % del salario. Salario integral: sin cesantías ni prima.</li>
 * </ul>
 */
public final class PayrollCalculator {

    private PayrollCalculator() {
    }

    static final BigDecimal THIRTY = BigDecimal.valueOf(30), HUNDRED = BigDecimal.valueOf(100);
    static final Map<Integer, BigDecimal> ARL = Map.of(1, new BigDecimal("0.522"), 2, new BigDecimal("1.044"), 3, new BigDecimal("2.436"),
            4, new BigDecimal("4.350"), 5, new BigDecimal("6.960"));

    public record Params(BigDecimal minimumWage, BigDecimal transportAid, BigDecimal monthlyHours, BigDecimal sundayPct, boolean exonerated) {
    }

    public record Worker(BigDecimal salary, boolean integral, int arlClass, LocalDate start, LocalDate end) {
    }

    /** kind: HORA (code 1..7), INCAPACIDAD (1 común, 2 profesional, 3 laboral), VACACIONES, LICENCIA (1 mat/pat, 2 remunerada,
     * 3 no remunerada), BONIFICACION (1 salarial, 2 no salarial), COMISION, AUXILIO (1 salarial, 2 no salarial), OTRO_DEVENGO,
     * PRESTAMO, LIBRANZA, RETENCION, OTRA_DEDUCCION. */
    public record Novelty(String kind, String code, BigDecimal quantity, BigDecimal percentage, BigDecimal amount, String description) {
    }

    /** Una línea de devengo o deducción con su clave de la nómina electrónica (suel, hora, inca, salu...). */
    public record Item(String key, String code, String label, BigDecimal quantity, BigDecimal percentage, BigDecimal amount, String description) {
    }

    public record Result(int daysWorked, BigDecimal contributionBase, List<Item> earnings, List<Item> deductions, BigDecimal totalEarned,
                         BigDecimal totalDeductions, BigDecimal netPay, Map<String, BigDecimal> employer, BigDecimal employerTotal,
                         Map<String, BigDecimal> provisions, BigDecimal provisionsTotal) {
    }

    /** Porcentaje por defecto de cada hora extra o recargo (código DIAN 1..7); el dominical es el del año. */
    public static BigDecimal defaultOvertimePct(String code, BigDecimal sundayPct) {
        return switch (code) {
            case "1" -> new BigDecimal("25");
            case "2" -> new BigDecimal("75");
            case "3" -> new BigDecimal("35");
            case "4" -> new BigDecimal("25").add(sundayPct);
            case "5" -> sundayPct;
            case "6" -> new BigDecimal("75").add(sundayPct);
            case "7" -> new BigDecimal("35").add(sundayPct);
            default -> BigDecimal.ZERO;
        };
    }

    public static Result calculate(Worker w, Params p, YearMonth month, List<Novelty> novelties) {
        BigDecimal daily = w.salary().divide(THIRTY, 6, RoundingMode.HALF_UP);
        BigDecimal hourly = w.salary().divide(p.monthlyHours(), 6, RoundingMode.HALF_UP);

        // Días del mes dentro del contrato (mes comercial de 30 días).
        int startDay = w.start().isAfter(month.atDay(1)) ? Math.min(w.start().getDayOfMonth(), 30) : 1;
        int endDay = w.end() != null && w.end().isBefore(month.atEndOfMonth()) ? Math.min(w.end().getDayOfMonth(), 30) : 30;
        int contractDays = Math.max(0, endDay - startDay + 1);

        List<Item> earn = new ArrayList<>(), ded = new ArrayList<>();
        BigDecimal salarial = BigDecimal.ZERO;
        int absentDays = 0, unpaidDays = 0;

        for (Novelty n : novelties) {
            BigDecimal q = n.quantity() == null ? BigDecimal.ZERO : n.quantity();
            switch (n.kind()) {
                case "HORA" -> {
                    BigDecimal pct = n.percentage() != null ? n.percentage() : defaultOvertimePct(n.code(), p.sundayPct());
                    boolean surchargeOnly = "3".equals(n.code()) || "5".equals(n.code()) || "7".equals(n.code());
                    BigDecimal factor = surchargeOnly ? pct.divide(HUNDRED, 6, RoundingMode.HALF_UP)
                            : BigDecimal.ONE.add(pct.divide(HUNDRED, 6, RoundingMode.HALF_UP));
                    BigDecimal amt = n.amount() != null ? n.amount() : money(hourly.multiply(q).multiply(factor));
                    earn.add(new Item("hora", n.code(), overtimeLabel(n.code()), q, pct, amt, null));
                    salarial = salarial.add(amt);
                }
                case "INCAPACIDAD" -> {
                    int days = q.intValue();
                    absentDays += days;
                    // Común: dos tercios del salario diario (nunca menos del mínimo diario); profesional y laboral: 100 %.
                    BigDecimal perDay = "1".equals(n.code())
                            ? daily.multiply(new BigDecimal("0.666667")).max(p.minimumWage().divide(THIRTY, 6, RoundingMode.HALF_UP))
                            : daily;
                    BigDecimal amt = n.amount() != null ? n.amount() : money(perDay.multiply(q));
                    earn.add(new Item("inca", n.code() == null ? "1" : n.code(), "Incapacidad", q, null, amt, null));
                    salarial = salarial.add(amt);
                }
                case "VACACIONES" -> {
                    absentDays += q.intValue();
                    BigDecimal amt = n.amount() != null ? n.amount() : money(daily.multiply(q));
                    earn.add(new Item("vaca", "1", "Vacaciones", q, null, amt, null));
                    salarial = salarial.add(amt);
                }
                case "LICENCIA" -> {
                    int days = q.intValue();
                    if ("3".equals(n.code())) {
                        unpaidDays += days;
                        earn.add(new Item("lice", "3", "Licencia no remunerada", q, null, BigDecimal.ZERO, null));
                    } else {
                        absentDays += days;
                        BigDecimal amt = n.amount() != null ? n.amount() : money(daily.multiply(q));
                        earn.add(new Item("lice", n.code() == null ? "2" : n.code(), "1".equals(n.code()) ? "Licencia de maternidad o paternidad"
                                : "Licencia remunerada", q, null, amt, null));
                        salarial = salarial.add(amt);
                    }
                }
                case "BONIFICACION" -> {
                    boolean sal = !"2".equals(n.code());
                    earn.add(new Item("boni", sal ? "1" : "2", sal ? "Bonificación salarial" : "Bonificación no salarial", null, null, amount(n), n.description()));
                    if (sal) salarial = salarial.add(amount(n));
                }
                case "COMISION" -> {
                    earn.add(new Item("comi", "1", "Comisiones", null, null, amount(n), n.description()));
                    salarial = salarial.add(amount(n));
                }
                case "AUXILIO" -> {
                    boolean sal = "1".equals(n.code());
                    earn.add(new Item("auxi", sal ? "1" : "2", sal ? "Auxilio salarial" : "Auxilio no salarial", null, null, amount(n), n.description()));
                    if (sal) salarial = salarial.add(amount(n));
                }
                case "OTRO_DEVENGO" -> earn.add(new Item("otro", "2", "Otro pago no salarial", null, null, amount(n),
                        n.description() == null ? "Otro pago" : n.description()));
                case "PRESTAMO" -> ded.add(new Item("deud", "1", "Préstamo de la empresa", null, null, amount(n), n.description()));
                case "LIBRANZA" -> ded.add(new Item("libr", "1", "Libranza", null, null, amount(n), n.description() == null ? "Libranza" : n.description()));
                case "RETENCION" -> ded.add(new Item("rete", "1", "Retención en la fuente", null, null, amount(n), null));
                case "OTRA_DEDUCCION" -> ded.add(new Item("otra", "1", "Otra deducción", null, null, amount(n), n.description()));
                default -> throw new IllegalArgumentException("novedad desconocida: " + n.kind());
            }
        }

        int ordinaryDays = Math.max(0, contractDays - absentDays - unpaidDays);
        int daysWorked = Math.max(0, contractDays - unpaidDays);
        BigDecimal basic = money(daily.multiply(BigDecimal.valueOf(ordinaryDays)));
        earn.add(0, new Item("suel", "1", w.integral() ? "Salario integral" : "Salario básico", BigDecimal.valueOf(ordinaryDays), null, basic, null));
        salarial = salarial.add(basic);

        BigDecimal transport = BigDecimal.ZERO;
        if (!w.integral() && w.salary().compareTo(p.minimumWage().multiply(BigDecimal.valueOf(2))) <= 0 && ordinaryDays > 0) {
            transport = money(p.transportAid().divide(THIRTY, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(ordinaryDays)));
            earn.add(1, new Item("tra", "1", "Auxilio de transporte", BigDecimal.valueOf(ordinaryDays), null, transport, null));
        }

        // Base de cotización.
        BigDecimal ibc = w.integral() ? salarial.multiply(new BigDecimal("0.70")) : salarial;
        BigDecimal minBase = p.minimumWage().divide(THIRTY, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(daysWorked));
        ibc = money(ibc.max(minBase).min(p.minimumWage().multiply(BigDecimal.valueOf(25))));
        if (daysWorked == 0) ibc = BigDecimal.ZERO;

        BigDecimal smmlv = p.minimumWage();
        ded.add(0, new Item("salu", "1", "Salud", null, new BigDecimal("4"), pct(ibc, "4"), null));
        ded.add(1, new Item("pens", "1", "Pensión", null, new BigDecimal("4"), pct(ibc, "4"), null));
        BigDecimal fsp = solidarityPct(ibc, smmlv);
        if (fsp.signum() > 0) ded.add(2, new Item("dedu", "1", "Fondo de solidaridad pensional", null, fsp, pct(ibc, fsp.toPlainString()), null));

        BigDecimal totalEarned = earn.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDed = ded.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean exempt = p.exonerated() && ibc.compareTo(smmlv.multiply(BigDecimal.TEN)) < 0;
        Map<String, BigDecimal> employer = new LinkedHashMap<>();
        employer.put("Salud", exempt ? BigDecimal.ZERO : pct(ibc, "8.5"));
        employer.put("Pensión", pct(ibc, "12"));
        employer.put("ARL", pct(ibc, ARL.getOrDefault(w.arlClass(), ARL.get(1)).toPlainString()));
        employer.put("Caja de compensación", pct(ibc, "4"));
        employer.put("ICBF", exempt ? BigDecimal.ZERO : pct(ibc, "3"));
        employer.put("SENA", exempt ? BigDecimal.ZERO : pct(ibc, "2"));
        BigDecimal employerTotal = employer.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> prov = new LinkedHashMap<>();
        if (!w.integral()) {
            // Cesantías y prima: todo lo salarial (horas extra incluidas) más el auxilio de transporte.
            BigDecimal benefitsBase = salarial.add(transport);
            BigDecimal ces = pct(benefitsBase, "8.33");
            prov.put("Cesantías", ces);
            // 12 % anual de las cesantías: cada mes, el 12 % de la cesantía del mes (≈ 1 % de la base).
            prov.put("Intereses a las cesantías", pct(ces, "12"));
            prov.put("Prima de servicios", pct(benefitsBase, "8.33"));
        }
        prov.put("Vacaciones", pct(money(daily.multiply(BigDecimal.valueOf(daysWorked))), "4.17"));
        BigDecimal provTotal = prov.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Result(daysWorked, ibc, earn, ded, totalEarned, totalDed, totalEarned.subtract(totalDed), employer, employerTotal, prov, provTotal);
    }

    /** Fondo de solidaridad pensional sobre el IBC, según cuántos salarios mínimos es. */
    static BigDecimal solidarityPct(BigDecimal ibc, BigDecimal smmlv) {
        BigDecimal times = ibc.divide(smmlv, 4, RoundingMode.HALF_UP);
        if (times.compareTo(BigDecimal.valueOf(4)) < 0) return BigDecimal.ZERO;
        if (times.compareTo(BigDecimal.valueOf(16)) < 0) return new BigDecimal("1");
        if (times.compareTo(BigDecimal.valueOf(17)) < 0) return new BigDecimal("1.2");
        if (times.compareTo(BigDecimal.valueOf(18)) < 0) return new BigDecimal("1.4");
        if (times.compareTo(BigDecimal.valueOf(19)) < 0) return new BigDecimal("1.6");
        if (times.compareTo(BigDecimal.valueOf(20)) < 0) return new BigDecimal("1.8");
        return new BigDecimal("2");
    }

    static String overtimeLabel(String code) {
        return switch (code == null ? "" : code) {
            case "1" -> "Hora extra diurna";
            case "2" -> "Hora extra nocturna";
            case "3" -> "Recargo nocturno";
            case "4" -> "Hora extra diurna dominical o festiva";
            case "5" -> "Recargo dominical o festivo";
            case "6" -> "Hora extra nocturna dominical o festiva";
            case "7" -> "Recargo nocturno dominical o festivo";
            default -> "Hora extra";
        };
    }

    private static BigDecimal amount(Novelty n) {
        if (n.amount() == null || n.amount().signum() <= 0) throw new IllegalArgumentException("la novedad " + n.kind() + " necesita valor");
        return money(n.amount());
    }

    static BigDecimal pct(BigDecimal base, String pct) {
        return money(base.multiply(new BigDecimal(pct)).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    /** Pesos sin decimales (redondeo al peso). */
    static BigDecimal money(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }
}
