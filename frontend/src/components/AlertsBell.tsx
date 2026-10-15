import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { relative } from '../lib/format'
import { SEVERITY_DOT, type Inbox, type NotificationItem } from '../lib/notificationTypes'
import { Icon } from './ui'

/** Campana del encabezado: notificaciones del negocio (cada usuario ve lo que su rol puede atender). */
export default function AlertsBell({ online }: { online: boolean }) {
  const { me } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [unread, setUnread] = useState(0)
  const [items, setItems] = useState<NotificationItem[] | null>(null)
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const loadCount = useCallback(() => {
    api<{ unread: number }>('/notifications/unread-count')
      .then((r) => setUnread(r.unread))
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!me || !online) return
    loadCount()
    const t = setInterval(loadCount, 60000)
    return () => clearInterval(t)
  }, [me, online, loadCount])

  useEffect(() => {
    if (!open) return
    setItems(null)
    api<Inbox>('/notifications?limit=15')
      .then((r) => (setItems(r.items), setUnread(r.unread)))
      .catch(() => setItems([]))
  }, [open])

  useEffect(() => setOpen(false), [location.pathname])
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  async function openItem(n: NotificationItem) {
    if (!n.read) {
      await api(`/notifications/${n.id}/read`, { method: 'POST' }).catch(() => {})
      setUnread((u) => Math.max(0, u - 1))
    }
    setOpen(false)
    navigate(n.link ?? '/notificaciones')
  }

  async function readAll() {
    await api('/notifications/read-all', { method: 'POST' }).catch(() => {})
    setUnread(0)
    setItems((list) => list?.map((x) => ({ ...x, read: true })) ?? null)
  }

  const critical = items?.some((n) => !n.read && (n.severity === 'CRITICA' || n.severity === 'ALTA'))

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`Notificaciones${unread ? ` (${unread} sin leer)` : ''}`}
        className="relative flex h-11 w-11 items-center justify-center rounded-xl text-muted hover:bg-line-soft hover:text-ink"
      >
        <Icon name="notifications" className="text-[22px]" />
        {unread > 0 && (
          <span className={`num absolute top-1.5 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] font-bold text-white ${critical === false ? 'bg-primary' : 'bg-danger'}`}>
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 z-50 mt-2 w-[22rem] max-w-[calc(100vw-2rem)] overflow-hidden rounded-2xl border border-line bg-card shadow-tier3">
          <div className="flex items-center justify-between border-b border-line-soft px-4 py-3">
            <span className="text-sm font-semibold">Notificaciones</span>
            {unread > 0 && (
              <button type="button" onClick={readAll} className="text-xs font-semibold text-primary">
                Marcar todo como leído
              </button>
            )}
          </div>
          {items === null ? (
            <p className="px-4 py-6 text-center text-sm text-muted">Cargando…</p>
          ) : items.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-muted">Todo en orden por ahora.</p>
          ) : (
            <ul className="max-h-[26rem] divide-y divide-line-soft overflow-y-auto">
              {items.map((n) => (
                <li key={n.id}>
                  <button type="button" onClick={() => openItem(n)} className={`flex w-full gap-2.5 px-4 py-2.5 text-left hover:bg-canvas ${n.read ? 'opacity-70' : ''}`}>
                    <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${n.read ? 'bg-transparent' : SEVERITY_DOT[n.severity]}`} />
                    <span className="min-w-0 text-sm">
                      <span className={`block ${n.read ? '' : 'font-semibold'}`}>{n.title}</span>
                      {n.body && <span className="line-clamp-2 text-xs text-muted">{n.body}</span>}
                      <span className="text-[11px] text-muted">
                        {n.typeLabel} · {relative(n.createdAt)}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <Link to="/notificaciones" className="block border-t border-line-soft px-4 py-2.5 text-center text-sm font-semibold text-primary hover:bg-canvas">
            Ver todas
          </Link>
        </div>
      )}
    </div>
  )
}
