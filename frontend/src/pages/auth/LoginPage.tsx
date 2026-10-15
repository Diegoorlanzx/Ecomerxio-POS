import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Banner, Button, Field, PasswordField } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { LoginResponse } from '../../lib/types'
import AuthLayout from './AuthLayout'

export default function LoginPage() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFields({})
    setLoading(true)
    try {
      const res = await api<LoginResponse>('/auth/login', { body: { email, password } })
      signIn(res)
      navigate('/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setFields(err.fields)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Entra a tu tienda" subtitle="Usa el correo y la contraseña que te dio el dueño de la tienda.">
      <form onSubmit={submit} className="space-y-4" noValidate>
        {error && <Banner>{error}</Banner>}
        <Field label="Correo" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fields.email} required />
        <PasswordField
          label="Contraseña"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fields.password}
          required
        />
        <div className="flex justify-end">
          <Link to="/recuperar" className="text-sm font-semibold text-primary hover:underline">
            ¿Olvidaste tu contraseña?
          </Link>
        </div>
        <Button type="submit" size="lg" className="w-full" loading={loading}>
          Entrar
        </Button>
      </form>
      <p className="mt-8 text-center text-sm text-muted">
        ¿Tienes una tienda nueva?{' '}
        <Link to="/registro" className="font-semibold text-primary hover:underline">
          Crea tu cuenta
        </Link>
      </p>
    </AuthLayout>
  )
}
