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
}

export interface DiningTableDto {
  id: number
  areaId: number | null
  name: string
  seats: number
  sortOrder: number
  state: TableState
  accounts: AccountOnTable[]
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
}

export interface ModifierOptionDto {
  id: number
  name: string
  price: number
  sortOrder: number
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
  saleId: number
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
