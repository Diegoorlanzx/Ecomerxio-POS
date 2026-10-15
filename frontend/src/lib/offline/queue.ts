import { useEffect, useState, useSyncExternalStore } from 'react'
import { api, ApiError } from '../api'
import type { SaleDto } from '../posTypes'
import { queueAll, queueDelete, queuePut } from './idb'

/** Una venta cobrada sin internet, guardada en el equipo hasta poder enviarla. */
export interface PendingSale {
  clientRef: string
  tenantId: number
  createdAt: string
  payload: Record<string, unknown>
  /** Lo que se le mostró al cliente (tiquete provisional). */
  receipt: SaleDto
  status: 'PENDING' | 'ERROR'
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

export async function loadQueue() {
  const items = await queueAll<PendingSale>()
  emit({ items: items.sort((a, b) => a.createdAt.localeCompare(b.createdAt)), loaded: true })
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
  emit({ syncing: true })
  let sent = 0
  try {
    for (const item of state.items.filter((i) => i.tenantId === tenantId && i.status === 'PENDING')) {
      try {
        await api('/pos/sales', { body: item.payload })
        await queueDelete(item.clientRef)
        emit({ items: state.items.filter((i) => i.clientRef !== item.clientRef) })
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
    pending: mine.filter((i) => i.status === 'PENDING').length,
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
