import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { CartLine } from '../../lib/cart'
import { scaleFor } from '../../lib/cart'
import { money, qty, relative } from '../../lib/format'
import { ORDER_STATUS, type OnlineOrder } from '../../lib/onlineTypes'
import type { Receipt, SaleDto, StoreSettings } from '../../lib/posTypes'
import CheckoutModal, { type CheckoutPayload } from '../pos/CheckoutModal'
import { PrintArea, ReceiptView } from '../pos/Receipt'
import { waDigits } from '../public/StorefrontPage'

const COLUMNS: { key: string; title: string; statuses: string[]; icon: string }[] = [
  { key: 'nuevos', title: 'Nuevos', statuses: ['NUEVO'], icon: 'notifications_active' },
  { key: 'prep', title: 'En preparación', statuses: ['ACEPTADO', 'PREPARANDO'], icon: 'inventory' },
  { key: 'listos', title: 'Listos y en camino', statuses: ['LISTO', 'EN_CAMINO'], icon: 'two_wheeler' },
]

/** Bandeja de pedidos en línea: aceptar (aparta el inventario), preparar, despachar y entregar cobrando en la caja. */
export default function OrdersPage() {
  const { me } = useAuth()
  const toast = useToast()
  const [tab, setTab] = useState<'activos' | 'historial'>('activos')
  const [list, setList] = useState<OnlineOrder[] | null>(null)
  const [open, setOpen] = useState<OnlineOrder | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<OnlineOrder[]>(`/online-orders${tab === 'historial' ? '?scope=HISTORIAL' : ''}`)
      .then((l) => {
        setList(l)
        setError('')
        setOpen((o) => (o ? (l.find((x) => x.id === o.id) ?? o) : o))
      })
      .catch((e) => setError(e.message))
  }, [tab])
  useEffect(() => {
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [load])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="flex-1 text-2xl font-bold tracking-tight">Pedidos en línea</h1>
        <Link to="/tienda-online">
          <Button variant="secondary" icon="storefront">
            Mi tienda en línea
          </Button>
        </Link>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'activos', label: 'En curso', count: tab === 'activos' ? list?.length : undefined },
          { value: 'historial', label: 'Entregados y cancelados' },
        ]}
      />
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : tab === 'historial' ? (
        list.length === 0 ? (
          <Card>
            <EmptyState icon="history" title="Sin pedidos todavía" />
          </Card>
        ) : (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {list.map((o) => (
              <OrderCard key={o.id} o={o} currency={me?.tenant.currency} onOpen={() => setOpen(o)} />
            ))}
          </div>
        )
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="shopping_bag" title="No hay pedidos en curso" text="Cuando un cliente pida desde tu tienda en línea, aparece aquí con un aviso." />
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          {COLUMNS.map((c) => {
            const items = list.filter((o) => c.statuses.includes(o.status))
            return (
              <section key={c.key} className="space-y-3">
                <h2 className="flex items-center gap-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">
                  <Icon name={c.icon} className="text-[18px]" /> {c.title} <span className="num">({items.length})</span>
                </h2>
                {items.map((o) => (
                  <OrderCard key={o.id} o={o} currency={me?.tenant.currency} onOpen={() => setOpen(o)} />
                ))}
              </section>
            )
          })}
        </div>
      )}
      {open && (
        <OrderDetail
          order={open}
          onClose={() => setOpen(null)}
          onChanged={(msg) => {
            if (msg) toast(msg)
            load()
          }}
        />
      )}
    </div>
  )
}

function OrderCard({ o, currency, onOpen }: { o: OnlineOrder; currency?: string; onOpen: () => void }) {
  const st = ORDER_STATUS[o.status]
  return (
    <button type="button" onClick={onOpen} className={`w-full rounded-2xl border bg-card p-4 text-left shadow-tier1 transition hover:border-primary/40 ${o.status === 'NUEVO' ? 'border-danger/40 ring-2 ring-danger/15' : 'border-line'}`}>
      <div className="flex items-center gap-2">
        <span className="num font-bold">#{o.number}</span>
        <Chip tone={st.tone}>{st.label}</Chip>
        <span className="ml-auto text-xs text-muted">{relative(o.createdAt)}</span>
      </div>
      <div className="mt-1 font-semibold">{o.customerName}</div>
      <div className="truncate text-xs text-muted">
        <Icon name={o.fulfillment === 'DOMICILIO' ? 'two_wheeler' : 'storefront'} className="mr-1 align-[-3px] text-[15px]" />
        {o.fulfillment === 'DOMICILIO' ? `${o.address}${o.zoneName ? ` · ${o.zoneName}` : ''}` : 'Recoge en la tienda'}
      </div>
      <div className="num mt-2 flex justify-between text-sm">
        <span className="text-muted">
          {o.lines.reduce((s, l) => s + l.quantity, 0)} productos · {o.payLabel}
        </span>
        <span className="font-bold">{money(o.total, currency)}</span>
      </div>
    </button>
  )
}

