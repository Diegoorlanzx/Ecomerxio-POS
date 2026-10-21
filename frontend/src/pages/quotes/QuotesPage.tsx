import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, InfoTip, Spinner, Tabs } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import { dayLabel, daysUntil, QUOTE_STATUS, type QuoteKind, type QuoteSummary } from '../../lib/quoteTypes'

const FILTERS: Record<QuoteKind, [string, string][]> = {
  COTIZACION: [
    ['', 'Todas'],
    ['BORRADOR', 'Borrador'],
    ['ENVIADA', 'Enviadas'],
    ['ACEPTADA', 'Aceptadas'],
    ['VENCIDA', 'Vencidas'],
    ['RECHAZADA', 'Rechazadas'],
  ],
  PREFACTURA: [
    ['', 'Todas'],
    ['BORRADOR', 'Por revisar'],
    ['APROBADA', 'Aprobadas'],
    ['FACTURADA', 'Facturadas'],
    ['ANULADA', 'Anuladas'],
  ],
}

/** Cotizaciones (ofertas con vigencia) y prefacturas (revisión de datos antes de facturar). */
export default function QuotesPage() {
  const { me, can } = useAuth()
  const [params, setParams] = useSearchParams()
  const kind: QuoteKind = params.get('tipo') === 'prefacturas' ? 'PREFACTURA' : 'COTIZACION'
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [list, setList] = useState<QuoteSummary[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setList(null)
    const t = setTimeout(
      () =>
        api<QuoteSummary[]>(`/quotes?kind=${kind}&status=${status}&q=${encodeURIComponent(q.trim())}`)
          .then((l) => {
            setList(l)
            setError('')
          })
          .catch((e) => setError(e.message)),
      q ? 250 : 0,
    )
    return () => clearTimeout(t)
  }, [kind, status, q])

  const isQuote = kind === 'COTIZACION'
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="flex flex-1 items-center gap-1 text-2xl font-bold tracking-tight">
          Cotizaciones y prefacturas
          <InfoTip
            title="¿Cuál uso?"
            text="La cotización es una oferta con fecha de vigencia: mientras esté vigente te compromete con esos precios. La prefactura sirve para revisar los datos del cliente, los impuestos y el stock antes de facturar, y que el cliente los confirme. Ninguna de las dos es factura."
          />
        </h1>
        {can('COTIZACIONES_CREAR') && (
          <Link to={`/cotizaciones/nueva${isQuote ? '' : '?tipo=prefactura'}`}>
            <Button icon="add">{isQuote ? 'Nueva cotización' : 'Nueva prefactura'}</Button>
          </Link>
        )}
      </div>
      <Tabs
        value={kind}
        onChange={(v) => {
          setStatus('')
          setParams(v === 'PREFACTURA' ? { tipo: 'prefacturas' } : {})
        }}
        items={[
          { value: 'COTIZACION', label: 'Cotizaciones' },
          { value: 'PREFACTURA', label: 'Prefacturas' },
        ]}
      />
      <div className="flex flex-wrap items-end gap-2">
        <Field label="Buscar" placeholder="Cliente, documento o número" value={q} onChange={(e) => setQ(e.target.value)} className="min-w-56 flex-1" />
        <div className="flex gap-1 overflow-x-auto pb-1">
          {FILTERS[kind].map(([v, l]) => (
            <button
              key={v}
              type="button"
              onClick={() => setStatus(v)}
              className={`h-10 shrink-0 rounded-full border px-3 text-sm ${status === v ? 'border-primary bg-primary-tint font-semibold text-primary' : 'border-line text-muted hover:border-primary/40'}`}
            >
              {l}
            </button>
          ))}
        </div>
      </div>
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={isQuote ? 'request_quote' : 'fact_check'}
            title={isQuote ? 'Sin cotizaciones' : 'Sin prefacturas'}
            text={
              isQuote
                ? 'Arma una cotización con productos del catálogo o servicios libres y compártela por WhatsApp: el cliente la acepta desde el enlace.'
                : 'Crea una desde una cotización aceptada (botón "Pasar a prefactura") o desde cero, para revisar los datos antes de facturar.'
            }
          />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {list.map((x) => {
            const st = QUOTE_STATUS[x.status] ?? { label: x.status, tone: 'neutral' as const }
            const left = daysUntil(x.validUntil)
            return (
              <Link key={x.id} to={`/cotizaciones/${x.id}`} className="rounded-2xl border border-line bg-card p-4 shadow-tier1 transition hover:border-primary/40">
                <div className="flex items-center gap-2">
                  <span className="num font-bold">{x.code}</span>
                  <Chip tone={st.tone}>{st.label}</Chip>
                  {x.clientCheck === 'CON_ERROR' && <Chip tone="danger" icon="report">Cliente reportó error</Chip>}
                  {x.clientCheck === 'CONFIRMADO' && <Chip tone="success" icon="verified">Datos confirmados</Chip>}
                </div>
                <div className="mt-1 truncate font-semibold">{x.customerName || 'Sin cliente'}</div>
                {x.customerDoc && <div className="num text-xs text-muted">{x.customerDoc}</div>}
                <div className="mt-2 flex items-end justify-between gap-2 text-sm">
                  <span className="text-xs text-muted">
                    <Icon name="event" className="mr-1 align-[-3px] text-[15px]" />
                    {dayLabel(x.issueDate)}
                    {isQuote && x.validUntil && ['BORRADOR', 'ENVIADA'].includes(x.status) && left != null && (
                      <span className={left <= 2 ? 'ml-1 font-semibold text-warning-ink' : 'ml-1'}>
                        · {left === 0 ? 'vence hoy' : `vence en ${left} día${left === 1 ? '' : 's'}`}
                      </span>
                    )}
                  </span>
                  <span className="num font-bold">{money(x.total, me?.tenant.currency)}</span>
                </div>
              </Link>
            )
          })}
        </div>
      )}
    </div>
  )
}
