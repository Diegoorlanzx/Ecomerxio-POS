import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Banner, Spinner } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { LoginResponse } from '../../lib/types'
import AuthLayout from './AuthLayout'

/**
 * Entrada desde el login de ecomerxio.com: llega con ?code= (un solo uso, 60 s) y lo canjea por la sesión.
 * El código se quita de la dirección apenas se lee.
 */
export default function SsoPage() {
  const [params] = useSearchParams()
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return // StrictMode monta dos veces en desarrollo: el código sirve una sola vez
    started.current = true
    const code = params.get('code') ?? ''
    window.history.replaceState(null, '', window.location.pathname)
    if (!code) {
      setError('Falta el código para entrar. Inicia sesión de nuevo.')
      return
    }
    api<LoginResponse>('/auth/handoff/redeem', { body: { code } })
      .then((res) => {
        signIn(res)
        navigate('/', { replace: true })
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'No se pudo entrar. Inicia sesión de nuevo.'))
  }, [params, signIn, navigate])

  return (
    <AuthLayout title="Entrando a tu tienda" subtitle="Un momento, estamos abriendo tu punto de venta.">
      {error ? (
        <div className="space-y-4">
          <Banner>{error}</Banner>
          <a href="https://www.ecomerxio.com/login" className="block text-center text-sm font-semibold text-primary hover:underline">
            Volver a iniciar sesión en ecomerxio.com
          </a>
          <Link to="/login" className="block text-center text-sm text-muted hover:underline">
            O entra aquí con tu correo
          </Link>
        </div>
      ) : (
        <Spinner />
      )}
    </AuthLayout>
  )
}
