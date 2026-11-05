import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import QrCode from '../../components/QrCode'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import type { PosProduct } from '../../lib/posTypes'
import type { DiningTableDto, Floor, ModifierGroupDto, StationCategory } from '../../lib/restaurantTypes'

/** Dirección del QR de una mesa: la carta de la tienda en línea con el código de la mesa. */
const tableUrl = (slug: string, token: string) => `${window.location.origin}/t/${slug}?mesa=${token}`

/** Imprime tarjetas con el QR de cada mesa (una por mesa, para poner en un portarretrato o pegar en la mesa). */
function printTableCards(cards: { name: string; svg: string }[], store: string) {
  const w = window.open('', '_blank', 'width=800,height=900')
  if (!w) return
  const esc = (x: string) => x.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]!)
  w.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>QR de las mesas</title><style>
    body{font-family:Arial,Helvetica,sans-serif;margin:0;padding:10mm}
    .grid{display:grid;grid-template-columns:repeat(2,1fr);gap:8mm}
    .card{border:1px solid #999;border-radius:6mm;padding:6mm;text-align:center;page-break-inside:avoid}
    .card svg{width:55mm;height:55mm}
    .t{font-size:26px;font-weight:800;margin:2mm 0}
    .s{font-size:13px;color:#333}
    .h{font-size:15px;font-weight:700;margin-top:2mm}
  </style></head><body><div class="grid">${cards
    .map((c) => `<div class="card"><div class="s">${esc(store)}</div><div class="t">${esc(c.name)}</div>${c.svg}<div class="h">Escanea, mira la carta y pide desde aquí</div><div class="s">Pagas en la mesa</div></div>`)
    .join('')}</div><script>window.onload=()=>setTimeout(()=>window.print(),300)</script></body></html>`)
  w.document.close()
}

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
  const [qr, setQr] = useState<DiningTableDto | null>(null)
  const [online, setOnline] = useState<{ slug: string; enabled: boolean; tableOrders: boolean } | null>(null)
  const { me, has } = useAuth()
  const load = useCallback(() => void api<Floor>('/restaurant/floor').then(setFloor), [])
  useEffect(load, [load])
  useEffect(() => {
    if (has('TIENDA_ONLINE')) api<{ slug: string; enabled: boolean; tableOrders: boolean }>('/online-store').then(setOnline).catch(() => {})
  }, [has])
  const qrReady = !!online && online.enabled && online.tableOrders

  function printAll(list: DiningTableDto[]) {
    if (!online) return
    const cards = list.map((t) => {
      const holder = document.getElementById(`qr-${t.id}`)
      return { name: t.name, svg: holder?.innerHTML ?? '' }
    })
    printTableCards(cards, me?.tenant.name ?? '')
  }

  async function newQr(t: DiningTableDto) {
    try {
      const updated = await api<DiningTableDto>(`/restaurant/tables/${t.id}/qr`, { body: {} })
      setQr({ ...t, qrToken: updated.qrToken })
      toast('Código nuevo: imprime de nuevo el QR de esta mesa')
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo', 'danger')
    }
  }

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
      <Card className="space-y-2 p-4">
        <h2 className="flex items-center gap-2 font-semibold">
          <Icon name="qr_code_2" className="text-[20px] text-primary" /> Pedidos desde la mesa con QR
        </h2>
        {!has('TIENDA_ONLINE') ? (
          <p className="text-sm text-muted">
            Enciende el módulo “Tienda en línea y catálogo QR” en <Link to="/negocio" className="font-semibold text-primary underline">Tipo de negocio</Link> para que tus clientes vean la carta y pidan desde su mesa.
          </p>
        ) : !qrReady ? (
          <p className="text-sm text-muted">
            Activa la tienda en línea y “Recibir pedidos desde la mesa” en <Link to="/tienda-online" className="font-semibold text-primary underline">Tienda en línea</Link>. Después imprime el QR de cada mesa aquí.
          </p>
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm text-muted">El cliente escanea, ve la carta y pide; el pedido le llega al mesero en el plano para pasarlo a la cuenta. Se paga en la mesa.</p>
            <Button size="sm" variant="secondary" icon="print" disabled={!floor.tables.length} onClick={() => printAll(floor.tables)}>
              Imprimir QR de todas
            </Button>
          </div>
        )}
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
                {qrReady && (
                  <Button size="sm" variant="ghost" icon="qr_code_2" onClick={() => setQr(t)}>
                    <span className="sr-only">QR de {t.name}</span>
                  </Button>
                )}
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
      {/* QR de cada mesa (ocultos) para imprimir todas las tarjetas */}
      {qrReady && (
        <div className="hidden" aria-hidden="true">
          {floor.tables.map((t) => (
            <div key={t.id} id={`qr-${t.id}`}>
              <QrCode text={tableUrl(online!.slug, t.qrToken)} size={220} />
            </div>
          ))}
        </div>
      )}
      {qr && online && (
        <Modal open title={`QR de ${qr.name}`} onClose={() => setQr(null)}>
          <div className="space-y-3 text-center">
            <div className="flex justify-center">
              <QrCode text={tableUrl(online.slug, qr.qrToken)} size={220} />
            </div>
            <a href={tableUrl(online.slug, qr.qrToken)} target="_blank" rel="noreferrer" className="block truncate text-xs text-primary underline">
              {tableUrl(online.slug, qr.qrToken)}
            </a>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" icon="autorenew" onClick={() => newQr(qr)}>
                Cambiar código
              </Button>
              <Button className="flex-1" icon="print" onClick={() => printAll([qr])}>
                Imprimir
              </Button>
            </div>
            <p className="text-xs text-muted">Cambia el código si alguien se llevó la foto del QR y hace pedidos que no son de la mesa.</p>
          </div>
        </Modal>
      )}
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
  /** stockId: producto que sale del inventario al elegir la opción (gaseosa a elección); stockQty por plato. */
  options: { id?: number; name: string; price: string; stockId: string; stockQty: string }[]
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
  /** Productos que una opción puede descontar: simples (no combos ni productos con variantes). */
  const stockable = useMemo(() => products.filter((p) => p.productType !== 'COMBO' && p.productType !== 'PADRE'), [products])

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
          options: form.options
            .filter((o) => o.name.trim())
            .map((o) => ({
              id: o.id,
              name: o.name,
              price: Number(o.price.replace(/\D/g, '')) || 0,
              productId: o.stockId ? Number(o.stockId) : null,
              productQty: o.stockId ? Number(o.stockQty.replace(',', '.')) || 1 : null,
            })),
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
        <Button icon="add" onClick={() => (setError(''), setForm({ name: '', minSelect: '0', maxSelect: '1', options: [{ name: '', price: '', stockId: '', stockQty: '1' }], productIds: [] }))}>
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
                      options: g.options.map((o) => ({
                        id: o.id,
                        name: o.name,
                        price: o.price ? String(o.price) : '',
                        stockId: o.productId ? String(o.productId) : '',
                        stockQty: o.productQty ? String(Number(o.productQty)) : '1',
                      })),
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
                <Chip key={o.id} icon={o.productId ? 'inventory_2' : undefined}>
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
              <p className="text-xs text-muted">
                Si la opción es un producto que tienes en inventario (la gaseosa del combo, el queso de la adición), elígelo en “Descuenta”: al cobrar sale del inventario y suma al costo del plato.
              </p>
              {form.options.map((o, i) => (
                <div key={i} className="space-y-1.5 rounded-xl border border-line-soft p-2">
                <div className="flex gap-2">
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
                <div className="flex items-center gap-2 text-xs">
                  <span className="shrink-0 text-muted">Descuenta</span>
                  <select
                    className="h-9 min-w-0 flex-1 rounded-lg border border-line bg-card px-2"
                    value={o.stockId}
                    onChange={(e) => setForm({ ...form, options: form.options.map((x, j) => (j === i ? { ...x, stockId: e.target.value } : x)) })}
                    aria-label={`Producto que descuenta ${o.name || 'la opción'}`}
                  >
                    <option value="">Nada del inventario</option>
                    {stockable.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  {o.stockId && (
                    <input
                      className="num h-9 w-20 rounded-lg border border-line px-2"
                      inputMode="decimal"
                      aria-label="Cantidad por plato"
                      title="Cantidad por plato"
                      value={o.stockQty}
                      onChange={(e) => setForm({ ...form, options: form.options.map((x, j) => (j === i ? { ...x, stockQty: e.target.value.replace(/[^0-9.,]/g, '') } : x)) })}
                    />
                  )}
                </div>
                </div>
              ))}
              <Button size="sm" variant="secondary" icon="add" onClick={() => setForm({ ...form, options: [...form.options, { name: '', price: '', stockId: '', stockQty: '1' }] })}>
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
