package com.tiendaposs.accounting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan de cuentas y mapa contable PROPUESTOS para un comerciante (PUC del Decreto 2650 de 1993, cuentas de 4 dígitos).
 * Son una propuesta: un contador público los revisa antes de vender el módulo (ver docs/FASE_14_CONTABILIDAD.md).
 * Solo se precargan cuentas estándar; lo que no tiene una cuenta obvia (otros impuestos, medios de pago nuevos) queda
 * sin asignar y sus operaciones van a "Pendientes por contabilizar" en vez de inventar una cuenta.
 */
public final class AccountingCatalog {

    private AccountingCatalog() {
    }

    public record DefaultAccount(String code, String name, String nature) {
    }

    public static final List<DefaultAccount> PUC = List.of(
            new DefaultAccount("1105", "Caja", "DEBITO"),
            new DefaultAccount("1110", "Bancos", "DEBITO"),
            new DefaultAccount("1305", "Clientes", "DEBITO"),
            new DefaultAccount("1355", "Anticipo de impuestos y contribuciones o saldos a favor", "DEBITO"),
            new DefaultAccount("1435", "Mercancías no fabricadas por la empresa", "DEBITO"),
            new DefaultAccount("2205", "Proveedores nacionales", "CREDITO"),
            new DefaultAccount("2335", "Costos y gastos por pagar", "CREDITO"),
            new DefaultAccount("2365", "Retención en la fuente", "CREDITO"),
            new DefaultAccount("2367", "Impuesto a las ventas retenido", "CREDITO"),
            new DefaultAccount("2368", "Impuesto de industria y comercio retenido", "CREDITO"),
            new DefaultAccount("2408", "Impuesto sobre las ventas por pagar", "CREDITO"),
            new DefaultAccount("2805", "Anticipos y avances recibidos", "CREDITO"),
            new DefaultAccount("3105", "Capital suscrito y pagado", "CREDITO"),
            new DefaultAccount("3115", "Aportes sociales", "CREDITO"),
            new DefaultAccount("3605", "Utilidad del ejercicio", "CREDITO"),
            new DefaultAccount("3610", "Pérdida del ejercicio", "DEBITO"),
            new DefaultAccount("4135", "Comercio al por mayor y al por menor", "CREDITO"),
            new DefaultAccount("4175", "Devoluciones en ventas (DB)", "DEBITO"),
            new DefaultAccount("4210", "Ingresos financieros", "CREDITO"),
            new DefaultAccount("4250", "Recuperaciones", "CREDITO"),
            new DefaultAccount("4295", "Ingresos diversos", "CREDITO"),
            new DefaultAccount("5105", "Gastos de personal", "DEBITO"),
            new DefaultAccount("5120", "Arrendamientos", "DEBITO"),
            new DefaultAccount("5135", "Servicios", "DEBITO"),
            new DefaultAccount("5195", "Diversos", "DEBITO"),
            new DefaultAccount("5305", "Gastos financieros", "DEBITO"),
            new DefaultAccount("5310", "Pérdida en venta y retiro de bienes", "DEBITO"),
            new DefaultAccount("6135", "Costo de ventas: comercio al por mayor y al por menor", "DEBITO"));

    public static final String SALES = "SALES", SALES_RETURNS = "SALES_RETURNS", COST_OF_SALES = "COST_OF_SALES",
            INVENTORY = "INVENTORY";
    /** Fase 14 (resto): compras, pagos, retenciones, inventario, caja, bancos y cierres. */
    public static final String SUPPLIERS = "SUPPLIERS", PURCHASE_OTHER = "PURCHASE_OTHER", BANK_FEES = "BANK_FEES",
            OTHER_INCOME = "OTHER_INCOME", OTHER_EXPENSE = "OTHER_EXPENSE", INVENTORY_LOSS = "INVENTORY_LOSS",
            INVENTORY_GAIN = "INVENTORY_GAIN", CASH_SHORT = "CASH_SHORT", CASH_OVER = "CASH_OVER", PROFIT = "PROFIT", LOSS = "LOSS";

    /** Llaves fijas del mapa (las demás llevan prefijo: PAY:, TAX:, TAX_CREDIT:, WH:, EXPENSE:, INCOME:). */
    public static final List<String> FIXED_KEYS = List.of(SALES, SALES_RETURNS, COST_OF_SALES, INVENTORY, SUPPLIERS, PURCHASE_OTHER,
            BANK_FEES, OTHER_INCOME, OTHER_EXPENSE, INVENTORY_LOSS, INVENTORY_GAIN, CASH_SHORT, CASH_OVER, PROFIT, LOSS);
    public static final List<String> PREFIXES = List.of("PAY:", "TAX:", "TAX_CREDIT:", "WH:", "EXPENSE:", "INCOME:");

