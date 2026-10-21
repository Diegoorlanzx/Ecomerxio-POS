import { Suspense, useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, QUEUED_EVENT, QUEUED_MESSAGE, STALE_EVENT } from '../lib/api'
import { ageLabel } from '../lib/offline/cache'
import { useAuth } from '../lib/auth'
import { exportQueue, pendingCount, syncPending, useOnline, usePendingSales } from '../lib/offline/queue'
import { kvDelete, kvKeys } from '../lib/offline/idb'
import { storageLow, useStorageInfo } from '../lib/offline/storage'
import { initials } from '../lib/format'
import AlertsBell from './AlertsBell'
import OrdersButton from './OrdersButton'
import { Button, Icon, Modal, Spinner, useToast } from './ui'
import { useGuide } from './guide'
import { APP_NAME } from '../lib/brand'

export interface NavItem {
  to: string
  label: string
  icon: string
  show: (can: (p: string) => boolean) => boolean
  /** Solo si la tienda usa este módulo. */
  module?: string
}

/** Las 4 pestañas del diseño + "Más" para el resto de módulos. */
export const MAIN_NAV: NavItem[] = [
  { to: '/pos', label: 'POS', icon: 'point_of_sale', show: (can) => can('POS_VENDER') },
  { to: '/mesas', label: 'Mesas', icon: 'table_restaurant', show: (can) => can('POS_VENDER'), module: 'RESTAURANTE' },
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
  '/asistente': 'Asistente',
  '/recomendaciones': 'Recomendaciones',
  '/pedidos': 'Pedidos en línea',
  '/tienda-online': 'Tienda en línea',
  '/negocio': 'Tipo de negocio',
  '/garantias': 'Garantías',
  '/etiquetas-bascula': 'Etiquetas de báscula',
  '/guia': 'Guía interactiva',
  '/facturacion': 'Facturación electrónica',
  '/contabilidad': 'Contabilidad',
  '/pendientes': 'Pendientes por enviar',
  '/mesas': 'Mesas',
  '/mesas/configurar': 'Configurar restaurante',
  '/cocina': 'Pantalla de cocina',
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
  const { me, can, has, signOut } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const [logoutPending, setLogoutPending] = useState(0)
  const guide = useGuide()
  const menuRef = useRef<HTMLDivElement>(null)
  const nav = MAIN_NAV.filter((n) => n.show(can) && (!n.module || has(n.module)))
  const online = useOnline()
  const pending = usePendingSales(me?.tenant.id)
  const storage = useStorageInfo()
  const toastAll = useToast()
  // Pantalla mostrada desde la copia guardada (sin internet): se avisa con la hora de la copia; se borra al navegar.
  const [stale, setStale] = useState<string | null>(null)
  useEffect(() => setStale(null), [location.pathname])
  useEffect(() => {
    const onStale = (e: Event) => setStale((e as CustomEvent<{ savedAt: string }>).detail.savedAt)
    const onQueued = () => toastAll(QUEUED_MESSAGE)
    window.addEventListener(STALE_EVENT, onStale)
    window.addEventListener(QUEUED_EVENT, onQueued)
    return () => {
      window.removeEventListener(STALE_EVENT, onStale)
      window.removeEventListener(QUEUED_EVENT, onQueued)
    }
  }, [toastAll])
  // Descarga previa: con internet, cada 30 min se guardan en el equipo las consultas de las pantallas principales,
  // para que sin internet tengan su copia aunque no se hayan abierto hoy. (Un 403 por permisos se ignora.)
  const prefetchTenant = me?.tenant.id
  useEffect(() => {
    if (!prefetchTenant || !online) return
    const paths = ['/categories', '/settings/pos', '/registers', '/credit', '/suppliers', '/finance/categories', '/finance/payables', '/purchases/suggestions', '/customers']
    const run = () => paths.forEach((p) => void api(p).catch(() => undefined))
    const first = setTimeout(run, 8000)
    const id = setInterval(run, 30 * 60_000)
    return () => {
      clearTimeout(first)
      clearInterval(id)
    }
  }, [prefetchTenant, online])
  // El buzón se vacía desde cualquier pantalla (no solo desde la caja): al volver la red y cada 20 s mientras quede algo.
  const tenantId = me?.tenant.id
  useEffect(() => {
    if (!tenantId || !online) return
    void syncPending(tenantId)
    if (pending.allPending === 0) return
    const id = setInterval(() => void syncPending(tenantId), 20_000)
    return () => clearInterval(id)
  }, [tenantId, online, pending.allPending])
  const [shiftLabel, setShiftLabel] = useState<string | null>(null)
  const canCash = can('CAJA_OPERAR')
  useEffect(() => {
    if (!canCash || !online) return
    api<{ number: number } | undefined>('/shifts/current')
      .then((s) => setShiftLabel(s ? `Caja abierta (Turno #${s.number})` : null))
      .catch(() => {})
  }, [canCash, online, location.pathname])
  const base = '/' + (location.pathname.split('/')[1] ?? '')
  const title = TITLES[base] ?? APP_NAME

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
            <div className="text-[11px] font-medium text-muted">{APP_NAME}</div>
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
        <div className="border-t border-line-soft p-3 text-[11px] text-muted">Versión 0.10 · Fase 10</div>
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
                <Link to="/pendientes" className="flex items-center gap-1.5">
                  <span className="h-2 w-2 shrink-0 rounded-full bg-warning" />
                  <span className="truncate rounded-full bg-warning-soft px-2 py-0.5 text-[10px] font-bold text-warning-ink">
                    Sin internet{pending.allPending ? ` · ${pending.allPending} por enviar` : ''}
                  </span>
                </Link>
              ) : pending.allPending > 0 ? (
                <Link to="/pendientes" className="flex items-center gap-1.5">
                  <span className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-warning" />
                  <span className="truncate rounded-full bg-warning-soft px-2 py-0.5 text-[10px] font-bold text-warning-ink">
                    {pending.syncing ? 'Enviando' : 'Por enviar'} · {pending.allPending}
                  </span>
                </Link>
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
            {has('TIENDA_ONLINE') && can('POS_VENDER') && <OrdersButton online={online} />}
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
                    onClick={() => (setMenuOpen(false), guide?.openPanel())}
                    className="flex h-11 w-full items-center gap-3 px-4 text-sm hover:bg-canvas"
                  >
                    <Icon name="school" className="text-[20px] text-muted" /> Guía interactiva
                  </button>
                  <button
                    onClick={async () => {
                      setMenuOpen(false)
                      const n = await pendingCount(me.tenant.id)
                      if (n > 0) return setLogoutPending(n)
                      await clearLocalCopies(me.tenant.id)
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
        <Suspense fallback={<Spinner />}>
          {guide?.state.practice && (
            <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl border-2 border-dashed border-warning bg-warning-soft px-3.5 py-2.5 text-sm font-semibold text-warning-ink">
              <Icon name="school" className="text-[20px]" />
              <span className="min-w-0 flex-1">Modo práctica: vende y cobra para aprender. Nada se guarda ni descuenta inventario.</span>
              <button type="button" onClick={() => guide.update({ practice: false })} className="rounded-lg bg-warning-ink px-3 py-1.5 text-xs text-white">
                Salir del modo práctica
              </button>
            </div>
          )}
          {stale && (
            <div className="mb-3 flex items-start gap-2 rounded-xl border border-warning/40 bg-warning-soft px-3.5 py-3 text-sm font-medium text-warning-ink">
              <Icon name="cloud_off" className="text-[20px]" />
              <span>Sin internet: estás viendo los datos guardados {ageLabel(stale)}. Lo que registres se enviará al volver la conexión.</span>
            </div>
          )}
          {storageLow(storage) && (
            <div className="mb-3 flex items-start gap-2 rounded-xl border border-warning/40 bg-warning-soft px-3.5 py-3 text-sm font-medium text-warning-ink">
              <Icon name="sd_card_alert" className="text-[20px]" />
              <span>
                Este equipo se está quedando sin espacio para la app ({Math.round((storage!.usage / storage!.quota) * 100)} %). Libera espacio en el
                disco para no perder ventas sin enviar.
              </span>
            </div>
          )}
          <Outlet />
        </Suspense>
      </main>
      {logoutPending > 0 && (
        <LogoutGuard
          tenantId={me.tenant.id}
          count={logoutPending}
          onClose={() => setLogoutPending(0)}
          onLogout={async () => {
            setLogoutPending(0)
            await signOut()
            navigate('/login')
          }}
        />
      )}

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

/** Borra del equipo las copias de consulta y el carrito de la tienda (nunca el buzón de ventas sin enviar). */
async function clearLocalCopies(tenantId: number) {
  const mine = (k: string) =>
    k === `pos-data-${tenantId}` || k === `idmap-${tenantId}` || [`tp_cart_${tenantId}_`, `shift-${tenantId}-`, `cache:${tenantId}:`].some((p) => k.startsWith(p))
  for (const k of await kvKeys()) if (mine(k)) await kvDelete(k)
  try {
    for (const k of Object.keys(localStorage)) if (k.startsWith(`tp_cart_${tenantId}_`)) localStorage.removeItem(k)
  } catch {
    /* sin almacenamiento */
  }
}

/** Al cerrar sesión con ventas que no han llegado a la nube: enviarlas, respaldarlas o salir sabiendo que quedan aquí. */
function LogoutGuard({ tenantId, count, onClose, onLogout }: { tenantId: number; count: number; onClose: () => void; onLogout: () => void }) {
  const toast = useToast()
  const online = useOnline()
  const [left, setLeft] = useState(count)
  const [busy, setBusy] = useState(false)

  async function sendNow() {
    setBusy(true)
    try {
      await syncPending(tenantId)
      const n = await pendingCount(tenantId)
      setLeft(n)
      if (n === 0) {
        toast('Todo enviado')
        await clearLocalCopies(tenantId)
        onLogout()
      } else toast(`Quedan ${n} sin enviar`, 'danger')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title="Hay ventas sin enviar"
      onClose={onClose}
      footer={
        <div className="flex w-full flex-col gap-2">
          {online && (
            <Button className="w-full" icon="cloud_upload" loading={busy} onClick={sendNow}>
              Enviar ahora y cerrar sesión
            </Button>
          )}
          <Button className="w-full" variant="secondary" icon="download" onClick={async () => toast(`${await exportQueue(tenantId)} ventas en el archivo`)}>
            Descargar respaldo
          </Button>
          <Button className="w-full" variant="ghost" onClick={onLogout}>
            Cerrar sesión de todos modos
          </Button>
        </div>
      }
    >
      <div className="space-y-2 text-sm">
        <p>
          <b>{left}</b> venta(s) de este equipo todavía no llegan a la nube.
        </p>
        <p className="text-muted">
          Si cierras sesión <b>no se pierden</b>: quedan guardadas en este equipo y se envían solas cuando alguien de la tienda vuelva a entrar
          con internet. No borres los datos del navegador ni cambies de equipo hasta que se envíen.
        </p>
      </div>
    </Modal>
  )
}
