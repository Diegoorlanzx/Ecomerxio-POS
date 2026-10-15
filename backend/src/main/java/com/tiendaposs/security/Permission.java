package com.tiendaposs.security;

/**
 * Catálogo fijo de permisos. Los roles (predefinidos o personalizados) son combinaciones de
 * estos códigos; el backend protege cada endpoint con {@code hasAuthority('CODIGO')}.
 * Agregar un permiso aquí lo hace aparecer automáticamente en la pantalla de roles.
 */
public enum Permission {

    DASHBOARD_VER("Panel", "Ver el panel principal"),

    POS_VENDER("Punto de venta", "Registrar y cobrar ventas"),
    POS_DESCUENTO("Punto de venta", "Aplicar descuentos dentro de su límite"),
    POS_DESCUENTO_ESPECIAL("Punto de venta", "Autorizar descuentos por encima del límite"),
    POS_PRECIO_ESPECIAL("Punto de venta", "Usar precios especiales o por debajo del mínimo"),
    POS_ANULAR("Punto de venta", "Anular ventas"),
    POS_DEVOLUCION("Punto de venta", "Registrar devoluciones y reembolsos"),
    POS_HISTORIAL("Punto de venta", "Consultar el historial de todas las ventas"),

    CAJA_OPERAR("Caja", "Abrir y cerrar su propia caja"),
    CAJA_MOVIMIENTOS("Caja", "Registrar ingresos, retiros y gastos de caja"),
    CAJA_AJUSTAR("Caja", "Autorizar ajustes y diferencias de caja"),
    CAJA_SUPERVISAR("Caja", "Ver y cerrar las cajas de otros empleados"),

    PRODUCTOS_VER("Productos", "Ver el catálogo"),
    PRODUCTOS_EDITAR("Productos", "Crear, editar y desactivar productos"),
    PRODUCTOS_PRECIOS("Productos", "Cambiar precios (también en forma masiva)"),
    PRODUCTOS_IMPORTAR("Productos", "Importar y exportar productos"),

    INVENTARIO_VER("Inventario", "Ver existencias y movimientos"),
    INVENTARIO_AJUSTAR("Inventario", "Hacer ajustes manuales de inventario"),
    INVENTARIO_CONTEO("Inventario", "Registrar conteos físicos"),

    COSTOS_VER("Costos y ganancias", "Ver costos, márgenes y utilidades"),

    PROVEEDORES_VER("Proveedores", "Ver proveedores"),
    PROVEEDORES_EDITAR("Proveedores", "Crear y editar proveedores"),

    COMPRAS_VER("Compras", "Ver órdenes de compra y sugerencias"),
    COMPRAS_CREAR("Compras", "Crear y editar órdenes de compra"),
    COMPRAS_APROBAR("Compras", "Aprobar órdenes de compra"),
    COMPRAS_RECIBIR("Compras", "Registrar la recepción de mercancía"),

    PAGOS_VERIFICAR("Pagos", "Verificar pagos digitales manualmente"),
    PAGOS_CONCILIAR("Pagos", "Conciliar pagos con extractos"),

    FINANZAS_VER("Finanzas", "Ver flujo de caja y reportes financieros"),
    FINANZAS_GASTOS("Finanzas", "Registrar gastos y pagos a proveedores"),
    FIADO_GESTIONAR("Finanzas", "Vender a crédito (fiado) y registrar abonos"),

    REPORTES_VER("Reportes", "Ver analítica e informes de ventas"),
    REPORTES_EXPORTAR("Reportes", "Exportar informes"),

    ALERTAS_VER("Alertas", "Recibir alertas y notificaciones del negocio"),
    ASISTENTE_IA("Asistente IA", "Usar el asistente de inteligencia artificial"),

    USUARIOS_GESTIONAR("Administración", "Crear, editar y desactivar usuarios"),
    ROLES_GESTIONAR("Administración", "Crear roles y cambiar permisos"),
    AUDITORIA_VER("Administración", "Ver el registro de actividad y accesos"),
    CONFIG_EDITAR("Administración", "Cambiar la configuración de la tienda");

    private final String group;
    private final String label;

    Permission(String group, String label) {
        this.group = group;
        this.label = label;
    }

    public String group() {
        return group;
    }

    public String label() {
        return label;
    }

    public static boolean exists(String code) {
        for (Permission p : values()) {
            if (p.name().equals(code)) return true;
        }
        return false;
    }
}
