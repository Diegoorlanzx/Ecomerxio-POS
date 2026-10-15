import { useCallback, useEffect, useMemo, useState } from 'react'
import { Banner, Button, Card, Chip, Field, Icon, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { PermissionDto, RoleDto } from '../../lib/types'

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
  catalog,
  readOnly,
  onClose,
  onSaved,
}: {
  role: RoleDto | null
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
