package com.tiendaposs.einvoice;

import java.util.Map;

/**
 * Fase 13C: cada error de facturación explicado en español, con su causa probable, la acción recomendada y dónde se
 * corrige. Los códigos propios (validación y configuración) son exactos; para los del proveedor o la DIAN, que todavía
 * no se conocen, se muestra su mensaje original con una guía general. Al conectar el proveedor real se agregan aquí sus
 * códigos más frecuentes.
 */
public final class ErrorCatalog {

    private ErrorCatalog() {
    }

    /**
     * @param fixTab  pestaña de Facturación donde se corrige (datos, clientes, productos, resoluciones) o null.
     * @param action  RETRY = corregir y reintentar; CHECK = consultar el estado; CONFIG = cambiar la configuración;
     *                NOTE = el documento ya salió: se corrige con nota.
     */
    public record Help(String title, String explanation, String cause, String recommendation, String fixTab, String action) {
    }

    private static final Map<String, Help> HELP = Map.ofEntries(
            Map.entry("CONFIG_RESOLUCION", new Help("Sin numeración disponible",
                    "No hay una resolución de numeración vigente y con números libres para este tipo de documento.",
                    "La resolución venció, se agotó el rango o todavía no se ha registrado la del ambiente actual.",
                    "Registra la resolución nueva (o la ampliación) en Resoluciones y reintenta. El documento toma el número al enviarse.",
                    "resoluciones", "RETRY")),
            Map.entry("CONFIG_PROVEEDOR", new Help("Sin proveedor real",
                    "Con el simulador no se emiten documentos en el ambiente de producción.",
                    "Ecomerxio POS todavía no tiene conectado un proveedor tecnológico autorizado.",
                    "Vuelve al ambiente de pruebas en Datos fiscales mientras se conecta el proveedor real.",
                    "datos", "CONFIG")),
            Map.entry("VAL_EMISOR", new Help("Faltan datos del negocio",
                    "El documento no se envió porque faltan o están mal datos fiscales del negocio.",
                    "Datos fiscales incompletos o un dígito de verificación que no corresponde al NIT.",
                    "Complétalos en Datos fiscales y reintenta. No se gastó ningún envío.",
                    "datos", "RETRY")),
            Map.entry("VAL_CLIENTE", new Help("Faltan datos del cliente",
                    "La factura va a nombre del cliente y le faltan datos para emitirla.",
                    "Tipo o número de documento, dígito de verificación, tipo de persona o correo.",
                    "Complétalos en Clientes y reintenta. Si el cliente no los tiene, anula la venta y cóbrala sin factura a su nombre.",
                    "clientes", "RETRY")),
            Map.entry("VAL_PROVEEDOR", new Help("Faltan datos del proveedor",
                    "El documento soporte va a nombre del proveedor que no factura y le faltan datos para emitirlo.",
                    "Número de documento, dígito de verificación, tipo de persona, dirección o municipio del proveedor.",
                    "Complétalos en Compras → Proveedores (ficha del proveedor) y reintenta. No se gastó ningún envío.",
                    "proveedores", "RETRY")),
            Map.entry("VAL_PRODUCTO", new Help("Producto sin clasificación tributaria",
                    "Un producto con IVA 0 % no dice si es exento o excluido, y el documento lo necesita.",
                    "El producto se creó con tarifa 0 sin elegir su clasificación.",
                    "Clasifícalo en Productos (o en el formulario del producto) y reintenta. Si dudas entre exento y excluido, pregúntale a tu contador.",
                    "productos", "RETRY")),
            Map.entry("VAL_SIN_LINEAS", new Help("Documento sin productos",
                    "El documento no tiene líneas para enviar.",
                    "La venta o la devolución no tiene productos asociados.",
                    "Revisa la venta en el historial. Si es un error, anúlala.",
                    null, "CONFIG")),
            Map.entry("SIN_RESPUESTA", new Help("El proveedor no respondió",
                    "No se sabe si el documento llegó: puede estar aceptado o no.",
                    "Caída de internet, del proveedor o una demora mayor a la normal.",
                    "No hagas nada: el sistema consulta el estado y solo lo reenvía si el proveedor nunca lo recibió. "
                            + "Puedes forzar la consulta con «Consultar estado». Nunca se emiten dos documentos por la misma venta.",
                    null, "CHECK")));

    private static final Help REJECTED = new Help("Rechazado por el proveedor o la DIAN",
            "El documento llegó pero no fue aceptado. El mensaje original dice qué dato falló.",
            "Un dato del comprador, del negocio, de los productos o de los totales no cumple las reglas de validación.",
            "Lee el mensaje original, corrige ese dato (cliente, producto o datos fiscales) y reintenta: se envía de nuevo con el mismo número. "
                    + "Si no sabes qué corregir, compártele el código a soporte.",
            null, "RETRY");

    /** Guía para un código; null si el documento no tiene error. */
    public static Help help(String code, String status) {
        if (code == null) return null;
        Help h = HELP.get(code);
        if (h != null) return h;
        return "RECHAZADO".equals(status) ? REJECTED : null;
    }
}
