import { useEffect, useState, useSyncExternalStore } from 'react'
import { http as api, ApiError } from '../http'
import type { SaleDto } from '../posTypes'
import { kvGet, kvSet, markSent, queueAll, queueDelete, queuePut, sentAll, sentDelete } from './idb'

/**
 * Buzón de salida del equipo. TODA operación que cambia datos pasa por aquí antes de ir al servidor (también con
 * internet): se guarda confirmada en disco, se envía, y solo sale del buzón cuando el servidor responde. Si se va la
 * luz o la red a mitad, al volver sigue en el equipo y se reenvía; el servidor no la repite (Idempotency-Key).
 * Todo se envía en el orden en que se hizo (abrir turno → ventas → gastos → cerrar turno).
 */
interface OutboxBase {
  clientRef: string
  tenantId: number
  createdAt: string
  /** SENDING = se está enviando ahora; PENDING = espera internet; ERROR = el servidor la rechazó. */
  status: 'SENDING' | 'PENDING' | 'ERROR'
  error: string | null
  attempts: number
}

/** Una venta (lleva el tiquete provisional que se le mostró al cliente). */
export interface PendingSale extends OutboxBase {
  kind?: 'SALE'
  payload: Record<string, unknown>
  receipt: SaleDto
}

/** Cualquier otra operación: abrir turno, gasto, abono, producto… */
export interface PendingOp extends OutboxBase {
  kind: 'OP'
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** Puede contener ids temporales "tmp:…" de algo creado sin internet; se cambian por el real antes de enviar. */
  path: string
  body?: unknown
  /** Qué es, para mostrarlo en "Pendientes por enviar" (p. ej. "Gasto de caja $20.000"). */
  label: string
  /** Si la operación CREA algo, el id temporal con que la app lo conoce mientras no hay internet. */
  tempId?: string
}

export type OutboxItem = PendingSale | PendingOp
export const isSale = (i: OutboxItem): i is PendingSale => i.kind !== 'OP'

interface State {
  items: OutboxItem[]
  syncing: boolean
  loaded: boolean
}

let state: State = { items: [], syncing: false, loaded: false }
const listeners = new Set<(s: State) => void>()
const emit = (patch: Partial<State>) => {
  state = { ...state, ...patch }
  listeners.forEach((l) => l(state))
}
const byDate = (a: OutboxItem, b: OutboxItem) => a.createdAt.localeCompare(b.createdAt)

export const SALES_SYNCED_EVENT = 'tiendaposs:sales-synced'
/** Se emite al enviar operaciones (no ventas): las pantallas recargan sus datos. */
export const OPS_SYNCED_EVENT = 'tiendaposs:ops-synced'

/** Registro de algo ya recibido por el servidor (se guarda 7 días para auditoría y luego se borra). */
export interface SentRecord {
  clientRef: string
  tenantId: number
  createdAt: string
  sentAt: string
  label: string
  saleId: number | null
  number: number | null
  total: number | null
}

const SENT_DAYS = 7

export async function loadQueue() {
  const items = await queueAll<OutboxItem>()
  // Lo que quedó "enviándose" es de una sesión que se cortó (apagón, cierre): vuelve a la cola.
  for (const i of items) {
    if (i.status === 'SENDING') {
      i.status = 'PENDING'
      await queuePut(i)
    }
  }
  emit({ items: items.sort(byDate), loaded: true })
  void pruneSent()
}

/** Relee el buzón del disco (otra pestaña pudo haber enviado o agregado). */
async function loadFromDisk() {
  const items = await queueAll<OutboxItem>()
  emit({ items: items.sort(byDate), loaded: true })
}

async function pruneSent() {
  const limit = Date.now() - SENT_DAYS * 86_400_000
  for (const s of await sentAll<SentRecord>()) if (Date.parse(s.sentAt) < limit) await sentDelete(s.clientRef)
}

export const recentSent = (tenantId: number) =>
  sentAll<SentRecord>().then((r) => r.filter((s) => s.tenantId === tenantId).sort((a, b) => b.sentAt.localeCompare(a.sentAt)))

