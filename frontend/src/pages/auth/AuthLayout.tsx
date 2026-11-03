import { useEffect, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icon } from '../../components/ui'
import { loadGoogleTag } from '../../lib/ads'
import { APP_NAME } from '../../lib/brand'
import { asset } from '../../lib/host'

/** Lo más llamativo del POS, en tarjetas del panel azul. */
const FEATURES: [string, string, string][] = [
  ['bolt', 'Cobra en segundos', 'Con lector de código de barras, en celular o computador.'],
  ['cloud_off', 'Vende sin internet', 'Todo se envía solo cuando vuelve la conexión.'],
  ['inventory', 'Inventario inteligente', 'Te avisa qué comprar antes de que se agote.'],
  ['qr_code_2', 'Cobra como quieras', 'Efectivo, datáfono o QR, con recibo por WhatsApp.'],
  ['verified_user', 'Tu equipo, con control', 'Permisos, turnos y cierres de caja por empleado.'],
  ['insights', 'Reportes claros', 'Ventas y ganancias del día, al instante.'],
]

/** Lo que sigue solo se muestra en el registro, donde el formulario es largo. */
const STEPS: [string, string][] = [
  ['Crea tu tienda', 'En un minuto, eligiendo tu tipo de negocio.'],
  ['Sube tus productos', 'Desde Excel o con el lector de código de barras.'],
  ['Vende hoy mismo', 'Abre la caja y empieza a cobrar.'],
]

const BUSINESSES: [string, string][] = [
  ['storefront', 'Tiendas y minimercados'],
  ['local_pharmacy', 'Droguerías'],
  ['restaurant', 'Restaurantes y bares'],
  ['hardware', 'Ferreterías'],
  ['checkroom', 'Ropa y calzado'],
  ['bakery_dining', 'Panaderías'],
  ['content_cut', 'Peluquerías y barberías'],
  ['local_shipping', 'Distribuidoras'],
]

const EXTRAS = [
  'Tienda en línea con QR',
  'Fiado con cupo y abonos',
  'Cotizaciones y remisiones',
  'Proveedores y compras',
  'Promociones y combos',
  'Copias de seguridad diarias',
]

/**
 * Marco de las pantallas públicas: panel azul con lo que ofrece el POS + formulario.
 * `trial` muestra arriba la oferta de los 15 días gratis (pantalla de registro).
 */
