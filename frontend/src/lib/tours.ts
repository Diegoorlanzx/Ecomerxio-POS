/**
 * Definición de los recorridos guiados. El target de cada paso es un atributo data-guide del DOM, o "text:<texto>" /
 * "css:<selector>" dentro de la página (ver TourOverlay). Si el elemento no está en pantalla, el paso se salta.
 */
import { MODULE_TOURS } from './moduleTours'

export interface TourStep {
  /** Valor del atributo data-guide del elemento a resaltar; vacío = sin highlight */
  target: string
  title: string
  text: string
  /** Pantalla (con su pestaña) que se abre antes de este paso, p. ej. "/facturacion?tab=documentos". */
  go?: string
}

export interface Tour {
  id: string
  title: string
  description: string
  icon: string
  /** Ruta a la que debe navegar el usuario antes de iniciar */
  route: string
  /** Grupo en la pantalla de la guía (el mismo orden del menú Más). */
  section: TourSection
  /** Solo se ofrece si el usuario puede abrir la pantalla. */
  show: (can: (p: string) => boolean) => boolean
  /** Solo si la tienda usa este módulo. */
  module?: string
  /**
   * Recorridos de una ficha (un cliente, un proveedor…): devuelve la ruta de la primera que exista, o null si no hay
   * ninguna (entonces se muestra `emptyText`).
   */
  resolve?: () => Promise<string | null>
  emptyText?: string
  steps: TourStep[]
}

export type TourSection = 'Lo básico' | 'Operación' | 'Análisis' | 'Administración'
export const TOUR_SECTIONS: TourSection[] = ['Lo básico', 'Operación', 'Análisis', 'Administración']

const BASIC_TOURS: Tour[] = [
  {
    id: 'dashboard',
    title: 'Panel de control',
    description: 'Entiende las ventas, la caja y las alertas de tu tienda en un vistazo.',
    icon: 'space_dashboard',
    route: '/dashboard',
    section: 'Lo básico',
    show: (c) => c('DASHBOARD_VER'),
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
        title: 'Dinero que entró',
        text: 'Lo que realmente entró en el período: lo cobrado en efectivo y digitales más los abonos de fiado de ese día, menos lo devuelto a clientes. Debajo ves lo vendido (con el cambio frente al período anterior) y lo que se fió sin pagar.',
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
        text: 'Lo que necesita tu atención hoy: productos agotados o por agotarse, pagos digitales por verificar y pedidos a proveedores. Toca cada aviso para resolverlo.',
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
    section: 'Lo básico',
    show: (c) => c('POS_VENDER'),
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
        text: 'Aquí van los productos que el cliente lleva, con el total a pagar. Puedes cambiar cantidades, aplicar descuentos o quitar artículos. En el celular, toca esta barra para ver el detalle.',
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
    section: 'Lo básico',
    show: (c) => c('CAJA_OPERAR') || c('CAJA_SUPERVISAR'),
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

export const TOURS: Tour[] = [...BASIC_TOURS, ...MODULE_TOURS]

export function tourById(id: string): Tour | undefined {
  return TOURS.find((t) => t.id === id)
}