// ---------------- ids temporales ----------------

const idMapKey = (tenantId: number) => `idmap-${tenantId}`

export function newTempId(): string {
  return `tmp:${newClientRef()}`
}
export const isTempId = (v: unknown): v is string => typeof v === 'string' && v.startsWith('tmp:')

/** Cambia los ids temporales por los reales en un texto o en cualquier valor (recursivo). Devuelve los que faltan. */
function resolveTemp(value: unknown, map: Record<string, number>, missing: Set<string>): unknown {
  if (typeof value === 'string') {
    return value.replace(/tmp:[A-Za-z0-9-]+/g, (m) => {
      if (map[m] !== undefined) return String(map[m])
      missing.add(m)
      return m
    })
  }
  if (Array.isArray(value)) return value.map((v) => resolveTemp(v, map, missing))
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value)) {
      // Un campo que es SOLO un id temporal pasa a ser número (el servidor espera un id numérico).
      if (isTempId(v) && map[v] !== undefined) out[k] = map[v]
      else out[k] = resolveTemp(v, map, missing)
    }
    return out
  }
  return value
}

/** Id real de algo creado sin internet, si ya se envió. */
export async function realId(tenantId: number, id: number | string): Promise<number | string> {
  if (!isTempId(id)) return id
  const map = (await kvGet<Record<string, number>>(idMapKey(tenantId))) ?? {}
  return map[id] ?? id
}

// ---------------- ventas ----------------

export async function enqueueSale(item: PendingSale): Promise<boolean> {
  const ok = await queuePut({ ...item, kind: 'SALE' })
  if (ok) emit({ items: [...state.items.filter((i) => i.clientRef !== item.clientRef), item].sort(byDate) })
  return ok
}

export async function discardSale(clientRef: string) {
  await queueDelete(clientRef)
  emit({ items: state.items.filter((i) => i.clientRef !== clientRef) })
}
export const discardItem = discardSale

/** Pasa algo del buzón al historial de enviadas, en una sola transacción. */
export async function confirmSent(item: OutboxItem, result: { id?: number; number?: number; total?: number } | null) {
  await markSent(item.clientRef, {
    clientRef: item.clientRef,
    tenantId: item.tenantId,
    createdAt: item.createdAt,
    sentAt: new Date().toISOString(),
    label: isSale(item) ? 'Venta' : item.label,
    saleId: isSale(item) ? (result?.id ?? null) : null,
    number: result?.number ?? null,
    total: isSale(item) ? (result?.total ?? item.receipt.total) : null,
  } satisfies SentRecord)
  emit({ items: state.items.filter((i) => i.clientRef !== item.clientRef) })
}

export async function updateSale(clientRef: string, patch: Partial<OutboxItem>) {
  const item = state.items.find((i) => i.clientRef === clientRef)
  if (item) await update(item, patch)
}

async function update(item: OutboxItem, patch: Partial<OutboxItem>) {
  const next = { ...item, ...patch } as OutboxItem
  await queuePut(next)
  emit({ items: state.items.map((i) => (i.clientRef === item.clientRef ? next : i)) })
}

export async function retrySale(clientRef: string) {
  const item = state.items.find((i) => i.clientRef === clientRef)
  if (item) await update(item, { status: 'PENDING', error: null, attempts: 0 })
}
export const retryItem = retrySale

// ---------------- operaciones ----------------

export interface OpRequest {
  tenantId: number
  method: PendingOp['method']
  path: string
  body?: unknown
  label: string
  /** Si la operación crea algo: id temporal para referirse a ello mientras no hay internet. */
  tempId?: string
}

export type OpResult<T> = { queued: false; data: T } | { queued: true; clientRef: string }

/** Es "sin conexión": no llegó respuesta del servidor (o llegó del proxy porque el servidor está caído). */
const isOffline = (e: unknown) => e instanceof ApiError && e.status === 0

