import { useCallback, useEffect, useMemo, useState } from 'react'
import { Banner, Button, Card, Chip, Field, Icon, Modal, SelectField, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { PermissionDto, RoleDto } from '../../lib/types'
import { WORKER_TYPES } from '../../lib/workerTypes'

export default function RolesPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [roles, setRoles] = useState<RoleDto[] | null>(null)
  const [catalog, setCatalog] = useState<PermissionDto[]>([])
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<RoleDto | 'new' | null>(null)
  const manage = can('ROLES_GESTIONAR')

  const load = useCallback(async () => {
    try {
      const [r, p] = await Promise.all([api<RoleDto[]>('/roles'), api<PermissionDto[]>('/permissions')])
      setRoles(r)
      setCatalog(p)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cargar')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  if (error) return <Banner>{error}</Banner>
  if (!roles) return <Spinner />

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Roles y permisos</h2>
          <p className="text-sm text-muted">Un rol agrupa permisos. Un empleado puede tener varios roles y recibe la suma de sus permisos.</p>
        </div>
        {manage && (
          <Button icon="add" onClick={() => setEditing('new')}>
            Crear rol
          </Button>
        )}
      </div>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {roles.map((r) => (
          <Card key={r.id} className="flex flex-col p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="font-semibold">{r.name}</span>
                  {r.systemCode ? <Chip tone="primary">Predefinido</Chip> : <Chip tone="wallet">Personalizado</Chip>}
                </div>
                {r.description && <p className="mt-1 text-sm text-muted">{r.description}</p>}
              </div>
              <div className="shrink-0 text-right">
                <div className="num text-lg font-bold">{r.userCount}</div>
                <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{r.userCount === 1 ? 'usuario' : 'usuarios'}</div>
              </div>
            </div>
            <div className="mt-3 flex items-center gap-2 text-sm text-muted">
              <Icon name="verified_user" className="text-[18px]" />
              {r.permissions.length} de {catalog.length} permisos
            </div>
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-line-soft">
              <div className="h-full rounded-full bg-primary" style={{ width: `${(r.permissions.length / Math.max(catalog.length, 1)) * 100}%` }} />
            </div>
            <div className="mt-4 flex gap-1.5">
              <Button size="sm" variant={manage && r.editable ? 'soft' : 'ghost'} icon={manage && r.editable ? 'tune' : 'visibility'} onClick={() => setEditing(r)}>
                {manage && r.editable ? 'Permisos' : 'Ver permisos'}
              </Button>
            </div>
          </Card>
        ))}
      </div>
      {editing && (
        <RoleEditor
          role={editing === 'new' ? null : editing}
          roles={roles}
          catalog={catalog}
          readOnly={!manage || (editing !== 'new' && !editing.editable)}
          onClose={() => setEditing(null)}
          onSaved={(msg) => {
            setEditing(null)
            toast(msg)
            load()
          }}
        />
      )}
    </div>
  )
}

function RoleEditor({
  role,
  roles,
  catalog,
  readOnly,
  onClose,
  onSaved,
}: {
  role: RoleDto | null
  roles: RoleDto[]
  catalog: PermissionDto[]
  readOnly: boolean
  onClose: () => void
  onSaved: (msg: string) => void
}) {
  const [name, setName] = useState(role?.name ?? '')
  const [description, setDescription] = useState(role?.description ?? '')
  const [perms, setPerms] = useState<Set<string>>(new Set(role?.permissions ?? []))
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // Plantilla elegida al crear: "tipo:cajero", "rol:12" o "cero".
  const [picked, setPicked] = useState('')
  const [autoText, setAutoText] = useState({ name: '', description: '' })

  const codes = useMemo(() => new Set(catalog.map((p) => p.code)), [catalog])
  const { has } = useAuth()
  const types = useMemo(() => WORKER_TYPES.filter((t) => !t.module || has(t.module)), [has])

  /** Marca los permisos de la plantilla y, si el nombre no lo escribió el usuario, propone nombre y descripción. */
  function applyTemplate(key: string, permissions: string[], newName: string, newDescription: string) {
    setPicked(key)
    setPerms(new Set(permissions.filter((c) => codes.has(c))))
    if (!name.trim() || name === autoText.name) setName(newName)
    if (!description.trim() || description === autoText.description) setDescription(newDescription)
    setAutoText({ name: newName, description: newDescription })
  }

  const groups = useMemo(() => {
    const m = new Map<string, PermissionDto[]>()
    catalog.forEach((p) => m.set(p.group, [...(m.get(p.group) ?? []), p]))
    return [...m.entries()]
  }, [catalog])

  function toggle(code: string) {
    if (readOnly) return
    setPerms((s) => {
      const n = new Set(s)
      if (n.has(code)) n.delete(code)
      else n.add(code)
      return n
    })
  }

  function toggleGroup(items: PermissionDto[]) {
    if (readOnly) return
    const all = items.every((p) => perms.has(p.code))
    setPerms((s) => {
      const n = new Set(s)
      items.forEach((p) => (all ? n.delete(p.code) : n.add(p.code)))
      return n
    })
  }

  async function run(fn: () => Promise<unknown>, msg: string) {
    setSaving(true)
    setFields({})
    setError('')
    try {
      await fn()
      onSaved(msg)
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  const body = { name, description, permissions: [...perms] }
  const save = () => run(() => (role ? api(`/roles/${role.id}`, { method: 'PUT', body }) : api('/roles', { body })), role ? 'Rol actualizado' : 'Rol creado')

  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={role ? (readOnly ? `Permisos de ${role.name}` : `Editar ${role.name}`) : 'Crear rol'}
      footer={
        readOnly ? (
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cerrar
          </Button>
        ) : (
          <>
            {role?.deletable && (
              <Button variant="ghost" icon="delete" className="text-danger-ink" onClick={() => run(() => api(`/roles/${role.id}`, { method: 'DELETE' }), 'Rol eliminado')}>
                Eliminar
              </Button>
            )}
            {role?.systemCode && (
              <Button variant="ghost" icon="restart_alt" onClick={() => run(() => api(`/roles/${role.id}/reset`, { method: 'POST' }), 'Permisos originales restablecidos')}>
                Restablecer
              </Button>
            )}
            <div className="flex-1" />
            <Button variant="secondary" onClick={onClose}>
              Cancelar
            </Button>
            <Button onClick={save} loading={saving}>
              Guardar
            </Button>
          </>
        )
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        {role?.systemCode === 'DUENO' && <Banner tone="primary">El rol Dueño siempre tiene todos los permisos.</Banner>}
        {!role && !readOnly && (
          <section className="space-y-3">
            <div>
              <h3 className="font-semibold">¿Para qué tipo de trabajador es?</h3>
              <p className="text-sm text-muted">Elige uno y se marcan sus permisos típicos. Después los ajustas abajo como quieras.</p>
            </div>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
              {types.map((t) => {
                const on = picked === 'tipo:' + t.key
                const n = t.permissions.filter((c) => codes.has(c)).length
                return (
                  <button
                    key={t.key}
                    type="button"
                    aria-pressed={on}
                    onClick={() => applyTemplate('tipo:' + t.key, t.permissions, t.name, t.description)}
                    className={`flex min-w-0 flex-col items-start gap-1 rounded-xl border p-3 text-left transition ${
                      on ? 'border-primary bg-primary-tint ring-2 ring-primary/30' : 'border-line bg-card hover:border-primary/40'
                    }`}
                  >
                    <span className="flex w-full items-center justify-between gap-2">
                      <Icon name={t.icon} className={`text-[22px] ${on ? 'text-primary' : 'text-muted'}`} />
                      {on && <Icon name="check_circle" className="text-[18px] text-primary" />}
                    </span>
                    <span className="text-sm leading-tight font-semibold">{t.name}</span>
                    <span className="line-clamp-2 text-xs text-muted">{t.description}</span>
                    <span className="num mt-auto pt-1 text-[11px] font-semibold text-primary">{n} permisos</span>
                  </button>
                )
              })}
              <button
                type="button"
                aria-pressed={picked === 'cero'}
                onClick={() => applyTemplate('cero', [], '', '')}
                className={`flex min-w-0 flex-col items-start gap-1 rounded-xl border border-dashed p-3 text-left transition ${
                  picked === 'cero' ? 'border-primary bg-primary-tint ring-2 ring-primary/30' : 'border-line hover:border-primary/40'
                }`}
              >
                <Icon name="add" className="text-[22px] text-muted" />
                <span className="text-sm leading-tight font-semibold">Desde cero</span>
                <span className="line-clamp-2 text-xs text-muted">Sin permisos marcados: tú escoges uno por uno.</span>
              </button>
            </div>
            {roles.length > 0 && (
              <SelectField
                label="O copia los permisos de un rol que ya tienes"
                value={picked.startsWith('rol:') ? picked : ''}
                onChange={(e) => {
                  const r = roles.find((x) => 'rol:' + x.id === e.target.value)
                  if (r) applyTemplate('rol:' + r.id, r.permissions, `${r.name} (copia)`, r.description ?? '')
                }}
              >
                <option value="">Elige un rol…</option>
                {roles.map((r) => (
                  <option key={r.id} value={'rol:' + r.id}>
                    {r.name} · {r.permissions.length} permisos
                  </option>
                ))}
              </SelectField>
            )}
          </section>
        )}
        {!readOnly && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Nombre del rol" value={name} onChange={(e) => setName(e.target.value)} error={fields.name} />
            <Field label="Descripción" value={description} onChange={(e) => setDescription(e.target.value)} error={fields.description} />
          </div>
        )}
        {fields.permissions && <Banner>{fields.permissions}</Banner>}
        <div className="text-sm font-semibold">
          {perms.size} permisos seleccionados
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {groups.map(([group, items]) => {
            const count = items.filter((p) => perms.has(p.code)).length
            return (
              <div key={group} className="rounded-xl border border-line p-3">
                <button
                  type="button"
                  onClick={() => toggleGroup(items)}
                  disabled={readOnly}
                  className="mb-2 flex w-full items-center justify-between text-left disabled:cursor-default"
                >
                  <span className="text-xs font-bold tracking-wide text-muted uppercase">{group}</span>
                  <span className={`num text-xs font-bold ${count ? 'text-primary' : 'text-muted'}`}>
                    {count}/{items.length}
                  </span>
                </button>
                <div className="space-y-1">
                  {items.map((p) => (
                    <label key={p.code} className={`flex items-start gap-2.5 rounded-lg px-1.5 py-1.5 text-sm ${readOnly ? '' : 'cursor-pointer hover:bg-canvas'}`}>
                      <input type="checkbox" disabled={readOnly} checked={perms.has(p.code)} onChange={() => toggle(p.code)} className="mt-0.5 h-4 w-4 accent-primary" />
                      <span className={perms.has(p.code) ? 'text-ink' : 'text-muted'}>{p.label}</span>
                    </label>
                  ))}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </Modal>
  )
}
