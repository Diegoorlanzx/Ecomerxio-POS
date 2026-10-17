/** Definición de los recorridos guiados. Los targets corresponden a atributos data-guide en el DOM. */

export interface TourStep {
  /** Valor del atributo data-guide del elemento a resaltar; vacío = sin highlight */
  target: string
  title: string
  text: string
}

export interface Tour {
  id: string
  title: string
  description: string
  icon: string
  /** Ruta a la que debe navegar el usuario antes de iniciar */
  route: string
  steps: TourStep[]
}

export const TOURS: Tour[] = [
  {
    id: 'dashboard',
    title: 'Panel de control',
    description: 'Entiende las ventas, la caja y las alertas de tu tienda en un vistazo.',
    icon: 'space_dashboard',
    route: '/dashboard',
    steps: [
      {
        target: '',
        title: 'Bienvenido al panel de control',
        text: 'Aquí ves el resumen completo de tu negocio: ventas del día, estado de la caja, productos críticos y accesos rápidos. Se actualiza automáticamente cada minuto.',
      },
      {
        target: 'dash-periodo',
        title: 'Elige el período',
        text: 'Cambia entre Hoy, Semana, Quincena y Mes para ver las ventas del período que necesites y compararlas con el período anterior.',
      },
      {
        target: 'dash-ventas',
        title: 'Ventas del período',
        text: 'El total de ventas del período seleccionado. El porcentaje de cambio muestra si vendiste más o menos que el período anterior.',
      },
      {
        target: 'dash-utilidad',
        title: 'Utilidad bruta',
        text: 'Es lo que queda después de restar el costo de los productos vendidos. Si no ves este dato, configura los costos en Inventario.',
      },
      {
        target: 'dash-caja',
        title: 'Estado de la caja',
        text: 'Muestra cuántas cajas hay abiertas ahora mismo. Desde aquí puedes ir directamente a abrir o revisar un turno.',
      },
      {
        target: 'dash-alertas',
        title: 'Alertas de inventario',
        text: 'Productos agotados o con stock bajo que necesitan reposición. Toca uno para ir directo al inventario.',
      },
      {
        target: 'dash-accesos',
        title: 'Accesos rápidos',
        text: 'Los módulos más usados están aquí para llegar en un toque. También puedes usar la barra de navegación en la parte inferior.',
      },
    ],
  },
  {
    id: 'pos',
    title: 'Punto de venta',
    description: 'Busca productos, arma el pedido y cobra paso a paso.',
    icon: 'point_of_sale',
    route: '/pos',
    steps: [
      {
        target: '',
        title: 'El punto de venta',
        text: 'Aquí registras las ventas. Funciona también sin internet: las ventas se guardan y se sincronizan cuando se restaure la conexión.',
      },
      {
        target: 'pos-buscar',
        title: 'Buscar productos',
        text: 'Escribe el nombre o parte de él para filtrar el catálogo. También puedes escanear el código de barras con la cámara o un lector externo.',
      },
      {
        target: 'pos-categorias',
        title: 'Filtrar por categoría',
        text: 'Toca una categoría para ver solo sus productos. Útil cuando el catálogo es grande y quieres ir directo a la sección.',
      },
      {
        target: 'pos-catalogo',
        title: 'Catálogo de productos',
        text: 'Toca un producto para agregarlo al carrito. Si tiene presentaciones (como litro / 500 ml), te pregunta cuál quieres.',
      },
      {
        target: 'pos-carrito',
        title: 'Carrito de compra',
        text: 'Aquí van los productos que el cliente lleva. Puedes cambiar cantidades, aplicar descuentos por línea o eliminar artículos.',
      },
      {
        target: 'pos-cobrar',
        title: 'Cobrar',
        text: 'Cuando el carrito esté listo, toca Cobrar. Puedes dividir el pago entre efectivo, Nequi, tarjeta y otros métodos que hayas configurado.',
      },
    ],
  },
  {
    id: 'caja',
    title: 'Caja',
    description: 'Abre el turno, registra movimientos, haz el arqueo y cierra.',
    icon: 'payments',
    route: '/caja',
    steps: [
      {
        target: '',
        title: 'La caja del negocio',
        text: 'Cada cajero trabaja dentro de un turno. El turno registra las ventas, los movimientos de efectivo y el cuadre al cierre.',
      },
      {
        target: 'caja-abrir',
        title: 'Abrir turno',
        text: 'Antes de empezar a vender, abre el turno con el efectivo que tienes en caja al inicio. Esto es la base para el cuadre al final.',
      },
      {
        target: 'caja-turno',
        title: 'Resumen del turno',
        text: 'Mientras el turno está abierto ves el total en caja, los pagos recibidos por método y los movimientos registrados.',
      },
      {
        target: 'caja-movimiento',
        title: 'Registrar movimientos',
        text: 'Registra gastos, retiros e ingresos de efectivo que no son ventas: el almuerzo del cajero, un pago a un proveedor, un alivio de caja.',
      },
      {
        target: 'caja-cerrar',
        title: 'Arqueo y cierre',
        text: 'Al cerrar el turno contarás el efectivo físico. El sistema compara lo que debería haber con lo que contaste y registra la diferencia.',
      },
      {
        target: 'caja-historial',
        title: 'Historial de turnos',
        text: 'Aquí quedan todos los turnos cerrados con su cuadre. Puedes revisar cualquier turno anterior, ver sus ventas y movimientos.',
      },
    ],
  },
]

export function tourById(id: string): Tour | undefined {
  return TOURS.find((t) => t.id === id)
}
