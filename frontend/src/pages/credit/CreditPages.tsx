import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, StatCard, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { CREDIT_STATUS, type CreditLedger, type CustomerCredit } from '../../lib/financeTypes'
import { dateTime, money, relative } from '../../lib/format'
import { METHOD_LABELS, type StoreSettings } from '../../lib/posTypes'

type Filter = 'todos' | 'vencidos' | 'por_vencer'

/** Cuentas por cobrar: quién debe, cuánto, desde cuándo y qué está vencido. */
export function CreditListPage() {
  const navigate = useNavigate()
  const [list, setList] = useState<CustomerCredit[] | null>(null)
  const [filter, setFilter] = useState<Filter>('todos')
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const [view, setView] = useState<'clientes' | 'cuadre'>(() => (new URLSearchParams(location.search).get('vista') === 'cuadre' ? 'cuadre' : 'clientes'))

  useEffect(() => {
    api<CustomerCredit[]>('/credit')
      .then(setList)
      .catch((e) => setError(e.message))
  }, [])

  const shown = useMemo(() => {
    const norm = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
    return (list ?? [])
      .filter((c) => (filter === 'vencidos' ? c.overdue > 0 : filter === 'por_vencer' ? c.status === 'POR_VENCER' : true))
      .filter((c) => !q || norm(`${c.name} ${c.docNumber ?? ''} ${c.phone ?? ''}`).includes(norm(q)))
  }, [list, filter, q])

  if (error) return <Banner>{error}</Banner>
  if (!list) return <Spinner />
  const total = list.reduce((s, c) => s + c.balance, 0)
  const overdue = list.reduce((s, c) => s + c.overdue, 0)

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Fiados</h1>
        <p className="text-sm text-muted">Clientes que compraron a crédito y lo que deben.</p>
      </div>
      <div className="sm:w-80">
        <Tabs
          value={view}
          onChange={(v) => (setView(v), history.replaceState(null, '', v === 'cuadre' ? '?vista=cuadre' : location.pathname))}
          items={[
            { value: 'clientes', label: 'Clientes' },
            { value: 'cuadre', label: 'Cuadre de fiados' },
          ]}
        />
      </div>
      {view === 'cuadre' ? (
        <LedgerView onOpen={(id) => navigate(`/fiados/${id}`)} />
      ) : (
        <>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatCard label="Por cobrar" help="Todo lo que te deben hoy los clientes por fiados: lo fiado menos lo abonado. Es dinero que ya vendiste pero no ha entrado." value={money(total)} icon="menu_book" tone="wallet" sub={`${list.length} clientes`} />
        <StatCard label="Vencido" help="La parte de lo que te deben que ya pasó la fecha de pago acordada. Es lo primero que hay que cobrar." value={money(overdue)} icon="event_busy" tone={overdue ? 'danger' : 'success'} sub={`${list.filter((c) => c.overdue > 0).length} clientes`} />
        <StatCard label="Por vencer (3 días)" help="Clientes con fiados que vencen en los próximos 3 días: buen momento para recordarles por WhatsApp." value={list.filter((c) => c.status === 'POR_VENCER').length} icon="schedule" tone="navy" />
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        <div className="sm:w-96">
          <Tabs
            value={filter}
            onChange={setFilter}
            items={[
              { value: 'todos', label: 'Todos' },
              { value: 'vencidos', label: 'Vencidos' },
              { value: 'por_vencer', label: 'Por vencer' },
            ]}
          />
        </div>
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Buscar por nombre, cédula o celular"
          className="h-12 flex-1 rounded-xl border border-line bg-card px-3.5 text-base outline-none focus:border-primary"
        />
      </div>
      {shown.length === 0 ? (
        <Card>
          <EmptyState icon="menu_book" title={list.length ? 'Nadie con ese filtro' : 'Nadie debe nada'} text="Para fiar, en el cobro elige “Fiado” y el cliente." />
        </Card>
      ) : (
        <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
          {shown.map((c) => {
            const st = CREDIT_STATUS[c.status]
            const used = c.limit > 0 ? Math.min(100, (c.balance / c.limit) * 100) : 100
            return (
              <button key={c.customerId} type="button" onClick={() => navigate(`/fiados/${c.customerId}`)} className="flex w-full items-center gap-3 px-3 py-3 text-left hover:bg-canvas">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-wallet-soft text-sm font-bold text-wallet-ink">
                  {c.name.slice(0, 1).toUpperCase()}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-semibold">{c.name}</span>
                    <Chip tone={st.tone}>{st.label}</Chip>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-line-soft">
                    <div className={`h-full rounded-full ${c.overdue > 0 ? 'bg-danger' : used > 85 ? 'bg-warning' : 'bg-wallet'}`} style={{ width: `${used}%` }} />
                  </div>
                  <div className="mt-1 text-xs text-muted">
                    Cupo {money(c.limit)} · {c.overdue > 0 ? `vencido hace ${c.daysOverdue} días` : c.oldestDue ? `vence el ${c.oldestDue}` : 'sin vencimientos'} · último abono {relative(c.lastPaymentAt).toLowerCase()}
                  </div>
                </div>
                <div className="text-right">
                  <div className="num font-bold">{money(c.balance)}</div>
                  {c.overdue > 0 && <div className="num text-xs font-semibold text-danger-ink">{money(c.overdue)} vencido</div>}
                </div>
              </button>
            )
          })}
        </div>
      )}
        </>
      )}
    </div>
  )
}

