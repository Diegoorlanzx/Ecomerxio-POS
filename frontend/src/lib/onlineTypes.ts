/** Tienda en línea (Fase 9): vitrina pública, configuración y pedidos. */
import type { VariantOption } from './posTypes'
import { apiBase, appUrl } from './host'

export interface DaySchedule {
  day: number
  open: string
  close: string
  closed: boolean
}

export interface Zone {
  id?: number
  name: string
  fee: number
  minOrder?: number
  active: boolean
}

export interface OnlineConfig {
  enabled: boolean
  moduleOn: boolean
  /** El plan incluye la tienda en línea (solo Pro). */
  planAllows: boolean
  slug: string
  mode: 'PEDIDOS' | 'CARTA'
  welcome?: string
  whatsapp?: string
  showStock: boolean
  deliveryEnabled: boolean
  pickupEnabled: boolean
  minOrder: number
  payMethods: string[]
  payLabels: Record<string, string>
  schedule: DaySchedule[]
  paused: boolean
  pausedMessage?: string
  prepMinutes: number
  showBadge: boolean
  zones: Zone[]
  publishedProducts: number
  openNow: boolean
  statusText: string
  /** Restaurante: pedidos desde la mesa por QR. */
  tableOrders: boolean
  restaurantOn: boolean
}

export interface PublishItem {
  id: number
  name: string
  productType: string
  parentId?: number
  categoryName?: string
  visible: boolean
  presentationId?: number
  price?: number
  onlinePrice?: number
  stock: number
  trackSerials: boolean
}

export interface PublicPresentation {
  id: number
  name: string
  factor: number
  price: number
}

export interface PublicProduct {
  id: number
  name: string
  brand?: string
  description?: string
  photoUrl?: string
  categoryId?: number
  unit: string
  productType: string
  parentId?: number
  variantLabel?: string
  variantValues?: Record<string, string>
  variantOptions?: VariantOption[]
  presentations: PublicPresentation[]
  available: boolean
  stock?: number
  /** Restaurante: opciones que se eligen al pedir el plato. */
  modifierGroups?: PublicModGroup[]
}

export interface PublicStore {
  slug: string
  name: string
  logoUrl?: string
  address?: string
  city?: string
  phone?: string
  whatsapp?: string
  welcome?: string
  mode: 'PEDIDOS' | 'CARTA'
  open: boolean
  statusText: string
  prepMinutes: number
  deliveryEnabled: boolean
  pickupEnabled: boolean
  minOrder: number
  zones: { id: number; name: string; fee: number; minOrder: number }[]
  payMethods: { code: string; label: string }[]
  showBadge: boolean
  currency: string
  categories: { id: number; parentId?: number; name: string }[]
  products: PublicProduct[]
  /** Restaurante: recibe pedidos desde la mesa por QR. */
  tableOrders?: boolean
}

/** Restaurante: grupo de opciones de un plato ("Término", "Bebida a elección"). */
export interface PublicModGroup {
  id: number
  name: string
  minSelect: number
  maxSelect: number
  options: { id: number; name: string; price: number }[]
}

export interface OrderLine {
  id: number
  productId: number
  presentationId?: number
  productName: string
  presentationName: string
  factor: number
  quantity: number
  unitPrice: number
  lineTotal: number
  /** Restaurante: opciones elegidas y nota para la cocina. */
  modifiers?: string | null
  note?: string | null
}

export type OrderStatus = 'NUEVO' | 'ACEPTADO' | 'PREPARANDO' | 'LISTO' | 'EN_CAMINO' | 'ENTREGADO' | 'CANCELADO' | 'EN_MESA'

export interface PublicOrder {
  token: string
  number: number
  status: OrderStatus
  storeName: string
  storeSlug: string
  customerName: string
  fulfillment: 'DOMICILIO' | 'RECOGER' | 'MESA'
  zoneName?: string
  address?: string
  payMethod: string
  payLabel: string
  subtotal: number
  deliveryFee: number
  total: number
  lines: OrderLine[]
  payInfo: { method: string; label: string; holder?: string; accountRef?: string; qrImage?: string }[]
  whatsappUrl?: string
  prepMinutes: number
  createdAt: string
  acceptedAt?: string
  readyAt?: string
  dispatchedAt?: string
  deliveredAt?: string
  cancelledAt?: string
  cancelReason?: string
  tableLabel?: string | null
}

export interface OnlineOrder {
  id: number
  number: number
  token: string
  status: OrderStatus
  customerId?: number
  customerName: string
  phone: string | null
  email?: string
  fulfillment: 'DOMICILIO' | 'RECOGER' | 'MESA'
  zoneName?: string
  address?: string
  notes?: string
  payMethod: string
  payLabel: string
  subtotal: number
  deliveryFee: number
  total: number
  saleId?: number
  cancelReason?: string
  handledBy?: string
  lines: OrderLine[]
  createdAt: string
  acceptedAt?: string
  readyAt?: string
  dispatchedAt?: string
  deliveredAt?: string
  cancelledAt?: string
  tableId?: number | null
  tableLabel?: string | null
  /** Restaurante: en qué va la cocina (PREPARANDO, LISTO o null si no ha empezado). */
  kitchenState?: 'PREPARANDO' | 'LISTO' | null
}

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: 'primary' | 'warning' | 'success' | 'danger' | 'neutral' | 'wallet' }> = {
  NUEVO: { label: 'Nuevo', tone: 'danger' },
  ACEPTADO: { label: 'Aceptado', tone: 'primary' },
  PREPARANDO: { label: 'En preparación', tone: 'warning' },
  LISTO: { label: 'Listo', tone: 'success' },
  EN_CAMINO: { label: 'En camino', tone: 'wallet' },
  ENTREGADO: { label: 'Entregado', tone: 'success' },
  CANCELADO: { label: 'Cancelado', tone: 'neutral' },
  EN_MESA: { label: 'Pasado a la mesa', tone: 'success' },
}

/** Dirección pública de la vitrina (mismo servidor que la app). */
export const storeUrl = (slug: string) => appUrl(`/t/${slug}`)

/** Llamadas públicas: sin token y sin cerrar la sesión de nadie. */
export async function publicApi<T>(path: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${apiBase()}/public${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new PublicError(0, 'Sin conexión. Revisa tu internet e intenta de nuevo.', {})
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new PublicError(res.status, data?.message ?? 'No se pudo completar', data?.fields ?? {})
  return data as T
}

export class PublicError extends Error {
  constructor(
    public status: number,
    message: string,
    public fields: Record<string, string>,
  ) {
    super(message)
  }
}
