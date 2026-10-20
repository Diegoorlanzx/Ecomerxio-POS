import type { AlertItem } from './financeTypes'
import type { Num } from './posTypes'

export interface Kpis {
  sales: Num
  revenueEx: Num
  cost?: Num
  grossProfit?: Num
  marginPct?: Num
  transactions: number
  units: Num
  avgTicket: Num
  discounts: Num
  taxes: Num
  refunds: Num
  voidedCount: number
  voidedTotal: Num
}

export interface Point {
  key: string
  label: string
  sales: Num
  transactions: number
  profit?: Num
}

export interface Group {
  key: string
  label: string
  units?: Num
  sales: Num
  revenueEx?: Num
  profit?: Num
  marginPct?: Num
  transactions: number
  sharePct: Num
}

export interface ProductRow {
  productId: number
  name: string
  categoryName?: string
  brand?: string
  units: Num
  sales: Num
  revenueEx: Num
  cost?: Num
  profit?: Num
  marginPct?: Num
  profitPerUnit?: Num
  discount: Num
  transactions: number
  prevUnits: Num
  prevSales: Num
  changePct?: Num
}

export interface SalesReport {
  from: string
  to: string
  prevFrom: string
  prevTo: string
  current: Kpis
  previous: Kpis
  changes: Record<string, Num | null>
  days: Point[]
  hours: Point[]
  weekdays: Point[]
  months: Point[]
  products: ProductRow[]
  categories: Group[]
  brands: Group[]
  employees: Group[]
  shifts: Group[]
  methods: Group[]
  declining: ProductRow[]
  bestWeekday?: string
  bestHour?: number
  costs: boolean
}

export type Quadrant = 'ESTRELLA' | 'VOLUMEN_BAJO_MARGEN' | 'NICHO_RENTABLE' | 'REVISAR' | 'NORMAL'

export interface ProfitRow {
  productId: number
  name: string
  categoryName?: string
  brand?: string
  units: Num
  revenueEx: Num
  cost: Num
  grossProfit: Num
  marginPct: Num
  avgPrice?: Num
  avgCost?: Num
  profitPerUnit?: Num
  discount: Num
  marginWithoutDiscountPct: Num
  allocatedExpenses?: Num
  profitAfterCosts?: Num
  currentPrice?: Num
  currentCost?: Num
  currentMarginPct?: Num
  profitSharePct: Num
  quadrant: Quadrant
}

export interface Profitability {
  from: string
  to: string
  revenueEx: Num
  cost: Num
  grossProfit: Num
  marginPct: Num
  discounts: Num
  marginWithoutDiscountsPct: Num
  expenses: Num
  netProfit: Num
  netMarginPct: Num
  allocateExpenses: boolean
  lowMarginPct: Num
  highMarginPct: Num
  products: ProfitRow[]
  categories: Group[]
  quadrantCounts: Record<Quadrant, number>
}

export interface Evolution {
  productId: number
  name: string
  currentPrice?: Num
  currentCost: Num
  priceChanges: { at: string; field: string; presentation?: string; oldValue?: Num; newValue?: Num; reason?: string; userName?: string }[]
  purchaseCosts: { at: string; type: string; unitCost: Num; quantity: Num; reason?: string }[]
  months: { month: string; units: Num; avgPrice?: Num; avgCost?: Num; marginPct?: Num }[]
}

export interface PeriodStat {
  key: 'dia' | 'semana' | 'quincena' | 'mes'
  label: string
  from: string
  to: string
  sales: Num
  transactions: number
  avgTicket: Num
  grossProfit?: Num
  prevFrom: string
  prevTo: string
  prevSales: Num
  changePct?: Num
  /** Dinero que entró: cobrado (sin fiado) + abonos de fiado recibidos en el período. */
  moneyIn?: Num
  collected?: Num
  /** Vendido a fiado en el período: no entró dinero. */
  creditSales?: Num
  creditPayments?: Num
}

export interface StockItem {
  productId: number
  name: string
  stock: Num
  coverageDays?: Num
  flag: string
}

export interface Dashboard {
  today: string
  periods: PeriodStat[]
  monthFinance?: { grossProfit: Num; marginPct: Num; expenses: Num; netProfit: Num }
  cash: { shiftId: number; number: number; registerName: string; userName: string; expectedCash: Num; salesTotal: Num }[]
  cashTotal: Num
  methodsToday: Group[]
  todayByHour: Point[]
  topProducts: ProductRow[]
  outOfStock: StockItem[]
  runningOut: StockItem[]
  lowRotation: StockItem[]
  lowRotationCount: number
  pending: {
    toOrderProducts: number
    toOrderCritical: number
    toOrderTotal?: Num
    toReceiveOrders: number
    lateOrders: number
    toReceiveTotal?: Num
    paymentsToVerify: number
    paymentsToVerifyTotal: Num
  }
  alerts: AlertItem[]
  alertsTotal: number
  costs: boolean
}

export const QUADRANTS: Record<Quadrant, { label: string; text: string; tone: 'success' | 'warning' | 'wallet' | 'danger' | 'neutral'; icon: string }> = {
  ESTRELLA: { label: 'Estrella', text: 'Vende mucho y deja buen margen', tone: 'success', icon: 'star' },
  VOLUMEN_BAJO_MARGEN: { label: 'Vende mucho, deja poco', text: 'Revisa precio o costo: mueve volumen con margen bajo', tone: 'warning', icon: 'trending_down' },
  NICHO_RENTABLE: { label: 'Poco pero rentable', text: 'Vende poco y deja buen margen: dale visibilidad', tone: 'wallet', icon: 'diamond' },
  REVISAR: { label: 'Para revisar', text: 'Vende poco y deja poco', tone: 'danger', icon: 'report' },
  NORMAL: { label: 'Normal', text: 'Margen intermedio', tone: 'neutral', icon: 'remove' },
}
