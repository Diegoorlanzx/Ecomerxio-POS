import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import type { AlertItem } from '../lib/purchasingTypes'
import { Icon } from './ui'

/** Campana del encabezado: alertas de inventario y compras (se refresca cada 2 minutos). */
export default function AlertsBell({ online }: { online: boolean }) {
  const { canAny } = useAuth()
  const location = useLocation()
  const allowed = canAny('INVENTARIO_VER', 'ALERTAS_VER', 'COMPRAS_VER')
  const [alerts, setAlerts] = useState<AlertItem[]>([])
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!allowed || !online) return
    const load = () => api<AlertItem[]>('/inventory/alerts').then(setAlerts).catch(() => {})
    load()
    const t = setInterval(load, 120000)
    return () => clearInterval(t)
  }, [allowed, online])

  useEffect(() => setOpen(false), [location.pathname])
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const critical = alerts.filter((a) => a.severity !== 'MEDIA').length
  const link = (a: AlertItem) =>
    a.type.startsWith('PEDIDO') && a.referenceId ? `/compras/${a.referenceId}` : a.type === 'VENCIDO' || a.type === 'POR_VENCER' ? '/inventario/vencimientos' : '/inventario/inteligente'

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`Notificaciones${alerts.length ? ` (${alerts.length})` : ''}`}
        className="relative flex h-11 w-11 items-center justify-center rounded-xl text-muted hover:bg-line-soft hover:text-ink"
      >
        <Icon name="notifications" className="text-[22px]" />
        {alerts.length > 0 && (
          <span className={`num absolute top-1.5 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] font-bold text-white ${critical ? 'bg-danger' : 'bg-primary'}`}>
            {alerts.length > 99 ? '99+' : alerts.length}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-80 max-w-[calc(100vw-2rem)] overflow-hidden rounded-2xl border border-line bg-card shadow-tier3">
          <div className="border-b border-line-soft px-4 py-3 text-sm font-semibold">Alertas del negocio</div>
          {alerts.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-muted">Todo en orden por ahora.</p>
          ) : (
            <ul className="max-h-96 divide-y divide-line-soft overflow-y-auto">
              {alerts.slice(0, 40).map((a, i) => (
                <li key={i}>
                  <Link to={link(a)} className="flex gap-2 px-4 py-2.5 hover:bg-canvas">
                    <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${a.severity === 'CRITICA' ? 'bg-danger' : a.severity === 'ALTA' ? 'bg-warning' : 'bg-primary'}`} />
                    <span className="text-sm">
                      <span className="block font-semibold">{a.title}</span>
                      <span className="text-xs text-muted">{a.detail}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
