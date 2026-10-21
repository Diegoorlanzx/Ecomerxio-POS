import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Banner, Button, Field, PasswordField, SelectField } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { LoginResponse } from '../../lib/types'
import AuthLayout from './AuthLayout'
import { PLANS, planFromQuery } from '../../lib/brand'
import { appUrl } from '../../lib/host'

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
  const [accept, setAccept] = useState({ terms: false, privacy: false })
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
      const res = await api<LoginResponse>('/auth/register', { body: { ...form, acceptTerms: accept.terms, acceptPrivacy: accept.privacy } })
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
        <div className="space-y-2 rounded-xl border border-line p-3 text-sm">
          <LegalCheck
            checked={accept.terms}
            onChange={(v) => setAccept((a) => ({ ...a, terms: v }))}
            error={fields.acceptTerms}
          >
            Acepto los <DocLink slug="terminos">Términos y condiciones</DocLink>, la <DocLink slug="licencia">Licencia de uso y propiedad intelectual</DocLink>,
            la <DocLink slug="servicio-y-responsabilidad">Política de servicio y limitación de responsabilidad</DocLink>, el{' '}
            <DocLink slug="facturacion-electronica">Anexo de facturación electrónica</DocLink> y el{' '}
            <DocLink slug="encargo-de-datos">Encargo del tratamiento de datos</DocLink> de mis clientes. Declaro que uso la app para mi negocio.
          </LegalCheck>
          <LegalCheck
            checked={accept.privacy}
            onChange={(v) => setAccept((a) => ({ ...a, privacy: v }))}
            error={fields.acceptPrivacy}
          >
            Autorizo el tratamiento de mis datos personales según la <DocLink slug="privacidad">Política de tratamiento de datos</DocLink>, incluida su
            transferencia a proveedores en la nube fuera de Colombia.
          </LegalCheck>
        </div>
        <Button type="submit" size="lg" className="w-full" loading={loading} disabled={!accept.terms || !accept.privacy}>
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

function DocLink({ slug, children }: { slug: string; children: React.ReactNode }) {
  return (
    <a href={appUrl(`/legal/${slug}`)} target="_blank" rel="noreferrer" className="font-semibold text-primary hover:underline">
      {children}
    </a>
  )
}

function LegalCheck({ checked, onChange, error, children }: { checked: boolean; onChange: (v: boolean) => void; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="flex cursor-pointer items-start gap-2.5">
        <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} className="mt-0.5 h-5 w-5 shrink-0 accent-[var(--color-primary)]" />
        <span className="text-muted">{children}</span>
      </label>
      {error && <p className="mt-1 pl-7 text-xs text-danger-ink">{error}</p>}
    </div>
  )
}
