import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Banner, Button, Field, PasswordField, SelectField } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { LoginResponse } from '../../lib/types'
import AuthLayout from './AuthLayout'
import { PLANS, planFromQuery } from '../../lib/brand'

/** Tipos de negocio (perfiles): cada uno enciende los módulos que suele usar. Se cambia luego en Más → Tipo de negocio. */
const BUSINESS_TYPES: [string, string][] = [
  ['TIENDA', 'Tienda de barrio o minimercado'],
  ['MODA', 'Ropa, calzado y accesorios'],
  ['TECNOLOGIA', 'Celulares, tecnología y electrodomésticos'],
  ['FRUVER_CARNICERIA', 'Fruver, carnicería o panadería'],
  ['RESTAURANTE', 'Restaurante, cafetería o bar'],
  ['SERVICIOS', 'Servicios (peluquería, taller, spa)'],
  ['DISTRIBUIDORA', 'Distribuidora o mayorista'],
  ['MIXTO', 'Varios tipos a la vez'],
]

export default function RegisterPage() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState({
    storeName: '', ownerName: '', email: '', phone: '', password: '', businessType: 'TIENDA', plan: planFromQuery(location.search),
  })
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm((f) => ({ ...f, [k]: e.target.value }))

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
        <fieldset>
          <legend className="mb-2 text-sm font-semibold">Plan</legend>
          <div className="grid gap-2">
            {PLANS.map((p) => (
              <label
                key={p.code}
                className={`flex cursor-pointer items-start gap-3 rounded-xl border p-3 transition ${
                  form.plan === p.code ? 'border-primary bg-primary/5' : 'border-line hover:border-primary/40'
                }`}
              >
                <input
                  type="radio"
                  name="plan"
                  value={p.code}
                  checked={form.plan === p.code}
                  onChange={() => setForm((f) => ({ ...f, plan: p.code }))}
                  className="mt-1 accent-[var(--color-primary)]"
                />
                <span className="min-w-0 flex-1">
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="font-semibold">{p.name}</span>
                    <span className="text-sm font-semibold tabular-nums">${p.priceMonthly.toLocaleString('es-CO')}/mes</span>
                  </span>
                  <span className="block text-xs text-muted">{p.summary}</span>
                </span>
              </label>
            ))}
          </div>
          <p className="mt-2 text-xs text-muted">15 días de prueba gratis, sin tarjeta. Puedes cambiar de plan después.</p>
        </fieldset>
        <Field label="Nombre de la tienda" placeholder="Ej: Cigarrería El Paisa" value={form.storeName} onChange={set('storeName')} error={fields.storeName} />
 <SelectField label="Tipo de negocio" value={form.businessType} onChange={set('businessType')} error={fields.businessType}>
          {BUSINESS_TYPES.map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </SelectField>
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
