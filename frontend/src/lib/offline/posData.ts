import { useCallback, useEffect, useState } from 'react'
import { http as api, ApiError } from '../http'
import type { Category, PosProduct, Promotion, ShiftSummary, StoreSettings } from '../posTypes'
import { kvGet, kvSet, queueAll } from './idb'
import { getLocalShift, saveLocalShift } from './localShift'

export interface PosData {
  catalog: PosProduct[]
  categories: Category[]
  promotions: Promotion[]
  settings: StoreSettings
  shift: ShiftSummary | null
  savedAt: string
}

/**
 * Datos de la caja: se bajan del servidor y se guardan en el equipo; sin internet se usa la copia
 * guardada (catálogo, promociones, ajustes y el turno abierto).
 */
export function usePosData(tenantId: number | undefined, userId?: number) {
  const [data, setData] = useState<PosData | null>(null)
  const [fromCache, setFromCache] = useState(false)
  const [error, setError] = useState('')
  const key = `pos-data-${tenantId}`

  const load = useCallback(async () => {
    if (!tenantId) return
    try {
      const [catalog, categories, promotions, settings, shift] = await Promise.all([
        api<PosProduct[]>('/pos/catalog'),
        api<Category[]>('/categories'),
        api<Promotion[]>('/pos/promotions?onlyActive=true'),
        api<StoreSettings>('/settings/pos'),
        api<ShiftSummary | undefined>('/shifts/current'),
      ])
      // Con cosas sin enviar en el buzón, el turno del servidor está atrasado: vale el de este equipo.
      const waiting = (await queueAll<{ tenantId: number; status: string }>()).some((i) => i.tenantId === tenantId && i.status !== 'ERROR')
      let current = shift ?? null
      if (userId) {
        if (waiting) current = (await getLocalShift(tenantId, userId)) ?? current
        else await saveLocalShift(tenantId, userId, current)
      }
      const d: PosData = { catalog, categories, promotions, settings, shift: current, savedAt: new Date().toISOString() }
      setData(d)
      setFromCache(false)
      setError('')
      void kvSet(key, d)
    } catch (e) {
      if (e instanceof ApiError && e.status === 0) {
        const cached = await kvGet<PosData>(key)
        if (cached) {
          // El turno puede haberse abierto o movido sin internet después de guardar la copia.
          if (userId) {
            const local = await getLocalShift(tenantId, userId)
            cached.shift = local && local.status === 'OPEN' ? local : null
          }
          setData(cached)
          setFromCache(true)
          return
        }
        setError('Sin conexión y sin una copia del catálogo en este equipo. Conéctate una vez para descargarla.')
      } else {
        setError(e instanceof Error ? e.message : 'No se pudo cargar la caja')
      }
    }
  }, [tenantId, userId, key])

  useEffect(() => {
    void load()
  }, [load])

  /** Descuenta del stock local lo vendido sin internet (para que la pantalla no muestre de más). */
  const consumeLocal = useCallback(
    (items: { productId: number; baseQty: number }[]) => {
      setData((d) => {
        if (!d) return d
        const next = {
          ...d,
          catalog: d.catalog.map((p) => {
            const used = items.filter((i) => i.productId === p.id).reduce((s, i) => s + i.baseQty, 0)
            return used ? { ...p, stock: p.stock - used } : p
          }),
        }
        void kvSet(key, next)
        return next
      })
    },
    [key],
  )

  return { data, fromCache, error, reload: load, consumeLocal }
}
