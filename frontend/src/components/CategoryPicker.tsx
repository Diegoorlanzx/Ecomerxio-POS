import { useState } from 'react'
import { ApiError, http } from '../lib/http'
import type { Category } from '../lib/posTypes'
import { Button, Field, SelectField } from './ui'

const NEW = '__nueva__'

/**
 * Elegir la categoría de un producto o crear una nueva ahí mismo, sin salir del formulario.
 * Vacío = el servidor lo deja en "Varios".
 */
export function CategoryPicker({
  categories,
  value,
  onChange,
  error,
  info,
  autoFocusNew,
}: {
  categories: Category[]
  value: string
  onChange: (id: string) => void
  error?: string
  info?: string
  autoFocusNew?: boolean
}) {
  // Las que se crean aquí se suman a la lista aunque la pantalla de atrás no se haya recargado.
  const [created, setCreated] = useState<Category[]>([])
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState('')
  const [fieldError, setFieldError] = useState('')
  const [saving, setSaving] = useState(false)

  const all = [...categories, ...created.filter((c) => !categories.some((x) => x.id === c.id))]
  const parents = all.filter((c) => !c.parentId).sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
  const hasVarios = parents.some((p) => p.name.trim().toLowerCase() === 'varios')

  async function create() {
    if (!name.trim()) {
      setFieldError('escribe el nombre')
      return
    }
    setSaving(true)
    setFieldError('')
    try {
      const c = await http<Category>('/categories', { body: { name: name.trim(), parentId: parentId ? Number(parentId) : undefined } })
      setCreated((x) => [...x, c])
      onChange(String(c.id))
      setCreating(false)
      setName('')
      setParentId('')
    } catch (e) {
      setFieldError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo crear (revisa la conexión)')
    } finally {
      setSaving(false)
    }
  }

  if (creating) {
    return (
      <div className="space-y-2 rounded-xl border border-primary/40 bg-primary-tint/40 p-3">
        <Field
          label="Nombre de la categoría nueva"
          autoFocus={autoFocusNew !== false}
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              void create()
            }
          }}
          error={fieldError}
          placeholder="Ej: Bebidas, Aseo, Mecato…"
        />
        {parents.length > 0 && (
          <SelectField label="Dentro de (opcional)" value={parentId} onChange={(e) => setParentId(e.target.value)} hint="Para crearla como subcategoría">
            <option value="">Es una categoría principal</option>
            {parents.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </SelectField>
        )}
        <div className="flex gap-2">
          <Button size="sm" icon="add" onClick={create} loading={saving}>
            Crear y usar
          </Button>
          <Button size="sm" variant="ghost" onClick={() => (setCreating(false), setFieldError(''))}>
            Cancelar
          </Button>
        </div>
      </div>
    )
  }

  return (
    <SelectField
      label="Categoría"
      info={info}
      value={value}
      onChange={(e) => (e.target.value === NEW ? setCreating(true) : onChange(e.target.value))}
      error={error}
      hint={value ? undefined : 'Si no eliges, queda en "Varios"'}
    >
      <option value="">{hasVarios ? 'Sin elegir (queda en Varios)' : 'Varios (automática)'}</option>
      {parents.map((p) => {
        const subs = all.filter((c) => c.parentId === p.id)
        return subs.length ? (
          <optgroup key={p.id} label={p.name}>
            <option value={p.id}>{p.name}</option>
            {subs.map((c) => (
              <option key={c.id} value={c.id}>
                {p.name} › {c.name}
              </option>
            ))}
          </optgroup>
        ) : (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        )
      })}
      <option value={NEW}>+ Crear categoría nueva…</option>
    </SelectField>
  )
}
