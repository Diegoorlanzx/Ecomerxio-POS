import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, EmptyState, Field, Icon, InfoTip, Modal, SelectField, Spinner, StatCard, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, shortDate } from '../../lib/format'
import type { ProductDto } from '../../lib/posTypes'
import { todayIso } from '../../lib/servicesTypes'
import { DAY_SHORT, type DistributionReport, type PriceListDto, type RouteCustomer, type RouteDto } from '../../lib/distributionTypes'

type Tab = 'listas' | 'rutas' | 'clientes'

/** Configurar distribución: listas de precios por tipo de cliente, rutas con vendedor y días, y clientes en cada ruta. */
export default function DistributionSettingsPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) ?? 'listas'
  const setTab = (t: Tab) => setParams(t === 'listas' ? {} : { tab: t }, { replace: true })
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Configurar distribución</h1>
        <p className="text-sm text-muted">Listas de precios, rutas con su vendedor y días de visita, y qué clientes van en cada ruta.</p>
      </div>
      <div className="overflow-x-auto">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'listas', label: 'Listas de precios' },
            { value: 'rutas', label: 'Rutas' },
            { value: 'clientes', label: 'Clientes por ruta' },
          ]}
        />
      </div>
      {tab === 'listas' && <ListsTab />}
      {tab === 'rutas' && <RoutesTab />}
      {tab === 'clientes' && <CustomersTab />}
    </div>
  )
}

function ListsTab() {
  const [lists, setLists] = useState<PriceListDto[] | null>(null)
  const [editing, setEditing] = useState<Partial<PriceListDto> | null>(null)
  const load = useCallback(
    () =>
      void api<PriceListDto[]>('/distribution/price-lists')
        .then(setLists)
        .catch(() => setLists([])),
    [],
  )
  useEffect(load, [load])
  if (!lists) return <Spinner />
  return (
    <div className="space-y-3" data-guide="distribucion-listas">
      <div className="flex justify-end">
        <Button icon="add" onClick={() => setEditing({ discountPct: 0, items: [] })}>
          Nueva lista
        </Button>
      </div>
      {lists.length === 0 ? (
        <EmptyState
          icon="sell"
          title="Sin listas de precios"
          text="Crea una lista por tipo de cliente (tiendas, mayoristas, especiales) con su descuento y precios por volumen."
        />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {lists.map((l) => (
            <Card key={l.id} className={`p-4 ${l.active ? '' : 'opacity-60'}`}>
              <div className="flex items-start justify-between">
                <span className="font-bold">{l.name}</span>
                <Button size="sm" variant="secondary" icon="edit" onClick={() => setEditing(l)}>
                  Editar
                </Button>
              </div>
              <div className="text-sm text-muted">
                {l.discountPct > 0 ? `${l.discountPct}% menos en todo` : 'Precio de venta'} · {l.items.length} precio(s) especial(es) · {l.customers} cliente(s)
              </div>
            </Card>
          ))}
        </div>
      )}
      {editing && <ListEditor initial={editing} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), load())} />}
    </div>
  )
}

interface Row {
  presentationId: number
  label: string
  basePrice: number
  minQty: string
  price: string
}