/**
 * Hace una operación pasando por el buzón: se guarda en disco, se intenta enviar y, si no hay internet, queda en
 * cola y devuelve { queued: true } (la pantalla sigue como si se hubiera hecho). Si el servidor la rechaza (datos
 * inválidos, sin permiso), sale del buzón y lanza el error como siempre.
 */
export async function runOp<T = unknown>(req: OpRequest): Promise<OpResult<T>> {
  const item: PendingOp = {
    kind: 'OP',
    clientRef: newClientRef(),
    tenantId: req.tenantId,
    createdAt: new Date().toISOString(),
    method: req.method,
    path: req.path,
    body: req.body,
    label: req.label,
    tempId: req.tempId,
    status: 'SENDING',
    error: null,
    attempts: 0,
  }
  const saved = await queuePut(item)
  if (saved) emit({ items: [...state.items, item].sort(byDate) })
  // Si hay cosas anteriores esperando, esta va detrás (el orden importa): queda en cola.
  const earlier = state.items.some((i) => i.tenantId === req.tenantId && i.clientRef !== item.clientRef && i.status !== 'ERROR' && i.createdAt <= item.createdAt)
  if (saved && (earlier || !navigator.onLine)) {
    await update(item, { status: 'PENDING' })
    if (navigator.onLine) void syncPending(req.tenantId)
    return { queued: true, clientRef: item.clientRef }
  }
  try {
    const data = await sendOp<T>(item)
    if (saved) await confirmSent(item, data as { id?: number; number?: number })
    return { queued: false, data }
  } catch (e) {
    if (isOffline(e) && saved) {
      await update(item, { status: 'PENDING' })
      return { queued: true, clientRef: item.clientRef }
    }
    if (saved) await discardSale(item.clientRef)
    throw e
  }
}

async function sendOp<T>(item: PendingOp): Promise<T> {
  const map = (await kvGet<Record<string, number>>(idMapKey(item.tenantId))) ?? {}
  const missing = new Set<string>()
  const path = resolveTemp(item.path, map, missing) as string
  const body = resolveTemp(item.body, map, missing)
  if (missing.size) throw new ApiError(409, 'Depende de algo hecho sin internet que no se pudo enviar. Revisa los pendientes anteriores.')
  const data = await api<T>(path, { method: item.method, body: item.body === undefined ? undefined : body, headers: { 'Idempotency-Key': item.clientRef } })
  const id = (data as { id?: unknown } | undefined)?.id
  if (item.tempId && typeof id === 'number') await kvSet(idMapKey(item.tenantId), { ...map, [item.tempId]: id })
  return data
}

// ---------------- envío ----------------

/**
 * Envía el buzón en orden, de lo más viejo a lo más nuevo. Repetirlo es seguro: cada cosa lleva su identificador y
 * el servidor devuelve la que ya guardó en vez de repetirla. Si una falla por falta de red, se detiene (el orden
 * importa); si el servidor la rechaza, queda en ERROR y se sigue con la siguiente.
 */
export async function syncPending(tenantId: number): Promise<number> {
  if (state.syncing || !navigator.onLine) return 0
  // Con varias pestañas abiertas, solo una envía a la vez.
  const locks = (navigator as Navigator & { locks?: LockManager }).locks
  if (locks) return (await locks.request('tiendaposs-sync', { ifAvailable: true }, (lock) => (lock ? sendAll(tenantId) : 0))) ?? 0
  return sendAll(tenantId)
}

