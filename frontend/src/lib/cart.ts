/**
 * Carrito del POS y cálculo de totales en pantalla. Es una copia fiel del calculador del servidor
 * (SaleCalculator + PromotionEngine) para mostrar totales al instante y emitir el tiquete
 * provisional sin internet. El servidor siempre recalcula al cobrar.
 */
import type { PosPresentation, PosProduct, Promotion, StoreSettings } from './posTypes'

export interface CartLine {
  key: string
  productId: number
  presentationId: number
  name: string
  presentationName: string
  factor: number
  quantity: number
  listPrice: number
  unitPrice: number
  minPrice: number
  discountPct: number
  taxRate: number
  fixedTax: number
  categoryId?: number
  unit: string
  /** Productos con serial: un serial por unidad base vendida. */
  trackSerials?: boolean
  serials?: string[]
  /** Restaurante: modificadores y nota del plato, y si falta mandarlo a cocina. */
  modifiers?: string
  kitchenNote?: string
  pendingKitchen?: boolean
}

export interface LineCalc {
  gross: number
  promoName?: string
  promoDiscount: number
  discount: number
  tax: number
  lineTotal: number
}

export interface CartTotals {
  lines: LineCalc[]
  subtotal: number
  discountTotal: number
  taxTotal: number
  total: number
  saleDiscount: number
}

const round = (v: number, scale: number) => {
  const f = 10 ** scale
  return Math.round((v + Number.EPSILON) * f) / f
}

export function lineFrom(p: PosProduct, pr: PosPresentation, quantity = 1): CartLine {
  return {
    key: `${pr.id}-${Math.random().toString(36).slice(2, 7)}`,
    productId: p.id,
    presentationId: pr.id,
    name: p.name,
    presentationName: pr.name,
    factor: Number(pr.factor),
    quantity,
    listPrice: Number(pr.price),
    unitPrice: Number(pr.price),
    minPrice: Number(pr.minPrice),
    discountPct: 0,
    taxRate: Number(p.taxRate),
    fixedTax: Number(p.fixedTax),
    categoryId: p.categoryId,
    unit: p.unit,
    trackSerials: p.trackSerials,
    serials: p.trackSerials ? [] : undefined,
  }
}

function promoDiscount(p: Promotion, factor: number, unitPrice: number, qty: number, scale: number): number {
  const gross = unitPrice * qty
  const base = factor === 1
  if (p.type === 'PORCENTAJE') {
    if (!p.percent) return 0
    if (p.minQty && qty * factor < p.minQty) return 0
    return round((gross * p.percent) / 100, scale)
  }
  if (p.type === 'PRECIO_POR_CANTIDAD') {
    if (!base || !p.bundleQty || p.bundlePrice === undefined || p.bundleQty <= 0) return 0
    const groups = Math.floor(qty / p.bundleQty)
    const saving = unitPrice * p.bundleQty - p.bundlePrice
    return saving <= 0 ? 0 : round(groups * saving, scale)
  }
  if (!base || !p.bundleQty || p.payQty === undefined || p.payQty >= p.bundleQty) return 0
  const groups = Math.floor(qty / p.bundleQty)
  return round(groups * (p.bundleQty - p.payQty) * unitPrice, scale)
}

export function bestPromo(promos: Promotion[], line: CartLine, categoryChain: number[], today: string, scale: number) {
  let best: { promo: Promotion; discount: number } | null = null
  if (line.unitPrice !== line.listPrice) return null
  for (const p of promos) {
    if (!p.active) continue
    if (p.startsOn && today < p.startsOn) continue
    if (p.endsOn && today > p.endsOn) continue
    const matches = (p.productId && p.productId === line.productId) || (!p.productId && p.categoryId && categoryChain.includes(p.categoryId))
    if (!matches) continue
    const d = promoDiscount(p, line.factor, line.unitPrice, line.quantity, scale)
    if (d > 0 && (!best || d > best.discount)) best = { promo: p, discount: d }
  }
  return best
}

