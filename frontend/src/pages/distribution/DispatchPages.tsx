import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, EmptyState, Field, Icon, InfoTip, Modal, SelectField, Spinner, StatCard, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty } from '../../lib/format'
import { METHOD_LABELS, type StoreSettings } from '../../lib/posTypes'
import { PRESALE_STATUS, type DispatchDto, type PickList, type PresaleOrder, type PresaleSummary, type RouteDto } from '../../lib/distributionTypes'

type Tab = 'PENDIENTE' | 'ALISTADO' | 'EN_RUTA' | 'CERRADO'

/** Hoja para imprimir: en pantalla no se ve; al imprimir solo sale esto. */
function PrintSheet({ children }: { children: ReactNode }) {
  return createPortal(
    <div id="print-area" aria-hidden>
      <div className="p-6 text-[12px] text-black">{children}</div>
    </div>,
    document.body,
  )
}

/**
 * Despachos: los pedidos de preventa que llegan se alistan (consolidado para la bodega), salen en un despacho con su
 * remisión, se entregan (total, parcial o devuelto) y al volver se cierra la ruta cuadrando el dinero.
 */
export default function DispatchPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const [tab, setTab] = useState<Tab>('PENDIENTE')
  const [orders, setOrders] = useState<PresaleSummary[] | null>(null)
  const [dispatches, setDispatches] = useState<DispatchDto[] | null>(null)
  const [selected, setSelected] = useState<number[]>([])
  const [pick, setPick] = useState<PickList | null>(null)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    setSelected([])
    if (tab === 'PENDIENTE' || tab === 'ALISTADO') {
      setOrders(null)
      api<PresaleSummary[]>(`/distribution/orders?status=${tab}`)
        .then(setOrders)
        .catch((e) => setError(e.message))
    } else {
      setDispatches(null)
      api<DispatchDto[]>(`/distribution/dispatches?status=${tab}`)
        .then(setDispatches)
        .catch((e) => setError(e.message))
    }
  }, [tab])
  useEffect(load, [load])

  const toggle = (id: number) => setSelected(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])

  async function doPick() {
    setBusy(true)
    setError('')
    try {
      setPick(
        await api<PickList>('/distribution/pick', {
          body: { orderIds: selected },
        }),
      )
      toast(`${selected.length} pedidos alistados`)
      load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo alistar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight">
          Despachos
          <InfoTip text="1) Elige los pedidos por alistar y toca Alistar: sale el consolidado para la bodega. 2) Elige los alistados y despáchalos con el conductor: cada pedido tiene su remisión. 3) En ruta, registra cada entrega (total, parcial o devuelta) y lo cobrado. 4) Al volver, cierra la ruta contando el efectivo." />
        </h1>
        <p className="text-sm text-muted">Preventa → alistamiento → despacho → entrega → cierre de ruta</p>
      </div>
      <div className="overflow-x-auto" data-guide="despachos-pasos">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'PENDIENTE', label: '1. Por alistar' },
            { value: 'ALISTADO', label: '2. Por despachar' },
            { value: 'EN_RUTA', label: '3. En ruta' },
            { value: 'CERRADO', label: 'Cerrados' },
          ]}
        />
      </div>
      {error && <Banner>{error}</Banner>}

      {(tab === 'PENDIENTE' || tab === 'ALISTADO') &&
        (!orders ? (
          <Spinner />
        ) : orders.length === 0 ? (
          <EmptyState
            icon="inventory_2"
            title={tab === 'PENDIENTE' ? 'No hay pedidos por alistar' : 'No hay pedidos por despachar'}
            text="Los pedidos de los vendedores aparecen aquí apenas llegan."
          />
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <Button size="sm" variant="secondary" onClick={() => setSelected(selected.length === orders.length ? [] : orders.map((o) => o.id))}>
                {selected.length === orders.length ? 'Quitar todos' : 'Elegir todos'}
              </Button>
              {tab === 'PENDIENTE' ? (
                <Button size="sm" icon="checklist" disabled={!selected.length} loading={busy} onClick={doPick}>
                  Alistar {selected.length || ''}
                </Button>
              ) : (
                <>
                  <Button
                    size="sm"
                    variant="secondary"
                    icon="print"
                    disabled={!selected.length}
                    onClick={() => api<PickList>(`/distribution/pick-list?ids=${selected.join(',')}`).then(setPick)}
                  >
                    Consolidado
                  </Button>
                  <Button size="sm" icon="local_shipping" disabled={!selected.length} onClick={() => setSending(true)}>
                    Despachar {selected.length || ''}
                  </Button>
                </>
              )}
            </div>
            <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card" data-guide="despachos-pedidos">
              {orders.map((o) => (
                <li key={o.id} className="flex items-center gap-3 px-4 py-3">
                  <input
                    type="checkbox"
                    checked={selected.includes(o.id)}
                    onChange={() => toggle(o.id)}
                    className="h-5 w-5"
                    aria-label={`Elegir pedido ${o.number}`}
                  />
                  <button type="button" onClick={() => toggle(o.id)} className="min-w-0 flex-1 text-left">
                    <div className="truncate font-semibold">
                      #{o.number} · {o.customerName}
                    </div>
                    <div className="truncate text-xs text-muted">
                      {o.routeName ?? 'Sin ruta'} · {o.sellerName} · {dateTime(o.takenAt)} · {o.items} {o.items === 1 ? 'producto' : 'productos'}
                      {o.offline && ' · tomado sin señal'}
                    </div>
                  </button>
                  <span className="num font-semibold">{money(o.total)}</span>
                </li>
              ))}
            </ul>
          </>
        ))}

      {(tab === 'EN_RUTA' || tab === 'CERRADO') &&
        (!dispatches ? (
          <Spinner />
        ) : dispatches.length === 0 ? (
          <EmptyState icon="local_shipping" title={tab === 'EN_RUTA' ? 'No hay vehículos en ruta' : 'Sin despachos cerrados'} />
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {dispatches.map((d) => (
              <button
                key={d.id}
                type="button"
                onClick={() => navigate(`/despachos/${d.id}`)}
                className="rounded-2xl border border-line bg-card p-4 text-left shadow-tier1"
              >
                <div className="flex items-center justify-between">
                  <span className="font-bold">
                    Despacho #{d.number} · {d.driverName}
                  </span>
                  <span className="num font-bold">{money(d.total)}</span>
                </div>
                <div className="text-xs text-muted">
                  {d.routeName ?? 'Sin ruta'} · {d.orders.length} pedidos · {dateTime(d.createdAt)}
                </div>
                {d.status === 'EN_RUTA' ? (
                  <div className="mt-1 text-sm font-semibold text-primary">{d.pending ? `${d.pending} por entregar` : 'Todo registrado: cierra la ruta'}</div>
                ) : (
                  <div className={`mt-1 text-sm font-semibold ${d.difference === 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
                    {d.difference === 0 ? 'Cuadró' : `Diferencia ${money(d.difference)}`}
                  </div>
                )}
              </button>
            ))}
          </div>
        ))}

      {pick && <PickModal pick={pick} onClose={() => setPick(null)} />}
      {sending && (
        <DispatchModal
          ids={selected}
          onClose={() => setSending(false)}
          onDone={(d) => {
            setSending(false)
            toast(`Despacho #${d.number} en ruta`)
            navigate(`/despachos/${d.id}`)
          }}
        />
      )}
    </div>
  )
}

function PickModal({ pick, onClose }: { pick: PickList; onClose: () => void }) {
  const short = pick.lines.filter((l) => l.shortage)
  const table = (
    <table className="w-full text-sm">
      <thead className="text-left text-xs text-muted uppercase">
        <tr>
          <th className="py-1">Producto</th>
          <th className="text-right">Alistar</th>
          <th className="text-right">En bodega</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-line-soft">
        {pick.lines.map((l) => (
          <tr key={l.presentationId} className={l.shortage ? 'text-danger-ink' : ''}>
            <td className="py-1.5">
              {l.productName}
              {l.presentationName && l.presentationName !== 'Unidad' && ` · ${l.presentationName}`}
            </td>
            <td className="num text-right font-bold">{qty(l.quantity)}</td>
            <td className="num text-right">{qty(l.stockUnits / (l.factor || 1))}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
  return (
    <Modal
      open
      wide
      title={`Consolidado de ${pick.orders.length} pedidos`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="print" onClick={() => window.print()}>
          Imprimir para la bodega
        </Button>
      }
    >
      <div className="space-y-3">
        {short.length > 0 && <Banner tone="warning">No alcanza lo de bodega para {short.length} producto(s): están en rojo.</Banner>}
        {table}
        <p className="text-xs text-muted">Pedidos: {pick.orders.map((o) => `#${o.number} ${o.customerName}`).join(' · ')}</p>
      </div>
      <PrintSheet>
        <h1 className="mb-1 text-lg font-bold">Consolidado de alistamiento</h1>
        <p className="mb-3">
          {new Date().toLocaleString('es-CO')} · pedidos {pick.orders.map((o) => `#${o.number}`).join(', ')}
        </p>
        {table}
      </PrintSheet>
    </Modal>
  )
}

function DispatchModal({ ids, onClose, onDone }: { ids: number[]; onClose: () => void; onDone: (d: DispatchDto) => void }) {
  const [routes, setRoutes] = useState<RouteDto[]>([])
  const [driver, setDriver] = useState('')
  const [vehicle, setVehicle] = useState('')
  const [routeId, setRouteId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(
    () =>
      void api<RouteDto[]>('/distribution/routes')
        .then(setRoutes)
        .catch(() => {}),
    [],
  )
  async function go() {
    setBusy(true)
    setError('')
    try {
      onDone(
        await api<DispatchDto>('/distribution/dispatches', {
          body: {
            orderIds: ids,
            driverName: driver,
            vehicle,
            routeId: routeId ? Number(routeId) : null,
          },
        }),
      )
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo despachar')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={`Despachar ${ids.length} pedidos`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="local_shipping" loading={busy} onClick={go}>
          Salir a ruta
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="Conductor" value={driver} onChange={(e) => setDriver(e.target.value)} placeholder="¿Quién lleva el pedido?" />
        <Field label="Vehículo (opcional)" value={vehicle} onChange={(e) => setVehicle(e.target.value)} placeholder="NPR placa ABC123" />
        <SelectField label="Ruta" value={routeId} onChange={(e) => setRouteId(e.target.value)}>
          <option value="">Sin ruta</option>
          {routes.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </SelectField>
      </div>
    </Modal>
  )
}

/** Un despacho en ruta: entregas, remisiones y cierre con el cuadre del dinero. */
export function DispatchDetailPage() {
  const { id } = useParams()
  const toast = useToast()
  const { me } = useAuth()
  const [d, setD] = useState<DispatchDto | null>(null)
  const [error, setError] = useState('')
  const [deliver, setDeliver] = useState<PresaleOrder | null>(null)
  const [remission, setRemission] = useState<PresaleOrder | null>(null)
  const [closing, setClosing] = useState(false)
  const [methods, setMethods] = useState<string[]>(['EFECTIVO'])
  const load = useCallback(
    () =>
      void api<DispatchDto>(`/distribution/dispatches/${id}`)
        .then(setD)
        .catch((e) => setError(e.message)),
    [id],
  )
  useEffect(() => {
    load()
    api<StoreSettings>('/settings/pos')
      .then((s) => setMethods(s.paymentMethods.split(',').filter((m) => m && m !== 'FIADO')))
      .catch(() => {})
  }, [load])

  if (!d) return error ? <Banner>{error}</Banner> : <Spinner />
  const open = d.status === 'EN_RUTA'
  const cash = d.collected.find((m) => m.method === 'EFECTIVO')?.amount ?? 0

  return (
    <div className="space-y-4">
      <Link to="/despachos" className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Despachos
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            Despacho #{d.number} · {d.driverName}
          </h1>
          <p className="text-sm text-muted">
            {d.routeName ?? 'Sin ruta'}
            {d.vehicle && ` · ${d.vehicle}`} · salió {dateTime(d.createdAt)}
            {d.closedAt && ` · cerrado ${dateTime(d.closedAt)} por ${d.closedBy}`}
          </p>
        </div>
        {open && (
          <Button icon="lock" onClick={() => setClosing(true)}>
            Cerrar ruta
          </Button>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Despachado" value={money(d.total)} icon="local_shipping" help="Valor de todos los pedidos que salieron." />
        <StatCard label="Entregado" value={money(d.delivered)} icon="task_alt" tone="success" help="Lo que el cliente recibió (se volvió venta)." />
        <StatCard
          label="Efectivo a entregar"
          value={money(cash)}
          icon="payments"
          tone="wallet"
          help="Lo cobrado en efectivo en las entregas: es lo que el conductor debe traer."
        />
        <StatCard
          label="A crédito"
          value={money(d.credit)}
          icon="menu_book"
          tone="navy"
          help="Lo entregado que el cliente no pagó: queda en su cartera (fiado)."
        />
      </div>
      {!open && d.cashCounted != null && (
        <Banner tone={d.difference === 0 ? 'success' : 'danger'}>
          Cierre: esperado {money(d.cashExpected)}, entregó {money(d.cashCounted)}
          {d.difference === 0 ? ' — cuadró.' : ` — diferencia ${money(d.difference)}.`}
          {d.notes && ` ${d.notes}`}
        </Banner>
      )}
      <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card" data-guide="despacho-entregas">
        {d.orders.map((o) => {
          const st = PRESALE_STATUS[o.status]
          return (
            <li key={o.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">
                  #{o.number} · {o.customerName}
                </div>
                <div className="truncate text-xs text-muted">
                  {o.customerAddress ?? o.customerPhone ?? ''} · {money(o.total)}
                  {o.deliveredAt && o.status !== 'DEVUELTO' && ` · entregado ${money(o.deliveredTotal)}`}
                  {o.creditTotal > 0 && ` · a crédito ${money(o.creditTotal)}`}
                  {o.returnReason && ` · ${o.returnReason}`}
                </div>
              </div>
              <span className={`rounded-lg px-2 py-0.5 text-xs font-semibold ${st.tone}`}>{st.label}</span>
              <Button size="sm" variant="secondary" icon="description" onClick={() => setRemission(o)}>
                Remisión
              </Button>
              {o.status === 'DESPACHADO' && (
                <Button size="sm" icon="where_to_vote" onClick={() => setDeliver(o)}>
                  Entregar
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      {d.collected.length > 0 && (
        <p className="text-sm text-muted">
          Cobrado: {d.collected.map((m) => `${METHOD_LABELS[m.method] ?? m.method} ${money(m.amount)}`).join(' · ')}
          {d.returned > 0 && ` · devuelto ${money(d.returned)}`}
        </p>
      )}

      {deliver && (
        <DeliverModal
          order={deliver}
          methods={methods}
          onClose={() => setDeliver(null)}
          onDone={(o) => {
            setDeliver(null)
            toast(`Pedido #${o.number}: ${PRESALE_STATUS[o.status].label.toLowerCase()}`)
            load()
          }}
        />
      )}
      {remission && <RemissionModal order={remission} dispatch={d} storeName={me?.tenant.name ?? ''} onClose={() => setRemission(null)} />}
      {closing && (
        <CloseModal
          d={d}
          cash={cash}
          onClose={() => setClosing(false)}
          onDone={(x) => {
            setClosing(false)
            setD(x)
            toast(x.difference === 0 ? 'Ruta cerrada: cuadró' : `Ruta cerrada con diferencia de ${money(x.difference)}`)
          }}
        />
      )}
    </div>
  )
}

function DeliverModal({ order, methods, onClose, onDone }: { order: PresaleOrder; methods: string[]; onClose: () => void; onDone: (o: PresaleOrder) => void }) {
  const [q, setQ] = useState<Record<number, string>>(() => Object.fromEntries(order.lines.map((l) => [l.id, String(l.quantity)])))
  const [method, setMethod] = useState(methods[0] ?? 'EFECTIVO')
  const [paid, setPaid] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const delivered = order.lines.reduce((s, l) => s + Math.min(Number(q[l.id] || 0), l.quantity) * l.unitPrice, 0)
  const partial = order.lines.some((l) => Number(q[l.id] || 0) < l.quantity)
  const amount = paid === null ? delivered : Number(paid || 0)
  const credit = Math.max(0, delivered - amount)

  async function go(nothing = false) {
    setBusy(true)
    setError('')
    try {
      const lines = order.lines.map((l) => ({
        lineId: l.id,
        deliveredQty: nothing ? 0 : Number(q[l.id] || 0),
      }))
      onDone(
        await api<PresaleOrder>(`/distribution/orders/${order.id}/deliver`, {
          body: {
            lines,
            payments: nothing || amount <= 0 ? [] : [{ method, amount: Math.min(amount, delivered) }],
            returnReason: nothing ? reason || 'El cliente no recibió' : partial ? reason : null,
          },
        }),
      )
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo registrar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={`Entrega del pedido #${order.number} · ${order.customerName}`}
      onClose={onClose}
      footer={
        <div className="flex w-full flex-col gap-2 sm:flex-row">
          <Button variant="secondary" icon="undo" loading={busy} onClick={() => go(true)}>
            No recibió nada
          </Button>
          <Button className="flex-1" icon="where_to_vote" loading={busy} onClick={() => go()} disabled={delivered <= 0}>
            Registrar entrega · {money(delivered)}
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted uppercase">
            <tr>
              <th className="py-1">Producto</th>
              <th className="text-right">Pedido</th>
              <th className="text-right">Recibió</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line-soft">
            {order.lines.map((l) => (
              <tr key={l.id}>
                <td className="py-1.5">
                  {l.productName} <span className="text-xs text-muted">{money(l.unitPrice)}</span>
                </td>
                <td className="num text-right">{qty(l.quantity)}</td>
                <td className="text-right">
                  <input
                    inputMode="decimal"
                    value={q[l.id] ?? ''}
                    onChange={(e) =>
                      setQ({
                        ...q,
                        [l.id]: e.target.value.replace(/[^\d.]/g, ''),
                      })
                    }
                    className={`num h-9 w-16 rounded-lg border text-center ${Number(q[l.id] || 0) < l.quantity ? 'border-warning bg-warning-soft' : 'border-line'}`}
                    aria-label={`Recibió de ${l.productName}`}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {partial && (
          <Field
            label="¿Por qué no se entregó todo?"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="No le alcanzó la plata, producto averiado…"
          />
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Cobró con" value={method} onChange={(e) => setMethod(e.target.value)}>
            {methods.map((m) => (
              <option key={m} value={m}>
                {METHOD_LABELS[m] ?? m}
              </option>
            ))}
          </SelectField>
          <Field
            label="Valor cobrado"
            inputMode="numeric"
            value={paid ?? String(Math.round(delivered))}
            onChange={(e) => setPaid(e.target.value.replace(/\D/g, ''))}
            hint={credit > 0 ? `Queda a crédito (cartera): ${money(credit)}` : 'Pagó todo'}
          />
        </div>
      </div>
    </Modal>
  )
}

function RemissionModal({ order, dispatch, storeName, onClose }: { order: PresaleOrder; dispatch: DispatchDto; storeName: string; onClose: () => void }) {
  const doc = (
    <div className="space-y-3">
      <div className="flex justify-between gap-4">
        <div>
          <div className="text-lg font-bold">{storeName}</div>
          <div>Remisión del pedido #{order.number}</div>
        </div>
        <div className="text-right">
          <div>Despacho #{dispatch.number}</div>
          <div>{new Date(dispatch.createdAt).toLocaleDateString('es-CO')}</div>
          <div>Conductor: {dispatch.driverName}</div>
        </div>
      </div>
      <div>
        <b>Cliente:</b> {order.customerName}
        {order.customerDoc && ` · ${order.customerDoc}`}
        <br />
        {order.customerAddress && (
          <>
            <b>Dirección:</b> {order.customerAddress}
            <br />
          </>
        )}
        {order.customerPhone && (
          <>
            <b>Teléfono:</b> {order.customerPhone}
            <br />
          </>
        )}
        <b>Vendedor:</b> {order.sellerName}
      </div>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-black text-left">
            <th className="py-1">Producto</th>
            <th className="text-right">Cant.</th>
            <th className="text-right">Precio</th>
            <th className="text-right">Total</th>
            <th className="text-right">Recibido</th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((l) => (
            <tr key={l.id} className="border-b border-slate-300">
              <td className="py-1">{l.productName}</td>
              <td className="text-right">{qty(l.quantity)}</td>
              <td className="text-right">{money(l.unitPrice)}</td>
              <td className="text-right">{money(l.lineTotal)}</td>
              <td className="text-right">{l.deliveredQty != null ? qty(l.deliveredQty) : '______'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="text-right text-base font-bold">Total: {money(order.total)}</div>
      {order.notes && <div>Nota: {order.notes}</div>}
      <div className="grid grid-cols-2 gap-8 pt-10">
        <div className="border-t border-black pt-1 text-center">Entregó</div>
        <div className="border-t border-black pt-1 text-center">Recibió (nombre, firma y sello)</div>
      </div>
      <p className="text-[10px] text-slate-500">Documento de entrega de mercancía. No es factura de venta.</p>
    </div>
  )
  return (
    <Modal
      open
      wide
      title={`Remisión · pedido #${order.number}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="print" onClick={() => window.print()}>
          Imprimir
        </Button>
      }
    >
      {doc}
      <PrintSheet>{doc}</PrintSheet>
    </Modal>
  )
}

function CloseModal({ d, cash, onClose, onDone }: { d: DispatchDto; cash: number; onClose: () => void; onDone: (d: DispatchDto) => void }) {
  const [counted, setCounted] = useState(String(Math.round(cash)))
  const [notes, setNotes] = useState('')
  const [returnPending, setReturnPending] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const diff = Number(counted || 0) - cash
  async function go() {
    setBusy(true)
    setError('')
    try {
      onDone(
        await api<DispatchDto>(`/distribution/dispatches/${d.id}/close`, {
          body: { cashCounted: Number(counted || 0), notes, returnPending },
        }),
      )
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cerrar')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={`Cerrar despacho #${d.number}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="lock" loading={busy} onClick={go}>
          Cerrar ruta
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <div className="rounded-xl bg-canvas p-3 text-sm">
          Efectivo cobrado en las entregas: <b className="num">{money(cash)}</b>
        </div>
        <Field label="Efectivo que entrega el conductor" inputMode="numeric" value={counted} onChange={(e) => setCounted(e.target.value.replace(/\D/g, ''))} />
        <div className={`text-sm font-semibold ${diff === 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
          {diff === 0 ? 'Cuadra' : diff > 0 ? `Sobran ${money(diff)}` : `Faltan ${money(-diff)}`}
        </div>
        {d.pending > 0 && (
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" checked={returnPending} onChange={(e) => setReturnPending(e.target.checked)} className="mt-1" />
            <span>Hay {d.pending} pedido(s) sin registrar: márcalos como devueltos (la mercancía vuelve a la bodega).</span>
          </label>
        )}
        <Field label="Nota (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} />
        <p className="text-xs text-muted">Si tienes la caja abierta, el efectivo entra a tu turno como "Recaudo de ruta".</p>
      </div>
    </Modal>
  )
}
