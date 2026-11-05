import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Banner, Button, EmptyState, Field, Icon, Modal, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, qty } from '../../lib/format'
import { playAlert, unlockSound } from '../../lib/kitchenAlerts'
import { TABLE_STATE, type DiningTableDto, type Floor, type TableOrder } from '../../lib/restaurantTypes'
import type { SaleDto } from '../../lib/posTypes'

const hour = (iso: string) => new Date(iso).toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' })

/**
 * Plano de mesas: cada mesa con su estado (libre, ocupada, por cobrar o con platos listos), el mesero, el total, el
 * tiempo y la próxima reserva. Tocar una mesa libre la abre; una ocupada abre su cuenta. Suena cuando la cocina
 * termina un plato de tus mesas y cuando un cliente pide desde el QR de su mesa.
 */
export default function FloorPage() {
  const { can, has, me } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [floor, setFloor] = useState<Floor | null>(null)
  const [area, setArea] = useState<string>('todas')
  const [error, setError] = useState('')
  const [opening, setOpening] = useState<DiningTableDto | null>(null)
  const [choose, setChoose] = useState<DiningTableDto | null>(null)
  const [orders, setOrders] = useState<TableOrder[]>([])
  const [review, setReview] = useState<TableOrder | null>(null)
  const [onlyMine, setOnlyMine] = useState(() => !can('POS_VENDER'))
  /** Platos listos que ya se avisaron, por cuenta (para sonar solo cuando aumentan). */
  const readySeen = useRef<Map<number, number> | null>(null)
  const ordersSeen = useRef<Set<number> | null>(null)

  const load = useCallback(() => {
    api<Floor>('/restaurant/floor')
      .then((f) => {
        setFloor(f)
        setError('')
        const before = readySeen.current
        const now = new Map<number, number>()
        const news: string[] = []
        for (const t of f.tables) {
          for (const a of t.accounts) {
            now.set(a.id, a.ready)
            if (before && a.mine && a.ready > (before.get(a.id) ?? 0)) news.push(t.name)
          }
        }
        readySeen.current = now
        if (news.length) {
          playAlert('ready')
          toast(`Platos listos para llevar: ${[...new Set(news)].join(', ')}`)
        }
      })
      .catch((e) => setError(e.message))
    if (has('TIENDA_ONLINE')) {
      api<TableOrder[]>('/restaurant/table-orders')
        .then((list) => {
          setOrders(list)
          const seen = ordersSeen.current
          if (seen && list.some((o) => !seen.has(o.id))) playAlert('order')
          ordersSeen.current = new Set(list.map((o) => o.id))
        })
        .catch(() => {})
    }
  }, [has, toast])
  useEffect(() => {
    load()
    const id = setInterval(load, 6_000) // la cocina va marcando platos listos
    return () => clearInterval(id)
  }, [load])

  const goAccount = (id: number) => navigate(`/pos?cuenta=${id}`)

  function tap(t: DiningTableDto) {
    if (t.accounts.length === 0) return setOpening(t)
    const mine = t.accounts.filter((a) => a.mine)
    if (!mine.length) return toast(`${t.name} la atiende ${t.accounts[0]!.waiterName}`, 'danger')
    if (mine.length === 1) return goAccount(mine[0]!.id)
    setChoose(t)
  }

  if (error) return <Banner>{error}</Banner>
  if (!floor) return <Spinner />
  const isMine = (t: DiningTableDto) => t.accounts.length === 0 || t.accounts.some((a) => a.mine && a.waiterName === me?.fullName)
  const shown = floor.tables.filter((t) => (area === 'todas' || String(t.areaId ?? 'sin') === area) && (!onlyMine || isMine(t)))
  const counts = floor.tables.reduce((acc, t) => ((acc[t.state] = (acc[t.state] ?? 0) + 1), acc), {} as Record<string, number>)

  return (
    <div className="space-y-4" onPointerDown={unlockSound}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Mesas</h1>
          <p className="text-sm text-muted">
            {counts.LIBRE ?? 0} libres · {(counts.OCUPADA ?? 0) + (counts.LISTO ?? 0)} ocupadas · {counts.POR_COBRAR ?? 0} por cobrar
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/reservas" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
            <Icon name="event_seat" className="text-[20px] text-primary" /> Reservas
          </Link>
          {(can('POS_VENDER') || can('COCINA_VER')) && (
            <Link to="/cocina" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
              <Icon name="soup_kitchen" className="text-[20px] text-primary" /> Cocina
            </Link>
          )}
          {can('CONFIG_EDITAR') && (
            <Link to="/mesas/configurar" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
              <Icon name="settings" className="text-[20px] text-muted" /> Configurar
            </Link>
          )}
        </div>
      </div>

      {orders.length > 0 && (
        <section className="space-y-2 rounded-2xl border-2 border-primary bg-primary-tint p-3">
          <h2 className="flex items-center gap-2 text-sm font-bold text-primary-active">
            <Icon name="qr_code_2" className="text-[20px]" /> Pedidos desde la mesa ({orders.length})
          </h2>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {orders.map((o) => (
              <button key={o.id} type="button" onClick={() => setReview(o)} className="rounded-xl bg-card p-3 text-left shadow-tier1 ring-1 ring-line">
                <div className="flex items-center justify-between text-sm">
                  <span className="font-bold">{o.tableLabel ?? 'Mesa'}</span>
                  <span className="text-xs text-muted">{hour(o.createdAt)}</span>
                </div>
                <div className="text-xs text-muted">
                  {o.customerName} · {o.lines.length} {o.lines.length === 1 ? 'producto' : 'productos'} · <b className="num text-ink">{money(o.total)}</b>
                </div>
                <div className="mt-1 text-xs font-bold text-primary">Revisar y pasar a la cuenta →</div>
              </button>
            ))}
          </div>
        </section>
      )}

      <div className="flex flex-wrap items-center gap-2">
        {floor.areas.length > 0 && (
          <div className="overflow-x-auto">
            <Tabs
              value={area}
              onChange={setArea}
              items={[{ value: 'todas', label: 'Todas' }, ...floor.areas.map((a) => ({ value: String(a.id), label: a.name }))]}
            />
          </div>
        )}
        <button
          type="button"
          onClick={() => setOnlyMine(!onlyMine)}
          className={`flex h-9 items-center gap-1.5 rounded-xl px-3 text-xs font-semibold ${onlyMine ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}
          aria-pressed={onlyMine}
        >
          <Icon name="person" className="text-[16px]" /> Mis mesas y libres
        </button>
      </div>

      <div className="flex flex-wrap gap-3 text-xs text-muted">
        {Object.entries(TABLE_STATE).map(([k, v]) => (
          <span key={k} className="flex items-center gap-1.5">
            <span className={`h-2.5 w-2.5 rounded-full ${v.dot}`} /> {v.label}
          </span>
        ))}
      </div>

      {floor.tables.length === 0 ? (
        <EmptyState
          icon="table_restaurant"
          title="Todavía no hay mesas"
          text="Crea tus mesas y salones para empezar a atender."
          action={can('CONFIG_EDITAR') ? <Button onClick={() => navigate('/mesas/configurar')}>Crear mesas</Button> : undefined}
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5" data-guide="mesas-plano">
          {shown.map((t) => {
            const st = TABLE_STATE[t.state]
            const total = t.accounts.reduce((s, a) => s + Number(a.total), 0)
            const minutes = Math.max(0, ...t.accounts.map((a) => a.minutes))
            const ready = t.accounts.reduce((s, a) => s + a.ready, 0)
            const pending = t.accounts.reduce((s, a) => s + a.pendingToSend, 0)
            const res = floor.reservations?.[String(t.id)]
            const others = t.accounts.length > 0 && !t.accounts.some((a) => a.mine)
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => tap(t)}
                className={`relative flex min-h-[112px] flex-col rounded-2xl border-2 p-3 text-left shadow-tier1 transition active:scale-[0.98] ${st.card} ${others ? 'opacity-60' : ''}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-base font-bold">{t.name}</span>
                  <span className="flex items-center gap-1 text-[11px] text-muted">
                    <Icon name="chair" className="text-[14px]" /> {t.seats}
                  </span>
                </div>
                {t.accounts.length === 0 ? (
                  <span className="mt-auto text-xs font-semibold text-muted">Libre · toca para abrir</span>
                ) : (
                  <div className="mt-1 space-y-0.5 text-xs">
                    <div className="num text-lg font-bold">{money(total)}</div>
                    <div className="text-muted">
                      {t.accounts[0]!.waiterName.split(' ')[0]} · {minutes} min{t.accounts.length > 1 ? ` · ${t.accounts.length} cuentas` : ''}
                    </div>
                    {ready > 0 && <div className="font-bold text-success-ink">● {ready} listo(s) para llevar</div>}
                    {pending > 0 && <div className="font-semibold text-warning-ink">{pending} sin enviar a cocina</div>}
                    {t.state === 'POR_COBRAR' && <div className="font-semibold text-warning-ink">Precuenta entregada</div>}
                  </div>
                )}
                {res && (
                  <div className="mt-1.5 flex items-center gap-1 rounded-lg bg-wallet-soft px-1.5 py-0.5 text-[11px] font-semibold text-wallet-ink">
                    <Icon name="event_seat" className="text-[14px]" />
                    <span className="truncate">
                      {hour(res.reservedAt)} · {res.name} ({res.people})
                    </span>
                  </div>
                )}
              </button>
            )
          })}
        </div>
      )}

      {opening && (
        <OpenTableModal
          table={opening}
          reservedFor={floor.reservations?.[String(opening.id)]?.name}
          onClose={() => setOpening(null)}
          onOpened={(sale) => {
            setOpening(null)
            toast(`${opening.name} abierta`)
            goAccount(sale.id)
          }}
        />
      )}
      {choose && (
        <Modal open title={`${choose.name}: ¿qué cuenta?`} onClose={() => setChoose(null)}>
          <div className="space-y-2">
            {choose.accounts
              .filter((a) => a.mine)
              .map((a) => (
                <button key={a.id} type="button" onClick={() => goAccount(a.id)} className="flex w-full items-center justify-between rounded-xl border border-line p-3 text-left">
                  <span className="font-semibold">{a.label}</span>
                  <span className="num font-bold">{money(a.total)}</span>
                </button>
              ))}
          </div>
        </Modal>
      )}
      {review && (
        <TableOrderModal
          order={review}
          onClose={() => setReview(null)}
          onDone={(msg) => {
            setReview(null)
            toast(msg)
            load()
          }}
        />
      )}
    </div>
  )
}