function OrderDetail({ order: o, onClose, onChanged }: { order: OnlineOrder; onClose: () => void; onChanged: (msg?: string) => void }) {
  const { me } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [cancel, setCancel] = useState(false)
  const [reason, setReason] = useState('')
  const [charge, setCharge] = useState<StoreSettings | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const currency = me?.tenant.currency ?? 'COP'
  const st = ORDER_STATUS[o.status]
  const active = !['ENTREGADO', 'CANCELADO'].includes(o.status)

  async function act(path: string, body?: unknown, msg?: string) {
    setBusy(true)
    setError('')
    try {
      await api(`/online-orders/${o.id}/${path}`, { method: 'POST', body: body ?? {} })
      onChanged(msg)
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo')
    } finally {
      setBusy(false)
    }
  }

  async function startCharge() {
    try {
      setCharge(await api<StoreSettings>('/settings/pos'))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  // Líneas para la pantalla de cobro (precios fijos del pedido + domicilio).
  const lines: CartLine[] = [
    ...o.lines.map((l) => ({
      key: String(l.id),
      productId: l.productId,
      presentationId: l.presentationId ?? 0,
      name: l.productName,
      presentationName: l.presentationName,
      factor: Number(l.factor),
      quantity: Number(l.quantity),
      listPrice: Number(l.unitPrice),
      unitPrice: Number(l.unitPrice),
      minPrice: 0,
      discountPct: 0,
      taxRate: 0,
      fixedTax: 0,
      unit: 'UNIDAD',
    })),
    ...(o.deliveryFee > 0
      ? [{ key: 'fee', productId: 0, presentationId: 0, name: 'Domicilio', presentationName: 'Servicio', factor: 1, quantity: 1, listPrice: o.deliveryFee, unitPrice: o.deliveryFee, minPrice: 0, discountPct: 0, taxRate: 0, fixedTax: 0, unit: 'UNIDAD' }]
      : []),
  ]

  async function deliver(p: CheckoutPayload): Promise<string | null> {
    try {
      const sale = await api<SaleDto>(`/online-orders/${o.id}/deliver`, { body: { payments: p.payments } })
      setCharge(null)
      try {
        setReceipt(await api<Receipt>(`/pos/sales/${sale.id}/receipt`))
      } catch {
        /* sin comprobante: igual quedó cobrado */
      }
      onChanged(`Pedido #${o.number} entregado: venta #${sale.number}`)
      return null
    } catch (e) {
      return e instanceof ApiError ? e.message : 'No se pudo cobrar'
    }
  }

  if (receipt) {
    return (
      <Modal
        open
        title={`Pedido #${o.number} cobrado`}
        onClose={onClose}
        footer={
          <>
            <Button variant="secondary" icon="print" className="flex-1" onClick={() => window.print()}>
              Imprimir
            </Button>
            <Button className="flex-1" onClick={onClose}>
              Listo
            </Button>
          </>
        }
      >
        <div className="rounded-xl bg-line-soft p-3">
          <ReceiptView r={receipt} />
        </div>
        <PrintArea r={receipt} />
      </Modal>
    )
  }

  if (charge) {
    return (
      <CheckoutModal
        lines={lines}
        settings={{ ...charge, pricesIncludeTax: true }}
        promotions={[]}
        categoryParents={new Map()}
        currency={currency}
        scale={scaleFor(currency)}
        title={`Cobrar pedido #${o.number}`}
        offline={false}
        fixedPrices
        onClose={() => setCharge(null)}
        onSubmit={deliver}
      />
    )
  }

  return (
    <Modal
      open
      wide
      title={`Pedido #${o.number}`}
      onClose={onClose}
      footer={
        active ? (
          <>
            {!cancel && (
              <Button variant="ghost" icon="cancel" className="text-danger-ink" onClick={() => setCancel(true)} disabled={busy}>
                Cancelar
              </Button>
            )}
            <div className="flex-1" />
            {o.status === 'NUEVO' && (
              <Button icon="thumb_up" onClick={() => act('accept', undefined, `Pedido #${o.number} aceptado`)} loading={busy}>
                Aceptar pedido
              </Button>
            )}
            {o.status === 'ACEPTADO' && (
              <Button variant="soft" icon="inventory" onClick={() => act('status/PREPARANDO')} loading={busy}>
                Preparando
              </Button>
            )}
            {['ACEPTADO', 'PREPARANDO'].includes(o.status) && (
              <Button variant="soft" icon="check_circle" onClick={() => act('status/LISTO', undefined, `Pedido #${o.number} listo`)} loading={busy}>
                Listo
              </Button>
            )}
            {o.fulfillment === 'DOMICILIO' && ['ACEPTADO', 'PREPARANDO', 'LISTO'].includes(o.status) && (
              <Button variant="soft" icon="two_wheeler" onClick={() => act('status/EN_CAMINO', undefined, `Pedido #${o.number} en camino`)} loading={busy}>
                En camino
              </Button>
            )}
            {o.status !== 'NUEVO' && (
              <Button icon="point_of_sale" onClick={startCharge} disabled={busy}>
                Entregar y cobrar
              </Button>
            )}
          </>
        ) : undefined
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="flex flex-wrap items-center gap-2">
          <Chip tone={st.tone}>{st.label}</Chip>
          <span className="text-sm text-muted">Hace {relative(o.createdAt).replace(/^hace /, '')}</span>
          {o.handledBy && <span className="text-sm text-muted">· atiende {o.handledBy}</span>}
          {o.saleId && <Chip tone="success">Venta registrada</Chip>}
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Card className="p-3">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Cliente</div>
            <div className="font-semibold">{o.customerName}</div>
            <a href={`tel:${o.phone}`} className="num text-sm text-primary">
              {o.phone}
            </a>
            <a href={`https://wa.me/${waDigits(o.phone)}`} target="_blank" rel="noreferrer" className="ml-3 text-sm font-semibold text-success-ink">
              WhatsApp
            </a>
            {o.email && <div className="text-sm text-muted">{o.email}</div>}
          </Card>
          <Card className="p-3">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">{o.fulfillment === 'DOMICILIO' ? 'Domicilio' : 'Recoge en la tienda'}</div>
            {o.fulfillment === 'DOMICILIO' && (
              <>
                <div className="font-semibold">{o.address}</div>
                {o.zoneName && <div className="text-sm text-muted">{o.zoneName}</div>}
              </>
            )}
            <div className="text-sm">Pago: {o.payLabel}</div>
          </Card>
        </div>
        {o.notes && <Banner tone="warning">Nota del cliente: {o.notes}</Banner>}
        <ul className="divide-y divide-line-soft rounded-xl border border-line text-sm">
          {o.lines.map((l) => (
            <li key={l.id} className="num flex justify-between gap-2 px-3 py-2">
              <span>
                <span className="font-bold">{qty(l.quantity)} ×</span> {l.productName}
                {l.presentationName !== 'Unidad' ? ` (${l.presentationName})` : ''}
              </span>
              <span>{money(l.lineTotal, currency)}</span>
            </li>
          ))}
          {o.deliveryFee > 0 && (
            <li className="num flex justify-between px-3 py-2">
              <span>Domicilio</span>
              <span>{money(o.deliveryFee, currency)}</span>
            </li>
          )}
          <li className="num flex justify-between px-3 py-2 text-base font-bold">
            <span>Total</span>
            <span>{money(o.total, currency)}</span>
          </li>
        </ul>
        {o.status === 'NUEVO' && <p className="text-xs text-muted">Al aceptarlo se apartan las existencias para que el mostrador no las venda.</p>}
        {o.cancelReason && <Banner>Cancelado: {o.cancelReason}</Banner>}
        {cancel && (
          <div className="space-y-2 rounded-xl border border-danger/40 bg-danger-soft/40 p-3">
            <Field label="Motivo de la cancelación" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: el cliente no contestó" />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setCancel(false)}>
                Volver
              </Button>
              <Button variant="danger" disabled={!reason.trim() || busy} onClick={() => act('cancel', { reason }, `Pedido #${o.number} cancelado`)}>
                Cancelar pedido
              </Button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  )
}
