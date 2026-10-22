import type { Receipt } from './posTypes'

/** Fase 16C: qué muestra el recibo, por tipo de comprobante. El servidor valida y aplica el candado de la factura. */
export type ReceiptProfileName = 'INTERNO' | 'FACTURA' | 'PRECUENTA' | 'FIADO'
export type ReceiptPreset = 'MINIMO' | 'SIMPLE' | 'DETALLADO' | 'PERSONALIZADO'

export const FLAG_GROUPS = [
  {
    title: 'Encabezado',
    flags: [
      ['logo', 'Logo'],
      ['storeName', 'Nombre del negocio'],
      ['legalData', 'Razón social, NIT y régimen'],
      ['address', 'Dirección'],
      ['phone', 'Teléfono'],
      ['header', 'Texto del encabezado'],
    ],
  },
  {
    title: 'Venta',
    flags: [
      ['saleNumber', 'Número de venta'],
      ['dateTime', 'Fecha y hora'],
      ['cashier', 'Quién atendió'],
      ['customer', 'Cliente'],
    ],
  },
  {
    title: 'Productos',
    flags: [
      ['productNames', 'Nombre de cada producto'],
      ['quantities', 'Cantidades'],
      ['unitPrice', 'Precio unitario'],
      ['lineDiscount', 'Descuento por producto'],
      ['lineTotal', 'Total por producto'],
      ['groupAsVarios', 'Agrupar todo como "Varios"'],
    ],
  },
  {
    title: 'Totales',
    flags: [
      ['subtotal', 'Subtotal'],
      ['discounts', 'Descuentos'],
      ['taxes', 'Impuestos'],
      ['savings', '"Usted ahorró"'],
    ],
  },
  {
    title: 'Pago',
    flags: [
      ['payments', 'Medios de pago'],
      ['change', 'Cambio'],
      ['creditBalance', 'Saldo del fiado'],
      ['signature', 'Línea para la firma del cliente'],
    ],
  },
  {
    title: 'Pie',
    flags: [
      ['footer', 'Texto del pie de página'],
      ['returnPolicy', 'Política de cambios y devoluciones'],
      ['onlineQr', 'QR de la tienda en línea'],
    ],
  },
] as const

export type ReceiptFlag = (typeof FLAG_GROUPS)[number]['flags'][number][0]
export type ReceiptProfile = { preset: ReceiptPreset } & Record<ReceiptFlag, boolean>

export interface ReceiptConfig {
  printMode: 'SIEMPRE' | 'PREGUNTAR' | 'NUNCA'
  copies: number
  fontSize: 'S' | 'M' | 'L'
  returnPolicy: string | null
  profiles: Record<ReceiptProfileName, ReceiptProfile>
}

export const PROFILE_LABELS: Record<ReceiptProfileName, { label: string; hint: string }> = {
  INTERNO: { label: 'Comprobante interno', hint: 'Ventas sin factura electrónica. Siempre dice "no es factura".' },
  FACTURA: { label: 'Factura electrónica', hint: 'Lo que exige la DIAN va con candado: no se puede quitar.' },
  PRECUENTA: { label: 'Precuenta', hint: 'Cuenta abierta o mesa antes de cobrar.' },
  FIADO: { label: 'Venta fiada', hint: 'Ventas a crédito sin factura: lleva saldo y firma.' },
}

/** Lo que la factura impresa no puede quitar (igual que ReceiptConfig.LOCKED_FOR_INVOICE en el servidor). */
export const LOCKED_FOR_INVOICE: ReceiptFlag[] = ['legalData', 'saleNumber', 'dateTime', 'customer', 'productNames', 'quantities', 'unitPrice', 'lineTotal', 'taxes']

const ALL_FLAGS = FLAG_GROUPS.flatMap((g) => g.flags.map((f) => f[0])) as ReceiptFlag[]

