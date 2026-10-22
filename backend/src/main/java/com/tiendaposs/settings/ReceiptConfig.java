package com.tiendaposs.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.common.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fase 16C: qué muestra el recibo, por tipo de comprobante. Se guarda como JSON en {@code store_settings.receipt_config};
 * las plantillas (Mínimo, Simple, Detallado) y la vista previa viven en la app ({@code lib/receiptConfig.ts}), aquí se
 * valida y se aplica el candado: el perfil FACTURA siempre lleva lo que exige la representación gráfica de la factura.
 */
public final class ReceiptConfig {

    private ReceiptConfig() {
    }

    /** Comprobante interno (no es factura), factura electrónica, precuenta de cuenta abierta y venta fiada. */
    public static final List<String> PROFILES = List.of("INTERNO", "FACTURA", "PRECUENTA", "FIADO");

    public static final List<String> FLAGS = List.of(
            "logo", "storeName", "legalData", "address", "phone", "header",
            "saleNumber", "dateTime", "cashier", "customer",
            "productNames", "quantities", "unitPrice", "lineDiscount", "lineTotal", "groupAsVarios",
            "subtotal", "discounts", "taxes", "savings",
            "payments", "change", "creditBalance", "signature",
            "footer", "returnPolicy", "onlineQr");

    /** Lo que la factura electrónica impresa no puede quitar (emisor, numeración, comprador, detalle e impuestos). */
    public static final Set<String> LOCKED_FOR_INVOICE = Set.of("legalData", "saleNumber", "dateTime", "customer",
            "productNames", "quantities", "unitPrice", "lineTotal", "taxes");

    private static final Set<String> PRESETS = Set.of("MINIMO", "SIMPLE", "DETALLADO", "PERSONALIZADO");
    private static final Set<String> PRINT_MODES = Set.of("SIEMPRE", "PREGUNTAR", "NUNCA");
    private static final Set<String> FONT_SIZES = Set.of("S", "M", "L");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Valida y completa la configuración; devuelve el JSON que se guarda. */
    @SuppressWarnings("unchecked")
    public static String normalize(Map<String, Object> in) {
        if (in == null) throw ApiException.field("config", "falta la configuración del recibo");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("printMode", pick(in.get("printMode"), PRINT_MODES, "PREGUNTAR", "printMode"));
        out.put("fontSize", pick(in.get("fontSize"), FONT_SIZES, "M", "fontSize"));
        Object copies = in.getOrDefault("copies", 1);
        if (!(copies instanceof Number n) || n.intValue() < 1 || n.intValue() > 3) throw ApiException.field("copies", "de 1 a 3 copias");
        out.put("copies", ((Number) copies).intValue());
        Object policy = in.get("returnPolicy");
        String text = policy == null ? null : policy.toString().trim();
        if (text != null && text.length() > 300) throw ApiException.field("returnPolicy", "máximo 300 caracteres");
        out.put("returnPolicy", text == null || text.isEmpty() ? null : text);

        Object rawProfiles = in.get("profiles");
        if (!(rawProfiles instanceof Map<?, ?> profiles)) throw ApiException.field("profiles", "faltan los tipos de comprobante");
        Map<String, Object> outProfiles = new LinkedHashMap<>();
        for (String name : PROFILES) {
            Object p = profiles.get(name);
            if (!(p instanceof Map<?, ?> pm)) throw ApiException.field("profiles", "falta el comprobante " + name);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("preset", pick(pm.get("preset"), PRESETS, "PERSONALIZADO", "preset"));
            for (String f : FLAGS) {
                Object v = pm.get(f);
                if (v != null && !(v instanceof Boolean)) throw ApiException.field("profiles", f + " debe ser sí o no");
                o.put(f, Boolean.TRUE.equals(v));
            }
            if ("FACTURA".equals(name)) {
                LOCKED_FOR_INVOICE.forEach(f -> o.put(f, true));
                o.put("groupAsVarios", false);
            }
            outProfiles.put(name, o);
        }
        out.put("profiles", outProfiles);
        for (String k : ((Map<String, Object>) in).keySet()) {
            if (!out.containsKey(k)) throw ApiException.field(k, "no es una opción del recibo");
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** La configuración guardada como mapa (null = la app usa sus valores por defecto). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return JSON.readValue(stored, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pick(Object v, Set<String> allowed, String fallback, String field) {
        if (v == null) return fallback;
        if (!allowed.contains(v.toString())) throw ApiException.field(field, "valor no válido: " + v);
        return v.toString();
    }
}
