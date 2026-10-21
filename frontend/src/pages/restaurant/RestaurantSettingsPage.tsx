import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money } from '../../lib/format'
import type { PosProduct } from '../../lib/posTypes'
import type { Floor, ModifierGroupDto, StationCategory } from '../../lib/restaurantTypes'

type Tab = 'mesas' | 'modificadores' | 'estaciones' | 'propinas'

/** Configuración del restaurante: salones y mesas, modificadores, estaciones de cocina/bar y propina voluntaria. */
export default function RestaurantSettingsPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) || 'mesas'
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Configurar restaurante</h1>
        <p className="text-sm text-muted">Mesas, modificadores de los platos, a dónde va cada comanda y la propina.</p>
      </div>
      <div className="overflow-x-auto">
        <Tabs<Tab>
          value={tab}
          onChange={(t) => setParams(t === 'mesas' ? {} : { tab: t }, { replace: true })}
          items={[
            { value: 'mesas', label: 'Mesas' },
            { value: 'modificadores', label: 'Modificadores' },
            { value: 'estaciones', label: 'Cocina y bar' },
            { value: 'propinas', label: 'Propina' },
          ]}
        />
      </div>
      {tab === 'mesas' && <TablesTab />}
      {tab === 'modificadores' && <ModifiersTab />}
      {tab === 'estaciones' && <StationsTab />}
      {tab === 'propinas' && <TipsTab />}
    </div>
  )
}

// ---------------- Mesas y salones ----------------

