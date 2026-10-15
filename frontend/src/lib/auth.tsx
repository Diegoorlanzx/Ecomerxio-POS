import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError, getToken, setToken, setUnauthorizedHandler } from './api'
import type { LoginResponse, Me } from './types'

interface AuthState {
  me: Me | null
  loading: boolean
  can: (permission: string) => boolean
  canAny: (...permissions: string[]) => boolean
  signIn: (res: LoginResponse) => void
  signOut: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

const ME_KEY = 'tp_me'

/** Copia del usuario en el equipo: permite abrir la caja sin internet. */
function cachedMe(): Me | null {
  try {
    return JSON.parse(localStorage.getItem(ME_KEY) ?? 'null')
  } catch {
    return null
  }
}

function saveMe(me: Me | null) {
  try {
    if (me) localStorage.setItem(ME_KEY, JSON.stringify(me))
    else localStorage.removeItem(ME_KEY)
  } catch {
    /* sin almacenamiento */
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(() => !!getToken())

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setMe(null)
      setLoading(false)
      return
    }
    try {
      const fresh = await api<Me>('/auth/me')
      saveMe(fresh)
      setMe(fresh)
    } catch (e) {
      // Sin conexión: se sigue con la sesión guardada. Solo un rechazo real del servidor la cierra.
      if (e instanceof ApiError && e.status === 0) setMe(cachedMe())
      else {
        saveMe(null)
        setMe(null)
      }
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      saveMe(null)
      setMe(null)
    })
    refresh()
  }, [refresh])

  const signIn = useCallback((res: LoginResponse) => {
    setToken(res.token)
    saveMe(res.me)
    setMe(res.me)
  }, [])

  const signOut = useCallback(async () => {
    try {
      await api('/auth/logout', { method: 'POST' })
    } catch {
      /* cerrar sesión local aunque el servidor no responda */
    }
    setToken(null)
    saveMe(null)
    setMe(null)
  }, [])

  const value = useMemo<AuthState>(() => {
    const perms = new Set(me?.permissions ?? [])
    return {
      me,
      loading,
      can: (p) => perms.has(p),
      canAny: (...ps) => ps.some((p) => perms.has(p)),
      signIn,
      signOut,
      refresh,
    }
  }, [me, loading, signIn, signOut, refresh])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fuera de AuthProvider')
  return ctx
}