    public static boolean validKey(String key) {
        return FIXED_KEYS.contains(key) || PREFIXES.stream().anyMatch(key::startsWith);
    }

    public static String whKey(String kind) {
        return "WH:" + kind;
    }

    public static String expenseKey(Long categoryId) {
        return "EXPENSE:" + categoryId;
    }

    public static String incomeKey(Long categoryId) {
        return "INCOME:" + categoryId;
    }

    /** Cuenta propuesta para una categoría de gastos o ingresos según su nombre (la cambia el contador). */
    public static String categoryAccount(String kind, String name) {
        String n = name == null ? "" : name.toLowerCase();
        if ("INGRESO".equals(kind)) return n.contains("inter") || n.contains("financ") ? "4210" : "4295";
        if (n.contains("arriendo") || n.contains("arrendamiento") || n.contains("alquiler")) return "5120";
        if (n.contains("nómina") || n.contains("nomina") || n.contains("sueldo") || n.contains("salario") || n.contains("personal")) return "5105";
        if (n.contains("servicio") || n.contains("internet") || n.contains("luz") || n.contains("agua") || n.contains("teléfono")) return "5135";
        if (n.contains("banc") || n.contains("financ") || n.contains("interés") || n.contains("comisi")) return "5305";
        return "5195";
    }

    public static String payKey(String method) {
        return "PAY:" + method.toUpperCase();
    }

    public static String taxKey(String taxName) {
        return "TAX:" + (taxName == null || taxName.isBlank() ? "IVA" : taxName.trim().toUpperCase());
    }

    /** Mapa propuesto. Los medios de pago distintos de efectivo y fiado van a Bancos (se cambia en el mapa). */
    public static Map<String, String> defaultMap(List<String> paymentMethods) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(SALES, "4135");
        m.put(SALES_RETURNS, "4175");
        m.put(COST_OF_SALES, "6135");
        m.put(INVENTORY, "1435");
        m.put(taxKey("IVA"), "2408");
        m.put("TAX_CREDIT:IVA", "2408");
        m.put(SUPPLIERS, "2205");
        m.put(PURCHASE_OTHER, "5195");
        m.put(whKey("RETEFUENTE"), "2365");
        m.put(whKey("RETEIVA"), "2367");
        m.put(whKey("RETEICA"), "2368");
        m.put(BANK_FEES, "5305");
        m.put(OTHER_INCOME, "4295");
        m.put(OTHER_EXPENSE, "5195");
        m.put(INVENTORY_LOSS, "5310");
        m.put(INVENTORY_GAIN, "4295");
        m.put(CASH_SHORT, "5195");
        m.put(CASH_OVER, "4295");
        m.put(PROFIT, "3605");
        m.put(LOSS, "3610");
        // Anticipos de órdenes de servicio aplicados al entregar (Fase 11).
        m.put(payKey("ANTICIPO"), "2805");
        for (String method : paymentMethods) {
            String up = method.toUpperCase();
            m.put(payKey(up), switch (up) {
                case "EFECTIVO" -> "1105";
                case "FIADO" -> "1305";
                default -> "1110";
            });
        }
        return m;
    }

    /** Nombre legible de una llave del mapa. */
    public static String label(String key) {
        return switch (key) {
            case SALES -> "Ingresos por ventas";
            case SALES_RETURNS -> "Devoluciones en ventas";
            case COST_OF_SALES -> "Costo de ventas";
            case INVENTORY -> "Inventario de mercancías";
            case SUPPLIERS -> "Proveedores (lo que se les debe)";
            case PURCHASE_OTHER -> "Cargos o descuentos de facturas de compra";
            case BANK_FEES -> "Comisiones y costos bancarios";
            case OTHER_INCOME -> "Ajustes a favor en bancos";
            case OTHER_EXPENSE -> "Ajustes en contra en bancos";
            case INVENTORY_LOSS -> "Faltantes y bajas de inventario";
            case INVENTORY_GAIN -> "Sobrantes de inventario";
            case CASH_SHORT -> "Faltantes de caja";
            case CASH_OVER -> "Sobrantes de caja";
            case PROFIT -> "Utilidad del ejercicio (cierre de año)";
            case LOSS -> "Pérdida del ejercicio (cierre de año)";
            default -> key.startsWith("PAY:") ? "Cobros y pagos con " + key.substring(4).toLowerCase().replace('_', ' ')
                    : key.startsWith("TAX_CREDIT:") ? "Impuesto descontable en compras: " + key.substring(11)
                    : key.startsWith("TAX:") ? "Impuesto generado: " + key.substring(4)
                    : key.startsWith("WH:") ? "Retención por pagar: " + key.substring(3).toLowerCase()
                    : key;
        };
    }
}
