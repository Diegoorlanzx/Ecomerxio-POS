import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Banner, Button, Field, PasswordField } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { LoginResponse } from '../../lib/types'
import AuthLayout from './AuthLayout'

export default function RegisterPage() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ storeName: '', ownerName: '', email: '', phone: '', password: '' })
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [k]: e.target.value }))

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFields({})
    setLoading(true)
    try {
      const res = await api<LoginResponse>('/auth/register', { body: form })
      signIn(res)
      navigate('/', { replace: true })
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
    <AuthLayout title="Crea tu tienda" subtitle="En un minuto tienes tu punto de venta listo. Tú quedas como dueño.">
      <form onSubmit={submit} className="space-y-4" noValidate>
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre de la tienda" placeholder="Ej: Cigarrería El Paisa" value={form.storeName} onChange={set('storeName')} error={fields.storeName} />
        <Field label="Tu nombre" autoComplete="name" value={form.ownerName} onChange={set('ownerName')} error={fields.ownerName} />
        <Field label="Correo" type="email" autoComplete="email" value={form.email} onChange={set('email')} error={fields.email} />
        <Field label="Celular (opcional)" type="tel" autoComplete="tel" value={form.phone} onChange={set('phone')} error={fields.phone} />
        <PasswordField
          label="Contraseña"
          autoComplete="new-password"
          value={form.password}
          onChange={set('password')}
          error={fields.password}
          hint="Mínimo 8 caracteres, con letras y números."
        />
        <Button type="submit" size="lg" className="w-full" loading={loading}>
          Crear mi tienda
        </Button>
      </form>
      <p className="mt-8 text-center text-sm text-muted">
        ¿Ya tienes cuenta?{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          Inicia sesión
        </Link>
      </p>
    </AuthLayout>
  )
}
