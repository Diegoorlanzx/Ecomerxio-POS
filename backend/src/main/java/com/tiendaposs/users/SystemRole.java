package com.tiendaposs.users;

import com.tiendaposs.security.Permission;

import java.util.EnumSet;
import java.util.Set;

import static com.tiendaposs.security.Permission.*;

/** Los 8 roles iniciales que se crean en cada tienda nueva. El dueño puede editar todos menos DUENO. */
public enum SystemRole {

    DUENO("Dueño", "Acceso completo: costos, ganancias, permisos, caja y configuración.",
            EnumSet.allOf(Permission.class)),

    ADMINISTRADOR("Administrador", "Gestiona la operación general dentro de los límites que fija el dueño.",
            EnumSet.complementOf(EnumSet.of(ROLES_GESTIONAR, CONFIG_EDITAR))),

    SUPERVISOR("Supervisor", "Revisa ventas, supervisa empleados y autoriza operaciones especiales.",
            EnumSet.of(DASHBOARD_VER, POS_VENDER, POS_DESCUENTO, POS_DESCUENTO_ESPECIAL, POS_PRECIO_ESPECIAL,
                    POS_ANULAR, POS_DEVOLUCION, POS_HISTORIAL, CAJA_OPERAR, CAJA_MOVIMIENTOS, CAJA_AJUSTAR,
                    CAJA_SUPERVISAR, PRODUCTOS_VER, INVENTARIO_VER, PAGOS_VERIFICAR, FIADO_GESTIONAR, FIADO_AUTORIZAR,
                    REPORTES_VER, ALERTAS_VER)),

    CAJERO("Cajero", "Registra ventas, recibe pagos y maneja su caja.",
            EnumSet.of(POS_VENDER, POS_DESCUENTO, CAJA_OPERAR, CAJA_MOVIMIENTOS, PRODUCTOS_VER, FIADO_GESTIONAR)),

    INVENTARIO("Encargado de inventario", "Gestiona productos, existencias, conteos y ajustes autorizados.",
            EnumSet.of(PRODUCTOS_VER, PRODUCTOS_EDITAR, PRODUCTOS_IMPORTAR, INVENTARIO_VER, INVENTARIO_AJUSTAR,
                    INVENTARIO_CONTEO, PROVEEDORES_VER, COMPRAS_VER, COMPRAS_RECIBIR, ALERTAS_VER)),

    COMPRAS("Compras", "Administra proveedores, órdenes de compra y recepción de mercancía.",
            EnumSet.of(PRODUCTOS_VER, INVENTARIO_VER, PROVEEDORES_VER, PROVEEDORES_EDITAR, COMPRAS_VER,
                    COMPRAS_CREAR, COMPRAS_RECIBIR, COSTOS_VER, ALERTAS_VER)),

    CONTADOR("Contador o analista", "Consulta informes financieros, costos y resultados.",
            EnumSet.of(DASHBOARD_VER, POS_HISTORIAL, PRODUCTOS_VER, INVENTARIO_VER, COSTOS_VER, PROVEEDORES_VER,
                    COMPRAS_VER, PAGOS_CONCILIAR, FINANZAS_VER, REPORTES_VER, REPORTES_EXPORTAR, ASISTENTE_IA)),

    AUDITOR("Auditor de solo lectura", "Consulta información e historiales sin poder modificar registros.",
            EnumSet.of(DASHBOARD_VER, POS_HISTORIAL, PRODUCTOS_VER, INVENTARIO_VER, COSTOS_VER, PROVEEDORES_VER,
                    COMPRAS_VER, FINANZAS_VER, REPORTES_VER, AUDITORIA_VER, ALERTAS_VER));

    private final String displayName;
    private final String description;
    private final Set<Permission> permissions;

    SystemRole(String displayName, String description, Set<Permission> permissions) {
        this.displayName = displayName;
        this.description = description;
        this.permissions = permissions;
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
