export interface SupplierMetrics {
  orders: number
  receipts: number
  purchasedTotal: number
  balance: number
  avgLeadDays?: number
  effectiveLeadDays: number
  onTimePct?: number
  fillRatePct?: number
  productCount: number
  lastPurchaseAt?: string
}

export interface Supplier {
  id: number
  name: string
  legalName?: string
  taxId?: string
  contactName?: string
  phone?: string
  whatsapp?: string
  email?: string
  address?: string
  city?: string
  paymentTerms?: string
  creditDays: number
  orderDays?: string
  deliveryDays?: string
  leadTimeDays?: number
  notes?: string
  active: boolean
  metrics: SupplierMetrics
  /** Para el documento soporte (proveedores que no facturan). */
  docType: string
  dv?: string
  personType?: 'NATURAL' | 'JURIDICA'
  municipalityCode?: string
  invoicesElectronically: boolean
  supportDocumentMissing: string[]
}

/** Factura electrónica de un proveedor ya importada como recepción. */
export interface SupplierInvoiceDto {
  id: number
  supplierId: number
  supplierName: string
  invoiceNumber: string
  cufe?: string
  issueDate?: string
  dueDate?: string
  subtotal: number
  taxTotal: number
  total: number
  receiptId?: number
  receiptNumber?: number
  orderId?: number
  orderNumber?: number
  fileName?: string
  importedBy?: string
  createdAt: string
}

export interface InvoiceLineMatch {
  productId: number
  productName: string
  sku?: string
  packFactor: number
  matchedBy: 'MEMORIA' | 'CODIGO_BARRAS' | 'SKU'
}

export interface InvoicePreviewLine {
  index: number
  code?: string
  barcode?: string
  description?: string
  quantity: number
  unitCode?: string
  unitPrice: number
  lineTotal: number
  taxRate: number
  tax: number
  match?: InvoiceLineMatch
}

export interface InvoicePreview {
  fileName?: string
  number: string
  cufe?: string
  issueDate?: string
  dueDate?: string
  currency?: string
  supplierTaxId?: string
  supplierDv?: string
  supplierName?: string
  supplierEmail?: string
  supplierAddress?: string
  buyerTaxId?: string
  subtotal: number
  taxTotal: number
  total: number
  supplier?: { id: number; name: string; invoicesElectronically: boolean }
  openOrders: { id: number; number: number; status: string; expectedDate?: string; estimatedTotal?: number }[]
  lines: InvoicePreviewLine[]
  warnings: string[]
  costIncludesTax: boolean
}

export interface CatalogItem {
  id: number
  supplierId: number
  supplierName: string
  productId: number
  productName: string
  sku?: string
  packName: string
  packFactor: number
  costPerPack?: number
  unitCost?: number
  minPacks: number
  multiplePacks: number
  supplierSku?: string
  isPrimary: boolean
  productStock?: number
  updatedAt: string
}

export interface SupplierPrice {
  productId: number
  productName: string
  oldCostPerUnit?: number
  newCostPerUnit: number
  source: string
  userName?: string
  createdAt: string
}

export interface SupplierReturn {
  id: number
  productId: number
  productName: string
  quantity: number
  unitCost: number
  reason: string
  userName?: string
  createdAt: string
}

export interface OrderLine {
  id: number
  productId: number
  productName: string
  sku?: string
  packName: string
  packFactor: number
  packsOrdered: number
  unitsOrdered: number
  costPerPack?: number
  subtotal?: number
  unitsReceived: number
  unitsRejected: number
  unitsPending: number
  reason?: string
  currentStock?: number
}

export interface ReceiptSummary {
  id: number
  number: number
  invoiceNumber?: string
  total?: number
  onTime?: boolean
  receivedBy: string
  receivedAt: string
}

export type OrderStatus = 'BORRADOR' | 'POR_APROBAR' | 'APROBADA' | 'PARCIAL' | 'RECIBIDA' | 'CANCELADA'

