import { useRef, useState } from 'react'
import { Banner, Button, Field, Icon, Modal, SelectField, useToast } from '../../components/ui'
import { api, apiBlob, ApiError } from '../../lib/api'
import { download } from '../../lib/format'
import type { Category } from '../../lib/posTypes'

/** Importar/exportar el catálogo en CSV (abre en Excel). */
export function ImportModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<{ created: number; updated: number; errors: string[] } | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function run() {
    if (!file) return
    setLoading(true)
    setError('')
    try {
      const csv = await file.text()
      const r = await api<{ created: number; updated: number; errors: string[] }>('/products/import', { body: { csv } })
      setResult(r)
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo importar')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal open onClose={onClose} title="Importar y exportar productos">
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-2">
          <Button variant="secondary" icon="download" onClick={async () => download('plantilla-productos.csv', await apiBlob('/products/import-template'))}>
            Plantilla
          </Button>
          <Button variant="secondary" icon="table_view" onClick={async () => download(`productos-${new Date().toISOString().slice(0, 10)}.csv`, await apiBlob('/products/export'))}>
            Exportar todo
          </Button>
        </div>
        <p className="text-sm text-muted">
          Llena la plantilla en Excel y guárdala como CSV. Si el SKU ya existe se actualiza; si no, se crea. Las filas con error no afectan las demás.
        </p>
        <button
          type="button"
          onClick={() => fileRef.current?.click()}
          className="flex w-full flex-col items-center rounded-2xl border-2 border-dashed border-line px-4 py-8 text-muted hover:border-primary hover:text-primary"
        >
          <Icon name="upload_file" className="text-[36px]" />
          <span className="mt-2 text-sm font-semibold">{file ? file.name : 'Elegir archivo CSV'}</span>
        </button>
        <input ref={fileRef} type="file" accept=".csv,text/csv" hidden onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        {error && <Banner>{error}</Banner>}
        {result && (
          <div className="space-y-2">
            <Banner tone="success">
              {result.created} creados · {result.updated} actualizados
              {result.errors.length ? ` · ${result.errors.length} con error` : ''}
            </Banner>
            {result.errors.length > 0 && (
              <ul className="max-h-48 space-y-1 overflow-y-auto rounded-xl bg-danger-soft p-3 text-xs text-danger-ink">
                {result.errors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        <Button className="w-full" icon="upload" onClick={run} loading={loading} disabled={!file}>
          Importar
        </Button>
      </div>
    </Modal>
  )
}

export function CategoriesModal({ categories, onClose, onChanged }: { categories: Category[]; onClose: () => void; onChanged: () => void }) {
  const toast = useToast()
  const [editing, setEditing] = useState<Category | null>(null)
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const parents = categories.filter((c) => !c.parentId)

  function edit(c: Category | null) {
    setEditing(c)
    setName(c?.name ?? '')
    setParentId(c?.parentId ? String(c.parentId) : '')
    setFields({})
    setError('')
  }

  async function save() {
    setFields({})
    setError('')
    try {
      const body = { name, parentId: parentId ? Number(parentId) : undefined }
      if (editing) await api(`/categories/${editing.id}`, { method: 'PUT', body })
      else await api('/categories', { body })
      toast(editing ? 'Categoría actualizada' : 'Categoría creada')
      edit(null)
      onChanged()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    }
  }

  async function remove(c: Category) {
    try {
      await api(`/categories/${c.id}`, { method: 'DELETE' })
      toast('Categoría eliminada')
      onChanged()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo eliminar', 'danger')
    }
  }

  return (
    <Modal open onClose={onClose} title="Categorías">
      <div className="space-y-4">
        <div className="space-y-3 rounded-xl border border-line p-3">
          <div className="text-sm font-semibold">{editing ? `Editar ${editing.name}` : 'Nueva categoría'}</div>
          {error && <Banner>{error}</Banner>}
          <Field label="Nombre" value={name} onChange={(e) => setName(e.target.value)} error={fields.name} />
          <SelectField label="Dentro de (para subcategoría)" value={parentId} onChange={(e) => setParentId(e.target.value)} error={fields.parentId}>
            <option value="">Ninguna (categoría principal)</option>
            {parents
              .filter((p) => p.id !== editing?.id)
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
          </SelectField>
          <div className="flex gap-2">
            {editing && (
              <Button variant="secondary" onClick={() => edit(null)}>
                Cancelar
              </Button>
            )}
            <Button className="flex-1" onClick={save} disabled={!name.trim()}>
              {editing ? 'Guardar' : 'Crear'}
            </Button>
          </div>
        </div>
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          {parents.length === 0 && <p className="p-4 text-sm text-muted">Aún no hay categorías.</p>}
          {parents.map((p) => (
            <div key={p.id}>
              <CatRow c={p} onEdit={() => edit(p)} onDelete={() => remove(p)} />
              {categories
                .filter((c) => c.parentId === p.id)
                .map((c) => (
                  <CatRow key={c.id} c={c} sub onEdit={() => edit(c)} onDelete={() => remove(c)} />
                ))}
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}

function CatRow({ c, sub, onEdit, onDelete }: { c: Category; sub?: boolean; onEdit: () => void; onDelete: () => void }) {
  return (
    <div className={`flex items-center gap-2 py-2 pr-2 ${sub ? 'pl-8' : 'pl-3'}`}>
      {sub && <Icon name="subdirectory_arrow_right" className="text-[18px] text-muted" />}
      <span className="flex-1 text-sm font-medium">{c.name}</span>
      <span className="num text-xs text-muted">{c.productCount}</span>
      <button onClick={onEdit} aria-label={`Editar ${c.name}`} className="flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-line-soft">
        <Icon name="edit" className="text-[18px]" />
      </button>
      <button onClick={onDelete} aria-label={`Eliminar ${c.name}`} className="flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-danger-soft hover:text-danger-ink">
        <Icon name="delete" className="text-[18px]" />
      </button>
    </div>
  )
}

/** Cambios sobre varios productos seleccionados. */
export function BulkModal({ ids, categories, onClose, onDone }: { ids: number[]; categories: Category[]; onClose: () => void; onDone: () => void }) {
  const [action, setAction] = useState('PRECIO_PORCENTAJE')
  const [value, setValue] = useState('')
  const [roundTo, setRoundTo] = useState('50')
  const [categoryId, setCategoryId] = useState('')
  const [status, setStatus] = useState('ACTIVE')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function run() {
    setLoading(true)
    setError('')
    try {
      await api('/products/bulk', {
        body: {
          productIds: ids,
          action,
          value: value ? Number(value.replace(',', '.')) : undefined,
          roundTo: action.startsWith('PRECIO') ? Number(roundTo) : undefined,
          categoryId: categoryId ? Number(categoryId) : undefined,
          status,
          reason: reason || undefined,
        },
      })
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo aplicar')
    } finally {
      setLoading(false)
    }
  }

  const needsValue = ['PRECIO_PORCENTAJE', 'PRECIO_FIJO', 'STOCK_MINIMO', 'STOCK_MAXIMO', 'IMPUESTO'].includes(action)
  return (
    <Modal
      open
      onClose={onClose}
      title={`Cambiar ${ids.length} ${ids.length === 1 ? 'producto' : 'productos'}`}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={run} loading={loading}>
            Aplicar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <SelectField label="¿Qué quieres cambiar?" value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="PRECIO_PORCENTAJE">Subir o bajar precios (%)</option>
          <option value="PRECIO_FIJO">Fijar el precio de la unidad</option>
          <option value="STOCK_MINIMO">Stock mínimo</option>
          <option value="STOCK_MAXIMO">Stock máximo</option>
          <option value="IMPUESTO">Tarifa de impuesto (%)</option>
          <option value="CATEGORIA">Mover a una categoría</option>
          <option value="ESTADO">Activar o descontinuar</option>
        </SelectField>
        {needsValue && (
          <Field
            label={action === 'PRECIO_PORCENTAJE' ? 'Porcentaje (negativo para bajar)' : 'Valor'}
            inputMode="decimal"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={action === 'PRECIO_PORCENTAJE' ? 'Ej: 8 o -5' : ''}
          />
        )}
        {action.startsWith('PRECIO') && (
          <SelectField label="Redondear a" value={roundTo} onChange={(e) => setRoundTo(e.target.value)}>
            <option value="1">Sin redondeo</option>
            <option value="50">$50</option>
            <option value="100">$100</option>
            <option value="500">$500</option>
            <option value="1000">$1.000</option>
          </SelectField>
        )}
        {action === 'CATEGORIA' && (
          <SelectField label="Categoría" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">Sin categoría</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.parentId ? `  › ${c.name}` : c.name}
              </option>
            ))}
          </SelectField>
        )}
        {action === 'ESTADO' && (
          <SelectField label="Estado" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">Activo</option>
            <option value="DISCONTINUED">Descontinuado</option>
          </SelectField>
        )}
        <Field label="Motivo (opcional)" value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
    </Modal>
  )
}
