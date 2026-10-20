import type { CashMovementDto, ShiftSummary } from '../posTypes'
import { kvGet, kvSet } from './idb'

/**
 * Copia local del turno abierto del cajero. Se reemplaza con la del servidor cada vez que hay internet y, sin
 * internet, se actualiza con lo que pasa en la caja (ventas, gastos, retiros) con la misma fórmula del servidor:
 * esperado = base + ventas en efectivo + ingresos − retiros − gastos ± ajustes − devoluciones en efectivo.
 */
const key = (tenantId: number, userId: number) => `shift-${tenantId}-${userId}`

export const getLocalShift = (tenantId: number, userId: number) => kvGet<ShiftSummary | null>(key(tenantId, userId)).then((s) => s ?? null)
export const saveLocalShift = (tenantId: number, userId: number, shift: ShiftSummary | null) => kvSet(key(tenantId, userId), shift)

const n = (v: unknown) => Number(v ?? 0)

function recompute(s: ShiftSummary): ShiftSummary {
  const expected = n(s.openingCash) + n(s.cashSales) + n(s.cashIn) - n(s.cashOut) - n(s.expenses) + n(s.adjustments) - n(s.cashRefunds)
  return { ...s, expectedCash: Math.round(expected * 100) / 100 }
}

/** Turno abierto sin internet: vive con un id temporal hasta que el servidor le dé número. */
export function openLocal(p: {
  tempId: string
  registerId: number
  registerName: string
  openingCash: number
  userId: number
  userName: string
}): ShiftSummary {
  return recompute({
    id: p.tempId as unknown as number,
    number: 0,
    registerId: p.registerId,
    registerName: p.registerName,
    userId: p.userId,
    userName: p.userName,
    status: 'OPEN',
    openingCash: p.openingCash,
    openedAt: new Date().toISOString(),
    salesCount: 0,
    salesTotal: 0,
    voidedCount: 0,
    voidedTotal: 0,
    byMethod: [],
    cashSales: 0,
    cashIn: 0,
    cashOut: 0,
    expenses: 0,
    adjustments: 0,
    cashRefunds: 0,
    refundsTotal: 0,
    expectedCash: 0,
    discountTotal: 0,
    movements: [],
  })
}

/** Suma una venta hecha sin internet al turno local. */
export function withSale(s: ShiftSummary, sale: { total: number; discountTotal: number; payments: { method: string; amount: number }[] }): ShiftSummary {
  const byMethod = [...s.byMethod.map((m) => ({ ...m }))]
  let cash = 0
  for (const p of sale.payments) {
    if (p.method === 'EFECTIVO') cash += p.amount
    const row = byMethod.find((m) => m.method === p.method)
    if (row) {
      row.amount = n(row.amount) + p.amount
      row.count += 1
    } else byMethod.push({ method: p.method, amount: p.amount, count: 1 })
  }
  return recompute({
    ...s,
    salesCount: s.salesCount + 1,
    salesTotal: n(s.salesTotal) + sale.total,
    discountTotal: n(s.discountTotal) + sale.discountTotal,
    cashSales: n(s.cashSales) + cash,
    byMethod,
  })
}

/** Suma un movimiento de caja hecho sin internet. */
export function withMovement(s: ShiftSummary, m: CashMovementDto): ShiftSummary {
  const a = n(m.amount)
  const next = { ...s, movements: [...s.movements, m] }
  switch (m.type) {
    case 'INGRESO':
    case 'ABONO_FIADO':
      next.cashIn = n(s.cashIn) + a
      break
    case 'RETIRO':
      next.cashOut = n(s.cashOut) + a
      break
    case 'GASTO':
    case 'PAGO_PROVEEDOR':
      next.expenses = n(s.expenses) + a
      break
    case 'AJUSTE_ENTRADA':
      next.adjustments = n(s.adjustments) + a
      break
    case 'AJUSTE_SALIDA':
      next.adjustments = n(s.adjustments) - a
      break
  }
  return recompute(next)
}

/** Cierre hecho sin internet: lo que verá el cajero hasta que el servidor confirme. */
export function closedLocal(s: ShiftSummary, counted: number, countDetail: string | undefined, notes: string | undefined, userName: string): ShiftSummary {
  const expected = n(s.expectedCash)
  return {
    ...s,
    status: 'CLOSED',
    closedAt: new Date().toISOString(),
    closedByName: userName,
    countedCash: counted,
    difference: Math.round((counted - expected) * 100) / 100,
    countDetail,
    closeNotes: notes,
  }
}

/** Aplica un cambio al turno local guardado (si existe y sigue abierto). */
export async function updateLocalShift(tenantId: number, userId: number, fn: (s: ShiftSummary) => ShiftSummary | null) {
  const s = await getLocalShift(tenantId, userId)
  if (!s || s.status !== 'OPEN') return null
  const next = fn(s)
  await saveLocalShift(tenantId, userId, next)
  return next
}