function flags(on: ReceiptFlag[]): Record<ReceiptFlag, boolean> {
  return Object.fromEntries(ALL_FLAGS.map((f) => [f, on.includes(f)])) as Record<ReceiptFlag, boolean>
}

const BASE: ReceiptFlag[] = ['logo', 'storeName', 'legalData', 'address', 'phone', 'header', 'saleNumber', 'dateTime', 'customer', 'footer', 'onlineQr']

export const PRESETS: Record<Exclude<ReceiptPreset, 'PERSONALIZADO'>, { label: string; hint: string; flags: ReceiptFlag[] }> = {
  MINIMO: { label: 'Mínimo', hint: 'Solo el total', flags: ['storeName', 'saleNumber', 'dateTime', 'payments', 'change'] },
  SIMPLE: {
    label: 'Simple',
    hint: 'Nombre de los productos y total',
    flags: [...BASE, 'productNames', 'lineTotal', 'payments', 'change', 'creditBalance'],
  },
  DETALLADO: {
    label: 'Detallado',
    hint: 'Cantidades, precios, descuentos e impuestos',
    flags: [...BASE, 'cashier', 'productNames', 'quantities', 'unitPrice', 'lineDiscount', 'lineTotal', 'subtotal', 'discounts', 'taxes', 'savings', 'payments', 'change', 'creditBalance', 'returnPolicy'],
  },
}

export function presetProfile(name: ReceiptProfileName, preset: Exclude<ReceiptPreset, 'PERSONALIZADO'>): ReceiptProfile {
  const on = [...PRESETS[preset].flags]
  if (name === 'FIADO') on.push('customer', 'creditBalance', 'signature')
  return lock(name, { preset, ...flags(on) })
}

export function lock(name: ReceiptProfileName, p: ReceiptProfile): ReceiptProfile {
  if (name !== 'FACTURA') return p
  const out = { ...p, groupAsVarios: false }
  LOCKED_FOR_INVOICE.forEach((f) => (out[f] = true))
  return out
}

/** Valores por defecto: equivalen al recibo de siempre (detallado). */
export function defaultReceiptConfig(showTax = true): ReceiptConfig {
  const det = (n: ReceiptProfileName) => {
    const p = presetProfile(n, 'DETALLADO')
    return n === 'FACTURA' ? p : { ...p, taxes: showTax }
  }
  return {
    printMode: 'PREGUNTAR',
    copies: 1,
    fontSize: 'M',
    returnPolicy: null,
    profiles: { INTERNO: det('INTERNO'), FACTURA: det('FACTURA'), PRECUENTA: det('PRECUENTA'), FIADO: det('FIADO') },
  }
}

/** La configuración guardada, completada con los valores por defecto. */
export function parseReceiptConfig(raw: unknown, showTax = true): ReceiptConfig {
  const base = defaultReceiptConfig(showTax)
  let c: Partial<ReceiptConfig> | null = null
  try {
    c = typeof raw === 'string' ? JSON.parse(raw) : (raw as Partial<ReceiptConfig> | null)
  } catch {
    c = null
  }
  if (!c || typeof c !== 'object') return base
  const profiles = { ...base.profiles }
  for (const n of Object.keys(profiles) as ReceiptProfileName[]) {
    const p = c.profiles?.[n]
    if (p) profiles[n] = lock(n, { ...profiles[n], ...p })
  }
  return {
    printMode: c.printMode ?? base.printMode,
    copies: c.copies ?? base.copies,
    fontSize: c.fontSize ?? base.fontSize,
    returnPolicy: c.returnPolicy ?? null,
    profiles,
  }
}

/** Qué perfil corresponde a este comprobante. */
export function profileFor(r: Receipt): ReceiptProfileName {
  const s = r.sale
  if (s.status === 'ABIERTA') return 'PRECUENTA'
  if (s.electronicDocument) return 'FACTURA'
  if (s.payments.some((p) => p.method === 'FIADO')) return 'FIADO'
  return 'INTERNO'
}
