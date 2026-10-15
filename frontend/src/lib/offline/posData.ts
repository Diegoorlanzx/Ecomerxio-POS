import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import type { Category, PosProduct, Promotion, ShiftSummary, StoreSettings } from '../posTypes'
import { kvGet, kvSet } from './idb'

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
export function usePosData(tenantId: number | undefined) {
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
      const d: PosData = { catalog, categories, promotions, settings, shift: shift ?? null, savedAt: new Date().toISOString() }
      setData(d)
      setFromCache(false)
      setError('')
      void kvSet(key, d)
    } catch (e) {
      if (e instanceof ApiError && e.status === 0) {
        const cached = await kvGet<PosData>(key)
        if (cached) {
          setData(cached)
          setFromCache(true)
          return
        }
        setError('Sin conexión y sin una copia del catálogo en este equipo. Conéctate una vez para descargarla.')
      } else {
        setError(e instanceof Error ? e.message : 'No se pudo cargar la caja')
      }
    }
  }, [tenantId, key])

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
