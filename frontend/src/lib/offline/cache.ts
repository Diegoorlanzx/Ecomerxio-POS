import { useCallback, useEffect, useState } from 'react'
import { http as api, ApiError } from '../http'
import { useAuth } from '../auth'
import { kvGet, kvSet } from './idb'

/**
 * Copia para consultar sin internet: cada lectura que sale bien se guarda (reemplazando la anterior, no crece) y,
 * sin conexión, se devuelve la última copia con su hora.
 */
export interface Cached<T> {
  data: T
  fromCache: boolean
  savedAt: string
}

const cacheKey = (tenantId: number, path: string) => `cache:${tenantId}:${path}`

export async function cachedApi<T>(tenantId: number, path: string): Promise<Cached<T>> {
  try {
    const data = await api<T>(path)
    const savedAt = new Date().toISOString()
    void kvSet(cacheKey(tenantId, path), { data, savedAt })
    return { data, fromCache: false, savedAt }
  } catch (e) {
    if (e instanceof ApiError && e.status === 0) {
      const hit = await kvGet<{ data: T; savedAt: string }>(cacheKey(tenantId, path))
      if (hit) return { data: hit.data, fromCache: true, savedAt: hit.savedAt }
    }
    throw e
  }
}

/** Escribe la copia a mano (p. ej. tras un cambio hecho sin internet, para que la pantalla lo muestre). */
export const setCached = (tenantId: number, path: string, data: unknown) => kvSet(cacheKey(tenantId, path), { data, savedAt: new Date().toISOString() })
export const getCached = <T>(tenantId: number, path: string) => kvGet<{ data: T; savedAt: string }>(cacheKey(tenantId, path))

/** Hook de lectura con copia: { data, fromCache, savedAt, error, reload }. */
export function useCachedApi<T>(path: string | null) {
  const { me } = useAuth()
  const tenantId = me?.tenant.id
  const [state, setState] = useState<{ data: T | null; fromCache: boolean; savedAt: string | null; error: string; loading: boolean }>({
    data: null,
    fromCache: false,
    savedAt: null,
    error: '',
    loading: true,
  })
  const reload = useCallback(async () => {
    if (!tenantId || !path) return
    setState((s) => ({ ...s, loading: true }))
    try {
      const r = await cachedApi<T>(tenantId, path)
      setState({ data: r.data, fromCache: r.fromCache, savedAt: r.savedAt, error: '', loading: false })
    } catch (e) {
      setState((s) => ({
        ...s,
        loading: false,
        error:
          e instanceof ApiError && e.status === 0
            ? 'Sin internet y sin una copia guardada de esta pantalla. Ábrela una vez con internet para guardarla.'
            : e instanceof Error
              ? e.message
              : 'No se pudo cargar',
      }))
    }
  }, [tenantId, path])
  useEffect(() => {
    void reload()
  }, [reload])
  return { ...state, reload }
}

/** "hace 5 min" / "a las 3:20 p. m." para mostrar la antigüedad de una copia. */
export function ageLabel(iso: string | null): string {
  if (!iso) return ''
  const min = Math.round((Date.now() - Date.parse(iso)) / 60000)
  if (min < 1) return 'hace un momento'
  if (min < 60) return `hace ${min} min`
  const d = new Date(iso)
  return `del ${d.toLocaleDateString('es-CO', { day: 'numeric', month: 'short' })} a las ${d.toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' })}`
}