export default function AuthLayout({ title, subtitle, trial, children }: { title: string; subtitle?: string; trial?: boolean; children: ReactNode }) {
  // La etiqueta de Google Ads vive solo en las páginas públicas de acceso, no dentro de la app.
  useEffect(loadGoogleTag, [])
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[1.1fr_1fr]">
      <section
        className={`relative hidden overflow-hidden bg-navy px-12 py-10 text-white lg:flex lg:flex-col lg:justify-between ${
          trial ? '' : 'lg:sticky lg:top-0 lg:h-dvh lg:self-start'
        }`}
      >
        <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-primary/30 blur-3xl" />
        <div className="absolute -bottom-32 -left-20 h-80 w-80 rounded-full bg-primary/20 blur-3xl" />
        <div className="relative flex items-center gap-3">
          <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-10 w-10" />
          <span className="text-xl font-bold tracking-tight">{APP_NAME}</span>
        </div>
        <div className="relative max-w-xl py-6">
          {trial && (
            <span className="mb-5 inline-flex items-center gap-2 rounded-full bg-amber-400 px-4 py-1.5 text-sm font-bold text-navy shadow-lg shadow-amber-400/20">
              <Icon name="redeem" fill className="text-[18px]" /> 15 días gratis · sin tarjeta
            </span>
          )}
          <h2 className="text-4xl leading-tight font-bold tracking-tight">
            Vende rápido. <span className="text-sky-300">Controla todo.</span>
          </h2>
          <p className="mt-3 text-base text-slate-300">
            {trial
              ? 'Empieza hoy tu tienda con todo lo que trae Ecomerxio POS:'
              : 'Caja, inventario inteligente, compras, pagos digitales y reportes para tu tienda, en el celular o en el computador.'}
          </p>
          <ul className="mt-6 grid grid-cols-2 gap-3">
            {FEATURES.map(([icon, name, text]) => (
              <li key={name} className="rounded-2xl border border-white/10 bg-white/[0.06] p-4 backdrop-blur-sm transition hover:bg-white/10">
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-sky-400 to-primary shadow-lg shadow-primary/30">
                  <Icon name={icon} className="text-[22px]" />
                </span>
                <p className="mt-3 text-sm font-semibold">{name}</p>
                <p className="mt-1 text-xs leading-relaxed text-slate-300">{text}</p>
              </li>
            ))}
          </ul>
          {trial && <TrialExtras />}
        </div>
        <p className="relative text-xs text-slate-400">© {new Date().getFullYear()} {APP_NAME}</p>
      </section>
      <section className={`flex min-h-dvh flex-col justify-center px-5 py-10 sm:px-10 ${trial ? 'lg:justify-start lg:py-16' : ''}`}>
        <div className="mx-auto w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-9 w-9" />
            <span className="text-lg font-bold tracking-tight">{APP_NAME}</span>
          </div>
          <h1 className="text-[28px] leading-tight font-bold tracking-tight">{title}</h1>
          {subtitle && <p className="mt-1.5 text-sm text-muted">{subtitle}</p>}
          <div className="mt-7">{children}</div>
          <nav className="mt-8 flex flex-wrap justify-center gap-x-3 gap-y-1 text-xs text-muted" aria-label="Documentos legales">
            <Link to="/legal/terminos" className="hover:text-primary">Términos</Link>
            <Link to="/legal/licencia" className="hover:text-primary">Licencia</Link>
            <Link to="/legal/servicio-y-responsabilidad" className="hover:text-primary">Servicio</Link>
            <Link to="/legal/privacidad" className="hover:text-primary">Privacidad</Link>
          </nav>
        </div>
      </section>
    </div>
  )
}

/** Pasos, tipos de negocio y lo demás que incluye: llenan el panel a lo largo del registro. */
function TrialExtras() {
  return (
    <div className="mt-14 space-y-14">
      <div>
        <h3 className="text-xl font-bold tracking-tight">Empiezas en 3 pasos</h3>
        <ol className="mt-5 space-y-4">
          {STEPS.map(([name, text], i) => (
            <li key={name} className="flex items-start gap-4">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber-400 text-sm font-bold text-navy">{i + 1}</span>
              <div>
                <p className="font-semibold">{name}</p>
                <p className="text-sm text-slate-300">{text}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
      <div>
        <h3 className="text-xl font-bold tracking-tight">Se adapta a tu negocio</h3>
        <p className="mt-1 text-sm text-slate-300">Cada tipo de negocio ve solo los módulos que necesita.</p>
        <div className="mt-5 grid grid-cols-2 gap-2">
          {BUSINESSES.map(([icon, text]) => (
            <span key={text} className="flex items-center gap-2.5 rounded-xl bg-white/[0.06] px-3 py-2.5 text-sm">
              <Icon name={icon} className="text-[20px] text-sky-300" /> {text}
            </span>
          ))}
        </div>
        <p className="mt-2 text-xs text-slate-400">Y más: papelerías, licoreras, veterinarias, talleres, ópticas, gimnasios…</p>
      </div>
      <div className="rounded-2xl border border-white/10 bg-gradient-to-br from-primary/30 to-white/[0.04] p-6">
        <h3 className="text-xl font-bold tracking-tight">Y mucho más incluido</h3>
        <ul className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3.5 text-sm text-slate-200">
          {EXTRAS.map((text) => (
            <li key={text} className="flex items-start gap-2">
              <Icon name="check_circle" fill className="mt-px text-[18px] text-emerald-400" /> {text}
            </li>
          ))}
        </ul>
        <p className="mt-4 text-xs text-slate-400">Algunas funciones dependen del plan que elijas.</p>
      </div>
    </div>
  )
}
