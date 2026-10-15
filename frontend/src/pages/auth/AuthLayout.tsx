import type { ReactNode } from 'react'
import { Icon } from '../../components/ui'

/** Marco de las pantallas públicas: panel azul con la promesa del producto + formulario. */
export default function AuthLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[1.1fr_1fr]">
      <section className="relative hidden overflow-hidden bg-navy p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-primary/30 blur-3xl" />
        <div className="relative flex items-center gap-3">
          <img src="/icon.svg" alt="" className="h-10 w-10" />
          <span className="text-xl font-bold tracking-tight">TiendaPOSS</span>
        </div>
        <div className="relative max-w-md">
          <h2 className="text-4xl leading-tight font-bold tracking-tight">Vende rápido. Controla todo.</h2>
          <p className="mt-4 text-base text-slate-300">
            Caja, inventario inteligente, compras, pagos digitales y reportes para tu tienda, en el celular o en el computador.
          </p>
          <ul className="mt-8 space-y-3 text-sm text-slate-200">
            {[
              ['bolt', 'Cobra en segundos con código de barras'],
              ['inventory', 'Sabe qué comprar antes de que se agote'],
              ['verified_user', 'Cada empleado con sus permisos'],
            ].map(([icon, text]) => (
              <li key={text} className="flex items-center gap-3">
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/10">
                  <Icon name={icon!} className="text-[18px]" />
                </span>
                {text}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-slate-400">© {new Date().getFullYear()} TiendaPOSS</p>
      </section>
      <section className="flex min-h-dvh flex-col justify-center px-5 py-10 sm:px-10">
        <div className="mx-auto w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <img src="/icon.svg" alt="" className="h-9 w-9" />
            <span className="text-lg font-bold tracking-tight">TiendaPOSS</span>
          </div>
          <h1 className="text-[28px] leading-tight font-bold tracking-tight">{title}</h1>
          {subtitle && <p className="mt-1.5 text-sm text-muted">{subtitle}</p>}
          <div className="mt-7">{children}</div>
        </div>
      </section>
    </div>
  )
}
