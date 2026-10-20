import type { Num } from './posTypes'

// ---------- Pagos digitales ----------

export interface PaymentRow {
  id: number
  saleId: number
  /** null cuando es un abono de fiado (creditPayment). */
  saleNumber: number
  creditPayment?: boolean
  method: string
  amount: Num
  reference?: string
  status: string
  duplicateRef: boolean
  userName?: string
  createdAt: string
  minutesAgo: number
  verifiedBy?: string
  verifiedAt?: string
  verificationNote?: string
  reconciledAt?: string
  customerName?: string
}

export interface PaymentEvent {
  id: number
  paymentId: number
  type: string
  detail?: string
  userName?: string
  createdAt: string
}

export interface PaymentAccount {
  id: number
  method: string
  label: string
  holder?: string
  accountRef?: string
  qrImage?: string
  active: boolean
}

export interface PaymentIntegration {
  id: number
  provider: string
  publicKey: string
  secret: string
  active: boolean
  lastEventAt?: string
  webhookPath: string
}

export interface ReconRow {
  id: number
  rowDate?: string
  amount: Num
  reference?: string
  description?: string
  status: 'CONCILIADO' | 'PROBABLE' | 'INCONSISTENTE' | 'SIN_REGISTRO' | 'DUPLICADO'
  paymentId?: number
  saleNumber?: number
  note?: string
}

export interface Reconciliation {
  id: number
  method: string
  fileName?: string
  dateFrom?: string
  dateTo?: string
  totalRows: number
  matched: number
  inconsistent: number
  unmatched: number
  createdBy: string
  createdAt: string
  rows?: ReconRow[]
  stillPending?: PaymentRow[]
}

export const RECON_STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' | 'primary' }> = {
  CONCILIADO: { label: 'Conciliado', tone: 'success' },
  PROBABLE: { label: 'Probable', tone: 'primary' },
  INCONSISTENTE: { label: 'Valor distinto', tone: 'danger' },
  SIN_REGISTRO: { label: 'Sin registro', tone: 'warning' },
  DUPLICADO: { label: 'Repetido', tone: 'danger' },
}

// ---------- Fiado ----------

export interface StatementLine {
  id: number
  type: 'CARGO' | 'ABONO' | 'AJUSTE' | 'RECHAZO'
  amount: Num
  balanceAfter: Num
  saleId?: number
  method?: string
  reference?: string
  dueDate?: string
  openAmount?: Num
  note?: string
  userName?: string
  authorizedBy?: string
  createdAt: string
}

export type CreditStatus = 'AL_DIA' | 'POR_VENCER' | 'VENCIDO' | 'BLOQUEADO'

export interface LedgerRow {
  customerId: number
  name: string
  opening: number
  charged: number
  paid: number
  rejected: number
  adjusted: number
  closing: number
}

/** Cuadre de fiados: saldo al inicio + fiado − abonos + abonos rechazados − anulaciones/devoluciones = saldo final. */
export interface CreditLedger {
  from: string
  to: string
  opening: number
  charged: number
  paid: number
  paidCash: number
  paidDigital: number
  paidPendingVerification: number
  rejected: number
  voided: number
  returned: number
  closing: number
  currentReceivables: number
  /** Solo si el período llega a hoy: el saldo final es igual a lo que deben hoy los clientes. */
  balanced: boolean | null
  customers: LedgerRow[]
}

export interface CustomerCredit {
  customerId: number
  name: string
  docNumber?: string
  phone?: string
  balance: Num
  overdue: Num
  limit: Num
  creditDays: number
  blocked: boolean
  oldestDue?: string
  daysOverdue: number
  status: CreditStatus
  lastPaymentAt?: string
  statement?: StatementLine[]
}

export const CREDIT_STATUS: Record<CreditStatus, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  AL_DIA: { label: 'Al día', tone: 'success' },
  POR_VENCER: { label: 'Por vencer', tone: 'warning' },
  VENCIDO: { label: 'Vencido', tone: 'danger' },
  BLOQUEADO: { label: 'Bloqueado', tone: 'neutral' },
}

// ---------- Finanzas ----------

export interface FinanceCategory {
  id: number
  kind: 'GASTO' | 'INGRESO'
  name: string
  fixed: boolean
  active: boolean
}

export interface FinanceEntry {
  id: number
  kind: 'GASTO' | 'INGRESO'
  categoryId?: number
  categoryName?: string
  description: string
  amount: Num
  entryDate: string
  method: string
  paidFrom: 'CAJA' | 'BANCO' | 'OTRO'
  reference?: string
  fromCashRegister: boolean
  createdBy?: string
  createdAt: string
  voided: boolean
  voidedBy?: string
}

export interface Payable {
  receiptId: number
  receiptNumber: number
  supplierId: number
  supplierName: string
  invoiceNumber?: string
  total: Num
  paid: Num
  balance: Num
  dueDate?: string
  daysToDue: number
  receivedAt: string
}

export interface SupplierBalance {
  supplierId: number
  supplierName: string
  balance: Num
  overdue: Num
  nextDue?: string
  invoices: number
}

export interface Payables {
  total: Num
  overdue: Num
  dueThisWeek: Num
  bySupplier: SupplierBalance[]
  invoices: Payable[]
}

export interface SupplierPayment {
  id: number
  supplierId: number
  supplierName: string
  amount: Num
  method: string
  paidFrom: string
  reference?: string
  note?: string
  paidOn: string
  userName: string
  appliedTo: string[]
}

export interface Amount {
  label: string
  amount: Num
}

export interface DayFlow {
  date: string
  sales: Num
  inflow: Num
  outflow: Num
}

export interface FinanceSummary {
  from: string
  to: string
  salesCount: number
  grossSales: Num
  refunds: Num
  netSales: Num
  discounts: Num
  taxes: Num
  revenueExTax: Num
  costOfSales: Num
  grossProfit: Num
  grossMarginPct: Num
  expenses: Num
  expensesByCategory: Amount[]
  netProfit: Num
  netMarginPct: Num
  otherIncome: Num
  incomeByCategory: Amount[]
  inflow: Num
  outflow: Num
  netCashFlow: Num
  inflowByMethod: Amount[]
  outflowByConcept: Amount[]
  creditSales: Num
  creditCollected: Num
  receivables: Num
  payables: Num
  purchases: Num
  supplierPaid: Num
  pendingVerification: Num
  purchaseBudget: Num
  days: DayFlow[]
}

export interface MonthlyReport {
  month: string
  current: FinanceSummary
  previous: FinanceSummary
  salesChangePct?: Num
  profitChangePct?: Num
}

export const FLOW_LABELS: Record<string, string> = {
  EFECTIVO: 'Efectivo',
  TRANSFERENCIA: 'Transferencias',
  NEQUI: 'Nequi',
  DAVIPLATA: 'Daviplata',
  BREB: 'Bre-B',
  TARJETA: 'Datáfono',
  OTRO: 'Otros medios',
  ABONOS_FIADO: 'Abonos de fiado',
  OTROS_INGRESOS: 'Otros ingresos',
  GASTOS: 'Gastos',
  PAGOS_PROVEEDORES: 'Pagos a proveedores',
  DEVOLUCIONES: 'Devoluciones',
}

export interface AlertItem {
  type: string
  severity: 'CRITICA' | 'ALTA' | 'MEDIA'
  title: string
  detail: string
  productId?: number
  referenceId?: number
  link: string
}
