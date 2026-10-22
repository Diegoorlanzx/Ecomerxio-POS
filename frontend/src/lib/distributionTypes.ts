/** Fase 12 — Distribución: listas de precios, rutas, preventa, despachos y cierre de ruta. */

export interface PriceListItem {
  presentationId: number
  minQty: number
  price: number
}

export interface PriceListDto {
  id: number
  name: string
  discountPct: number
  active: boolean
  items: PriceListItem[]
  customers: number
}

export interface RouteDto {
  id: number
  name: string
  sellerUserId: number | null
  sellerName: string | null
  visitDays: number[]
  commissionPct: number
  active: boolean
  customers: number
}

export interface RouteCustomer {
  id: number
  name: string
  phone: string | null
  address: string | null
  routeId: number | null
  routeOrder: number
  priceListId: number | null
}

export interface SellerCustomer {
  id: number
  name: string
  phone: string | null
  address: string | null
  routeId: number
  routeOrder: number
  priceListId: number | null
  today: boolean
  balance: number
  overdue: number
  blocked: boolean
  visitResult: string | null
  orderToday: number | null
}

export interface SellerProduct {
  productId: number
  presentationId: number
  name: string
  presentationName: string
  price: number
  stock: number
  factor: number
  unit: string
  categoryId: number | null
}

export interface SellerData {
  date: string
  weekday: number
  routes: RouteDto[]
  customers: SellerCustomer[]
  priceLists: PriceListDto[]
  products: SellerProduct[]
  creditEnabled: boolean
}

export type PresaleStatus = 'PENDIENTE' | 'ALISTADO' | 'DESPACHADO' | 'ENTREGADO' | 'PARCIAL' | 'DEVUELTO' | 'ANULADO'

export const PRESALE_STATUS: Record<PresaleStatus, { label: string; tone: string }> = {
  PENDIENTE: { label: 'Por alistar', tone: 'bg-warning-soft text-warning-ink' },
  ALISTADO: { label: 'Alistado', tone: 'bg-primary-tint text-primary' },
  DESPACHADO: { label: 'En ruta', tone: 'bg-primary-tint text-primary' },
  ENTREGADO: { label: 'Entregado', tone: 'bg-success-soft text-success-ink' },
  PARCIAL: {
    label: 'Entrega parcial',
    tone: 'bg-warning-soft text-warning-ink',
  },
  DEVUELTO: { label: 'Devuelto', tone: 'bg-danger-soft text-danger-ink' },
  ANULADO: { label: 'Anulado', tone: 'bg-slate-100 text-slate-500' },
}

export interface PresaleLine {
  id: number
  presentationId: number
  productId: number
  productName: string
  presentationName: string | null
  quantity: number
  unitPrice: number
  deliveredQty: number | null
  lineTotal: number
}

export interface PresaleOrder {
  id: number
  number: number
  customerId: number
  customerName: string
  customerPhone: string | null
  customerAddress: string | null
  customerDoc: string | null
  routeId: number | null
  routeName: string | null
  sellerName: string | null
  status: PresaleStatus
  notes: string | null
  total: number
  takenAt: string
  syncedAt: string
  offline: boolean
  pickedAt: string | null
  dispatchId: number | null
  deliveredAt: string | null
  saleId: number | null
  deliveredTotal: number
  collectedTotal: number
  creditTotal: number
  returnReason: string | null
  lines: PresaleLine[]
}

export interface PresaleSummary {
  id: number
  number: number
  customerName: string
  routeName: string | null
  sellerName: string | null
  status: PresaleStatus
  total: number
  deliveredTotal: number
  items: number
  takenAt: string
  offline: boolean
  dispatchId: number | null
}

export interface PickList {
  orders: PresaleSummary[]
  lines: {
    presentationId: number
    productName: string
    presentationName: string | null
    quantity: number
    stockUnits: number
    factor: number
    shortage: boolean
  }[]
  total: number
}

export interface DispatchDto {
  id: number
  number: number
  routeId: number | null
  routeName: string | null
  driverName: string
  vehicle: string | null
  status: 'EN_RUTA' | 'CERRADO'
  createdBy: string
  createdAt: string
  closedAt: string | null
  closedBy: string | null
  cashExpected: number
  cashCounted: number | null
  difference: number | null
  notes: string | null
  orders: PresaleOrder[]
  total: number
  delivered: number
  returned: number
  credit: number
  collected: { method: string; amount: number }[]
  pending: number
}

export interface DistributionReport {
  from: string
  to: string
  sellers: {
    sellerUserId: number | null
    sellerName: string | null
    orders: number
    ordered: number
    delivered: number
    commission: number
  }[]
  routes: {
    routeId: number
    routeName: string
    sellerName: string | null
    customers: number
    scheduledVisits: number
    visits: number
    orders: number
    effectivenessPct: number
    delivered: number
    returned: number
    overdue: number
  }[]
}

export const DAY_SHORT = ['', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom']

/** Mismo cálculo que el servidor: escalón de volumen de la lista, si no su descuento general, si no el precio de venta. */
export function listPrice(lists: PriceListDto[], listId: number | null, p: SellerProduct, qty: number): number {
  const l = lists.find((x) => x.id === listId)
  if (!l) return p.price
  const tiers = l.items.filter((i) => i.presentationId === p.presentationId && i.minQty <= qty).sort((a, b) => b.minQty - a.minQty)
  if (tiers.length) return tiers[0]!.price
  if (l.discountPct > 0) return Math.round((p.price * (100 - l.discountPct)) / 100)
  return p.price
}
