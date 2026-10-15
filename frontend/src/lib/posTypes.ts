export type Num = number

export interface Category {
  id: number
  parentId?: number
  name: string
  icon?: string
  sortOrder: number
  active: boolean
  productCount: number
}

export interface PresentationDto {
  id?: number
  name: string
  factor: Num
  barcode?: string
  price: Num
  minPrice: Num
  isBase: boolean
  active?: boolean
  marginPct?: Num
}

export type StockStatus = 'AGOTADO' | 'BAJO' | 'NORMAL' | 'EXCESO'

export interface ProductDto {
  id: number
  sku: string
  name: string
  description?: string
  brand?: string
  photo?: string
  categoryId?: number
  categoryName?: string
  unit: string
  trackStock: boolean
  stock: Num
  stockStatus: StockStatus
  cost?: Num
  lastCost?: Num
  stockMin: Num
  stockMax: Num
  safetyStock: Num
  taxName: string
  taxRate: Num
  fixedTax: Num
  status: 'ACTIVE' | 'DISCONTINUED'
  favorite: boolean
  presentations: PresentationDto[]
  stockValue?: Num
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  pages: number
}

export interface PosPresentation {
  id: number
  name: string
  factor: Num
  barcode?: string
  price: Num
  minPrice: Num
  isBase: boolean
}

export interface PosProduct {
  id: number
  sku: string
  name: string
  brand?: string
  photo?: string
  categoryId?: number
  unit: string
  trackStock: boolean
  stock: Num
  stockStatus: StockStatus
  taxName: string
  taxRate: Num
  fixedTax: Num
  favorite: boolean
  presentations: PosPresentation[]
}

export interface Promotion {
  id: number
  name: string
  type: 'PORCENTAJE' | 'PRECIO_POR_CANTIDAD' | 'LLEVA_PAGA'
  productId?: number
  productName?: string
  categoryId?: number
  categoryName?: string
  percent?: Num
  minQty?: Num
  bundleQty?: Num
  bundlePrice?: Num
  payQty?: Num
  startsOn?: string
  endsOn?: string
  active: boolean
}

export interface StoreSettings {
  tenantId: number
  pricesIncludeTax: boolean
  maxDiscountPct: Num
  allowNegativeStock: boolean
  requireOpenShift: boolean
  openAccountsLimit: number
  costMethod: 'PROMEDIO' | 'ULTIMO'
  paymentMethods: string
  receiptHeader?: string
  receiptFooter?: string
  receiptWidthMm: number
  receiptShowTax: boolean
  defaultTaxName: string
  defaultTaxRate: Num
  cashDenominations: string
}

export interface SaleLineDto {
  id: number
  productId: number
  presentationId: number
  productName: string
  presentationName: string
  factor: Num
  quantity: Num
  listPrice: Num
  unitPrice: Num
  priceAuthorizedBy?: string
  discountPct: Num
  promoName?: string
  gross: Num
  discount: Num
  taxName?: string
  taxRate: Num
  tax: Num
  lineTotal: Num
  unitCost?: Num
  returnedQty: Num
  addedBy?: string
}

export interface PaymentDto {
  id: number
  method: string
  amount: Num
  received?: Num
  changeGiven: Num
  reference?: string
  status: string
  userName?: string
  createdAt: string
}

export interface ReturnDto {
  id: number
  number: number
  reason: string
  refundMethod: string
  refundAmount: Num
  userName?: string
  authorizedBy?: string
  createdAt: string
  lines: { saleLineId: number; productName: string; quantity: Num; amount: Num; restock: boolean; condition: string }[]
}

export interface SaleDto {
  id: number
  number?: number
  status: 'ABIERTA' | 'EN_ESPERA' | 'PAGADA' | 'ANULADA'
  accountLabel?: string
  shiftId?: number
  registerId?: number
  userId: number
  userName: string
  customerId?: number
  customerName?: string
  customerDoc?: string
  subtotal: Num
  discountTotal: Num
  taxTotal: Num
  total: Num
  costTotal?: Num
  paidTotal: Num
  changeAmount: Num
  refundedTotal: Num
  saleDiscountPct?: Num
  saleDiscountAmount?: Num
  discountAuthorizedBy?: string
  notes?: string
  clientRef?: string
  offline: boolean
  reviewFlags: string[]
  reviewedAt?: string
  createdAt: string
  completedAt?: string
  voidedAt?: string
  voidedBy?: string
  voidReason?: string
  voidAuthorizedBy?: string
  lines: SaleLineDto[]
  payments: PaymentDto[]
  returns: ReturnDto[]
}

