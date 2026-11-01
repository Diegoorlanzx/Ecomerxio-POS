import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icon } from '../../components/ui'
import { APP_NAME } from '../../lib/brand'
import { asset } from '../../lib/host'

/** Lo más llamativo del POS, en tarjetas del panel azul. */
const FEATURES: [string, string, string][] = [
  ['bolt', 'Cobra en segundos', 'Con lector de código de barras, en el celular o el computador.'],
  ['cloud_off', 'Vende sin internet', 'Si se cae la conexión sigues vendiendo y todo se envía solo al volver.'],
  ['inventory', 'Inventario inteligente', 'Se descuenta con cada venta y te avisa qué comprar antes de que se agote.'],
  ['qr_code_2', 'Cobra como quieras', 'Efectivo, datáfono o QR, y el recibo por WhatsApp.'],
  ['verified_user', 'Tu equipo, con control', 'Cada empleado con sus permisos, turnos y cierres de caja.'],
  ['tune', 'Hecho para tu negocio', 'Tienda, droguería, restaurante, ferretería… solo ves lo que usas.'],
]

const HIGHLIGHTS: [string, string][] = [
  ['category', '+20 tipos de negocio'],
  ['devices', 'Celular, tablet o PC'],
  ['insights', 'Reportes al instante'],
]

/**
 * Marco de las pantallas públicas: panel azul con lo que ofrece el POS + formulario.
 * `trial` muestra arriba la oferta de los 15 días gratis (pantalla de registro).
 */
export default function AuthLayout({ title, subtitle, trial, children }: { title: string; subtitle?: string; trial?: boolean; children: ReactNode }) {
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[1.1fr_1fr]">
      <section className="relative hidden overflow-hidden bg-navy px-12 py-10 text-white lg:sticky lg:top-0 lg:flex lg:h-dvh lg:flex-col lg:justify-between lg:self-start">
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
          <div className="mt-5 flex flex-wrap gap-2">
            {HIGHLIGHTS.map(([icon, text]) => (
              <span key={text} className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-slate-200">
                <Icon name={icon} className="text-[16px] text-sky-300" /> {text}
              </span>
            ))}
          </div>
        </div>
        <p className="relative text-xs text-slate-400">© {new Date().getFullYear()} {APP_NAME}</p>
      </section>
      <section className="flex min-h-dvh flex-col justify-center px-5 py-10 sm:px-10">
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