async function sendAll(tenantId: number): Promise<number> {
  if (state.syncing) return 0
  await loadFromDisk()
  emit({ syncing: true })
  let sales = 0
  let ops = 0
  try {
    for (const item of state.items.filter((i) => i.tenantId === tenantId && i.status === 'PENDING')) {
      try {
        if (isSale(item)) {
          // El turno pudo abrirse sin internet (id temporal): se cambia por el real; si ese turno no llegó, la venta
          // va sin turno y el servidor la marca para revisar (nunca se pierde por eso).
          const map = (await kvGet<Record<string, number>>(idMapKey(tenantId))) ?? {}
          const missing = new Set<string>()
          let payload = resolveTemp(item.payload, map, missing) as Record<string, unknown>
          if (missing.size) payload = { ...payload, offlineShiftId: undefined }
          const sale = await api<{ id?: number; number?: number; total?: number }>('/pos/sales', {
            body: payload,
            headers: { 'Idempotency-Key': item.clientRef },
          })
          await confirmSent(item, sale ?? null)
          sales++
        } else {
          const data = await sendOp<{ id?: number; number?: number }>(item)
          await confirmSent(item, data ?? null)
          ops++
        }
      } catch (err) {
        if (err instanceof ApiError && (err.status === 0 || err.status === 401 || err.status >= 500)) {
          await update(item, { attempts: item.attempts + 1 })
          break
        }
        await update(item, { status: 'ERROR', error: err instanceof Error ? err.message : 'Error', attempts: item.attempts + 1 })
      }
    }
  } finally {
    emit({ syncing: false })
  }
  if (sales > 0) window.dispatchEvent(new CustomEvent(SALES_SYNCED_EVENT, { detail: { sent: sales } }))
  if (ops > 0) window.dispatchEvent(new CustomEvent(OPS_SYNCED_EVENT, { detail: { sent: ops } }))
  return sales + ops
}

// ---------------- respaldo y consultas ----------------

/** Respaldo del buzón en un archivo, para guardarlo fuera del navegador antes de cerrar sesión o formatear. */
export async function exportQueue(tenantId: number): Promise<number> {
  const items = (await queueAll<OutboxItem>()).filter((i) => i.tenantId === tenantId)
  const blob = new Blob([JSON.stringify({ app: 'Ecomerxio POS', exportedAt: new Date().toISOString(), tenantId, items }, null, 2)], {
    type: 'application/json',
  })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `pendientes-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.json`
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
  return items.length
}

/** Cuántas cosas de la tienda siguen sin llegar al servidor (leído del disco, no de memoria). */
export async function pendingCount(tenantId: number): Promise<number> {
  return (await queueAll<OutboxItem>()).filter((i) => i.tenantId === tenantId).length
}

/** El buzón tal como está en memoria ahora (para revisar duplicados sin esperar al disco). */
export function outboxSnapshot(): OutboxItem[] {
  return state.items
}

function useOutboxState() {
  const [snap, setSnap] = useState<State>(state)
  useEffect(() => {
    listeners.add(setSnap)
    setSnap(state)
    if (!state.loaded) void loadQueue()
    return () => {
      listeners.delete(setSnap)
    }
  }, [])
  return snap
}

/** Todo el buzón de la tienda (ventas y operaciones). */
export function useOutbox(tenantId: number | undefined) {
  const snap = useOutboxState()
  const mine = snap.items.filter((i) => i.tenantId === tenantId)
  return {
    items: mine,
    pending: mine.filter((i) => i.status !== 'ERROR').length,
    errors: mine.filter((i) => i.status === 'ERROR').length,
    syncing: snap.syncing,
  }
}

/** Solo las ventas del buzón (para el historial de ventas y la caja). */
export function usePendingSales(tenantId: number | undefined) {
  const snap = useOutboxState()
  const all = snap.items.filter((i) => i.tenantId === tenantId)
  const mine = all.filter(isSale)
  return {
    items: mine,
    pending: mine.filter((i) => i.status !== 'ERROR').length,
    errors: mine.filter((i) => i.status === 'ERROR').length,
    syncing: snap.syncing,
    /** Ventas y demás operaciones sin enviar. */
    allPending: all.filter((i) => i.status !== 'ERROR').length,
  }
}

function subscribe(cb: () => void) {
  window.addEventListener('online', cb)
  window.addEventListener('offline', cb)
  return () => {
    window.removeEventListener('online', cb)
    window.removeEventListener('offline', cb)
  }
}

/** true si el equipo dice tener conexión (no garantiza que el servidor responda). */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribe, () => navigator.onLine, () => true)
}

export function newClientRef(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
}