export interface SaleSummary {
  id: number
  number: number
  status: string
  userName: string
  customerName?: string
  total: Num
  refundedTotal: Num
  methods: string[]
  itemCount: number
  offline: boolean
  reviewFlags: string[]
  reviewed: boolean
  completedAt: string
}

export interface AccountSummary {
  id: number
  label: string
  userName: string
  customerName?: string
  total: Num
  itemCount: number
  createdAt: string
  version: number
}

export interface Receipt {
  sale: SaleDto
  storeName: string
  legalName?: string
  taxId?: string
  taxRegime?: string
  address?: string
  city?: string
  phone?: string
  logo?: string
  header?: string
  footer?: string
  widthMm: number
  showTax: boolean
  currency: string
}

export interface RegisterDto {
  id: number
  name: string
  active: boolean
  openShiftId?: number
  openShiftUser?: string
}

export interface CashMovementDto {
  id: number
  type: string
  amount: Num
  reason: string
  category?: string
  userName: string
  authorizedBy?: string
  createdAt: string
}

export interface ShiftSummary {
  id: number
  number: number
  registerId: number
  registerName: string
  userId: number
  userName: string
  status: 'OPEN' | 'CLOSED'
  openingCash: Num
  openedAt: string
  closedAt?: string
  closedByName?: string
  salesCount: number
  salesTotal: Num
  voidedCount: number
  voidedTotal: Num
  byMethod: { method: string; amount: Num; count: number }[]
  cashSales: Num
  cashIn: Num
  cashOut: Num
  expenses: Num
  adjustments: Num
  cashRefunds: Num
  refundsTotal: Num
  expectedCash: Num
  countedCash?: Num
  difference?: Num
  countDetail?: string
  closeNotes?: string
  differenceAuthorizedBy?: string
  discountTotal: Num
  movements: CashMovementDto[]
}

export interface Customer {
  id: number
  name: string
  docType?: string
  docNumber?: string
  phone?: string
  email?: string
  address?: string
  notes?: string
}

export interface MovementDto {
  id: number
  productId: number
  productName: string
  type: string
  quantity: Num
  stockAfter: Num
  unitCost?: Num
  referenceType?: string
  referenceId?: number
  reason?: string
  userName?: string
  createdAt: string
}

export interface PriceHistoryDto {
  id: number
  presentationId?: number
  presentationName?: string
  field: string
  oldValue?: Num
  newValue?: Num
  reason?: string
  userName?: string
  createdAt: string
}

export const METHOD_LABELS: Record<string, string> = {
  EFECTIVO: 'Efectivo',
  TRANSFERENCIA: 'Transferencia',
  NEQUI: 'Nequi',
  DAVIPLATA: 'Daviplata',
  BREB: 'Bre-B / llave',
  TARJETA: 'Datáfono / tarjeta',
  OTRO: 'Otro',
}

export const METHOD_ICONS: Record<string, string> = {
  EFECTIVO: 'payments',
  TRANSFERENCIA: 'account_balance',
  NEQUI: 'smartphone',
  DAVIPLATA: 'smartphone',
  BREB: 'key',
  TARJETA: 'credit_card',
  OTRO: 'more_horiz',
}

export const DIGITAL_WALLETS = new Set(['NEQUI', 'DAVIPLATA', 'BREB'])

export const UNIT_LABELS: Record<string, string> = {
  UNIDAD: 'Unidad',
  KILOGRAMO: 'Kilogramo',
  GRAMO: 'Gramo',
  LITRO: 'Litro',
  MILILITRO: 'Mililitro',
  METRO: 'Metro',
  LIBRA: 'Libra',
}
