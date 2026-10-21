type Tone = 'success' | 'warning' | 'danger' | 'wallet' | 'primary' | 'neutral'

export type QuoteKind = 'COTIZACION' | 'PREFACTURA'

export interface QuoteLine {
  id?: number
  productId?: number | null
  presentationId?: number | null
  description: string
  presentationName?: string | null
  factor: number
  quantity: number
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

export interface Issue {
  severity: 'ERROR' | 'WARNING' | 'INFO'
  code: string
  message: string
  fix?: 'CLIENTE' | 'LINEAS' | 'FACTURACION' | 'PRODUCTOS' | null
}

export interface Validation {
  ok: boolean
  errors: number
  warnings: number
  issues: Issue[]
  recomputedTotal: number
}

export interface Quote {
  id: number
  kind: QuoteKind
  number: number
  code: string
  status: string
  customerId?: number | null
  customerName?: string | null
  customerDocType?: string | null
  customerDoc?: string | null
  customerDv?: string | null
  customerPersonType?: 'NATURAL' | 'JURIDICA' | null
  customerEmail?: string | null
  customerPhone?: string | null
  customerAddress?: string | null
  customerMunicipality?: string | null
  issueDate: string
  validUntil?: string | null
  notes?: string | null
  terms?: string | null
  saleDiscountAmount: number
  subtotal: number
  discountTotal: number
  taxTotal: number
  total: number
  pricesIncludeTax: boolean
  sourceId?: number | null
  sourceCode?: string | null
  prefacturaId?: number | null
  prefacturaCode?: string | null
  saleId?: number | null
  saleNumber?: number | null
  publicToken: string
  createdBy?: string
  createdAt: string
  updatedAt: string
  sentAt?: string | null
  decidedAt?: string | null
  decidedBy?: string | null
  decisionNote?: string | null
  clientCheck?: 'CONFIRMADO' | 'CON_ERROR' | null
  clientCheckNote?: string | null
  clientCheckAt?: string | null
  approvedBy?: string | null
  approvedAt?: string | null
  approvedWarnings: string[]
  cancelledAt?: string | null
  cancelReason?: string | null
  lines: QuoteLine[]
  validation?: Validation | null
}

export interface QuoteSummary {
  id: number
  kind: QuoteKind
  code: string
  status: string
  customerName?: string | null
  customerDoc?: string | null
  total: number
  issueDate: string
  validUntil?: string | null
  createdBy?: string
  createdAt: string
  clientCheck?: string | null
}

export interface PublicQuote {
  kind: QuoteKind
  code: string
  status: string
  storeName: string
  storeLegalName?: string | null
  storeTaxId?: string | null
  storePhone?: string | null
  storeWhatsapp?: string | null
  storeEmail?: string | null
  storeAddress?: string | null
  storeCity?: string | null
  storeLogo?: string | null
  currency: string
  customerName?: string | null
  customerDocType?: string | null
  customerDoc?: string | null
  customerDv?: string | null
  customerPersonType?: string | null
  customerEmail?: string | null
  customerPhone?: string | null
  customerAddress?: string | null
  customerMunicipality?: string | null
  issueDate: string
  validUntil?: string | null
  notes?: string | null
  terms?: string | null
  pricesIncludeTax: boolean
  subtotal: number
  discountTotal: number
  taxTotal: number
  total: number
  lines: { description: string; presentationName?: string | null; quantity: number; unitPrice: number; discountPct: number; taxRate: number; lineTotal: number }[]
  decidedBy?: string | null
  decidedAt?: string | null
  clientCheck?: string | null
  clientCheckAt?: string | null
}

export const QUOTE_STATUS: Record<string, { label: string; tone: Tone }> = {
  BORRADOR: { label: 'Borrador', tone: 'neutral' },
  ENVIADA: { label: 'Enviada', tone: 'primary' },
  ACEPTADA: { label: 'Aceptada', tone: 'success' },
  RECHAZADA: { label: 'Rechazada', tone: 'danger' },
  VENCIDA: { label: 'Vencida', tone: 'warning' },
  CONVERTIDA: { label: 'Pasó a prefactura', tone: 'wallet' },
  COBRADA: { label: 'Cobrada', tone: 'success' },
  APROBADA: { label: 'Aprobada', tone: 'success' },
  FACTURADA: { label: 'Facturada', tone: 'success' },
  ANULADA: { label: 'Anulada', tone: 'danger' },
}

export const DOC_TYPES: [string, string][] = [
  ['CC', 'Cédula de ciudadanía'],
  ['NIT', 'NIT'],
  ['CE', 'Cédula de extranjería'],
  ['PP', 'Pasaporte'],
  ['TI', 'Tarjeta de identidad'],
]

/** Texto por defecto de condiciones para la cotización (la tienda lo puede cambiar en cada una). */
export function defaultTerms(kind: QuoteKind, pricesIncludeTax: boolean): string {
  if (kind === 'PREFACTURA') {
    return 'Documento de revisión previo a la factura. No es factura de venta ni documento equivalente y no sirve como soporte de costos o gastos. Por favor revisa tus datos y avísanos si algo no está bien.'
  }
  return `Oferta comercial válida hasta la fecha indicada. Precios en pesos colombianos ${pricesIncludeTax ? 'con IVA incluido' : 'más IVA'}. Sujeta a disponibilidad de inventario al momento de la aceptación. Esta cotización no es una factura.`
}

const MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic']

/** "2026-10-21" → "21 oct 2026" (sin pasar por Date: evita correrse un día por la zona horaria). */
export function dayLabel(d?: string | null): string {
  if (!d) return ''
  const [y, m, day] = d.split('-').map(Number)
  return `${day} ${MONTHS[m - 1]} ${y}`
}

/** Días que faltan para una fecha "AAAA-MM-DD" (negativo si ya pasó). */
export function daysUntil(d?: string | null): number | null {
  if (!d) return null
  const [y, m, day] = d.split('-').map(Number)
  const now = new Date()
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((Date.UTC(y, m - 1, day) - today) / 86_400_000)
}
