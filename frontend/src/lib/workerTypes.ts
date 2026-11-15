/**
 * Plantillas para crear un rol según el tipo de trabajador: marcan los permisos típicos de ese puesto y
 * luego el dueño los ajusta. Solo se usan los permisos que existen en la tienda (los de módulos apagados se ignoran).
 * `module` = el tipo solo aparece si la tienda tiene ese módulo encendido (p. ej. mesero solo con restaurante).
 */
export type WorkerType = { key: string; name: string; icon: string; description: string; module?: string; permissions: string[] }

const CAJA = ['POS_VENDER', 'POS_DESCUENTO', 'CAJA_OPERAR', 'CAJA_MOVIMIENTOS', 'PRODUCTOS_VER', 'FIADO_GESTIONAR']

export const WORKER_TYPES: WorkerType[] = [
  {
    key: 'cajero',
    name: 'Cajero',
    icon: 'point_of_sale',
    description: 'Vende, cobra y maneja su propia caja.',
    permissions: CAJA,
  },
  {
    key: 'vendedor',
    name: 'Vendedor o asesor',
    icon: 'support_agent',
    description: 'Atiende clientes, consulta existencias y hace cotizaciones.',
    permissions: ['POS_VENDER', 'POS_DESCUENTO', 'PRODUCTOS_VER', 'INVENTARIO_VER', 'COTIZACIONES_VER', 'COTIZACIONES_CREAR', 'FIADO_GESTIONAR'],
  },
  {
    key: 'mesero',
    name: 'Mesero',
    icon: 'room_service',
    description: 'Atiende sus mesas y envía los pedidos a cocina. No cobra.',
    module: 'RESTAURANTE',
    permissions: ['MESAS_ATENDER', 'PRODUCTOS_VER'],
  },
  {
    key: 'cocina',
    name: 'Cocina o bar',
    icon: 'skillet',
    description: 'Solo ve la pantalla de cocina y marca los platos listos.',
    module: 'RESTAURANTE',
    permissions: ['COCINA_VER'],
  },
  {
    key: 'bodega',
    name: 'Auxiliar de bodega',
    icon: 'inventory_2',
    description: 'Recibe mercancía y hace conteos. No cambia precios.',
    permissions: ['PRODUCTOS_VER', 'INVENTARIO_VER', 'INVENTARIO_CONTEO', 'PROVEEDORES_VER', 'COMPRAS_VER', 'COMPRAS_RECIBIR'],
  },
  {
    key: 'inventario',
    name: 'Encargado de inventario',
    icon: 'inventory',
    description: 'Crea productos, ajusta existencias y recibe alertas de stock.',
    permissions: ['PRODUCTOS_VER', 'PRODUCTOS_EDITAR', 'PRODUCTOS_IMPORTAR', 'INVENTARIO_VER', 'INVENTARIO_AJUSTAR', 'INVENTARIO_CONTEO', 'COMPRAS_VER', 'COMPRAS_RECIBIR', 'PROVEEDORES_VER', 'ALERTAS_VER'],
  },
  {
    key: 'compras',
    name: 'Comprador',
    icon: 'shopping_cart',
    description: 'Hace pedidos a proveedores y recibe la mercancía.',
    permissions: ['PRODUCTOS_VER', 'INVENTARIO_VER', 'COSTOS_VER', 'PROVEEDORES_VER', 'PROVEEDORES_EDITAR', 'COMPRAS_VER', 'COMPRAS_CREAR', 'COMPRAS_RECIBIR', 'ALERTAS_VER'],
  },
  {
    key: 'supervisor',
    name: 'Supervisor de turno',
    icon: 'supervisor_account',
    description: 'Lo del cajero más anular, devolver, autorizar y revisar cajas.',
    permissions: [
      ...CAJA,
      'DASHBOARD_VER',
      'POS_DESCUENTO_ESPECIAL',
      'POS_ANULAR',
      'POS_DEVOLUCION',
      'POS_HISTORIAL',
      'CAJA_AJUSTAR',
      'CAJA_SUPERVISAR',
      'FIADO_AUTORIZAR',
      'PAGOS_VERIFICAR',
      'INVENTARIO_VER',
      'ALERTAS_VER',
      'REPORTES_VER',
    ],
  },
  {
    key: 'facturacion',
    name: 'Auxiliar administrativo',
    icon: 'receipt_long',
    description: 'Cotizaciones, prefacturas, remisiones, pagos y cartera.',
    permissions: [
      'POS_HISTORIAL',
      'PRODUCTOS_VER',
      'COTIZACIONES_VER',
      'COTIZACIONES_CREAR',
      'PREFACTURAS_APROBAR',
      'REMISIONES_VER',
      'REMISIONES_CREAR',
      'PAGOS_VERIFICAR',
      'FIADO_GESTIONAR',
      'FINANZAS_GASTOS',
    ],
  },
  {
    key: 'contador',
    name: 'Contador',
    icon: 'calculate',
    description: 'Consulta finanzas, costos, contabilidad y nómina. No vende.',
    permissions: [
      'DASHBOARD_VER',
      'POS_HISTORIAL',
      'COSTOS_VER',
      'FINANZAS_VER',
      'PAGOS_CONCILIAR',
      'REPORTES_VER',
      'REPORTES_EXPORTAR',
      'CONTABILIDAD_VER',
      'CONTABILIDAD_EDITAR',
      'NOMINA_VER',
      'VENTAS_HISTORIAL_PREVIO',
    ],
  },
]
