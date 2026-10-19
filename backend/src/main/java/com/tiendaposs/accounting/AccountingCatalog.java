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
            new DefaultAccount("3105", "Capital suscrito y pagado", "CREDITO"),
            new DefaultAccount("3115", "Aportes sociales", "CREDITO"),
            new DefaultAccount("3605", "Utilidad del ejercicio", "CREDITO"),
            new DefaultAccount("4135", "Comercio al por mayor y al por menor", "CREDITO"),
            new DefaultAccount("4175", "Devoluciones en ventas (DB)", "DEBITO"),
            new DefaultAccount("4210", "Ingresos financieros", "CREDITO"),
            new DefaultAccount("4250", "Recuperaciones", "CREDITO"),
            new DefaultAccount("5105", "Gastos de personal", "DEBITO"),
            new DefaultAccount("5120", "Arrendamientos", "DEBITO"),
            new DefaultAccount("5135", "Servicios", "DEBITO"),
            new DefaultAccount("5195", "Diversos", "DEBITO"),
            new DefaultAccount("5305", "Gastos financieros", "DEBITO"),
            new DefaultAccount("6135", "Costo de ventas: comercio al por mayor y al por menor", "DEBITO"));

    public static final String SALES = "SALES", SALES_RETURNS = "SALES_RETURNS", COST_OF_SALES = "COST_OF_SALES",
            INVENTORY = "INVENTORY";

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
            default -> key.startsWith("PAY:") ? "Cobros con " + key.substring(4).toLowerCase().replace('_', ' ')
                    : key.startsWith("TAX:") ? "Impuesto generado: " + key.substring(4) : key;
        };
    }
}
