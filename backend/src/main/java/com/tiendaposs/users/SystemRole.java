package com.tiendaposs.users;

import com.tiendaposs.security.Permission;

import java.util.EnumSet;
import java.util.Set;

import static com.tiendaposs.security.Permission.*;

/**
 * Roles predefinidos. Los que no tienen módulo se crean en cada tienda nueva; los de un módulo (mesero y cocina del
 * restaurante) solo en las tiendas que lo tienen encendido. El dueño puede editar todos menos DUENO.
 */
public enum SystemRole {

    DUENO("Dueño", "Acceso completo: costos, ganancias, permisos, caja y configuración.",
            EnumSet.allOf(Permission.class)),

    ADMINISTRADOR("Administrador", "Gestiona la operación general dentro de los límites que fija el dueño.",
            EnumSet.complementOf(EnumSet.of(ROLES_GESTIONAR, CONFIG_EDITAR))),

    SUPERVISOR("Supervisor", "Revisa ventas, supervisa empleados y autoriza operaciones especiales.",
            EnumSet.of(DASHBOARD_VER, POS_VENDER, POS_DESCUENTO, POS_DESCUENTO_ESPECIAL, POS_PRECIO_ESPECIAL,
                    POS_ANULAR, POS_DEVOLUCION, POS_HISTORIAL, CAJA_OPERAR, CAJA_MOVIMIENTOS, CAJA_AJUSTAR,
                    CAJA_SUPERVISAR, PRODUCTOS_VER, INVENTARIO_VER, PAGOS_VERIFICAR, MESAS_ATENDER, MESAS_TODAS, COCINA_VER, FIADO_GESTIONAR, FIADO_AUTORIZAR,
                    REPORTES_VER, ALERTAS_VER, COTIZACIONES_VER, COTIZACIONES_CREAR, FACTURA_OMITIR, REMISIONES_VER, REMISIONES_CREAR)),

    CAJERO("Cajero", "Registra ventas, recibe pagos y maneja su caja.",
            EnumSet.of(POS_VENDER, POS_DESCUENTO, CAJA_OPERAR, CAJA_MOVIMIENTOS, PRODUCTOS_VER, FIADO_GESTIONAR,
                    COTIZACIONES_VER, COTIZACIONES_CREAR, REMISIONES_VER, REMISIONES_CREAR)),

    INVENTARIO("Encargado de inventario", "Gestiona productos, existencias, conteos y ajustes autorizados.",
            EnumSet.of(PRODUCTOS_VER, PRODUCTOS_EDITAR, PRODUCTOS_IMPORTAR, INVENTARIO_VER, INVENTARIO_AJUSTAR,
                    INVENTARIO_CONTEO, PROVEEDORES_VER, COMPRAS_VER, COMPRAS_RECIBIR, ALERTAS_VER)),

    COMPRAS("Compras", "Administra proveedores, órdenes de compra y recepción de mercancía.",
            EnumSet.of(PRODUCTOS_VER, INVENTARIO_VER, PROVEEDORES_VER, PROVEEDORES_EDITAR, COMPRAS_VER,
                    COMPRAS_CREAR, COMPRAS_RECIBIR, COSTOS_VER, ALERTAS_VER)),

    CONTADOR("Contador o analista", "Consulta informes financieros, costos y resultados.",
            EnumSet.of(DASHBOARD_VER, POS_HISTORIAL, PRODUCTOS_VER, INVENTARIO_VER, COSTOS_VER, PROVEEDORES_VER,
                    COMPRAS_VER, PAGOS_CONCILIAR, FINANZAS_VER, REPORTES_VER, REPORTES_EXPORTAR, ASISTENTE_IA,
                    FACTURACION_CONFIGURAR, CONTABILIDAD_VER, CONTABILIDAD_EDITAR, COTIZACIONES_VER, NOMINA_VER, NOMINA_GESTIONAR, REMISIONES_VER)),

    AUDITOR("Auditor de solo lectura", "Consulta información e historiales sin poder modificar registros.",
            EnumSet.of(DASHBOARD_VER, POS_HISTORIAL, PRODUCTOS_VER, INVENTARIO_VER, COSTOS_VER, PROVEEDORES_VER,
                    COMPRAS_VER, FINANZAS_VER, REPORTES_VER, AUDITORIA_VER, ALERTAS_VER, CONTABILIDAD_VER, COTIZACIONES_VER, REMISIONES_VER)),

    MESERO("Mesero", "Atiende sus mesas desde el celular: pide platos, envía a cocina y saca la precuenta. No cobra ni abre caja.",
            EnumSet.of(MESAS_ATENDER, PRODUCTOS_VER), "RESTAURANTE"),

    COCINA("Cocina o bar", "Solo ve la pantalla de comandas de su estación y marca los platos en preparación y listos.",
            EnumSet.of(COCINA_VER), "RESTAURANTE");

    private final String displayName;
    private final String description;
    private final Set<Permission> permissions;
    /** Módulo que necesita la tienda para tener este rol (null = todas las tiendas). */
    private final String module;

    SystemRole(String displayName, String description, Set<Permission> permissions) {
        this(displayName, description, permissions, null);
    }

    SystemRole(String displayName, String description, Set<Permission> permissions, String module) {
        this.displayName = displayName;
        this.description = description;
        this.permissions = permissions;
        this.module = module;
    }

    public String module() {
        return module;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
