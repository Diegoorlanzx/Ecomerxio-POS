/** Fase 10 — Restaurante: tipos del plano de mesas, modificadores y cocina. */

export type TableState = 'LIBRE' | 'OCUPADA' | 'POR_COBRAR' | 'LISTO'

export interface AccountOnTable {
  id: number
  label: string
  waiterName: string
  total: number
  items: number
  guests: number
  minutes: number
  prebill: boolean
  ready: number
  cooking: number
  pendingToSend: number
  /** El usuario puede abrir la cuenta (el mesero solo abre las suyas). */
  mine: boolean
}

export interface DiningTableDto {
  id: number
  areaId: number | null
  name: string
  seats: number
  sortOrder: number
  state: TableState
  accounts: AccountOnTable[]
  /** Código del QR de la mesa (pedidos desde la mesa). */
  qrToken: string
}

export interface DiningArea {
  id: number
  name: string
  sortOrder: number
}

export interface Floor {
  areas: DiningArea[]
  tables: DiningTableDto[]
  tipsEnabled: boolean
  tipSuggestedPct: number
  /** Próxima reserva de cada mesa (las próximas 3 horas), por id de mesa. */
  reservations: Record<string, Reservation>
}

export type ReservationStatus = 'PENDIENTE' | 'CONFIRMADA' | 'LLEGO' | 'NO_LLEGO' | 'CANCELADA'

export interface Reservation {
  id: number
  tableId: number | null
  tableName: string | null
  name: string
  phone: string | null
  people: number
  reservedAt: string
  durationMin: number
  notes: string | null
  status: ReservationStatus
  saleId: number | null
  createdBy: string | null
}

export const RESERVATION_STATUS: Record<ReservationStatus, { label: string; tone: 'primary' | 'success' | 'warning' | 'danger' | 'neutral' }> = {
  PENDIENTE: { label: 'Por confirmar', tone: 'warning' },
  CONFIRMADA: { label: 'Confirmada', tone: 'primary' },
  LLEGO: { label: 'Llegó', tone: 'success' },
  NO_LLEGO: { label: 'No llegó', tone: 'danger' },
  CANCELADA: { label: 'Cancelada', tone: 'neutral' },
}

/** Pedido hecho desde la mesa por el QR, esperando al mesero. */
export interface TableOrder {
  id: number
  number: number
  customerName: string
  tableId: number | null
  tableLabel: string | null
  notes: string | null
  total: number
  createdAt: string
  lines: { id: number; productName: string; presentationName: string; quantity: number; unitPrice: number; lineTotal: number; modifiers: string | null; note: string | null }[]
}

export interface ModifierOptionDto {
  id: number
  name: string
  price: number
  sortOrder: number
  /** "Gaseosa a elección": producto que sale del inventario al elegir la opción. */
  productId: number | null
  productName: string | null
  productQty: number
}

export interface ModifierGroupDto {
  id: number
  name: string
  minSelect: number
  maxSelect: number
  sortOrder: number
  options: ModifierOptionDto[]
  productIds: number[]
}

export type Station = 'COCINA' | 'BAR'

export interface KitchenTicketDto {
  id: number
  saleId: number | null
  comanda: number
  station: Station
  tableLabel: string
  waiterName: string | null
  productName: string
  quantity: number
  modifiers: string | null
  note: string | null
  kind: 'PEDIDO' | 'ANULAR'
  status: 'PENDIENTE' | 'PREPARANDO' | 'LISTO' | 'ENTREGADO'
  createdAt: string
  startedAt: string | null
  readyAt: string | null
  deliveredAt: string | null
  minutes: number
  /** Comanda de un pedido en línea (domicilio o para llevar). */
  onlineOrderId: number | null
}

export interface StationCategory {
  id: number
  name: string
  station: Station | null
}

export const TABLE_STATE: Record<TableState, { label: string; card: string; dot: string }> = {
  LIBRE: { label: 'Libre', card: 'border-line bg-card', dot: 'bg-slate-300' },
  OCUPADA: { label: 'Ocupada', card: 'border-primary/50 bg-primary-tint', dot: 'bg-primary' },
  POR_COBRAR: { label: 'Por cobrar', card: 'border-warning bg-warning-soft', dot: 'bg-warning' },
  LISTO: { label: 'Platos listos', card: 'border-success bg-success-soft', dot: 'bg-success' },
}