export function calculate(
  lines: CartLine[],
  settings: Pick<StoreSettings, 'pricesIncludeTax'>,
  promos: Promotion[],
  categoryParents: Map<number, number | undefined>,
  saleDiscountPct: number,
  saleDiscountAmount: number,
  scale: number,
): CartTotals {
  const today = new Date().toLocaleDateString('en-CA')
  const n = lines.length
  const gross: number[] = []
  const lineDisc: number[] = []
  const after: number[] = []
  const promoInfo: { name?: string; discount: number }[] = []
  let sumAfter = 0
  for (const l of lines) {
    const chain: number[] = []
    let c = l.categoryId
    while (c !== undefined && chain.length < 5) {
      chain.push(c)
      c = categoryParents.get(c)
    }
    const g = round(l.unitPrice * l.quantity, scale)
    const best = bestPromo(promos, l, chain, today, scale)
    const promo = Math.min(best?.discount ?? 0, g)
    const rest = g - promo
    const manual = round((rest * (l.discountPct || 0)) / 100, scale)
    gross.push(g)
    lineDisc.push(promo + manual)
    after.push(g - promo - manual)
    promoInfo.push({ name: best?.promo.name, discount: promo })
    sumAfter += g - promo - manual
  }
  let saleDisc = 0
  if (saleDiscountPct > 0) saleDisc = round((sumAfter * saleDiscountPct) / 100, scale)
  else if (saleDiscountAmount > 0) saleDisc = round(saleDiscountAmount, scale)
  saleDisc = Math.min(saleDisc, sumAfter)
  const share = new Array(n).fill(0)
  if (saleDisc > 0 && sumAfter > 0) {
    let assigned = 0
    let biggest = 0
    const f = 10 ** scale
    for (let i = 0; i < n; i++) {
      share[i] = Math.floor(((saleDisc * after[i]!) / sumAfter) * f) / f
      assigned += share[i]
      if (after[i]! > after[biggest]!) biggest = i
    }
    share[biggest] += round(saleDisc - assigned, scale)
  }
  const out: LineCalc[] = []
  let subtotal = 0,
    discountTotal = 0,
    taxTotal = 0,
    total = 0
  for (let i = 0; i < n; i++) {
    const l = lines[i]!
    const discount = lineDisc[i]! + share[i]
    const net = gross[i]! - discount
    const fixed = round(l.fixedTax * (l.factor || 1) * l.quantity, 2)
    let tax: number, lineTotal: number
    if (settings.pricesIncludeTax) {
      const taxable = Math.max(0, net - fixed)
      tax = round(taxable - round(taxable / (1 + l.taxRate / 100), 2) + Math.min(fixed, net), 2)
      lineTotal = net
    } else {
      tax = round(round((net * l.taxRate) / 100, scale) + fixed, scale)
      lineTotal = net + tax
    }
    out.push({ gross: gross[i]!, promoName: promoInfo[i]!.name, promoDiscount: promoInfo[i]!.discount, discount, tax, lineTotal })
    subtotal += gross[i]!
    discountTotal += discount
    taxTotal += tax
    total += lineTotal
  }
  return { lines: out, subtotal, discountTotal, taxTotal: round(taxTotal, 2), total: round(total, 2), saleDiscount: saleDisc }
}

export function scaleFor(currency: string) {
  return ['COP', 'CLP', 'PYG', 'ARS'].includes(currency) ? 0 : 2
}

/** Siguientes valores "redondos" para pagar en efectivo: 23.400 → 24.000, 25.000, 30.000, 50.000. */
export function cashSuggestions(total: number): number[] {
  const out = new Set<number>()
  for (const step of [1000, 5000, 10000, 20000, 50000, 100000]) {
    const v = Math.ceil(total / step) * step
    if (v > total) out.add(v)
  }
  return [...out].sort((a, b) => a - b).slice(0, 4)
}