export interface PurchaseOrder {
  id: number
  number: number
  supplierId: number
  supplierName: string
  status: OrderStatus
  source: string
  expectedDate?: string
  late: boolean
  notes?: string
  estimatedTotal?: number
  receivedTotal?: number
  createdBy: string
  createdAt: string
  approvedBy?: string
  approvedAt?: string
  sentAt?: string
  sentVia?: string
  receivedAt?: string
  cancelReason?: string
  lines: OrderLine[]
  receipts: ReceiptSummary[]
}

export interface SuggestionLine {
  productId: number
  productName: string
  sku: string
  priority: 'CRITICO' | 'ALTO' | 'NORMAL'
  stock: number
  onOrder: number
  dailyDemand: number
  coverageDays?: number
  reorderPoint: number
  targetStock: number
  neededUnits: number
  packName: string
  packFactor: number
  packs: number
  costPerPack: number
  estimatedCost: number
  reason: string
  alternatives: { supplierId: number; supplierName: string; unitCost: number; packName: string; packFactor: number }[]
}

export interface SupplierSuggestion {
  supplierId: number
  supplierName: string
  whatsapp?: string
  leadDays: number
  orderDays?: string
  nextOrderDate: string
  estimatedTotal: number
  critical: number
  lines: SuggestionLine[]
}

export interface Suggestions {
  suppliers: SupplierSuggestion[]
  withoutSupplier: SuggestionLine[]
  monthlyBudget?: number
  spentThisMonth?: number
  budgetLeft?: number
  suggestedTotal?: number
  criticalTotal?: number
}

export interface ProductInsight {
  productId: number
  name: string
  sku: string
  categoryName?: string
  unit: string
  stock: number
  stockMin: number
  stockMax: number
  safetyStock: number
  soldWindow: number
  soldLast7: number
  dailyVelocity: number
  trendPct?: number
  coverageDays?: number
  revenueWindow: number
  grossProfitWindow?: number
  abc: 'A' | 'B' | 'C'
  lastSaleAt?: string
  stockValue?: number
  immobilizedValue?: number
  onOrder: number
  flags: string[]
}

export interface Insights {
  summary: {
    activeProducts: number
    totalValue?: number
    immobilizedValue?: number
    outOfStock: number
    belowMin: number
    stockoutSoon: number
    noRotation: number
    excess: number
    expiringLots: number
    expiredLots: number
    abc: { a: number; b: number; c: number; aRevenuePct: number }
    analysisDays: number
  }
  products: ProductInsight[]
}

export interface Lot {
  id: number
  productId: number
  productName: string
  lotCode?: string
  expiryDate?: string
  remaining: number
  daysLeft: number
  value?: number
}

export interface AlertItem {
  type: string
  severity: 'CRITICA' | 'ALTA' | 'MEDIA'
  title: string
  detail: string
  productId?: number
  referenceId?: number
}

export interface CountLine {
  id: number
  productId: number
  productName: string
  sku?: string
  barcode?: string
  unit?: string
  theoretical?: number
  counted?: number
  difference?: number
  differenceValue?: number
  countedBy?: string
  countedAt?: string
  unusual: boolean
}

export interface InventoryCount {
  id: number
  number: number
  name: string
  status: 'ABIERTO' | 'CERRADO' | 'ANULADO'
  blind: boolean
  categoryId?: number
  categoryName?: string
  createdBy: string
  createdAt: string
  closedBy?: string
  closedAt?: string
  notes?: string
  totalLines: number
  countedLines: number
  withDifference: number
  unusualLines: number
  shrinkageValue?: number
  surplusValue?: number
  lines: CountLine[]
}

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: 'neutral' | 'warning' | 'primary' | 'wallet' | 'success' | 'danger' }> = {
  BORRADOR: { label: 'Borrador', tone: 'neutral' },
  POR_APROBAR: { label: 'Por aprobar', tone: 'warning' },
  APROBADA: { label: 'Aprobada', tone: 'primary' },
  PARCIAL: { label: 'Recibida parcial', tone: 'wallet' },
  RECIBIDA: { label: 'Recibida', tone: 'success' },
  CANCELADA: { label: 'Cancelada', tone: 'danger' },
}

export const WEEK_DAYS = ['LUN', 'MAR', 'MIE', 'JUE', 'VIE', 'SAB', 'DOM'] as const
