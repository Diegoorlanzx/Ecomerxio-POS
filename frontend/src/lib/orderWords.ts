/**
 * Vocabulario de las órdenes de servicio según el tipo de negocio: un taller recibe un vehículo con placa, una lavandería
 * una prenda, una panadería un encargo y una óptica un trabajo de laboratorio. La lógica (estados, anticipo, aviso por
 * WhatsApp cuando está listo) es la misma.
 */
export interface OrderWords {
  title: string
  receive: string
  item: string
  itemPlaceholder: string
  serial: string
  serialPlaceholder?: string
  searchPlaceholder: string
  emptyText: string
  /** Muestra el historial por serial/placa en el detalle. */
  history: boolean
}

const BASE: OrderWords = {
  title: 'Órdenes de servicio',
  receive: 'Recibir equipo',
  item: 'Equipo',
  itemPlaceholder: 'Celular, portátil, licuadora…',
  serial: 'Serial / IMEI',
  searchPlaceholder: 'Buscar por número, cliente, equipo o serial',
  emptyText: 'Cuando recibas un equipo para reparar, créale su orden.',
  history: true,
}

const BY_TYPE: Record<string, Partial<OrderWords>> = {
  TALLER: {
    title: 'Órdenes del taller',
    receive: 'Recibir vehículo',
    item: 'Vehículo',
    itemPlaceholder: 'Moto, carro, bicicleta…',
    serial: 'Placa',
    serialPlaceholder: 'ABC123',
    searchPlaceholder: 'Buscar por número, cliente, vehículo o placa',
    emptyText: 'Cuando recibas un vehículo, créale su orden: queda su historial por placa.',
  },
  LAVANDERIA: {
    title: 'Órdenes de prendas',
    receive: 'Recibir prendas',
    item: 'Prenda',
    itemPlaceholder: 'Camisa, vestido, zapatos, cortinas…',
    serial: 'Marca o ticket',
    searchPlaceholder: 'Buscar por número, cliente o prenda',
    emptyText: 'Cuando recibas prendas, créales su orden y avisa por WhatsApp cuando estén listas.',
    history: false,
  },
  PANADERIA: {
    title: 'Encargos',
    receive: 'Nuevo encargo',
    item: 'Encargo',
    itemPlaceholder: 'Torta de chocolate para 20, 50 pandebonos…',
    serial: 'Referencia',
    searchPlaceholder: 'Buscar por número, cliente o encargo',
    emptyText: 'Registra los encargos con su anticipo y la fecha de entrega.',
    history: false,
  },
  OPTICA: {
    title: 'Trabajos de laboratorio',
    receive: 'Nuevo trabajo',
    item: 'Trabajo',
    itemPlaceholder: 'Lentes progresivos con montura…',
    serial: 'N.º de fórmula',
    searchPlaceholder: 'Buscar por número, cliente o trabajo',
    emptyText: 'Registra cada trabajo de laboratorio con su anticipo y avisa cuando estén listos los lentes.',
    history: false,
  },
}

export function orderWords(businessType?: string | null): OrderWords {
  return { ...BASE, ...(businessType ? BY_TYPE[businessType] ?? {} : {}) }
}
