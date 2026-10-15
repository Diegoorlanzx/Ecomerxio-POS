import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Icon, Spinner } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { dateTime } from '../../lib/format'
import type { AuditPage, UserDto } from '../../lib/types'

const ACTION_LABELS: Record<string, { label: string; icon: string }> = {
  LOGIN: { label: 'Inicio de sesión', icon: 'login' },
  LOGOUT: { label: 'Cierre de sesión', icon: 'logout' },
  LOGOUT_TODOS: { label: 'Cerró todas las sesiones', icon: 'devices' },
  LOGIN_FALLIDO: { label: 'Intento fallido', icon: 'gpp_bad' },
  CUENTA_BLOQUEADA: { label: 'Cuenta bloqueada', icon: 'lock' },
  CLAVE_RECUPERACION: { label: 'Pidió recuperar clave', icon: 'mail' },
  CLAVE_RESTABLECIDA: { label: 'Restableció su clave', icon: 'key' },
  CLAVE_CAMBIADA: { label: 'Cambió su clave', icon: 'key' },
  CLAVE_ASIGNADA: { label: 'Asignó una clave', icon: 'key' },
  TIENDA_CREADA: { label: 'Tienda creada', icon: 'storefront' },
  USUARIO_CREADO: { label: 'Usuario creado', icon: 'person_add' },
  USUARIO_EDITADO: { label: 'Usuario editado', icon: 'edit' },
  USUARIO_ACTIVADO: { label: 'Usuario activado', icon: 'person_check' },
  USUARIO_DESACTIVADO: { label: 'Usuario desactivado', icon: 'person_off' },
  USUARIO_DESBLOQUEADO: { label: 'Usuario desbloqueado', icon: 'lock_open' },
  ROLES_USUARIO: { label: 'Cambio de roles', icon: 'badge' },
  ROL_CREADO: { label: 'Rol creado', icon: 'add_moderator' },
  ROL_EDITADO: { label: 'Rol editado', icon: 'edit' },
  ROL_ELIMINADO: { label: 'Rol eliminado', icon: 'remove_moderator' },
  PERMISOS_ROL: { label: 'Cambio de permisos', icon: 'admin_panel_settings' },
  CONFIG_TIENDA: { label: 'Configuración', icon: 'settings' },
}

export default function ActivityPage() {
  const [users, setUsers] = useState<UserDto[]>([])
  const [userId, setUserId] = useState('')
  const [action, setAction] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sensitiveOnly, setSensitiveOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [data, setData] = useState<AuditPage | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<UserDto[]>('/users').then(setUsers).catch(() => {})
  }, [])

  const load = useCallback(async () => {
    const q = new URLSearchParams({ page: String(page), size: '40', sensitiveOnly: String(sensitiveOnly) })
    if (userId) q.set('userId', userId)
    if (action) q.set('action', action)
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    try {
      setData(await api<AuditPage>(`/audit?${q}`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cargar')
    }
  }, [page, sensitiveOnly, userId, action, from, to])

  useEffect(() => {
    load()
  }, [load])

  const select = 'h-11 rounded-xl border border-line bg-card px-3 text-sm outline-none focus:border-primary'

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Actividad y accesos</h2>
        <p className="text-sm text-muted">Quién hizo qué y cuándo. Las operaciones sensibles se marcan en rojo.</p>
      </div>
      <Card className="grid grid-cols-2 gap-2 p-3 lg:grid-cols-5">
        <select aria-label="Empleado" className={`${select} col-span-2 lg:col-span-1`} value={userId} onChange={(e) => (setPage(0), setUserId(e.target.value))}>
          <option value="">Todos los empleados</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>
              {u.fullName}
            </option>
          ))}
        </select>
        <select aria-label="Tipo" className={`${select} col-span-2 lg:col-span-1`} value={action} onChange={(e) => (setPage(0), setAction(e.target.value))}>
          <option value="">Todas las acciones</option>
          {Object.entries(ACTION_LABELS).map(([k, v]) => (
            <option key={k} value={k}>
              {v.label}
            </option>
          ))}
        </select>
        <input aria-label="Desde" type="date" className={select} value={from} onChange={(e) => (setPage(0), setFrom(e.target.value))} />
        <input aria-label="Hasta" type="date" className={select} value={to} onChange={(e) => (setPage(0), setTo(e.target.value))} />
        <label className="col-span-2 flex h-11 items-center gap-2 rounded-xl border border-line px-3 text-sm font-medium lg:col-span-1">
          <input type="checkbox" className="h-4 w-4 accent-primary" checked={sensitiveOnly} onChange={(e) => (setPage(0), setSensitiveOnly(e.target.checked))} />
          Solo sensibles
        </label>
      </Card>
      {error && <Banner>{error}</Banner>}
      {!data ? (
        <Spinner />
      ) : data.items.length === 0 ? (
        <Card>
          <EmptyState icon="history" title="Sin actividad con estos filtros" />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {data.items.map((e) => {
            const meta = ACTION_LABELS[e.action] ?? { label: e.action, icon: 'bolt' }
            return (
              <div key={e.id} className="flex items-start gap-3 px-4 py-3">
                <div className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${e.sensitive ? 'bg-danger-soft text-danger-ink' : 'bg-primary-tint text-primary'}`}>
                  <Icon name={meta.icon} className="text-[18px]" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-sm font-semibold">{meta.label}</span>
                    {e.sensitive && <Chip tone="danger">Sensible</Chip>}
                  </div>
                  <p className="text-sm text-ink/80">{e.summary}</p>
                  <p className="mt-0.5 text-xs text-muted">
                    {e.userName ?? 'Sistema'} · {dateTime(e.createdAt)}
                    {e.ip ? ` · IP ${e.ip}` : ''}
                  </p>
                </div>
              </div>
            )
          })}
        </Card>
      )}
      {data && data.pages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button size="sm" variant="secondary" icon="chevron_left" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Anterior
          </Button>
          <span className="num text-sm text-muted">
            {page + 1} / {data.pages}
          </span>
          <Button size="sm" variant="secondary" disabled={page + 1 >= data.pages} onClick={() => setPage((p) => p + 1)}>
            Siguiente
          </Button>
        </div>
      )}
    </div>
  )
}
