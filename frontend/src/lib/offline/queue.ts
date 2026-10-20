import { useEffect, useState, useSyncExternalStore } from 'react'
import { api, ApiError } from '../api'
import type { SaleDto } from '../posTypes'
import { markSent, queueAll, queueDelete, queuePut, sentAll, sentDelete } from './idb'

/**
 * Una venta en el buzón de salida. TODA venta pasa por aquí antes de ir al servidor (también con internet): si se
 * va la luz a mitad del envío, al volver sigue en el equipo y se reenvía; el servidor no la duplica (clientRef).
 */
export interface PendingSale {
  clientRef: string
  tenantId: number
  createdAt: string
  payload: Record<string, unknown>
  /** Lo que se le mostró al cliente (tiquete provisional). */
  receipt: SaleDto
  /** SENDING = la caja la está enviando ahora mismo; PENDING = espera internet; ERROR = el servidor la rechazó. */
  status: 'SENDING' | 'PENDING' | 'ERROR'
  error: string | null
  attempts: number
}

interface State {
  items: PendingSale[]
  syncing: boolean
  loaded: boolean
}

let state: State = { items: [], syncing: false, loaded: false }
const listeners = new Set<(s: State) => void>()
const emit = (patch: Partial<State>) => {
  state = { ...state, ...patch }
  listeners.forEach((l) => l(state))
}

export const SALES_SYNCED_EVENT = 'tiendaposs:sales-synced'

/** Registro de una venta ya recibida por el servidor (se guarda 7 días para auditoría y luego se borra). */
export interface SentSale {
  clientRef: string
  tenantId: number
  createdAt: string
  sentAt: string
  saleId: number | null
  number: number | null
  total: number
}

const SENT_DAYS = 7

export async function loadQueue() {
  const items = await queueAll<PendingSale>()
  // Una venta que quedó "enviándose" es de una sesión que se cortó (apagón, cierre): vuelve a la cola.
  for (const i of items) {
    if (i.status === 'SENDING') {
      i.status = 'PENDING'
      await queuePut(i)
    }
  }
  emit({ items: items.sort((a, b) => a.createdAt.localeCompare(b.createdAt)), loaded: true })
  void pruneSent()
}

async function pruneSent() {
  const limit = Date.now() - SENT_DAYS * 86_400_000
  for (const s of await sentAll<SentSale>()) if (Date.parse(s.sentAt) < limit) await sentDelete(s.clientRef)
}

/** Pasa una venta del buzón al historial de enviadas, en una sola transacción. */
export async function confirmSent(item: PendingSale, sale: { id?: number; number?: number; total?: number } | null) {
  await markSent(item.clientRef, {
    clientRef: item.clientRef,
    tenantId: item.tenantId,
    createdAt: item.createdAt,
    sentAt: new Date().toISOString(),
    saleId: sale?.id ?? null,
    number: sale?.number ?? null,
    total: sale?.total ?? item.receipt.total,
  } satisfies SentSale)
  emit({ items: state.items.filter((i) => i.clientRef !== item.clientRef) })
}

export async function enqueueSale(item: PendingSale): Promise<boolean> {
  const ok = await queuePut(item)
  if (ok) emit({ items: [...state.items.filter((i) => i.clientRef !== item.clientRef), item] })
  return ok
}

export async function discardSale(clientRef: string) {
  await queueDelete(clientRef)
  emit({ items: state.items.filter((i) => i.clientRef !== clientRef) })
}

export async function updateSale(clientRef: string, patch: Partial<PendingSale>) {
  const item = state.items.find((i) => i.clientRef === clientRef)
  if (item) await update(item, patch)
}

async function update(item: PendingSale, patch: Partial<PendingSale>) {
  const next = { ...item, ...patch }
  await queuePut(next)
  emit({ items: state.items.map((i) => (i.clientRef === item.clientRef ? next : i)) })
}

export async function retrySale(clientRef: string) {
  const item = state.items.find((i) => i.clientRef === clientRef)
  if (item) await update(item, { status: 'PENDING', error: null, attempts: 0 })
}

/**
 * Envía las ventas pendientes, de la más vieja a la más nueva. Repetirlo es seguro: cada venta lleva
 * su identificador y el servidor devuelve la que ya guardó en vez de duplicarla.
 */
export async function syncPending(tenantId: number): Promise<number> {
  if (state.syncing || !navigator.onLine) return 0
  // Con varias pestañas abiertas, solo una envía a la vez (las demás no esperan: lo hará la que tiene el turno).
  const locks = (navigator as Navigator & { locks?: LockManager }).locks
  if (locks) {
    return (await locks.request('tiendaposs-sync', { ifAvailable: true }, (lock) => (lock ? sendAll(tenantId) : 0))) ?? 0
  }
  return sendAll(tenantId)
}

async function sendAll(tenantId: number): Promise<number> {
  if (state.syncing) return 0
  await loadFromDisk()
  emit({ syncing: true })
  let sent = 0
  try {
    for (const item of state.items.filter((i) => i.tenantId === tenantId && i.status === 'PENDING')) {
      try {
        const sale = await api<{ id?: number; number?: number; total?: number }>('/pos/sales', { body: item.payload })
        await confirmSent(item, sale ?? null)
        sent++
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
  if (sent > 0) window.dispatchEvent(new CustomEvent(SALES_SYNCED_EVENT, { detail: { sent } }))
  return sent
}

/** Relee el buzón del disco (otra pestaña pudo haber enviado o agregado ventas). Las "enviándose" se respetan. */
async function loadFromDisk() {
  const items = await queueAll<PendingSale>()
  emit({ items: items.sort((a, b) => a.createdAt.localeCompare(b.createdAt)), loaded: true })
}

/** Respaldo del buzón en un archivo, para guardarlo fuera del navegador antes de cerrar sesión o formatear. */
export async function exportQueue(tenantId: number): Promise<number> {
  const items = (await queueAll<PendingSale>()).filter((i) => i.tenantId === tenantId)
  const blob = new Blob([JSON.stringify({ app: 'Ecomerxio POS', exportedAt: new Date().toISOString(), tenantId, items }, null, 2)], {
    type: 'application/json',
  })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `ventas-pendientes-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.json`
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
  return items.length
}

/** Cuántas ventas de la tienda siguen sin llegar al servidor (leído del disco, no de memoria). */
export async function pendingCount(tenantId: number): Promise<number> {
  return (await queueAll<PendingSale>()).filter((i) => i.tenantId === tenantId).length
}

export function usePendingSales(tenantId: number | undefined) {
  const [snap, setSnap] = useState<State>(state)
  useEffect(() => {
    listeners.add(setSnap)
    setSnap(state)
    if (!state.loaded) void loadQueue()
    return () => {
      listeners.delete(setSnap)
    }
  }, [])
  const mine = snap.items.filter((i) => i.tenantId === tenantId)
  return {
    items: mine,
    pending: mine.filter((i) => i.status !== 'ERROR').length,
    errors: mine.filter((i) => i.status === 'ERROR').length,
    syncing: snap.syncing,
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
