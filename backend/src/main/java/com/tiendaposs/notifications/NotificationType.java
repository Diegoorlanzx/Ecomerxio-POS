package com.tiendaposs.notifications;

import java.math.BigDecimal;

/**
 * Tipos de aviso. Cada uno dice quién lo ve (permiso), su gravedad por defecto y, si aplica,
 * el umbral que la tienda puede ajustar.
 */
public enum NotificationType {
    STOCK_CRITICO("Stock crítico", "Producto agotado o por debajo del mínimo", "INVENTARIO_VER", "ALTA", null, null),
    PROXIMO_AGOTAMIENTO("Próximo agotamiento", "Al ritmo de venta se acaba pronto", "INVENTARIO_VER", "MEDIA", 3, "días de cobertura o menos"),
    SIN_ROTACION("Productos sin rotación", "Resumen semanal de productos quietos con existencias", "INVENTARIO_VER", "INFO", null, null),
    EXCESO_INVENTARIO("Exceso de inventario", "Resumen semanal de productos con más existencias de las necesarias", "INVENTARIO_VER", "INFO", null, null),
    PEDIDO_RECOMENDADO("Pedido recomendado", "La reposición automática sugiere pedir a un proveedor", "COMPRAS_VER", "MEDIA", null, null),
    PEDIDO_POR_APROBAR("Pedido pendiente de aprobación", "Una orden de compra espera aprobación", "COMPRAS_APROBAR", "MEDIA", null, null),
    PEDIDO_RETRASADO("Pedido retrasado", "Un pedido pasó su fecha de entrega", "COMPRAS_VER", "ALTA", null, null),
    MERCANCIA_RECIBIDA("Mercancía recibida", "Se registró una recepción de mercancía", "COMPRAS_VER", "INFO", null, null),
    PAGO_POR_VERIFICAR("Pago pendiente de verificación", "Un pago digital lleva tiempo sin verificarse", "PAGOS_VERIFICAR", "ALTA", 30, "minutos sin verificar"),
    PAGO_INCONSISTENTE("Pago duplicado o inconsistente", "Referencia repetida o valor distinto al del extracto", "PAGOS_VERIFICAR", "CRITICA", null, null),
    DIFERENCIA_CAJA("Diferencias de caja", "Un turno cerró con faltante o sobrante", "CAJA_SUPERVISAR", "ALTA", 5000, "pesos de diferencia o más"),
    DEVOLUCIONES_INUSUALES("Devoluciones y anulaciones inusuales", "Un empleado hizo muchas anulaciones o devoluciones en el día", "CAJA_SUPERVISAR", "ALTA", 3, "anulaciones + devoluciones al día por empleado"),
    PAGO_PROVEEDOR("Recordatorio de pago a proveedores", "Una factura de proveedor está por vencer o vencida", "FINANZAS_VER", "MEDIA", 3, "días antes del vencimiento"),
    FIADO_VENCIDO("Fiado vencido", "Resumen semanal de clientes con fiado vencido", "FIADO_GESTIONAR", "MEDIA", null, null),
    REPORTE_DIARIO("Reporte diario de ventas", "Resumen del día a la hora de cierre configurada", "REPORTES_VER", "INFO", null, null),
    RESUMEN_SEMANAL("Resumen semanal", "Cómo le fue a la tienda la semana anterior", "REPORTES_VER", "INFO", null, null),
    RESUMEN_MENSUAL("Resumen mensual", "Cómo le fue a la tienda el mes anterior", "REPORTES_VER", "INFO", null, null);

    public final String label;
    public final String description;
    public final String permission;
    public final String severity;
    public final BigDecimal defaultThreshold;
    public final String thresholdLabel;

    NotificationType(String label, String description, String permission, String severity, Integer defaultThreshold, String thresholdLabel) {
        this.label = label;
        this.description = description;
        this.permission = permission;
        this.severity = severity;
        this.defaultThreshold = defaultThreshold == null ? null : BigDecimal.valueOf(defaultThreshold);
        this.thresholdLabel = thresholdLabel;
    }
}
