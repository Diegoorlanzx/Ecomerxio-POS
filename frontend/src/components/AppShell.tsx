import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useOnline, usePendingSales } from '../lib/offline/queue'
import { initials } from '../lib/format'
import AlertsBell from './AlertsBell'
import { Icon } from './ui'

export interface NavItem {
  to: string
  label: string
  icon: string
  show: (can: (p: string) => boolean) => boolean
}

/** Las 4 pestañas del diseño + "Más" para el resto de módulos. */
export const MAIN_NAV: NavItem[] = [
  { to: '/pos', label: 'POS', icon: 'point_of_sale', show: (can) => can('POS_VENDER') },
  { to: '/dashboard', label: 'Dashboard', icon: 'space_dashboard', show: (can) => can('DASHBOARD_VER') },
  { to: '/caja', label: 'Caja', icon: 'payments', show: (can) => can('CAJA_OPERAR') || can('CAJA_SUPERVISAR') },
  { to: '/inventario', label: 'Inventario', icon: 'inventory_2', show: (can) => can('INVENTARIO_VER') || can('PRODUCTOS_VER') },
  { to: '/mas', label: 'Más', icon: 'apps', show: () => true },
]

const TITLES: Record<string, string> = {
  '/pos': 'Punto de venta',
  '/dashboard': 'Dashboard',
  '/caja': 'Caja',
  '/inventario': 'Inventario',
  '/mas': 'Más',
  '/usuarios': 'Usuarios',
  '/roles': 'Roles y permisos',
  '/actividad': 'Actividad',
  '/configuracion': 'Configuración',
  '/perfil': 'Mi cuenta',
  '/promociones': 'Promociones',
  '/movimientos': 'Movimientos',
  '/ventas': 'Ventas',
  '/ajustes-pos': 'Ajustes de caja y venta',
  '/proveedores': 'Proveedores',
  '/compras': 'Compras',
  '/pagos': 'Pagos digitales',
  '/fiados': 'Fiados',
  '/finanzas': 'Finanzas',
  '/analitica': 'Analítica',
  '/rentabilidad': 'Rentabilidad',
  '/notificaciones': 'Notificaciones',
  '/respaldos': 'Copias de seguridad',
}

export function StoreLogo({ logo, name, size = 36 }: { logo?: string; name: string; size?: number }) {
  if (logo) {
    return <img src={logo} alt={name} style={{ height: size, width: size }} className="rounded-xl object-contain" />
  }
  return (
    <div style={{ height: size, width: size }} className="flex shrink-0 items-center justify-center rounded-xl bg-primary text-white">
      <Icon name="storefront" className="text-[20px]" />
    </div>
  )
}