function TablesTab() {
  const toast = useToast()
  const [floor, setFloor] = useState<Floor | null>(null)
  const [edit, setEdit] = useState<{ id?: number; name: string; seats: string; areaId: string } | null>(null)
  const [newArea, setNewArea] = useState('')
  const [bulk, setBulk] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const load = useCallback(() => void api<Floor>('/restaurant/floor').then(setFloor), [])
  useEffect(load, [load])

  async function saveTable() {
    if (!edit) return
    setFields({})
    try {
      const body = { name: edit.name, seats: Number(edit.seats) || 4, areaId: edit.areaId ? Number(edit.areaId) : null }
      await api(edit.id ? `/restaurant/tables/${edit.id}` : '/restaurant/tables', { method: edit.id ? 'PUT' : 'POST', body })
      setEdit(null)
      toast('Mesa guardada')
      load()
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { name: e.message })
    }
  }

  /** Crea varias mesas seguidas: "Mesa 1" a "Mesa N". */
  async function createMany() {
    const n = Math.min(60, Number(bulk) || 0)
    if (!n || !floor) return
    const existing = new Set(floor.tables.map((t) => t.name.toLowerCase()))
    let made = 0
    for (let i = 1; made < n && i < 500; i++) {
      const name = `Mesa ${i}`
      if (existing.has(name.toLowerCase())) continue
      await api('/restaurant/tables', { body: { name, seats: 4, sortOrder: i } }).catch(() => undefined)
      made++
    }
    setBulk('')
    toast(`${made} mesas creadas`)
    load()
  }

  async function addArea() {
    if (!newArea.trim()) return
    await api('/restaurant/areas', { body: { name: newArea.trim() } }).catch((e) => toast(e.message, 'danger'))
    setNewArea('')
    load()
  }

  if (!floor) return <Spinner />
  const areaName = (id: number | null) => floor.areas.find((a) => a.id === id)?.name ?? 'Sin salón'
  return (
    <div className="space-y-4">
      <Card className="space-y-3 p-4">
        <h2 className="font-semibold">Salones</h2>
        <div className="flex flex-wrap gap-2">
          {floor.areas.map((a) => (
            <Chip key={a.id}>
              {a.name}
              <button type="button" aria-label={`Quitar ${a.name}`} onClick={() => api(`/restaurant/areas/${a.id}`, { method: 'DELETE' }).then(load)} className="ml-1">
                ×
              </button>
            </Chip>
          ))}
          {floor.areas.length === 0 && <span className="text-sm text-muted">Opcional: Salón principal, Terraza, Segundo piso…</span>}
        </div>
        <div className="flex gap-2">
          <Field label="Nuevo salón" value={newArea} onChange={(e) => setNewArea(e.target.value)} placeholder="Terraza" />
          <Button className="self-end" variant="secondary" onClick={addArea}>
            Agregar
          </Button>
        </div>
      </Card>
      <Card className="space-y-3 p-4">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <h2 className="font-semibold">Mesas ({floor.tables.length})</h2>
          <div className="flex items-end gap-2">
            <Field label="Crear varias" inputMode="numeric" placeholder="10" value={bulk} onChange={(e) => setBulk(e.target.value.replace(/\D/g, ''))} />
            <Button variant="secondary" onClick={createMany} disabled={!bulk}>
              Crear
            </Button>
            <Button icon="add" onClick={() => (setFields({}), setEdit({ name: '', seats: '4', areaId: '' }))}>
              Mesa
            </Button>
          </div>
        </div>
        {floor.tables.length === 0 ? (
          <EmptyState icon="table_restaurant" title="Sin mesas" text="Escribe cuántas tienes en “Crear varias” para empezar rápido." />
        ) : (
          <ul className="divide-y divide-line-soft">
            {floor.tables.map((t) => (
              <li key={t.id} className="flex items-center gap-3 py-2 text-sm">
                <span className="flex-1 font-semibold">{t.name}</span>
                <span className="text-muted">{areaName(t.areaId)}</span>
                <span className="text-muted">{t.seats} puestos</span>
                <Button size="sm" variant="ghost" icon="edit" onClick={() => (setFields({}), setEdit({ id: t.id, name: t.name, seats: String(t.seats), areaId: t.areaId ? String(t.areaId) : '' }))}>
                  <span className="sr-only">Editar</span>
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  icon="delete"
                  onClick={() => api(`/restaurant/tables/${t.id}`, { method: 'DELETE' }).then(load).catch((e) => toast(e.message, 'danger'))}
                >
                  <span className="sr-only">Quitar</span>
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Modal open={!!edit} title={edit?.id ? 'Editar mesa' : 'Nueva mesa'} onClose={() => setEdit(null)} footer={<Button className="w-full" onClick={saveTable}>Guardar</Button>}>
        {edit && (
          <div className="space-y-3">
            <Field label="Nombre" value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} error={fields.name} placeholder="Mesa 5, Barra 1…" />
            <Field label="Puestos" inputMode="numeric" value={edit.seats} onChange={(e) => setEdit({ ...edit, seats: e.target.value.replace(/\D/g, '') })} error={fields.seats} />
            <SelectField label="Salón" value={edit.areaId} onChange={(e) => setEdit({ ...edit, areaId: e.target.value })}>
              <option value="">Sin salón</option>
              {floor.areas.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </SelectField>
          </div>
        )}
      </Modal>
    </div>
  )
}

// ---------------- Modificadores ----------------

interface GroupForm {
  id?: number
  name: string
  minSelect: string
  maxSelect: string
  options: { id?: number; name: string; price: string }[]
  productIds: number[]
}

function ModifiersTab() {
  const toast = useToast()
  const [groups, setGroups] = useState<ModifierGroupDto[] | null>(null)
  const [products, setProducts] = useState<PosProduct[]>([])
  const [form, setForm] = useState<GroupForm | null>(null)
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const load = useCallback(() => void api<ModifierGroupDto[]>('/restaurant/modifiers').then(setGroups), [])
  useEffect(() => {
    load()
    api<PosProduct[]>('/pos/catalog').then(setProducts).catch(() => setProducts([]))
  }, [load])
  const names = useMemo(() => new Map(products.map((p) => [p.id, p.name])), [products])

  async function save() {
    if (!form) return
    setError('')
    try {
      await api(form.id ? `/restaurant/modifiers/${form.id}` : '/restaurant/modifiers', {
        method: form.id ? 'PUT' : 'POST',
        body: {
          name: form.name,
          minSelect: Number(form.minSelect) || 0,
          maxSelect: Number(form.maxSelect) || 0,
          options: form.options.filter((o) => o.name.trim()).map((o) => ({ id: o.id, name: o.name, price: Number(o.price.replace(/\D/g, '')) || 0 })),
          productIds: form.productIds,
        },
      })
      setForm(null)
      toast('Modificadores guardados')
      load()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar')
    }
  }

  if (!groups) return <Spinner />
  const shown = products.filter((p) => !q || p.name.toLowerCase().includes(q.toLowerCase())).slice(0, 40)
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">
        Grupos de opciones que el mesero elige al pedir un plato: “Término” (obligatorio, una), “Adiciones” (opcionales, con precio), “Sin…”.
      </p>
      <div className="flex justify-end">
        <Button icon="add" onClick={() => (setError(''), setForm({ name: '', minSelect: '0', maxSelect: '1', options: [{ name: '', price: '' }], productIds: [] }))}>
          Nuevo grupo
        </Button>
      </div>
      {groups.length === 0 ? (
        <EmptyState icon="tune" title="Sin modificadores" text="Ejemplo: Término de la carne — Azul, Término medio, Tres cuartos, Bien asada." />
      ) : (
        groups.map((g) => (
          <Card key={g.id} className="space-y-2 p-4">
            <div className="flex items-center justify-between gap-2">
              <div>
                <div className="font-semibold">{g.name}</div>
                <div className="text-xs text-muted">
                  {g.minSelect > 0 ? `Obligatorio (${g.minSelect})` : 'Opcional'} · máximo {g.maxSelect || 'sin límite'} · {g.productIds.length} producto(s)
                </div>
              </div>
              <div className="flex gap-1">
                <Button
                  size="sm"
                  variant="ghost"
                  icon="edit"
                  onClick={() =>
                    (setError(''),
                    setForm({
                      id: g.id,
                      name: g.name,
                      minSelect: String(g.minSelect),
                      maxSelect: String(g.maxSelect),
                      options: g.options.map((o) => ({ id: o.id, name: o.name, price: o.price ? String(o.price) : '' })),
                      productIds: g.productIds,
                    }))
                  }
                >
                  <span className="sr-only">Editar</span>
                </Button>
                <Button size="sm" variant="ghost" icon="delete" onClick={() => api(`/restaurant/modifiers/${g.id}`, { method: 'DELETE' }).then(load)}>
                  <span className="sr-only">Quitar</span>
                </Button>
              </div>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {g.options.map((o) => (
                <Chip key={o.id}>
                  {o.name}
                  {o.price > 0 ? ` +${money(o.price)}` : ''}
                </Chip>
              ))}
            </div>
            <div className="text-xs text-muted">{g.productIds.map((id) => names.get(id) ?? '').filter(Boolean).join(', ')}</div>
          </Card>
        ))
      )}
      <Modal open={!!form} wide title={form?.id ? 'Editar grupo' : 'Nuevo grupo de modificadores'} onClose={() => setForm(null)} footer={<Button className="w-full" onClick={save}>Guardar</Button>}>
        {form && (
          <div className="space-y-4">
            {error && <Banner>{error}</Banner>}
            <Field label="Nombre del grupo" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Término de la carne" />
            <div className="grid grid-cols-2 gap-3">
              <Field label="Mínimo a elegir" inputMode="numeric" value={form.minSelect} onChange={(e) => setForm({ ...form, minSelect: e.target.value.replace(/\D/g, '') })} hint="0 = opcional" />
              <Field label="Máximo a elegir" inputMode="numeric" value={form.maxSelect} onChange={(e) => setForm({ ...form, maxSelect: e.target.value.replace(/\D/g, '') })} hint="0 = sin límite" />
            </div>
            <div className="space-y-2">
              <div className="text-sm font-semibold">Opciones</div>
              {form.options.map((o, i) => (
                <div key={i} className="flex gap-2">
                  <input
                    className="h-11 flex-1 rounded-xl border border-line px-3"
                    placeholder="Término medio"
                    value={o.name}
                    onChange={(e) => setForm({ ...form, options: form.options.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}
                  />
                  <input
                    className="num h-11 w-28 rounded-xl border border-line px-3"
                    placeholder="Precio extra"
                    inputMode="numeric"
                    value={o.price}
                    onChange={(e) => setForm({ ...form, options: form.options.map((x, j) => (j === i ? { ...x, price: e.target.value.replace(/\D/g, '') } : x)) })}
                  />
                  <Button size="sm" variant="ghost" icon="close" onClick={() => setForm({ ...form, options: form.options.filter((_, j) => j !== i) })}>
                    <span className="sr-only">Quitar opción</span>
                  </Button>
                </div>
              ))}
              <Button size="sm" variant="secondary" icon="add" onClick={() => setForm({ ...form, options: [...form.options, { name: '', price: '' }] })}>
                Opción
              </Button>
            </div>
            <div className="space-y-2">
              <div className="text-sm font-semibold">¿En qué productos aparece?</div>
              <Field label="Buscar producto" value={q} onChange={(e) => setQ(e.target.value)} />
              <div className="max-h-56 space-y-1 overflow-y-auto">
                {shown.map((p) => (
                  <label key={p.id} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={form.productIds.includes(p.id)}
                      onChange={(e) =>
                        setForm({ ...form, productIds: e.target.checked ? [...form.productIds, p.id] : form.productIds.filter((x) => x !== p.id) })
                      }
                    />
                    {p.name}
                  </label>
                ))}
              </div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}

// ---------------- Estaciones ----------------

function StationsTab() {
  const toast = useToast()
  const [cats, setCats] = useState<StationCategory[] | null>(null)
  useEffect(() => void api<StationCategory[]>('/restaurant/stations').then(setCats), [])
  async function set(id: number, station: string) {
    try {
      setCats(await api<StationCategory[]>('/restaurant/stations', { method: 'PUT', body: { categories: { [id]: station } } }))
      toast('Guardado')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    }
  }
  if (!cats) return <Spinner />
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        A dónde va la comanda de cada categoría. Lo que no se prepara (gaseosas en botella, dulces) déjalo en “No va a cocina”.
      </p>
      <Card className="divide-y divide-line-soft">
        {cats.map((c) => (
          <div key={c.id} className="flex items-center gap-3 px-4 py-2.5">
            <span className="flex-1 text-sm font-semibold">{c.name}</span>
            <select className="h-10 rounded-xl border border-line px-2 text-sm" value={c.station ?? ''} onChange={(e) => set(c.id, e.target.value)}>
              <option value="">No va a cocina</option>
              <option value="COCINA">Cocina</option>
              <option value="BAR">Bar</option>
            </select>
          </div>
        ))}
        {cats.length === 0 && (
          <div className="p-4">
            <EmptyState icon="category" title="Sin categorías" text="Crea categorías en Inventario (Platos, Bebidas, Postres…)." />
          </div>
        )}
      </Card>
    </div>
  )
}

// ---------------- Propina ----------------

function TipsTab() {
  const toast = useToast()
  const [floor, setFloor] = useState<Floor | null>(null)
  const [pct, setPct] = useState('')
  useEffect(() => void api<Floor>('/restaurant/floor').then((f) => (setFloor(f), setPct(String(f.tipSuggestedPct)))), [])
  async function save(enabled: boolean) {
    try {
      setFloor(await api<Floor>('/restaurant/settings', { method: 'PUT', body: { tipsEnabled: enabled, tipSuggestedPct: Number(pct) || 0 } }))
      toast('Guardado')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    }
  }
  if (!floor) return <Spinner />
  return (
    <Card className="space-y-4 p-4">
      <Toggle checked={floor.tipsEnabled} onChange={save} label="Ofrecer propina voluntaria al cobrar" />
      <Field label="Porcentaje sugerido" inputMode="numeric" value={pct} onChange={(e) => setPct(e.target.value.replace(/[^\d.]/g, ''))} onBlur={() => save(floor.tipsEnabled)} hint="Se sugiere sobre el total; el cliente puede no dar propina o dar otro valor." />
      <Banner tone="primary">
        <span className="flex items-start gap-1">
          <Icon name="info" className="text-[16px]" /> La propina es voluntaria y va aparte del total de la venta: no es ingreso de la tienda ni lleva impuestos. La
          que se recibe en efectivo se suma al efectivo esperado del turno.
        </span>
      </Banner>
    </Card>
  )
}