function ListEditor({ initial, onClose, onSaved }: { initial: Partial<PriceListDto>; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const [name, setName] = useState(initial.name ?? '')
  const [discount, setDiscount] = useState(String(initial.discountPct ?? 0))
  const [active, setActive] = useState(initial.active ?? true)
  const [rows, setRows] = useState<Row[]>(() =>
    (initial.items ?? []).map((i) => ({
      presentationId: i.presentationId,
      label: `Producto ${i.presentationId}`,
      basePrice: 0,
      minQty: String(i.minQty),
      price: String(i.price),
    })),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // Nombres de los productos de la lista.
  useEffect(() => {
    if (!initial.items?.length) return
    api<{ items: ProductDto[] }>('/products?size=500&status=ACTIVE&level=ITEMS')
      .then((r) => {
        const names = new Map<number, { label: string; price: number }>()
        r.items.forEach((p) =>
          p.presentations.forEach(
            (pr) =>
              pr.id &&
              names.set(pr.id, {
                label: `${p.name}${pr.name !== 'Unidad' ? ' · ' + pr.name : ''}`,
                price: Number(pr.price),
              }),
          ),
        )
        setRows((rs) =>
          rs.map((x) => ({
            ...x,
            label: names.get(x.presentationId)?.label ?? x.label,
            basePrice: names.get(x.presentationId)?.price ?? 0,
          })),
        )
      })
      .catch(() => {})
  }, [initial.items])

  async function save() {
    setBusy(true)
    setError('')
    try {
      const body = {
        name,
        discountPct: Number(discount) || 0,
        active,
        items: rows
          .filter((r) => r.price !== '')
          .map((r) => ({
            presentationId: r.presentationId,
            minQty: Number(r.minQty) || 1,
            price: Number(r.price),
          })),
      }
      if (initial.id)
        await api(`/distribution/price-lists/${initial.id}`, {
          method: 'PUT',
          body,
        })
      else await api('/distribution/price-lists', { body })
      toast('Lista guardada')
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={initial.id ? `Lista ${initial.name}` : 'Nueva lista de precios'}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="save" loading={busy} onClick={save}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Nombre" value={name} onChange={(e) => setName(e.target.value)} placeholder="Tiendas, Mayoristas…" />
          <Field
            label="% de descuento general"
            inputMode="decimal"
            value={discount}
            onChange={(e) => setDiscount(e.target.value.replace(/[^\d.]/g, ''))}
            hint="Para lo que no tenga precio especial abajo"
          />
        </div>
        <div>
          <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-muted uppercase">
            Precios especiales y por volumen
            <InfoTip text="Agrega un producto con su precio. Para descuento por volumen agrégalo otra vez con una cantidad mínima mayor: p. ej. desde 1 a 4.000 y desde 10 a 3.500." />
          </div>
          <ProductPicker
            level="ITEMS"
            placeholder="Agregar producto a la lista…"
            onPick={(p: ProductDto) => {
              const pr = p.presentations.find((x) => x.isBase) ?? p.presentations[0]
              if (pr?.id)
                setRows([
                  ...rows,
                  {
                    presentationId: pr.id,
                    label: p.name,
                    basePrice: Number(pr.price),
                    minQty: '1',
                    price: String(pr.price),
                  },
                ])
            }}
          />
          {rows.length > 0 && (
            <table className="mt-2 w-full text-sm">
              <thead className="text-left text-xs text-muted uppercase">
                <tr>
                  <th className="py-1">Producto</th>
                  <th>Desde</th>
                  <th>Precio</th>
                  <th />
                </tr>
              </thead>
              <tbody className="divide-y divide-line-soft">
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td className="py-1.5">
                      {r.label}
                      {r.basePrice > 0 && <div className="text-xs text-muted">Venta {money(r.basePrice)}</div>}
                    </td>
                    <td>
                      <input
                        inputMode="decimal"
                        value={r.minQty}
                        onChange={(e) =>
                          setRows(
                            rows.map((x, j) =>
                              j === i
                                ? {
                                    ...x,
                                    minQty: e.target.value.replace(/[^\d.]/g, ''),
                                  }
                                : x,
                            ),
                          )
                        }
                        className="num h-9 w-16 rounded-lg border border-line text-center"
                        aria-label="Cantidad mínima"
                      />
                    </td>
                    <td>
                      <input
                        inputMode="numeric"
                        value={r.price}
                        onChange={(e) =>
                          setRows(
                            rows.map((x, j) =>
                              j === i
                                ? {
                                    ...x,
                                    price: e.target.value.replace(/[^\d.]/g, ''),
                                  }
                                : x,
                            ),
                          )
                        }
                        className="num h-9 w-24 rounded-lg border border-line text-center"
                        aria-label="Precio"
                      />
                    </td>
                    <td className="text-right">
                      <button type="button" aria-label="Quitar" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                        <Icon name="delete" className="text-[18px] text-muted" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <Toggle checked={active} onChange={setActive} label="Activa" />
      </div>
    </Modal>
  )
}

function RoutesTab() {
  const [routes, setRoutes] = useState<RouteDto[] | null>(null)
  const [users, setUsers] = useState<{ id: number; fullName: string; active: boolean }[]>([])
  const [editing, setEditing] = useState<Partial<RouteDto> | null>(null)
  const load = useCallback(
    () =>
      void api<RouteDto[]>('/distribution/routes')
        .then(setRoutes)
        .catch(() => setRoutes([])),
    [],
  )
  useEffect(() => {
    load()
    api<{ id: number; fullName: string; active: boolean }[]>('/users')
      .then(setUsers)
      .catch(() => {})
  }, [load])
  if (!routes) return <Spinner />
  return (
    <div className="space-y-3" data-guide="distribucion-rutas">
      <div className="flex justify-end">
        <Button icon="add_road" onClick={() => setEditing({ visitDays: [], commissionPct: 0 })}>
          Nueva ruta
        </Button>
      </div>
      {routes.length === 0 ? (
        <EmptyState icon="route" title="Sin rutas" text="Una ruta agrupa clientes que se visitan el mismo día, con su vendedor." />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {routes.map((r) => (
            <Card key={r.id} className={`p-4 ${r.active ? '' : 'opacity-60'}`}>
              <div className="flex items-start justify-between">
                <span className="font-bold">{r.name}</span>
                <Button size="sm" variant="secondary" icon="edit" onClick={() => setEditing(r)}>
                  Editar
                </Button>
              </div>
              <div className="text-sm text-muted">
                {r.sellerName ?? 'Sin vendedor'} · {r.visitDays.length ? r.visitDays.map((d) => DAY_SHORT[d]).join(', ') : 'sin días'} · {r.customers}{' '}
                {r.customers === 1 ? 'cliente' : 'clientes'}
                {r.commissionPct > 0 && ` · comisión ${r.commissionPct}%`}
              </div>
            </Card>
          ))}
        </div>
      )}
      {editing && (
        <RouteEditor initial={editing} users={users.filter((u) => u.active)} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), load())} />
      )}
    </div>
  )
}

function RouteEditor({
  initial,
  users,
  onClose,
  onSaved,
}: {
  initial: Partial<RouteDto>
  users: { id: number; fullName: string }[]
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const [name, setName] = useState(initial.name ?? '')
  const [seller, setSeller] = useState(initial.sellerUserId ? String(initial.sellerUserId) : '')
  const [days, setDays] = useState<number[]>(initial.visitDays ?? [])
  const [pct, setPct] = useState(String(initial.commissionPct ?? 0))
  const [active, setActive] = useState(initial.active ?? true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save() {
    setBusy(true)
    setError('')
    try {
      const body = {
        name,
        sellerUserId: seller ? Number(seller) : null,
        visitDays: days,
        commissionPct: Number(pct) || 0,
        active,
      }
      if (initial.id)
        await api(`/distribution/routes/${initial.id}`, {
          method: 'PUT',
          body,
        })
      else await api('/distribution/routes', { body })
      toast('Ruta guardada')
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={initial.id ? `Ruta ${initial.name}` : 'Nueva ruta'}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="save" loading={busy} onClick={save}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre" value={name} onChange={(e) => setName(e.target.value)} placeholder="Ruta Norte" />
        <SelectField label="Vendedor" value={seller} onChange={(e) => setSeller(e.target.value)}>
          <option value="">Sin vendedor</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>
              {u.fullName}
            </option>
          ))}
        </SelectField>
        <div>
          <div className="mb-1.5 text-xs font-semibold text-muted uppercase">Días de visita</div>
          <div className="flex flex-wrap gap-2">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => {
              const on = days.includes(d)
              return (
                <button
                  key={d}
                  type="button"
                  onClick={() => setDays(on ? days.filter((x) => x !== d) : [...days, d])}
                  className={`h-10 w-12 rounded-xl border text-sm font-semibold ${on ? 'border-primary bg-primary text-white' : 'border-line bg-card'}`}
                >
                  {DAY_SHORT[d]}
                </button>
              )
            })}
          </div>
        </div>
        <Field
          label="% de comisión del vendedor"
          inputMode="decimal"
          value={pct}
          onChange={(e) => setPct(e.target.value.replace(/[^\d.]/g, ''))}
          hint="Sobre lo entregado de sus pedidos"
        />
        <Toggle checked={active} onChange={setActive} label="Activa" />
      </div>
    </Modal>
  )
}

