import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from './api'
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
      setMe(await api<Me>('/auth/me'))
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => setMe(null))
    refresh()
  }, [refresh])

  const signIn = useCallback((res: LoginResponse) => {
    setToken(res.token)
    setMe(res.me)
  }, [])

  const signOut = useCallback(async () => {
    try {
      await api('/auth/logout', { method: 'POST' })
    } catch {
      /* cerrar sesión local aunque el servidor no responda */
    }
    setToken(null)
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
