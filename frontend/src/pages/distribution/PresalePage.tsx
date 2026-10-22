import { useCallback, useEffect, useMemo, useState } from 'react'
import { Banner, Button, EmptyState, Field, Icon, InfoTip, Modal, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, qty as fmtQty } from '../../lib/format'
import { DAY_SHORT, listPrice, type PresaleOrder, type SellerCustomer, type SellerData, type SellerProduct } from '../../lib/distributionTypes'

/** Lo hecho hoy en este celular (también lo que aún no se ha enviado por falta de señal). */
type Done = Record<number, { label: string; queued: boolean }>
const doneKey = (date: string) => `tp_presale_done:${date}`
function loadDone(date: string): Done {
  try {
    return JSON.parse(localStorage.getItem(doneKey(date)) ?? '{}')
  } catch {
    return {}
  }
}
function saveDone(date: string, d: Done) {
  try {
    localStorage.setItem(doneKey(date), JSON.stringify(d))
  } catch {
    /* sin almacenamiento */
  }
}
const newRef = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`)

/**
 * Preventa en el celular del vendedor: sus clientes de hoy en orden de ruta, con lo que deben; toca uno para tomar el
 * pedido con los precios de su lista. Sin señal también funciona: el pedido queda guardado y se envía solo al volver.
 */
export default function PresalePage() {
  const toast = useToast()
  const [data, setData] = useState<SellerData | null>(null)
  const [error, setError] = useState('')
  const [view, setView] = useState<'hoy' | 'todos'>('hoy')
  const [route, setRoute] = useState<number | null>(null)
  const [open, setOpen] = useState<SellerCustomer | null>(null)
  const [done, setDone] = useState<Done>({})
  const [online, setOnline] = useState(navigator.onLine)

  const load = useCallback(() => {
    api<SellerData>('/distribution/seller')
      .then((d) => {
        setData(d)
        setDone(loadDone(d.date))
        setError('')
      })
      .catch((e) => setError(e.message))
  }, [])
  useEffect(() => {
    load()
    const on = () => (setOnline(true), load())
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => (window.removeEventListener('online', on), window.removeEventListener('offline', off))
  }, [load])

  function mark(c: SellerCustomer, label: string, queued: boolean) {
    if (!data) return
    const next = { ...done, [c.id]: { label, queued } }
    setDone(next)
    saveDone(data.date, next)
  }

  if (error && !data) return <Banner>{error}</Banner>
  if (!data) return <Spinner />
  if (!data.routes.length)
    return <EmptyState icon="route" title="No tienes rutas asignadas" text="Pide que te asignen una ruta en Distribución → Configurar → Rutas." />

  const list = data.customers.filter((c) => (view === 'todos' || c.today) && (!route || c.routeId === route))
  const todayCount = data.customers.filter((c) => c.today).length
  const visited = data.customers.filter((c) => c.today && (c.visitResult || done[c.id])).length

  return (
    <div className="space-y-4">
      <div>
        <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight">
          Preventa
          <InfoTip text="Tus clientes de hoy en el orden de la ruta. Toca uno para tomarle el pedido con los precios de su lista. Si no hay señal, el pedido se guarda en el celular y se envía solo cuando vuelva." />
        </h1>
        <p className="text-sm text-muted">
          {DAY_SHORT[data.weekday]} · visitados {visited} de {todayCount} · {data.routes.map((r) => r.name).join(', ')}
        </p>
      </div>
      {!online && <Banner tone="warning">Sin señal: sigue tomando pedidos. Quedan guardados en este celular y se envían solos al volver la conexión.</Banner>}
      <div className="flex flex-wrap items-center gap-2">
        <Tabs
          value={view}
          onChange={setView}
          items={[
            { value: 'hoy', label: 'Hoy', count: todayCount },
            { value: 'todos', label: 'Todos', count: data.customers.length },
          ]}
        />
        {data.routes.length > 1 && (
          <select
            value={route ?? ''}
            onChange={(e) => setRoute(e.target.value ? Number(e.target.value) : null)}
            className="h-10 rounded-xl border border-line bg-card px-2 text-sm"
          >
            <option value="">Todas las rutas</option>
            {data.routes.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        )}
      </div>

      {list.length === 0 ? (
        <EmptyState
          icon="storefront"
          title={view === 'hoy' ? 'Hoy no hay visitas en tu ruta' : 'Sin clientes'}
          text="Revisa los días de visita de la ruta o mira todos los clientes."
        />
      ) : (
        <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card" data-guide="preventa-clientes">
          {list.map((c) => {
            const local = done[c.id]
            const status =
              local?.label ?? (c.orderToday ? 'Con pedido' : c.visitResult === 'NO_PIDIO' ? 'No pidió' : c.visitResult === 'CERRADO' ? 'Cerrado' : null)
            return (
              <li key={c.id}>
                <button type="button" onClick={() => setOpen(c)} className="flex w-full items-center gap-3 px-4 py-3 text-left">
                  <span className="num w-6 shrink-0 text-xs font-bold text-muted">{c.routeOrder || ''}</span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-semibold">{c.name}</div>
                    <div className="truncate text-xs text-muted">{c.address ?? c.phone ?? ''}</div>
                    {c.balance > 0 && (
                      <div className={`text-xs font-semibold ${c.overdue > 0 ? 'text-danger-ink' : 'text-warning-ink'}`}>
                        Debe {money(c.balance)}
                        {c.overdue > 0 && ` · vencido ${money(c.overdue)}`}
                      </div>
                    )}
                  </div>
                  {status ? (
                    <span
                      className={`rounded-lg px-2 py-0.5 text-xs font-semibold ${local?.queued ? 'bg-warning-soft text-warning-ink' : status === 'Con pedido' || status.startsWith('Pedido') ? 'bg-success-soft text-success-ink' : 'bg-slate-100 text-slate-600'}`}
                    >
                      {status}
                    </span>
                  ) : (
                    <Icon name="chevron_right" className="text-muted" />
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {open && (
        <OrderSheet
          data={data}
          customer={open}
          onClose={() => setOpen(null)}
          onDone={(label, queued) => {
            mark(open, label, queued)
            setOpen(null)
            toast(queued ? `${label}: guardado en el celular, se envía al volver la señal` : label)
          }}
        />
      )}
    </div>
  )
}

function OrderSheet({
  data,
  customer,
  onClose,
  onDone,
}: {
  data: SellerData
  customer: SellerCustomer
  onClose: () => void
  onDone: (label: string, queued: boolean) => void
}) {
  const [q, setQ] = useState('')
  const [cart, setCart] = useState<Record<number, number>>({})
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [ref] = useState(newRef)
  const byPres = useMemo(() => new Map(data.products.map((p) => [p.presentationId, p])), [data.products])

  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    const base = t
      ? data.products.filter((p) => `${p.name} ${p.presentationName}`.toLowerCase().includes(t))
      : data.products.filter((p) => cart[p.presentationId])
    return base.slice(0, 60)
  }, [q, data.products, cart])

  const lines = Object.entries(cart)
    .filter(([, n]) => n > 0)
    .map(([id, n]) => {
      const p = byPres.get(Number(id))!
      const price = listPrice(data.priceLists, customer.priceListId, p, n)
      return { p, n, price, total: price * n }
    })
  const total = lines.reduce((s, l) => s + l.total, 0)
  const listName = data.priceLists.find((l) => l.id === customer.priceListId)?.name

  const set = (p: SellerProduct, n: number) => setCart({ ...cart, [p.presentationId]: Math.max(0, n) })

  async function send() {
    setBusy('send')
    setError('')
    try {
      const o = await api<PresaleOrder>('/distribution/orders', {
        body: {
          clientRef: ref,
          customerId: customer.id,
          notes,
          takenAt: new Date().toISOString(),
          offline: !navigator.onLine,
          lines: lines.map((l) => ({
            presentationId: l.p.presentationId,
            quantity: l.n,
          })),
        },
      })
      onDone(`Pedido #${o.number}`, false)
    } catch (e) {
      if (e instanceof ApiError && e.queued) onDone('Pedido guardado', true)
      else setError(e instanceof ApiError ? e.message : 'No se pudo enviar')
    } finally {
      setBusy('')
    }
  }

  async function visit(result: 'NO_PIDIO' | 'CERRADO') {
    setBusy(result)
    try {
      await api('/distribution/visits', {
        body: {
          clientRef: ref + ':' + result,
          customerId: customer.id,
          result,
        },
      })
      onDone(result === 'NO_PIDIO' ? 'No pidió' : 'Cerrado', false)
    } catch (e) {
      if (e instanceof ApiError && e.queued) onDone(result === 'NO_PIDIO' ? 'No pidió' : 'Cerrado', true)
      else setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy('')
    }
  }

  return (
    <Modal
      open
      wide
      title={customer.name}
      onClose={onClose}
      footer={
        <div className="flex w-full flex-col gap-2">
          <Button className="w-full" icon="send" loading={busy === 'send'} disabled={!lines.length} onClick={send}>
            {lines.length ? `Enviar pedido · ${money(total)}` : 'Agrega productos'}
          </Button>
          <div className="grid grid-cols-2 gap-2">
            <Button variant="secondary" size="sm" icon="do_not_disturb_on" loading={busy === 'NO_PIDIO'} onClick={() => visit('NO_PIDIO')}>
              No pidió
            </Button>
            <Button variant="secondary" size="sm" icon="door_front" loading={busy === 'CERRADO'} onClick={() => visit('CERRADO')}>
              Estaba cerrado
            </Button>
          </div>
        </div>
      }
    >
      <div className="space-y-3" data-guide="preventa-pedido">
        {error && <Banner>{error}</Banner>}
        <div className="flex flex-wrap gap-2 text-xs">
          {listName && <span className="rounded-lg bg-primary-tint px-2 py-1 font-semibold text-primary">Lista: {listName}</span>}
          {customer.balance > 0 && (
            <span
              className={`rounded-lg px-2 py-1 font-semibold ${customer.overdue > 0 ? 'bg-danger-soft text-danger-ink' : 'bg-warning-soft text-warning-ink'}`}
            >
              Debe {money(customer.balance)}
              {customer.overdue > 0 && ` (vencido ${money(customer.overdue)})`}
            </span>
          )}
          {customer.blocked && <span className="rounded-lg bg-danger-soft px-2 py-1 font-semibold text-danger-ink">Crédito bloqueado</span>}
        </div>
        <Field label="Buscar producto" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Arroz, aceite…" />
        {shown.length === 0 && !q && <p className="text-sm text-muted">Busca los productos que pide el cliente.</p>}
        <ul className="divide-y divide-line-soft">
          {shown.map((p) => {
            const n = cart[p.presentationId] ?? 0
            const price = listPrice(data.priceLists, customer.priceListId, p, Math.max(1, n))
            return (
              <li key={p.presentationId} className="flex items-center gap-2 py-2">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">
                    {p.name}
                    {p.presentationName !== 'Unidad' && <span className="text-muted"> · {p.presentationName}</span>}
                  </div>
                  <div className="text-xs text-muted">
                    {money(price)}
                    {price < p.price && <s className="ml-1">{money(p.price)}</s>} · hay {fmtQty(p.stock / (p.factor || 1))}
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <Button size="sm" variant="secondary" icon="remove" onClick={() => set(p, n - 1)} disabled={!n}>
                    <span className="sr-only">Menos</span>
                  </Button>
                  <input
                    inputMode="numeric"
                    value={n || ''}
                    onChange={(e) => set(p, Number(e.target.value.replace(/\D/g, '')) || 0)}
                    className="num h-9 w-12 rounded-lg border border-line text-center"
                    aria-label={`Cantidad de ${p.name}`}
                  />
                  <Button size="sm" variant="secondary" icon="add" onClick={() => set(p, n + 1)}>
                    <span className="sr-only">Más</span>
                  </Button>
                </div>
              </li>
            )
          })}
        </ul>
        {lines.length > 0 && (
          <div className="rounded-xl bg-canvas p-3 text-sm">
            <div className="mb-1 font-semibold">Pedido ({lines.length})</div>
            {lines.map((l) => (
              <div key={l.p.presentationId} className="flex justify-between">
                <span>
                  {l.n} × {l.p.name}
                </span>
                <span className="num">{money(l.total)}</span>
              </div>
            ))}
            <div className="mt-1 flex justify-between border-t border-line-soft pt-1 font-bold">
              <span>Total</span>
              <span className="num">{money(total)}</span>
            </div>
          </div>
        )}
        <Field label="Nota (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={500} placeholder="Entregar en la mañana…" />
      </div>
    </Modal>
  )
}