export default function AppShell() {
  const { me, can, signOut } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const nav = MAIN_NAV.filter((n) => n.show(can))
  const online = useOnline()
  const pending = usePendingSales(me?.tenant.id)
  const [shiftLabel, setShiftLabel] = useState<string | null>(null)
  const canCash = can('CAJA_OPERAR')
  useEffect(() => {
    if (!canCash || !online) return
    api<{ number: number } | undefined>('/shifts/current')
      .then((s) => setShiftLabel(s ? `Caja abierta (Turno #${s.number})` : null))
      .catch(() => {})
  }, [canCash, online, location.pathname])
  const base = '/' + (location.pathname.split('/')[1] ?? '')
  const title = TITLES[base] ?? 'TiendaPOSS'

  useEffect(() => {
    if (!menuOpen) return
    const close = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [menuOpen])

  useEffect(() => setMenuOpen(false), [location.pathname])

  if (!me) return null

  return (
    <div className="min-h-dvh lg:pl-64 print:pl-0">
      {/* Riel lateral en tablet/PC */}
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 flex-col border-r border-line bg-card lg:flex print:hidden">
        <div className="flex h-16 items-center gap-3 border-b border-line-soft px-5">
          <StoreLogo logo={me.tenant.logo} name={me.tenant.name} />
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold">{me.tenant.name}</div>
            <div className="text-[11px] font-medium text-muted">TiendaPOSS</div>
          </div>
        </div>
        <nav className="flex-1 space-y-1 p-3">
          {nav.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) =>
                `flex h-11 items-center gap-3 rounded-xl px-3 text-sm font-semibold transition ${
                  isActive ? 'bg-primary text-white shadow-tier1' : 'text-muted hover:bg-line-soft hover:text-ink'
                }`
              }
            >
              <Icon name={n.icon} className="text-[22px]" />
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-line-soft p-3 text-[11px] text-muted">Versión 0.6 · Fase 5</div>
      </aside>

      {/* Encabezado */}
      <header className="pt-safe sticky top-0 z-30 print:hidden bg-canvas/85 shadow-[0_1px_8px_rgba(0,0,0,0.04)] backdrop-blur-xl">
        <div className="flex h-16 items-center justify-between gap-2 px-4 lg:px-6">
          <div className="flex min-w-0 items-center gap-2.5">
            <div className="lg:hidden">
              <StoreLogo logo={me.tenant.logo} name={me.tenant.name} size={32} />
            </div>
            <div className="min-w-0">
              {!online ? (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 shrink-0 rounded-full bg-warning" />
                  <span className="truncate rounded-full bg-warning-soft px-2 py-0.5 text-[10px] font-bold text-warning-ink">
                    Sin internet{pending.pending ? ` · ${pending.pending} por enviar` : ''}
                  </span>
                </div>
              ) : shiftLabel ? (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-success" />
                  <span className="truncate rounded-full bg-success-soft px-2 py-0.5 text-[10px] font-bold text-success-ink">{shiftLabel}</span>
                </div>
              ) : (
                <div className="truncate text-[11px] font-semibold text-muted lg:hidden">{me.tenant.name}</div>
              )}
              <h1 className="truncate text-base font-semibold lg:text-lg">{title}</h1>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            <AlertsBell online={online} />
            <div className="relative" ref={menuRef}>
              <button
                onClick={() => setMenuOpen((o) => !o)}
                aria-label="Menú de usuario"
                aria-expanded={menuOpen}
                className="flex items-center gap-2 rounded-xl py-1 pr-1 pl-1 hover:bg-line-soft"
              >
                <div className="flex h-9 w-9 items-center justify-center rounded-full bg-navy text-xs font-bold text-white">
                  {initials(me.fullName)}
                </div>
                <div className="hidden text-left leading-tight sm:block">
                  <div className="max-w-36 truncate text-xs font-semibold">{me.fullName}</div>
                  <div className="max-w-36 truncate text-[10px] text-muted">{me.roles.join(', ')}</div>
                </div>
              </button>
              {menuOpen && (
                <div className="absolute right-0 mt-2 w-64 overflow-hidden rounded-2xl border border-line bg-card shadow-tier3">
                  <div className="border-b border-line-soft px-4 py-3">
                    <div className="truncate text-sm font-semibold">{me.fullName}</div>
                    <div className="truncate text-xs text-muted">{me.email}</div>
                  </div>
                  <Link to="/perfil" className="flex h-11 items-center gap-3 px-4 text-sm hover:bg-canvas">
                    <Icon name="manage_accounts" className="text-[20px] text-muted" /> Mi cuenta
                  </Link>
                  <button
                    onClick={async () => {
                      await signOut()
                      navigate('/login')
                    }}
                    className="flex h-11 w-full items-center gap-3 px-4 text-sm text-danger-ink hover:bg-danger-soft"
                  >
                    <Icon name="logout" className="text-[20px]" /> Cerrar sesión
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-6xl px-4 pt-3 pb-28 lg:px-6 lg:pb-10">
        <Outlet />
      </main>

      {/* Barra inferior en celular */}
      <nav className="pb-safe fixed inset-x-0 bottom-0 z-40 border-t border-line bg-card/95 backdrop-blur-xl lg:hidden print:hidden">
        <div className="mx-auto flex h-16 max-w-xl items-stretch">
          {nav.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) =>
                `flex flex-1 flex-col items-center justify-center gap-0.5 text-[11px] font-semibold transition ${
                  isActive ? 'text-primary' : 'text-muted'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <Icon name={n.icon} fill={isActive} className="text-[24px]" />
                  {n.label}
                </>
              )}
            </NavLink>
          ))}
        </div>
      </nav>
    </div>
  )
}
