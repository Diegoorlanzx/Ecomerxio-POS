type Tone = 'success' | 'warning' | 'danger' | 'wallet' | 'primary' | 'neutral'

export interface RemissionLine {
  id: number
  productId?: number | null
  presentationId?: number | null
  description: string
  presentationName?: string | null
  factor: number
  quantity: number
  returnedQty: number
  pendingQty: number
  unitPrice: number
  discountPct: number
  taxName?: string | null
  taxRate: number
  fixedTax: number
  gross: number
  discount: number
  tax: number
  lineTotal: number
  free: boolean
}

export interface Remission {
  id: number
  number: number
  code: string
  status: string
  customerId?: number | null
  customerName: string
  customerDocType?: string | null
  customerDoc?: string | null
  customerPhone?: string | null
  deliveryAddress?: string | null
  issueDate: string
  notes?: string | null
  showPrices: boolean
  subtotal: number
  discountTotal: number
  taxTotal: number
  total: number
  /** Lo que falta por facturar (entregado menos devuelto). */
  pendingTotal: number
  pricesIncludeTax: boolean
  quoteId?: number | null
  quoteCode?: string | null
  saleId?: number | null
  saleNumber?: number | null
  /** Las otras remisiones que salieron en la misma factura. */
  invoicedWith: string[]
  createdBy?: string
  createdAt: string
  deliveredAt?: string | null
  deliveredBy?: string | null
  receivedBy?: string | null
  receivedDoc?: string | null
  invoicedAt?: string | null
  cancelledAt?: string | null
  cancelReason?: string | null
  lines: RemissionLine[]
  store: {
    name: string
    legalName?: string | null
    taxId?: string | null
    phone?: string | null
    email?: string | null
    address?: string | null
    city?: string | null
    logo?: string | null
    currency: string
  }
}

export interface RemissionSummary {
  id: number
  code: string
  status: string
  customerId?: number | null
  customerName: string
  customerDoc?: string | null
  total: number
  pendingTotal: number
  issueDate: string
  deliveredAt?: string | null
  createdBy?: string
  saleNumber?: number | null
}

export const REMISSION_STATUS: Record<string, { label: string; tone: Tone }> = {
  BORRADOR: { label: 'Borrador', tone: 'neutral' },
  ENTREGADA: { label: 'Entregada · por facturar', tone: 'warning' },
  FACTURADA: { label: 'Facturada', tone: 'success' },
  ANULADA: { label: 'Anulada', tone: 'danger' },
}
