import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, InfoTip, Spinner, useToast } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import { dayLabel } from '../../lib/quoteTypes'
import { REMISSION_STATUS, type RemissionSummary } from '../../lib/remissionTypes'
import InvoiceRemissions from './InvoiceRemissions'

const FILTERS: [string, string][] = [
  ['', 'Todas'],
  ['BORRADOR', 'Borrador'],
  ['ENTREGADA', 'Por facturar'],
  ['FACTURADA', 'Facturadas'],
  ['ANULADA', 'Anuladas'],
]

/** Remisiones: la mercancía sale con el cliente y se factura después; varias del mismo cliente se facturan juntas. */
export default function RemissionsPage() {
  const { me, can } = useAuth()
  const toast = useToast()
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [list, setList] = useState<RemissionSummary[] | null>(null)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const [invoicing, setInvoicing] = useState(false)
  const [reload, setReload] = useState(0)
  const currency = me?.tenant.currency ?? 'COP'

  useEffect(() => {
    setList(null)
    const t = setTimeout(
      () =>
        api<RemissionSummary[]>(`/remissions?status=${status}&q=${encodeURIComponent(q.trim())}`)
          .then((l) => {
            setList(l)
            setError('')
          })
          .catch((e) => setError(e.message)),
      q ? 250 : 0,
    )
    return () => clearTimeout(t)
  }, [status, q, reload])

  const canInvoice = can('REMISIONES_CREAR') && can('POS_VENDER')
  const picked = (list ?? []).filter((x) => selected.includes(x.id))
  const customerKey = (x: RemissionSummary) => (x.customerId ? `c${x.customerId}` : `n${x.customerName.toLowerCase()}`)
  const pickedKey = picked[0] ? customerKey(picked[0]) : null

  function toggle(x: RemissionSummary) {
    setSelected((s) => (s.includes(x.id) ? s.filter((i) => i !== x.id) : [...s, x.id]))
  }

  return (
    <div className="space-y-4 pb-24">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="flex flex-1 items-center gap-1 text-2xl font-bold tracking-tight">
          Remisiones
          <InfoTip
            title="¿Para qué sirve?"
            text="Cuando entregas mercancía y la facturas después: obras, clientes con cuenta mensual, consignación. La remisión viaja con la mercancía y la firma quien recibe. Al entregarla sale del inventario; si el cliente devuelve algo, vuelve a entrar. Luego facturas una o varias del mismo cliente en una sola venta."
          />
        </h1>
        {can('REMISIONES_CREAR') && (
          <Link to="/remisiones/nueva">
            <Button icon="add">Nueva remisión</Button>
          </Link>
        )}
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <Field label="Buscar" placeholder="Cliente, documento o número" value={q} onChange={(e) => setQ(e.target.value)} className="min-w-56 flex-1" />
        <div className="flex gap-1 overflow-x-auto pb-1">
          {FILTERS.map(([v, l]) => (
            <button
              key={v}
              type="button"
              onClick={() => {
                setStatus(v)
                setSelected([])
              }}
              className={`h-10 shrink-0 rounded-full border px-3 text-sm ${status === v ? 'border-primary bg-primary-tint font-semibold text-primary' : 'border-line text-muted hover:border-primary/40'}`}
            >
              {l}
            </button>
          ))}
        </div>
      </div>
      {canInvoice && status === 'ENTREGADA' && list && list.length > 0 && (
        <p className="text-sm text-muted">Marca las remisiones de un mismo cliente para facturarlas juntas en una sola venta.</p>
      )}
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon="local_shipping"
            title="Sin remisiones"
            text="Crea una remisión cuando entregues mercancía que vas a facturar después. También puedes despachar una cotización con el botón &quot;Despachar con remisión&quot;."
          />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {list.map((x) => {
            const st = REMISSION_STATUS[x.status] ?? { label: x.status, tone: 'neutral' as const }
            const selectable = canInvoice && x.status === 'ENTREGADA'
            const isSel = selected.includes(x.id)
            const blocked = selectable && !isSel && pickedKey !== null && customerKey(x) !== pickedKey
            return (
              <div key={x.id} className={`relative rounded-2xl border bg-card shadow-tier1 transition ${isSel ? 'border-primary ring-2 ring-primary/20' : 'border-line hover:border-primary/40'}`}>
                <Link to={`/remisiones/${x.id}`} className="block p-4">
                  <div className="flex items-center gap-2 pr-8">
                    <span className="num font-bold">{x.code}</span>
                    <Chip tone={st.tone}>{st.label}</Chip>
                  </div>
                  <div className="mt-1 truncate font-semibold">{x.customerName}</div>
                  {x.customerDoc && <div className="num text-xs text-muted">{x.customerDoc}</div>}
                  <div className="mt-2 flex items-end justify-between gap-2 text-sm">
                    <span className="text-xs text-muted">
                      <Icon name="event" className="mr-1 align-[-3px] text-[15px]" />
                      {dayLabel(x.issueDate)}
                      {x.saleNumber && <span> · venta #{x.saleNumber}</span>}
                    </span>
                    <span className="text-right">
                      <span className="num block font-bold">{money(x.status === 'ENTREGADA' ? x.pendingTotal : x.total, currency)}</span>
                      {x.status === 'ENTREGADA' && Number(x.pendingTotal) !== Number(x.total) && <span className="block text-[11px] text-muted">sin lo devuelto</span>}
                    </span>
                  </div>
                </Link>
                {selectable && (
                  <label className="absolute top-3 right-3 flex h-8 w-8 cursor-pointer items-center justify-center" title={blocked ? 'Es de otro cliente' : 'Facturar junto con otras'}>
                    <input
                      type="checkbox"
                      checked={isSel}
                      disabled={blocked}
                      onChange={() => toggle(x)}
                      aria-label={`Seleccionar ${x.code}`}
                      className="h-5 w-5 accent-[var(--color-primary)] disabled:opacity-30"
                    />
                  </label>
                )}
              </div>
            )
          })}
        </div>
      )}

      {picked.length > 0 && (
        <div className="fixed inset-x-4 bottom-24 z-30 mx-auto max-w-xl rounded-2xl border border-line bg-card/95 px-4 py-3 shadow-tier2 backdrop-blur lg:bottom-4">
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1 text-sm">
              <div className="truncate text-xs text-muted">
                {picked.length} {picked.length === 1 ? 'remisión' : 'remisiones'} de {picked[0].customerName}
              </div>
              <div className="num text-xl font-bold">{money(picked.reduce((s, x) => s + Number(x.pendingTotal), 0), currency)}</div>
            </div>
            <Button variant="ghost" onClick={() => setSelected([])}>
              Quitar
            </Button>
            <Button icon="point_of_sale" onClick={() => setInvoicing(true)}>
              Facturar
            </Button>
          </div>
        </div>
      )}

      {invoicing && (
        <InvoiceRemissions
          ids={selected}
          onClose={() => setInvoicing(false)}
          onDone={() => {
            setInvoicing(false)
            setSelected([])
            setReload((n) => n + 1)
            toast('Remisiones facturadas')
          }}
        />
      )}
    </div>
  )
}
