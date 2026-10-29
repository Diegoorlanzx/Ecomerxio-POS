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
  /** Cupo de promociones que gasta esta venta (id de la promoción → veces). */
  promoUses: Map<number, number>
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

type PromoPick = { discount: number; uses: number }
const NONE: PromoPick = { discount: 0, uses: 0 }

/** Lo que le queda de cupo a una promociÃ³n (undefined = sin lÃ­mite). */
export function promoRemaining(p: Promotion): number | undefined {
  if (p.usageLimit == null) return undefined
  return Math.max(0, Number(p.usageLimit) - Number(p.usedCount ?? 0))
}

/** Descuento de una promociÃ³n de un solo producto, sin pasarse de `left` (undefined = sin lÃ­mite). */
function promoPick(p: Promotion, factor: number, unitPrice: number, qty: number, scale: number, left?: number): PromoPick {
  if (left !== undefined && left <= 0) return NONE
  const gross = unitPrice * qty
  const base = factor === 1
  if (p.type === 'PORCENTAJE') {
    if (!p.percent) return NONE
    const units = qty * factor
    if (p.minQty && units < p.minQty) return NONE
    if (left === undefined || left >= units) return { discount: round((gross * p.percent) / 100, scale), uses: units }
    // Solo le quedan algunas unidades: el descuento va sobre esas.
    return { discount: round((gross * p.percent * left) / (100 * units), scale), uses: left }
  }
  const cap = (groups: number) => (left === undefined ? groups : Math.min(groups, Math.floor(left)))
  if (p.type === 'PRECIO_POR_CANTIDAD') {
    if (!base || !p.bundleQty || p.bundlePrice == null || p.bundleQty <= 0) return NONE
    const groups = cap(Math.floor(qty / p.bundleQty))
    const saving = unitPrice * p.bundleQty - p.bundlePrice
    return saving <= 0 ? NONE : { discount: round(groups * saving, scale), uses: groups }
  }
  if (p.type === 'LLEVA_PAGA') {
    if (!base || !p.bundleQty || p.payQty == null || p.payQty >= p.bundleQty) return NONE
    const groups = cap(Math.floor(qty / p.bundleQty))
    return { discount: round(groups * (p.bundleQty - p.payQty) * unitPrice, scale), uses: groups }
  }
  return NONE
}

/**
 * Copia de PromotionEngine.apply del servidor: primero "compra X, lleva Y", luego en cada lÃ­nea libre la promociÃ³n que
 * mÃ¡s ahorra, sin pasarse del cupo de cada una. Devuelve la promociÃ³n de cada lÃ­nea y cuÃ¡nto cupo gasta la venta.
 */
export function applyPromotions(promos: Promotion[], lines: CartLine[], chains: number[][], today: string, scale: number) {
  const live = promos
    .filter((p) => p.active && (!p.startsOn || today >= p.startsOn) && (!p.endsOn || today <= p.endsOn))
    .sort((a, b) => a.id - b.id)
  const left = new Map<number, number>()
  for (const p of live) {
    const r = promoRemaining(p)
    if (r !== undefined) left.set(p.id, r)
  }
  const out: ({ promo: Promotion; discount: number } | null)[] = lines.map(() => null)
  const uses = new Map<number, number>()
  const use = (p: Promotion, n: number) => {
    uses.set(p.id, (uses.get(p.id) ?? 0) + n)
    if (left.has(p.id)) left.set(p.id, left.get(p.id)! - n)
  }
  const eligible = (l: CartLine) => l.unitPrice === l.listPrice
  const matches = (p: Promotion, l: CartLine, i: number) =>
    (p.productId && p.productId === l.productId) || (!p.productId && p.categoryId && chains[i]!.includes(p.categoryId))

  for (const p of live) {
    if (p.type !== 'COMPRA_LLEVA' || !p.rewardProductId || !p.bundleQty || !p.rewardQty || !p.percent) continue
    let bought = 0
    lines.forEach((l, i) => {
      if (eligible(l) && l.productId !== p.rewardProductId && matches(p, l, i)) bought += l.quantity * l.factor
    })
    let combos = Math.floor(bought / Number(p.bundleQty))
    if (left.has(p.id)) combos = Math.min(combos, Math.floor(left.get(p.id)!))
    const toGive = combos * Number(p.rewardQty)
    let given = 0
    lines.forEach((l, i) => {
      if (given >= toGive || out[i] || !eligible(l) || l.productId !== p.rewardProductId || l.factor !== 1) return
      const units = Math.min(l.quantity, toGive - given)
      const d = round((units * l.unitPrice * Number(p.percent)) / 100, scale)
      if (d <= 0) return
      out[i] = { promo: p, discount: d }
      given += units
    })
    if (given > 0) use(p, Math.ceil(given / Number(p.rewardQty)))
  }

  lines.forEach((l, i) => {
    if (out[i] || !eligible(l)) return
    let best: { promo: Promotion; pick: PromoPick } | null = null
    for (const p of live) {
      if (p.type === 'COMPRA_LLEVA' || !matches(p, l, i)) continue
      const k = promoPick(p, l.factor, l.unitPrice, l.quantity, scale, left.get(p.id))
      if (k.discount > 0 && (!best || k.discount > best.pick.discount)) best = { promo: p, pick: k }
    }
    if (best) {
      out[i] = { promo: best.promo, discount: best.pick.discount }
      use(best.promo, best.pick.uses)
    }
  })
  return { lines: out, uses }
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
  const chains = lines.map((l) => {
    const chain: number[] = []
    let c = l.categoryId
    while (c !== undefined && chain.length < 5) {
      chain.push(c)
      c = categoryParents.get(c)
    }
    return chain
  })
  const applied = applyPromotions(promos, lines, chains, today, scale)
  lines.forEach((l, i) => {
    const g = round(l.unitPrice * l.quantity, scale)
    const best = applied.lines[i]
    const promo = Math.min(best?.discount ?? 0, g)
    const rest = g - promo
    const manual = round((rest * (l.discountPct || 0)) / 100, scale)
    gross.push(g)
    lineDisc.push(promo + manual)
    after.push(g - promo - manual)
    promoInfo.push({ name: best?.promo.name, discount: promo })
    sumAfter += g - promo - manual
  })
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
  return { lines: out, subtotal, discountTotal, taxTotal: round(taxTotal, 2), total: round(total, 2), saleDiscount: saleDisc, promoUses: applied.uses }
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
