import { useCallback, useEffect, useMemo, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, PasswordField, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { initials, relative } from '../../lib/format'
import type { RoleDto, UserDto } from '../../lib/types'

type Editing = { mode: 'new' } | { mode: 'edit'; user: UserDto }

export default function UsersPage() {
  const { me, can } = useAuth()
  const toast = useToast()
  const [users, setUsers] = useState<UserDto[] | null>(null)
  const [roles, setRoles] = useState<RoleDto[]>([])
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [showInactive, setShowInactive] = useState(false)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [pwdUser, setPwdUser] = useState<UserDto | null>(null)
  const manage = can('USUARIOS_GESTIONAR')

  const load = useCallback(async () => {
    try {
      const [u, r] = await Promise.all([api<UserDto[]>('/users'), api<RoleDto[]>('/roles').catch(() => [])])
      setUsers(u)
      setRoles(r)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cargar')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (users ?? []).filter(
      (u) => (showInactive || u.active) && (!q || u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)),
    )
  }, [users, query, showInactive])

  async function toggleActive(u: UserDto) {
    try {
      await api(`/users/${u.id}/${u.active ? 'deactivate' : 'activate'}`, { method: 'POST' })
      toast(u.active ? `${u.fullName} quedó desactivado` : `${u.fullName} está activo otra vez`)
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
    }
  }

  async function unlock(u: UserDto) {
    await api(`/users/${u.id}/unlock`, { method: 'POST' })
    toast(`${u.fullName} desbloqueado`)
    load()
  }

  if (error) return <Banner>{error}</Banner>
  if (!users) return <Spinner />

  const activeCount = users.filter((u) => u.active).length

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Equipo</h2>
          <p className="text-sm text-muted">
            {activeCount} {activeCount === 1 ? 'usuario activo' : 'usuarios activos'} · los desactivados conservan todo su historial
          </p>
        </div>
        {manage && (
          <Button icon="person_add" onClick={() => setEditing({ mode: 'new' })}>
            Nuevo usuario
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-56 flex-1">
          <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[20px] text-muted" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Buscar por nombre o correo"
            className="h-12 w-full rounded-xl border border-line bg-card pr-3 pl-10 text-[15px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
          />
        </div>
        <label className="flex h-12 items-center gap-2 rounded-xl border border-line bg-card px-3 text-sm font-medium">
          <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} className="h-4 w-4 accent-primary" />
          Ver desactivados
        </label>
      </div>

      {visible.length === 0 ? (
        <Card>
          <EmptyState icon="group" title="No hay usuarios con ese filtro" />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {visible.map((u) => (
            <Card key={u.id} className={`p-4 ${u.active ? '' : 'opacity-60'}`}>
              <div className="flex items-start gap-3">
                <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white ${u.owner ? 'bg-primary' : 'bg-navy'}`}>
                  {initials(u.fullName)}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate font-semibold">{u.fullName}</span>
                    {u.id === me?.id && <Chip tone="primary">Tú</Chip>}
                    {!u.active && <Chip tone="neutral">Desactivado</Chip>}
                    {u.locked && (
                      <Chip tone="danger" icon="lock">
                        Bloqueado
                      </Chip>
                    )}
                  </div>
                  <div className="truncate text-sm text-muted">{u.email}</div>
                  <div className="mt-2 flex flex-wrap gap-1">
                    {u.roles.map((r) => (
                      <Chip key={r.id} tone={r.systemCode === 'DUENO' ? 'primary' : 'neutral'}>
                        {r.name}
                      </Chip>
                    ))}
                  </div>
                  <div className="mt-2 text-xs text-muted">Último ingreso: {relative(u.lastLoginAt)}</div>
                </div>
              </div>
              {manage && (
                <div className="mt-3 flex flex-wrap gap-1.5 border-t border-line-soft pt-3">
                  <Button size="sm" variant="soft" icon="edit" onClick={() => setEditing({ mode: 'edit', user: u })}>
                    Editar
                  </Button>
                  <Button size="sm" variant="ghost" icon="key" onClick={() => setPwdUser(u)}>
                    Contraseña
                  </Button>
                  {u.locked && (
                    <Button size="sm" variant="ghost" icon="lock_open" onClick={() => unlock(u)}>
                      Desbloquear
                    </Button>
                  )}
                  {u.id !== me?.id && (
                    <Button size="sm" variant="ghost" icon={u.active ? 'person_off' : 'person_check'} onClick={() => toggleActive(u)}>
                      {u.active ? 'Desactivar' : 'Activar'}
                    </Button>
                  )}
                </div>
              )}
            </Card>
          ))}
        </div>
      )}

      {editing && (
        <UserForm
          editing={editing}
          roles={roles}
          onClose={() => setEditing(null)}
          onSaved={(msg) => {
            setEditing(null)
            toast(msg)
            load()
          }}
        />
      )}
      {pwdUser && (
        <PasswordForm
          user={pwdUser}
          onClose={() => setPwdUser(null)}
          onSaved={() => {
            setPwdUser(null)
            toast('Contraseña asignada. Sus sesiones abiertas se cerraron.')
          }}
        />
      )}
    </div>
  )
}

function UserForm({ editing, roles, onClose, onSaved }: { editing: Editing; roles: RoleDto[]; onClose: () => void; onSaved: (msg: string) => void }) {
  const initial = editing.mode === 'edit' ? editing.user : null
  const [fullName, setFullName] = useState(initial?.fullName ?? '')
  const [email, setEmail] = useState(initial?.email ?? '')
  const [phone, setPhone] = useState(initial?.phone ?? '')
  const [password, setPassword] = useState('')
  const [roleIds, setRoleIds] = useState<Set<number>>(new Set(initial?.roles.map((r) => r.id) ?? []))
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  function toggle(id: number) {
    setRoleIds((s) => {
      const n = new Set(s)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })
  }

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const body = { fullName, email, phone, roleIds: [...roleIds], ...(editing.mode === 'new' ? { password } : {}) }
      if (editing.mode === 'new') await api('/users', { body })
      else await api(`/users/${editing.user.id}`, { method: 'PUT', body })
      onSaved(editing.mode === 'new' ? `${fullName} ya puede entrar` : 'Cambios guardados')
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing.mode === 'new' ? 'Nuevo usuario' : 'Editar usuario'}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving}>
            Guardar
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre completo" value={fullName} onChange={(e) => setFullName(e.target.value)} error={fields.fullName} />
        <Field label="Correo (para entrar)" type="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fields.email} />
        <Field label="Celular (opcional)" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} error={fields.phone} />
        {editing.mode === 'new' && (
          <PasswordField
            label="Contraseña inicial"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={fields.password}
            hint="Entrégasela al empleado; puede cambiarla en Mi cuenta."
          />
        )}
        <div>
          <div className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">Roles</div>
          {fields.roleIds && <p className="mb-2 text-xs font-medium text-danger-ink">{fields.roleIds}</p>}
          <div className="space-y-2">
            {roles.map((r) => (
              <label
                key={r.id}
                className={`flex cursor-pointer items-start gap-3 rounded-xl border p-3 transition ${roleIds.has(r.id) ? 'border-primary bg-primary-tint' : 'border-line hover:bg-canvas'}`}
              >
                <input type="checkbox" checked={roleIds.has(r.id)} onChange={() => toggle(r.id)} className="mt-0.5 h-4 w-4 accent-primary" />
                <div>
                  <div className="text-sm font-semibold">{r.name}</div>
                  {r.description && <div className="text-xs text-muted">{r.description}</div>}
                </div>
              </label>
            ))}
          </div>
        </div>
      </div>
    </Modal>
  )
}

function PasswordForm({ user, onClose, onSaved }: { user: UserDto; onClose: () => void; onSaved: () => void }) {
  const [password, setPassword] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      await api(`/users/${user.id}/password`, { body: { password } })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`Contraseña de ${user.fullName}`}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving}>
            Asignar
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <PasswordField
          label="Contraseña nueva"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fields.password}
          hint="Al guardarla se cierran las sesiones abiertas de este usuario."
        />
      </div>
    </Modal>
  )
}