const LINE_LABEL = { CARGO: 'Fiado', ABONO: 'Abono', AJUSTE: 'Ajuste', RECHAZO: 'Abono rechazado (la plata no llegó)' } as const
/** Movimientos que suben la deuda. */
const ADDS = (t: string) => t === 'CARGO' || t === 'RECHAZO'

/** Estado de cuenta de un cliente: fiados, abonos, cupo y recordatorio por WhatsApp. */
export function CreditDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const toast = useToast()
  const [c, setC] = useState<CustomerCredit | null>(null)
  const [error, setError] = useState('')
  const [pay, setPay] = useState(false)
  const [terms, setTerms] = useState(false)
  const [interest, setInterest] = useState(false)

  const load = useCallback(() => {
    api<CustomerCredit>(`/credit/${id}`)
      .then(setC)
      .catch((e) => setError(e.message))
  }, [id])
  useEffect(load, [load])

  async function remind() {
    const r = await api<{ message: string; whatsappUrl: string }>(`/credit/${id}/reminder`)
    if (r.whatsappUrl) window.open(r.whatsappUrl, '_blank', 'noopener')
    else {
      await navigator.clipboard?.writeText(r.message).catch(() => {})
      toast('El cliente no tiene celular: copiamos el mensaje')
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!c) return <Spinner />
  const st = CREDIT_STATUS[c.status]
  const lines = c.statement ?? []

  return (
    <div className="space-y-4">
      <Link to="/fiados" className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Fiados
      </Link>
      <div className="rounded-2xl bg-navy p-5 text-white">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-lg font-semibold">{c.name}</div>
            <div className="text-xs text-slate-300">
              {c.docNumber ?? 'Sin documento'} · {c.phone ?? 'sin celular'}
            </div>
          </div>
          <Chip tone={st.tone}>{st.label}</Chip>
        </div>
        <div className="mt-3 text-[11px] font-bold tracking-[0.08em] text-blue-200 uppercase">Debe</div>
        <div className="num text-[36px] leading-tight font-extrabold">{money(c.balance)}</div>
        <div className="num mt-2 grid grid-cols-3 gap-2 text-xs text-slate-300">
          <span>
            Vencido
            <b className={`block text-sm ${c.overdue > 0 ? 'text-red-300' : 'text-white'}`}>{money(c.overdue)}</b>
          </span>
          <span>
            Cupo
            <b className="block text-sm text-white">{money(c.limit)}</b>
          </span>
          <span>
            Plazo
            <b className="block text-sm text-white">{c.creditDays} días</b>
          </span>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {can('FIADO_GESTIONAR') && (
          <Button icon="payments" onClick={() => setPay(true)} disabled={c.balance <= 0}>
            Registrar abono
          </Button>
        )}
        <Button variant="secondary" icon="chat" onClick={remind} disabled={c.balance <= 0}>
          Recordar por WhatsApp
        </Button>
        {can('FIADO_AUTORIZAR') && (
          <Button variant="secondary" icon="tune" onClick={() => setTerms(true)}>
            Cupo y plazo
          </Button>
        )}
        {can('FIADO_AUTORIZAR') && c.overdue > 0 && (
          <Button variant="secondary" icon="percent" onClick={() => setInterest(true)}>
            Cobrar intereses
          </Button>
        )}
      </div>

      <Card>
        <div className="border-b border-line-soft px-4 py-3 text-sm font-semibold">Movimientos</div>
        {lines.length === 0 ? (
          <EmptyState icon="receipt_long" title="Sin movimientos" />
        ) : (
          <div className="divide-y divide-line-soft">
            {lines.map((l) => (
              <div key={l.id} className="flex items-start gap-3 px-4 py-2.5">
                <Icon
                  name={l.type === 'CARGO' ? 'shopping_basket' : l.type === 'ABONO' ? 'savings' : l.type === 'RECHAZO' ? 'block' : 'tune'}
                  className={`mt-0.5 text-[20px] ${l.type === 'RECHAZO' ? 'text-danger-ink' : l.type === 'CARGO' ? 'text-wallet-ink' : 'text-success'}`}
                />
                <div className="min-w-0 flex-1 text-sm">
                  <div className="font-semibold">
                    {LINE_LABEL[l.type]}
                    {l.note ? ` · ${l.note}` : ''}
                  </div>
                  <div className="text-xs text-muted">
                    {dateTime(l.createdAt)} · {l.userName}
                    {l.method && l.type === 'ABONO' ? ` · ${METHOD_LABELS[l.method] ?? l.method}` : ''}
                    {l.type === 'CARGO' && l.dueDate ? ` · vence ${l.dueDate}` : ''}
                    {l.authorizedBy ? ` · autorizó ${l.authorizedBy}` : ''}
                  </div>
                </div>
                <div className="text-right">
                  <div className={`num text-sm font-bold ${ADDS(l.type) ? (l.type === 'RECHAZO' ? 'text-danger-ink' : '') : 'text-success-ink'}`}>
                    {ADDS(l.type) ? (l.type === 'RECHAZO' ? '+' : '') : '−'}
                    {money(l.amount)}
                  </div>
                  <div className="num text-xs text-muted">Saldo {money(l.balanceAfter)}</div>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {pay && (
        <PayModal
          credit={c}
          onClose={() => setPay(false)}
          onDone={(n) => {
            setPay(false)
            setC(n)
            toast('Abono registrado')
          }}
        />
      )}
      {interest && (
        <InterestModal
          credit={c}
          onClose={() => setInterest(false)}
          onDone={(r) => {
            setInterest(false)
            load()
            toast(r.debitNoteNumber ? `Intereses cargados con la nota débito ${r.debitNoteNumber}` : 'Intereses cargados a la cuenta')
          }}
        />
      )}
      {terms && (
        <TermsModal
          credit={c}
          onClose={() => setTerms(false)}
          onDone={(n) => {
            setTerms(false)
            setC(n)
            toast('Condiciones actualizadas')
          }}
        />
      )}
    </div>
  )
}

function PayModal({ credit, onClose, onDone }: { credit: CustomerCredit; onClose: () => void; onDone: (c: CustomerCredit) => void }) {
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState('EFECTIVO')
  const [methods, setMethods] = useState<string[]>(['EFECTIVO'])
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    api<StoreSettings>('/settings/pos')
      .then((s) => setMethods(s.paymentMethods.split(',').filter((m) => m && m !== 'FIADO')))
      .catch(() => {})
  }, [])

  async function submit() {
    setLoading(true)
    setFields({})
    setError('')
    try {
      onDone(await api<CustomerCredit>(`/credit/${credit.customerId}/payments`, { method: 'POST', body: { amount: Number(amount), method, reference: reference || undefined, note: note || undefined } }))
    } catch (e) {
      if (e instanceof ApiError) Object.keys(e.fields).length ? setFields(e.fields) : setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title={`Abono de ${credit.name}`}
      onClose={onClose}
      footer={
        <Button className="flex-1" size="lg" icon="check" onClick={submit} loading={loading} disabled={!Number(amount)}>
          Registrar {amount ? money(Number(amount)) : 'abono'}
        </Button>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">
          Debe <b className="num text-ink">{money(credit.balance)}</b>. El abono se aplica primero a lo más antiguo.
        </p>
        <Field
          label="Valor"
          inputMode="numeric"
          autoFocus
          value={amount ? Number(amount).toLocaleString('es-CO') : ''}
          onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))}
          error={fields.amount}
          trailing={
            <button type="button" onClick={() => setAmount(String(Math.round(credit.balance)))} className="mr-1 rounded-lg bg-primary-tint px-2 py-1 text-xs font-bold text-primary">
              Todo
            </button>
          }
        />
        <SelectField label="Medio" value={method} onChange={(e) => setMethod(e.target.value)} error={fields.method}>
          {methods.map((m) => (
            <option key={m} value={m}>
              {METHOD_LABELS[m] ?? m}
            </option>
          ))}
        </SelectField>
        {method !== 'EFECTIVO' && <Field label="Referencia" value={reference} onChange={(e) => setReference(e.target.value)} />}
        {method === 'EFECTIVO' && <p className="text-xs text-muted">El efectivo entra a tu turno de caja abierto.</p>}
        <Field label="Nota (opcional)" value={note} onChange={(e) => setNote(e.target.value)} />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

/** Fase 16E: intereses de mora sobre un fiado vencido; si la venta tiene factura aceptada sale la nota débito. */
function InterestModal({ credit, onClose, onDone }: { credit: CustomerCredit; onClose: () => void; onDone: (r: { debitNoteNumber?: string | null }) => void }) {
  const today = new Date().toLocaleDateString('en-CA')
  const overdue = (credit.statement ?? []).filter((l) => l.type === 'CARGO' && l.saleId && l.dueDate && l.dueDate < today && (l.openAmount ?? 0) > 0)
  const [saleId, setSaleId] = useState(String(overdue[0]?.saleId ?? ''))
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit() {
    setSaving(true)
    setError('')
    try {
      onDone(await api(`/credit/${credit.customerId}/interest`, { body: { saleId: Number(saleId), amount: Number(amount.replace(/\D/g, '')), note: note || undefined } }))
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo cargar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={`Intereses de mora · ${credit.name}`}
      onClose={onClose}
      footer={
        <Button className="w-full" loading={saving} disabled={!saleId || !amount} onClick={submit}>
          Cargar intereses
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted">Suman a la deuda. Si la venta tiene factura electrónica aceptada, sale también la nota débito.</p>
        <SelectField label="Fiado vencido" value={saleId} onChange={(e) => setSaleId(e.target.value)}>
          {overdue.length === 0 && <option value="">No hay fiados vencidos</option>}
          {overdue.map((l) => (
            <option key={l.id} value={l.saleId!}>
              {l.note ?? `Venta`} · debe {money(l.openAmount ?? 0)} · venció {l.dueDate}
            </option>
          ))}
        </SelectField>
        <Field label="Valor de los intereses" inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Field label="Nota (opcional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Ej: 2 % mensual por 1 mes de mora" />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

function TermsModal({ credit, onClose, onDone }: { credit: CustomerCredit; onClose: () => void; onDone: (c: CustomerCredit) => void }) {
  const [limit, setLimit] = useState(String(Math.round(credit.limit)))
  const [days, setDays] = useState(String(credit.creditDays))
  const [blocked, setBlocked] = useState(credit.blocked)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function submit() {
    setLoading(true)
    try {
      onDone(await api<CustomerCredit>(`/credit/${credit.customerId}/terms`, { method: 'PUT', body: { creditLimit: Number(limit), creditDays: Number(days), blocked } }))
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { creditLimit: e.message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title="Cupo y plazo"
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={submit} loading={loading}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        <Field label="Cupo máximo" inputMode="numeric" value={limit ? Number(limit).toLocaleString('es-CO') : ''} onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} error={fields.creditLimit} />
        <Field label="Plazo para pagar (días)" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value.replace(/\D/g, ''))} error={fields.creditDays} />
        <Toggle checked={blocked} onChange={setBlocked} label="Bloquear fiado" hint="No se le podrá fiar más hasta desbloquearlo." />
      </div>
    </Modal>
  )
}

const isoDay = (d: Date) => d.toLocaleDateString('en-CA')

/** Cuadre de fiados de un período, con la revisión de que cuadra con lo que deben hoy los clientes. */
function LedgerView({ onOpen }: { onOpen: (customerId: number) => void }) {
  const today = isoDay(new Date())
  const [from, setFrom] = useState(today)
  const [to, setTo] = useState(today)
  const [l, setL] = useState<CreditLedger | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setL(null)
    setError('')
    api<CreditLedger>(`/credit/ledger?from=${from}&to=${to}`)
      .then(setL)
      .catch((e) => setError(e.message))
  }, [from, to])

  function quick(kind: 'hoy' | 'semana' | 'mes') {
    const d = new Date()
    if (kind === 'semana') d.setDate(d.getDate() - ((d.getDay() + 6) % 7))
    if (kind === 'mes') d.setDate(1)
    setFrom(isoDay(d))
    setTo(today)
  }

  function exportCsv() {
    if (!l) return
    const rows = [
      ['Cliente', 'Saldo inicial', 'Fiado', 'Abonos', 'Abonos rechazados', 'Anulaciones y devoluciones', 'Saldo final'],
      ...l.customers.map((c) => [c.name, c.opening, c.charged, c.paid, c.rejected, c.adjusted, c.closing]),
      ['TOTAL', l.opening, l.charged, l.paid, l.rejected, l.voided + l.returned, l.closing],
    ]
    const csv = '\uFEFF' + rows.map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(';')).join('\n')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
    a.download = `cuadre-fiados-${from}-a-${to}.csv`
    a.click()
  }

  const Line = ({ k, v, sign, strong, tone }: { k: string; v: number; sign?: '+' | '−' | '='; strong?: boolean; tone?: string }) => (
    <div className={`flex items-center justify-between py-1.5 ${strong ? 'border-t border-line pt-2 font-bold' : ''}`}>
      <span className={tone}>
        {sign && <span className="num mr-2 inline-block w-3 text-muted">{sign}</span>}
        {k}
      </span>
      <span className="num">{money(v)}</span>
    </div>
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2 print:hidden">
        <Field label="Desde" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        <Field label="Hasta" type="date" value={to} max={today} onChange={(e) => setTo(e.target.value)} />
        <Button variant="soft" size="sm" onClick={() => quick('hoy')}>Hoy</Button>
        <Button variant="soft" size="sm" onClick={() => quick('semana')}>Semana</Button>
        <Button variant="soft" size="sm" onClick={() => quick('mes')}>Mes</Button>
        <div className="ml-auto flex gap-2">
          <Button variant="secondary" size="sm" icon="print" onClick={() => window.print()}>Imprimir</Button>
          <Button variant="secondary" size="sm" icon="download" onClick={exportCsv} disabled={!l}>Excel</Button>
        </div>
      </div>
      {error && <Banner>{error}</Banner>}
      {!l ? (
        !error && <Spinner />
      ) : (
        <>
          <Card className="p-4 text-sm">
            <div className="mb-2 font-semibold">
              Fiados del {l.from} al {l.to}
            </div>
            <Line k="Saldo por cobrar al inicio" v={l.opening} />
            <Line sign="+" k="Fiado en el período" v={l.charged} />
            <Line sign="−" k={`Abonos recibidos (efectivo ${money(l.paidCash)} · digitales ${money(l.paidDigital)})`} v={l.paid} tone="text-success-ink" />
            {l.rejected > 0 && <Line sign="+" k="Abonos rechazados (la plata no llegó)" v={l.rejected} tone="text-danger-ink" />}
            {l.voided + l.returned > 0 && <Line sign="−" k={`Anulaciones ${money(l.voided)} · devoluciones ${money(l.returned)}`} v={l.voided + l.returned} />}
            <Line sign="=" k="Saldo por cobrar al final" v={l.closing} strong />
            {l.paidPendingVerification > 0 && (
              <p className="mt-2 text-xs text-warning-ink">
                {money(l.paidPendingVerification)} de los abonos digitales todavía están por verificar en Pagos digitales.
              </p>
            )}
            <div className="mt-3">
              {l.balanced === null ? (
                <Chip tone="neutral">Período pasado: el saldo final es el de ese día</Chip>
              ) : l.balanced ? (
                <Chip tone="success">Cuadra: es igual a lo que deben hoy los clientes ({money(l.currentReceivables)})</Chip>
              ) : (
                <Chip tone="danger">No cuadra: los clientes deben hoy {money(l.currentReceivables)}. Revisa los movimientos.</Chip>
              )}
            </div>
          </Card>
          {l.customers.length === 0 ? (
            <EmptyState icon="menu_book" title="Sin fiados en este período" />
          ) : (
            <Card className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-muted uppercase">
                    <th className="px-4 py-2">Cliente</th>
                    <th className="px-2 py-2 text-right">Al inicio</th>
                    <th className="px-2 py-2 text-right">Fiado</th>
                    <th className="px-2 py-2 text-right">Abonos</th>
                    <th className="px-2 py-2 text-right">Ajustes</th>
                    <th className="px-4 py-2 text-right">Al final</th>
                  </tr>
                </thead>
                <tbody>
                  {l.customers.map((c) => (
                    <tr key={c.customerId} className="cursor-pointer border-b border-line-soft hover:bg-canvas" onClick={() => onOpen(c.customerId)}>
                      <td className="px-4 py-2 font-semibold">{c.name}</td>
                      <td className="num px-2 py-2 text-right">{money(c.opening)}</td>
                      <td className="num px-2 py-2 text-right">{c.charged ? money(c.charged) : ''}</td>
                      <td className="num px-2 py-2 text-right text-success-ink">{c.paid ? `−${money(c.paid)}` : ''}</td>
                      <td className="num px-2 py-2 text-right">
                        {c.rejected ? <span className="text-danger-ink">+{money(c.rejected)} </span> : ''}
                        {c.adjusted ? `−${money(c.adjusted)}` : ''}
                      </td>
                      <td className="num px-4 py-2 text-right font-bold">{money(c.closing)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}
        </>
      )}
    </div>
  )
}