function CustomersTab() {
  const toast = useToast()
  const [customers, setCustomers] = useState<RouteCustomer[] | null>(null)
  const [routes, setRoutes] = useState<RouteDto[]>([])
  const [lists, setLists] = useState<PriceListDto[]>([])
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState('')
  useEffect(() => {
    api<RouteCustomer[]>('/distribution/customers')
      .then(setCustomers)
      .catch(() => setCustomers([]))
    api<RouteDto[]>('/distribution/routes')
      .then(setRoutes)
      .catch(() => {})
    api<PriceListDto[]>('/distribution/price-lists')
      .then(setLists)
      .catch(() => {})
  }, [])
  async function update(c: RouteCustomer, patch: Partial<RouteCustomer>) {
    const next = { ...c, ...patch }
    setCustomers((cs) => cs!.map((x) => (x.id === c.id ? next : x)))
    try {
      await api(`/distribution/customers/${c.id}`, {
        method: 'PUT',
        body: {
          routeId: next.routeId,
          priceListId: next.priceListId,
          routeOrder: next.routeOrder,
        },
      })
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar')
    }
  }
  if (!customers) return <Spinner />
  const shown = customers.filter(
    (c) => (!filter || String(c.routeId ?? 'sin') === filter) && (!q.trim() || c.name.toLowerCase().includes(q.trim().toLowerCase())),
  )
  return (
    <div className="space-y-3" data-guide="distribucion-clientes">
      <div className="flex flex-wrap gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Buscar cliente"
          className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-card px-3 text-sm"
        />
        <select value={filter} onChange={(e) => setFilter(e.target.value)} className="h-10 rounded-xl border border-line bg-card px-2 text-sm">
          <option value="">Todas las rutas</option>
          <option value="sin">Sin ruta</option>
          {routes.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </div>
      {shown.length === 0 ? (
        <EmptyState icon="group" title="Sin clientes" text="Crea los clientes en la caja o en Fiados y asígnalos aquí a su ruta." />
      ) : (
        <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
          {shown.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center gap-2 px-4 py-2.5 text-sm">
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{c.name}</div>
                <div className="truncate text-xs text-muted">{c.address ?? c.phone ?? ''}</div>
              </div>
              <select
                value={c.routeId ?? ''}
                onChange={(e) =>
                  update(c, {
                    routeId: e.target.value ? Number(e.target.value) : null,
                  })
                }
                className="h-9 rounded-lg border border-line bg-card px-1.5"
                aria-label="Ruta"
              >
                <option value="">Sin ruta</option>
                {routes.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
              <input
                inputMode="numeric"
                value={c.routeOrder || ''}
                onChange={(e) =>
                  update(c, {
                    routeOrder: Number(e.target.value.replace(/\D/g, '')) || 0,
                  })
                }
                placeholder="Orden"
                className="num h-9 w-16 rounded-lg border border-line text-center"
                aria-label="Orden en la ruta"
              />
              <select
                value={c.priceListId ?? ''}
                onChange={(e) =>
                  update(c, {
                    priceListId: e.target.value ? Number(e.target.value) : null,
                  })
                }
                className="h-9 rounded-lg border border-line bg-card px-1.5"
                aria-label="Lista de precios"
              >
                <option value="">Precio de venta</option>
                {lists.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Reportes de distribución: ventas por vendedor (con su comisión) y por ruta (efectividad de visitas y cartera vencida). */
export function DistributionReportPage() {
  const today = new Date()
  const first = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-01`
  const [from, setFrom] = useState(first)
  const [to, setTo] = useState(todayIso())
  const [rep, setRep] = useState<DistributionReport | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<DistributionReport>(`/distribution/report?from=${from}&to=${to}`)
      .then((r) => (setRep(r), setError('')))
      .catch((e) => setError(e.message))
  }, [from, to])
  if (error) return <Banner>{error}</Banner>
  if (!rep) return <Spinner />
  const delivered = rep.sellers.reduce((s, x) => s + x.delivered, 0)
  const overdue = rep.routes.reduce((s, x) => s + x.overdue, 0)
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Reportes de distribución</h1>
          <p className="text-sm text-muted">
            Del {shortDate(rep.from)} al {shortDate(rep.to)}
          </p>
        </div>
        <div className="flex gap-2">
          <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <StatCard label="Entregado" value={money(delivered)} icon="task_alt" tone="success" help="Lo entregado de los pedidos tomados en el período." />
        <StatCard
          label="Cartera vencida en rutas"
          value={money(overdue)}
          icon="warning"
          tone="danger"
          help="Lo que deben, ya vencido, los clientes de las rutas (hoy)."
        />
      </div>
      <section>
        <h2 className="mb-2 text-sm font-semibold text-muted uppercase">Por vendedor</h2>
        <div className="overflow-x-auto rounded-2xl border border-line bg-card">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-2">Vendedor</th>
                <th className="text-right">Pedidos</th>
                <th className="text-right">Pedido</th>
                <th className="text-right">Entregado</th>
                <th className="px-4 text-right">Comisión</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-soft">
              {rep.sellers.map((s, i) => (
                <tr key={i}>
                  <td className="px-4 py-2 font-semibold">{s.sellerName ?? '—'}</td>
                  <td className="num text-right">{s.orders}</td>
                  <td className="num text-right">{money(s.ordered)}</td>
                  <td className="num text-right">{money(s.delivered)}</td>
                  <td className="num px-4 text-right font-semibold">{money(s.commission)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section>
        <h2 className="mb-2 flex items-center gap-1 text-sm font-semibold text-muted uppercase">
          Por ruta
          <InfoTip text="Efectividad = pedidos ÷ visitas programadas (clientes de la ruta × días de visita del período hasta hoy)." />
        </h2>
        <div className="overflow-x-auto rounded-2xl border border-line bg-card">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-2">Ruta</th>
                <th className="text-right">Clientes</th>
                <th className="text-right">Programadas</th>
                <th className="text-right">Visitas</th>
                <th className="text-right">Pedidos</th>
                <th className="text-right">Efectividad</th>
                <th className="text-right">Entregado</th>
                <th className="text-right">Devuelto</th>
                <th className="px-4 text-right">Vencido</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-soft">
              {rep.routes.map((r) => (
                <tr key={r.routeId}>
                  <td className="px-4 py-2">
                    <div className="font-semibold">{r.routeName}</div>
                    <div className="text-xs text-muted">{r.sellerName ?? ''}</div>
                  </td>
                  <td className="num text-right">{r.customers}</td>
                  <td className="num text-right">{r.scheduledVisits}</td>
                  <td className="num text-right">{r.visits}</td>
                  <td className="num text-right">{r.orders}</td>
                  <td className="num text-right font-semibold">{r.effectivenessPct}%</td>
                  <td className="num text-right">{money(r.delivered)}</td>
                  <td className="num text-right">{money(r.returned)}</td>
                  <td className={`num px-4 text-right ${r.overdue > 0 ? 'font-semibold text-danger-ink' : ''}`}>{money(r.overdue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