function OpenTableModal({
  table,
  reservedFor,
  onClose,
  onOpened,
}: {
  table: DiningTableDto
  reservedFor?: string
  onClose: () => void
  onOpened: (s: SaleDto) => void
}) {
  const [guests, setGuests] = useState(String(Math.min(2, table.seats)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function open() {
    setBusy(true)
    setError('')
    try {
      onOpened(await api<SaleDto>(`/restaurant/tables/${table.id}/open`, { body: { guests: Number(guests) || undefined } }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo abrir la mesa')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={`Abrir ${table.name}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="restaurant" loading={busy} onClick={open}>
          Abrir mesa y tomar el pedido
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        {reservedFor && (
          <Banner tone="warning">
            Esta mesa está reservada para {reservedFor}. Si son ellos, ábrela desde <Link to="/reservas" className="underline">Reservas</Link> con “Llegaron”.
          </Banner>
        )}
        <Field label="¿Cuántas personas?" inputMode="numeric" value={guests} onChange={(e) => setGuests(e.target.value.replace(/\D/g, ''))} />
        <p className="text-xs text-muted">La cuenta queda a tu nombre como mesero.</p>
      </div>
    </Modal>
  )
}

/** Pedido que el cliente hizo desde el QR de su mesa: el mesero lo revisa y lo pasa a la cuenta (va directo a cocina). */
function TableOrderModal({ order, onClose, onDone }: { order: TableOrder; onClose: () => void; onDone: (msg: string) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')

  async function accept() {
    setBusy(true)
    setError('')
    try {
      await api(`/restaurant/table-orders/${order.id}/accept`, { body: {} })
      onDone(`Pedido pasado a ${order.tableLabel} y enviado a la cocina`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo pasar el pedido')
    } finally {
      setBusy(false)
    }
  }
  async function reject() {
    if (!reason.trim()) return setError('Escribe el motivo')
    setBusy(true)
    setError('')
    try {
      await api(`/restaurant/table-orders/${order.id}/reject`, { body: { reason: reason.trim() } })
      onDone('Pedido descartado')
    } catch (e) {
      setError(e instanceof ApiError ? e.fields.reason ?? e.message : 'No se pudo descartar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title={`${order.tableLabel ?? 'Mesa'} · pedido de ${order.customerName}`}
      onClose={onClose}
      footer={
        rejecting ? (
          <div className="flex w-full gap-2">
            <Button variant="secondary" onClick={() => setRejecting(false)}>
              Volver
            </Button>
            <Button variant="danger" className="flex-1" loading={busy} onClick={reject}>
              Descartar pedido
            </Button>
          </div>
        ) : (
          <div className="flex w-full gap-2">
            <Button variant="secondary" icon="close" onClick={() => setRejecting(true)}>
              Descartar
            </Button>
            <Button className="flex-1" icon="soup_kitchen" loading={busy} onClick={accept}>
              Pasar a la cuenta y a cocina
            </Button>
          </div>
        )
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <ul className="divide-y divide-line-soft rounded-xl border border-line">
          {order.lines.map((l) => (
            <li key={l.id} className="px-3 py-2 text-sm">
              <div className="flex justify-between gap-2">
                <span className="font-semibold">
                  {qty(Number(l.quantity))} × {l.productName}
                </span>
                <span className="num">{money(l.lineTotal)}</span>
              </div>
              {l.modifiers && <div className="text-xs text-muted">{l.modifiers}</div>}
              {l.note && <div className="text-xs font-semibold text-warning-ink">“{l.note}”</div>}
            </li>
          ))}
        </ul>
        {order.notes && <p className="text-sm text-muted">Nota: {order.notes}</p>}
        <div className="flex justify-between text-sm font-bold">
          <span>Total</span>
          <span className="num">{money(order.total)}</span>
        </div>
        {rejecting && <Field label="¿Por qué se descarta?" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ya lo pidieron al mesero, pedido repetido…" />}
        <p className="text-xs text-muted">Al pasarlo, los precios son los de la carta del salón y la comanda sale de una vez a la cocina.</p>
      </div>
    </Modal>
  )
}
