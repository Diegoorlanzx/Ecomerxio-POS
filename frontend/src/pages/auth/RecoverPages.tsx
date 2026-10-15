import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Banner, Button, Field, PasswordField } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import AuthLayout from './AuthLayout'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState('')
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFields({})
    setLoading(true)
    try {
      const res = await api<{ message: string }>('/auth/forgot-password', { body: { email } })
      setSent(res.message)
    } catch (err) {
      if (err instanceof ApiError) {
        setFields(err.fields)
        if (!Object.keys(err.fields).length) setError(err.message)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Recupera tu contraseña" subtitle="Te enviamos un enlace al correo para crear una nueva.">
      {sent ? (
        <div className="space-y-6">
          <Banner tone="success">{sent}</Banner>
          <p className="text-sm text-muted">
            ¿Eres empleado y no tienes acceso a tu correo? Pídele al dueño o al administrador que te asigne una contraseña nueva desde
            Usuarios.
          </p>
          <Link to="/login" className="block text-center text-sm font-semibold text-primary hover:underline">
            Volver a iniciar sesión
          </Link>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          {error && <Banner>{error}</Banner>}
          <Field label="Correo" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fields.email} />
          <Button type="submit" size="lg" className="w-full" loading={loading}>
            Enviar enlace
          </Button>
          <Link to="/login" className="block text-center text-sm font-semibold text-primary hover:underline">
            Volver
          </Link>
        </form>
      )}
    </AuthLayout>
  )
}

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFields({})
    if (password !== confirm) {
      setFields({ confirm: 'las contraseñas no coinciden' })
      return
    }
    setLoading(true)
    try {
      await api('/auth/reset-password', { body: { token, password } })
      setDone(true)
      setTimeout(() => navigate('/login'), 2000)
    } catch (err) {
      if (err instanceof ApiError) {
        setFields(err.fields)
        if (!Object.keys(err.fields).length) setError(err.message)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Crea una contraseña nueva">
      {done ? (
        <Banner tone="success">Listo. Tu contraseña cambió; te llevamos a iniciar sesión.</Banner>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          {!token && <Banner>El enlace está incompleto. Pide uno nuevo.</Banner>}
          {error && <Banner>{error}</Banner>}
          <PasswordField
            label="Contraseña nueva"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={fields.password}
            hint="Mínimo 8 caracteres, con letras y números."
          />
          <PasswordField label="Repite la contraseña" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} error={fields.confirm} />
          <Button type="submit" size="lg" className="w-full" loading={loading} disabled={!token}>
            Guardar contraseña
          </Button>
        </form>
      )}
    </AuthLayout>
  )
}
