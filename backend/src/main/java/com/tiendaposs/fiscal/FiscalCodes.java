package com.tiendaposs.fiscal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Códigos que usa la facturación electrónica. Están aquí y no regados por el código para revisarlos en un solo lugar
 * cuando cambie la norma o cuando el proveedor los confirme (ver docs/PLAN_FACTURACION_Y_FINANZAS.md, "a confirmar").
 */
public final class FiscalCodes {

    private FiscalCodes() {
    }

    /** Tipo de documento de identidad → código DIAN. */
    public static final Map<String, String> ID_TYPES = new LinkedHashMap<>();
    /** Nombre para mostrar de cada tipo de documento. */
    public static final Map<String, String> ID_TYPE_LABELS = new LinkedHashMap<>();

    static {
        idType("CC", "13", "Cédula de ciudadanía");
        idType("NIT", "31", "NIT");
        idType("CE", "22", "Cédula de extranjería");
        idType("TI", "12", "Tarjeta de identidad");
        idType("RC", "11", "Registro civil");
        idType("PP", "41", "Pasaporte");
        idType("PEP", "47", "Permiso especial de permanencia (PEP)");
        idType("TE", "21", "Tarjeta de extranjería");
        idType("DIE", "42", "Documento de identificación extranjero");
        idType("NIT_EXT", "50", "NIT de otro país");
        idType("NUIP", "91", "NUIP");
    }

    private static void idType(String code, String dian, String label) {
        ID_TYPES.put(code, dian);
        ID_TYPE_LABELS.put(code, label);
    }

    /** Responsabilidades fiscales que admite el documento electrónico. */
    public static final Map<String, String> RESPONSIBILITIES = new LinkedHashMap<>();

    static {
        RESPONSIBILITIES.put("O-13", "Gran contribuyente");
        RESPONSIBILITIES.put("O-15", "Autorretenedor");
        RESPONSIBILITIES.put("O-23", "Agente de retención IVA");
        RESPONSIBILITIES.put("O-47", "Régimen simple de tributación");
        RESPONSIBILITIES.put("R-99-PN", "No aplica – Otros");
    }

    /** Tipos de documento que llevan resolución de numeración. */
    public static final Map<String, String> DOCUMENT_TYPES = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPES.put("POS_ELECTRONICO", "Documento equivalente POS electrónico");
        DOCUMENT_TYPES.put("FACTURA", "Factura electrónica de venta");
        DOCUMENT_TYPES.put("DOCUMENTO_SOPORTE", "Documento soporte (compras a no obligados)");
    }

    /** Unidad del producto en Ecomerxio POS → código de unidad del documento electrónico. */
    public static final Map<String, String> UNITS = Map.of(
            "UNIDAD", "94", "KILOGRAMO", "KGM", "GRAMO", "GRM", "LITRO", "LTR",
            "MILILITRO", "MLT", "METRO", "MTR", "LIBRA", "LBR");

    public static final List<String> TAX_CLASSES = List.of("GRAVADO", "EXENTO", "EXCLUIDO");

    /** Consumidor final: el comprador por defecto cuando no se identifica. */
    public static final String FINAL_CONSUMER_ID = "222222222222";
}
