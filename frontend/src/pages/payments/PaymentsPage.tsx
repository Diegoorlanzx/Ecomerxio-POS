import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, StatCard, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import {
  RECON_STATUS,
  type PaymentAccount,
  type PaymentEvent,
  type PaymentIntegration,
  type PaymentRow,
  type Reconciliation,
} from '../../lib/financeTypes'
import { dateTime, imageToDataUrl, money, relative, shortDate } from '../../lib/format'
import { METHOD_ICONS, METHOD_LABELS, PAYMENT_STATUS, type StoreSettings } from '../../lib/posTypes'
import { serverUrl } from '../../lib/host'

type Tab = 'pendientes' | 'historial' | 'conciliar' | 'cuentas'
const DIGITAL = ['NEQUI', 'DAVIPLATA', 'BREB', 'TRANSFERENCIA']

/** Pagos digitales: verificación manual, historial con trazabilidad, conciliación con extractos y cuentas QR. */
export default function PaymentsPage() {
  const { can } = useAuth()
  const [tab, setTab] = useState<Tab>(can('PAGOS_VERIFICAR') ? 'pendientes' : 'conciliar')
  const [pendingCount, setPendingCount] = useState<number>()
  const items: { value: Tab; label: string; count?: number }[] = []
  if (can('PAGOS_VERIFICAR')) items.push({ value: 'pendientes', label: 'Por verificar', count: pendingCount })
  items.push({ value: 'historial', label: 'Historial' })
  if (can('PAGOS_CONCILIAR')) items.push({ value: 'conciliar', label: 'Conciliar' })
  if (can('CONFIG_EDITAR')) items.push({ value: 'cuentas', label: 'Cuentas QR' })

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Pagos digitales</h1>
        <p className="text-sm text-muted">Un pantallazo no es prueba de pago: confirma que el dinero llegó antes de darlo por recibido.</p>
      </div>
      <div className="overflow-x-auto">
        <Tabs value={tab} onChange={setTab} items={items} />
      </div>
      {tab === 'pendientes' && <PendingTab onCount={setPendingCount} />}
      {tab === 'historial' && <HistoryTab />}
      {tab === 'conciliar' && <ReconcileTab />}
      {tab === 'cuentas' && <AccountsTab />}
    </div>
  )
}

function MethodBadge({ method }: { method: string }) {
  return (
    <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-wallet-soft text-wallet-ink">
      <Icon name={METHOD_ICONS[method] ?? 'payments'} className="text-[22px]" />
    </span>
  )
}

function StatusChip({ status }: { status: string }) {
  const st = PAYMENT_STATUS[status] ?? { label: status, tone: 'neutral' as const }
  return <Chip tone={st.tone}>{st.label}</Chip>
}

// ---------------- Por verificar ----------------

function PendingTab({ onCount }: { onCount: (n: number) => void }) {
  const toast = useToast()
  const [pending, setPending] = useState<PaymentRow[] | null>(null)
  const [rejected, setRejected] = useState<PaymentRow[]>([])
  const [action, setAction] = useState<{ kind: 'confirm' | 'reject' | 'replace'; p: PaymentRow } | null>(null)
  const [events, setEvents] = useState<PaymentRow | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<PaymentRow[]>('/payments?status=PENDIENTE')
      .then((p) => (setPending(p), onCount(p.length)))
      .catch((e) => setError(e.message))
    api<PaymentRow[]>('/payments?status=RECHAZADO').then(setRejected).catch(() => {})
  }, [onCount])
  useEffect(() => {
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [load])

  if (error) return <Banner>{error}</Banner>
  if (!pending) return <Spinner />
  const total = pending.reduce((s, p) => s + p.amount, 0)
  const old = pending.filter((p) => p.minutesAgo >= 30).length

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatCard label="Por verificar" help="Pagos digitales (Nequi, Daviplata, Bre-B, transferencias) y abonos digitales que nadie ha confirmado todavía en la app del banco." value={money(total)} icon="hourglass_top" tone="wallet" sub={`${pending.length} pagos`} />
        <StatCard label="Más de 30 min" help="Pagos por verificar que llevan más de media hora: si el dinero no llegó, mejor saberlo pronto." value={old} icon="schedule" tone={old ? 'danger' : 'success'} sub={old ? 'Revísalos primero' : 'Al día'} />
        <StatCard label="Rechazados sin cobrar" help="Pagos que se rechazaron porque el dinero no llegó y que aún no se han cobrado de otra forma: el cliente quedó debiendo." value={rejected.length} icon="block" tone={rejected.length ? 'danger' : 'success'} />
      </div>

      {pending.length === 0 ? (
        <Card>
          <EmptyState icon="verified" title="No hay pagos por verificar" text="Los pagos por Nequi, Daviplata, Bre-B y transferencias aparecen aquí apenas se cobran." />
        </Card>
      ) : (
        <div className="space-y-2">
          {pending.map((p) => (
            <Card key={p.id} className="p-3">
              <div className="flex items-start gap-3">
                <MethodBadge method={p.method} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="num text-lg font-bold">{money(p.amount)}</span>
                    <span className="text-sm text-muted">{METHOD_LABELS[p.method] ?? p.method}</span>
                    {p.duplicateRef && (
                      <Chip tone="danger" icon="content_copy">
                        Referencia repetida
                      </Chip>
                    )}
                    {p.minutesAgo >= 30 && <Chip tone="warning">Hace {p.minutesAgo >= 120 ? `${Math.floor(p.minutesAgo / 60)} h` : `${p.minutesAgo} min`}</Chip>}
                  </div>
                  <div className="mt-0.5 text-xs text-muted">
                    {origin(p)} · {p.reference ? `Ref. ${p.reference}` : 'Sin referencia'} · {p.userName} · {relative(p.createdAt)}
                    {p.customerName ? ` · ${p.customerName}` : ''}
                  </div>
                </div>
              </div>
              <div className="mt-3 flex gap-2">
                <Button size="sm" variant="ghost" icon="timeline" onClick={() => setEvents(p)}>
                  Historial
                </Button>
                <div className="flex-1" />
                <Button size="sm" variant="secondary" icon="close" onClick={() => setAction({ kind: 'reject', p })}>
                  No llegó
                </Button>
                <Button size="sm" icon="check" onClick={() => setAction({ kind: 'confirm', p })}>
                  Llegó
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      {rejected.length > 0 && (
        <div>
          <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">Rechazados: el cliente quedó debiendo</h2>
          <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
            {rejected.map((p) => (
              <div key={p.id} className="flex items-center gap-3 px-3 py-2.5">
                <Icon name="block" className="text-[20px] text-danger-ink" />
                <div className="min-w-0 flex-1 text-sm">
                  <div className="font-semibold">
                    <span className="num">{money(p.amount)}</span> · {origin(p)}
                  </div>
                  <div className="truncate text-xs text-muted">{p.verificationNote}</div>
                </div>
                <Button size="sm" variant="soft" onClick={() => setAction({ kind: 'replace', p })}>
                  Cobrar de nuevo
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}

      {action && (
        <ActionModal
          kind={action.kind}
          payment={action.p}
          onClose={() => setAction(null)}
          onDone={(msg) => {
            setAction(null)
            toast(msg)
            load()
          }}
        />
      )}
      {events && <EventsModal payment={events} onClose={() => setEvents(null)} />}
    </div>
  )
}

function ActionModal({ kind, payment, onClose, onDone }: { kind: 'confirm' | 'reject' | 'replace'; payment: PaymentRow; onClose: () => void; onDone: (msg: string) => void }) {
  const [text, setText] = useState('')
  const [method, setMethod] = useState('EFECTIVO')
  const [methods, setMethods] = useState<string[]>(['EFECTIVO'])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (kind !== 'replace') return
    api<StoreSettings>('/settings/pos')
      .then((s) => setMethods(s.paymentMethods.split(',').filter((m) => m && m !== 'FIADO')))
      .catch(() => {})
  }, [kind])

  async function submit() {
    setError('')
    setLoading(true)
    try {
      if (kind === 'confirm') {
        await api(`/payments/${payment.id}/confirm`, { method: 'POST', body: { note: text || undefined } })
        onDone('Pago confirmado')
      } else if (kind === 'reject') {
        await api(`/payments/${payment.id}/reject`, { method: 'POST', body: { reason: text } })
        onDone('Pago rechazado: la venta quedó para revisión')
      } else {
        await api(`/payments/${payment.id}/replace`, { method: 'POST', body: { method, amount: payment.amount, reference: text || undefined } })
        onDone('Pago registrado')
      }
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar')
    } finally {
      setLoading(false)
    }
  }

  const title = kind === 'confirm' ? 'Confirmar que llegó' : kind === 'reject' ? 'El pago no llegó' : 'Cobrar de nuevo'
  return (
    <Modal
      open
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant={kind === 'reject' ? 'danger' : 'primary'} className="flex-1" onClick={submit} loading={loading} disabled={kind === 'reject' && !text.trim()}>
            {kind === 'confirm' ? 'Sí, llegó' : kind === 'reject' ? 'Rechazar pago' : `Cobrar ${money(payment.amount)}`}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="rounded-xl bg-canvas px-3 py-2.5 text-sm">
          <b className="num">{money(payment.amount)}</b> por {METHOD_LABELS[payment.method]?.toLowerCase()} · {origin(payment).toLowerCase()}
          {payment.reference && <div className="text-xs text-muted">Referencia {payment.reference}</div>}
        </div>
        {kind === 'confirm' && (
          <>
            <p className="text-sm text-muted">Confirma solo si viste el dinero en la cuenta de la tienda (app del banco o de la billetera).</p>
            <Field label="Nota (opcional)" value={text} onChange={(e) => setText(e.target.value)} placeholder="Ej: visto en la app de Nequi" />
          </>
        )}
        {kind === 'reject' && (
          <>
            <Banner tone="warning">La venta queda marcada con saldo por cobrar. Cuando el cliente pague, usa “Cobrar de nuevo”.</Banner>
            <Field label="Motivo" value={text} onChange={(e) => setText(e.target.value)} placeholder="Ej: no aparece en la cuenta" autoFocus />
          </>
        )}
        {kind === 'replace' && (
          <>
            <SelectField label="Medio" value={method} onChange={(e) => setMethod(e.target.value)}>
              {methods.map((m) => (
                <option key={m} value={m}>
                  {METHOD_LABELS[m] ?? m}
                </option>
              ))}
            </SelectField>
            {method !== 'EFECTIVO' && <Field label="Referencia" value={text} onChange={(e) => setText(e.target.value)} />}
            {method === 'EFECTIVO' && <p className="text-xs text-muted">El efectivo entra a tu turno abierto.</p>}
          </>
        )}
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

const EVENT_LABELS: Record<string, string> = {
  REGISTRADO: 'Registrado',
  CONFIRMADO: 'Confirmado',
  RECHAZADO: 'Rechazado',
  REEMPLAZADO: 'Reemplazado',
  REEMBOLSADO: 'Reembolsado',
  CONCILIADO: 'Conciliado',
  REFERENCIA_DUPLICADA: 'Referencia repetida',
  IMPORTE_INCONSISTENTE: 'Valor distinto',
  WEBHOOK: 'Aviso del proveedor',
}

function EventsModal({ payment, onClose }: { payment: PaymentRow; onClose: () => void }) {
  const [events, setEvents] = useState<PaymentEvent[] | null>(null)
  useEffect(() => {
    api<PaymentEvent[]>(`/payments/events?paymentId=${payment.id}`).then(setEvents).catch(() => setEvents([]))
  }, [payment.id])
  return (
    <Modal open title={`Pago de ${money(payment.amount)} · ${origin(payment).toLowerCase()}`} onClose={onClose}>
      {!events ? (
        <Spinner />
      ) : (
        <ol className="relative space-y-4 border-l-2 border-line-soft pl-5">
          {events.map((e) => (
            <li key={e.id}>
              <span className="absolute -left-[7px] mt-1.5 h-3 w-3 rounded-full bg-wallet" />
              <div className="text-sm font-semibold">{EVENT_LABELS[e.type] ?? e.type}</div>
              <div className="text-xs text-muted">
                {dateTime(e.createdAt)} · {e.userName}
              </div>
              {e.detail && <div className="mt-0.5 text-sm">{e.detail}</div>}
            </li>
          ))}
        </ol>
      )}
    </Modal>
  )
}

// ---------------- Historial ----------------

function HistoryTab() {
  const [method, setMethod] = useState('')
  const [status, setStatus] = useState('')
  const [from, setFrom] = useState(() => new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10))
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10))
  const [rows, setRows] = useState<PaymentRow[] | null>(null)
  const [events, setEvents] = useState<PaymentRow | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setRows(null)
    const q = new URLSearchParams({ from, to })
    if (method) q.set('method', method)
    if (status) q.set('status', status)
    api<PaymentRow[]>(`/payments?${q}`)
      .then(setRows)
      .catch((e) => setError(e.message))
  }, [method, status, from, to])

  const byStatus = (s: string) => (rows ?? []).filter((r) => r.status === s).reduce((a, r) => a + r.amount, 0)

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <SelectField label="Medio" value={method} onChange={(e) => setMethod(e.target.value)}>
          <option value="">Todos</option>
          {['EFECTIVO', ...DIGITAL, 'TARJETA', 'OTRO'].map((m) => (
            <option key={m} value={m}>
              {METHOD_LABELS[m]}
            </option>
          ))}
        </SelectField>
        <SelectField label="Estado" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Todos</option>
          {['PENDIENTE', 'CONFIRMADO', 'RECHAZADO', 'REEMBOLSADO', 'ANULADO'].map((s) => (
            <option key={s} value={s}>
              {PAYMENT_STATUS[s].label}
            </option>
          ))}
        </SelectField>
        <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      {error && <Banner>{error}</Banner>}
      {!rows ? (
        <Spinner />
      ) : (
        <>
          <div className="grid grid-cols-3 gap-3">
            <StatCard label="Confirmado" help="Pagos digitales del período que ya se confirmaron (a mano o con el extracto del banco)." value={money(byStatus('CONFIRMADO'))} icon="check_circle" tone="success" />
            <StatCard label="Por verificar" help="Pagos digitales (Nequi, Daviplata, Bre-B, transferencias) y abonos digitales que nadie ha confirmado todavía en la app del banco." value={money(byStatus('PENDIENTE'))} icon="hourglass_top" tone="wallet" />
            <StatCard label="Rechazado" help="Pagos digitales del período que se rechazaron porque el dinero no llegó." value={money(byStatus('RECHAZADO'))} icon="block" tone="danger" />
          </div>
          {rows.length === 0 ? (
            <Card>
              <EmptyState icon="receipt_long" title="Sin pagos en este período" />
            </Card>
          ) : (
            <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
              {rows.map((p) => (
                <button key={p.id} type="button" onClick={() => setEvents(p)} className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-canvas">
                  <Icon name={METHOD_ICONS[p.method] ?? 'payments'} className="text-[20px] text-muted" />
                  <div className="min-w-0 flex-1 text-sm">
                    <div className="font-semibold">
                      {METHOD_LABELS[p.method] ?? p.method} · {origin(p)}
                      {p.reconciledAt && <Icon name="fact_check" className="ml-1 align-[-3px] text-[16px] text-success" />}
                    </div>
                    <div className="truncate text-xs text-muted">
                      {shortDate(p.createdAt)}
                      {p.reference ? ` · Ref. ${p.reference}` : ''}
                      {p.verifiedBy ? ` · Verificó ${p.verifiedBy}` : ''}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="num text-sm font-bold">{money(p.amount)}</div>
                    <StatusChip status={p.status} />
                  </div>
                </button>
              ))}
            </div>
          )}
        </>
      )}
      {events && <EventsModal payment={events} onClose={() => setEvents(null)} />}
    </div>
  )
}

// ---------------- Conciliación ----------------

function ReconcileTab() {
  const toast = useToast()
  const [method, setMethod] = useState('NEQUI')
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<Reconciliation | null>(null)
  const [past, setPast] = useState<Reconciliation[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const loadPast = () => api<Reconciliation[]>('/payments/reconciliations').then(setPast).catch(() => {})
  useEffect(() => {
    loadPast()
  }, [])

  async function run() {
    if (!file) return
    setError('')
    setLoading(true)
    try {
      if (file.size > 2_500_000) throw new Error('El archivo es muy grande (máximo 2,5 MB)')
      const csv = await file.text()
      const r = await api<Reconciliation>('/payments/reconcile', { method: 'POST', body: { method, fileName: file.name, csv } })
      setResult(r)
      setFile(null)
      toast(r.matched === 1 ? "1 pago conciliado" : `${r.matched} pagos conciliados`)
      loadPast()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : e instanceof Error ? e.message : 'No se pudo leer el archivo')
    } finally {
      setLoading(false)
    }
  }

  async function open(id: number) {
    setResult(await api<Reconciliation>(`/payments/reconciliations/${id}`))
  }

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <h3 className="font-semibold">Cruzar con el extracto</h3>
        <p className="mb-3 text-sm text-muted">
          Descarga el extracto en CSV desde la app del banco o de la billetera y súbelo. Se cruza por referencia y, si no hay, por valor y fecha. Lo que coincide queda confirmado.
        </p>
        <div className="grid gap-3 sm:grid-cols-[180px_1fr_auto] sm:items-end">
          <SelectField label="Medio" value={method} onChange={(e) => setMethod(e.target.value)}>
            {DIGITAL.map((m) => (
              <option key={m} value={m}>
                {METHOD_LABELS[m]}
              </option>
            ))}
          </SelectField>
          <label className="flex h-12 cursor-pointer items-center gap-2 rounded-xl border border-dashed border-line px-3 text-sm hover:bg-canvas">
            <Icon name="upload_file" className="text-[20px] text-primary" />
            <span className="truncate">{file ? file.name : 'Elegir archivo CSV'}</span>
            <input type="file" accept=".csv,.txt,text/csv" className="hidden" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </label>
          <Button icon="fact_check" onClick={run} loading={loading} disabled={!file}>
            Conciliar
          </Button>
        </div>
        {error && (
          <div className="mt-3">
            <Banner>{error}</Banner>
          </div>
        )}
      </Card>

      {result && (
        <Card className="p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h3 className="font-semibold">
                {METHOD_LABELS[result.method]} · {result.fileName ?? 'extracto'}
              </h3>
              <p className="text-xs text-muted">
                {result.totalRows} entradas de dinero {result.dateFrom ? `del ${result.dateFrom} al ${result.dateTo}` : ''} · {result.createdBy}
              </p>
            </div>
            <div className="flex gap-1.5">
              <Chip tone="success">{result.matched} conciliados</Chip>
              {result.inconsistent > 0 && <Chip tone="danger">{result.inconsistent} con diferencia</Chip>}
              {result.unmatched > 0 && <Chip tone="warning">{result.unmatched} sin cruzar</Chip>}
            </div>
          </div>
          <div className="divide-y divide-line-soft rounded-xl border border-line">
            {(result.rows ?? []).map((r) => (
              <div key={r.id} className="flex items-center gap-3 px-3 py-2">
                <div className="min-w-0 flex-1 text-sm">
                  <div className="font-semibold">
                    <span className="num">{money(r.amount)}</span>
                    {r.saleNumber ? ` · Venta #${r.saleNumber}` : ''}
                  </div>
                  <div className="truncate text-xs text-muted">
                    {r.rowDate ?? ''} {r.reference ? `· Ref. ${r.reference}` : ''} {r.description ? `· ${r.description}` : ''}
                  </div>
                  {r.note && r.status !== 'CONCILIADO' && <div className="text-xs text-ink/70">{r.note}</div>}
                </div>
                <Chip tone={RECON_STATUS[r.status]?.tone ?? 'neutral'}>{RECON_STATUS[r.status]?.label ?? r.status}</Chip>
              </div>
            ))}
          </div>
          {(result.stillPending?.length ?? 0) > 0 && (
            <div className="mt-3">
              <Banner tone="warning">
                {result.stillPending!.length} pagos registrados por {METHOD_LABELS[result.method]?.toLowerCase()} no aparecen en el extracto (
                {money(result.stillPending!.reduce((s, p) => s + p.amount, 0))}). Revísalos en “Por verificar”.
              </Banner>
            </div>
          )}
        </Card>
      )}

      {past.length > 0 && (
        <div>
          <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">Conciliaciones anteriores</h2>
          <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
            {past.map((r) => (
              <button key={r.id} type="button" onClick={() => open(r.id)} className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-canvas">
                <Icon name="fact_check" className="text-[20px] text-primary" />
                <div className="min-w-0 flex-1 text-sm">
                  <div className="font-semibold">
                    {METHOD_LABELS[r.method]} · {r.fileName ?? 'extracto'}
                  </div>
                  <div className="text-xs text-muted">
                    {dateTime(r.createdAt)} · {r.createdBy}
                  </div>
                </div>
                <span className="text-xs text-muted">
                  {r.matched}/{r.totalRows}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ---------------- Cuentas QR e integración ----------------

function AccountsTab() {
  const toast = useToast()
  const [accounts, setAccounts] = useState<PaymentAccount[] | null>(null)
  const [integrations, setIntegrations] = useState<PaymentIntegration[]>([])
  const [editing, setEditing] = useState<PaymentAccount | 'new' | null>(null)
  const [created, setCreated] = useState<PaymentIntegration | null>(null)

  const load = () => {
    api<PaymentAccount[]>('/payment-accounts').then(setAccounts).catch(() => setAccounts([]))
    api<PaymentIntegration[]>('/payment-integrations').then(setIntegrations).catch(() => {})
  }
  useEffect(load, [])

  async function createIntegration() {
    const i = await api<PaymentIntegration>('/payment-integrations', { method: 'POST', body: { provider: 'GENERICO' } })
    setCreated(i)
    load()
  }

  if (!accounts) return <Spinner />
  return (
    <div className="space-y-4">
      <Card className="p-4">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div>
            <h3 className="font-semibold">Cuentas para recibir pagos</h3>
            <p className="text-sm text-muted">Su QR aparece en el cobro para que el cliente pague directo a la tienda.</p>
          </div>
          <Button size="sm" icon="add" onClick={() => setEditing('new')}>
            Agregar
          </Button>
        </div>
        {accounts.length === 0 ? (
          <EmptyState icon="qr_code_2" title="Sin cuentas todavía" text="Agrega tu Nequi, Daviplata o llave Bre-B con su código QR." />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {accounts.map((a) => (
              <button key={a.id} type="button" onClick={() => setEditing(a)} className="flex items-center gap-3 rounded-xl border border-line p-3 text-left hover:border-wallet">
                {a.qrImage ? (
                  <img src={a.qrImage} alt="" className="h-16 w-16 rounded-lg bg-white object-contain" />
                ) : (
                  <span className="flex h-16 w-16 items-center justify-center rounded-lg bg-wallet-soft text-wallet-ink">
                    <Icon name="qr_code_2" className="text-[30px]" />
                  </span>
                )}
                <div className="min-w-0 text-sm">
                  <div className="font-semibold">{a.label}</div>
                  <div className="text-xs text-muted">
                    {METHOD_LABELS[a.method]} · {a.accountRef ?? 'sin número'}
                  </div>
                  {!a.active && <Chip>Inactiva</Chip>}
                </div>
              </button>
            ))}
          </div>
        )}
      </Card>

      <Card className="p-4">
        <h3 className="font-semibold">Confirmación automática</h3>
        <p className="mb-3 text-sm text-muted">
          Si usas una pasarela o un puente con tu banco que avise cada pago recibido, conéctalo aquí: los pagos con la misma referencia y valor se confirman solos. El aviso debe venir firmado
          con la llave secreta (HMAC-SHA256 en la cabecera X-Signature).
        </p>
        {integrations.map((i) => (
          <div key={i.id} className="mb-2 rounded-xl bg-canvas px-3 py-2.5 text-sm">
            <div className="font-semibold">{i.provider}</div>
            <div className="num text-xs break-all text-muted">
              {serverUrl(i.webhookPath)}
            </div>
            <div className="text-xs text-muted">Llave secreta {i.secret} · último aviso {relative(i.lastEventAt)}</div>
          </div>
        ))}
        <Button size="sm" variant="secondary" icon="key" onClick={createIntegration}>
          Crear llaves de integración
        </Button>
      </Card>

      {editing && (
        <AccountModal
          account={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(msg) => {
            setEditing(null)
            toast(msg)
            load()
          }}
        />
      )}
      {created && (
        <Modal open title="Llaves creadas" onClose={() => setCreated(null)}>
          <div className="space-y-3 text-sm">
            <Banner tone="warning">Copia la llave secreta ahora: no se volverá a mostrar completa.</Banner>
            <Field label="Dirección del aviso (webhook)" readOnly value={serverUrl(created.webhookPath)} />
            <Field label="Llave secreta" readOnly value={created.secret} />
            <p className="text-xs text-muted">
              Cuerpo esperado: {'{"reference":"…","amount":12000,"status":"APPROVED","transactionId":"…"}'}
            </p>
          </div>
        </Modal>
      )}
    </div>
  )
}

function AccountModal({ account, onClose, onSaved }: { account: PaymentAccount | null; onClose: () => void; onSaved: (msg: string) => void }) {
  const [a, setA] = useState({
    method: account?.method ?? 'NEQUI',
    label: account?.label ?? '',
    holder: account?.holder ?? '',
    accountRef: account?.accountRef ?? '',
    qrImage: account?.qrImage ?? '',
    active: account?.active ?? true,
  })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function save() {
    setLoading(true)
    setFields({})
    try {
      await api(account ? `/payment-accounts/${account.id}` : '/payment-accounts', { method: account ? 'PUT' : 'POST', body: { ...a, qrImage: a.qrImage || null } })
      onSaved('Cuenta guardada')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { label: e.message })
    } finally {
      setLoading(false)
    }
  }

  async function remove() {
    if (!account) return
    await api(`/payment-accounts/${account.id}`, { method: 'DELETE' })
    onSaved('Cuenta eliminada')
  }

  return (
    <Modal
      open
      title={account ? 'Editar cuenta' : 'Nueva cuenta de cobro'}
      onClose={onClose}
      footer={
        <>
          {account && (
            <Button variant="ghost" icon="delete" onClick={remove}>
              Eliminar
            </Button>
          )}
          <div className="flex-1" />
          <Button onClick={save} loading={loading} icon="save">
            Guardar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <SelectField label="Medio" value={a.method} onChange={(e) => setA({ ...a, method: e.target.value })}>
          {DIGITAL.map((m) => (
            <option key={m} value={m}>
              {METHOD_LABELS[m]}
            </option>
          ))}
        </SelectField>
        <Field label="Nombre para mostrar" value={a.label} onChange={(e) => setA({ ...a, label: e.target.value })} error={fields.label} placeholder="Ej: Nequi de la tienda" />
        <div className="grid grid-cols-2 gap-2">
          <Field label="Titular" value={a.holder} onChange={(e) => setA({ ...a, holder: e.target.value })} />
          <Field label="Número o llave" value={a.accountRef} onChange={(e) => setA({ ...a, accountRef: e.target.value })} />
        </div>
        <div>
          <span className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">Código QR</span>
          <div className="flex items-center gap-3">
            {a.qrImage ? (
              <img src={a.qrImage} alt="QR" className="h-28 w-28 rounded-xl border border-line bg-white object-contain" />
            ) : (
              <span className="flex h-28 w-28 items-center justify-center rounded-xl border border-dashed border-line text-muted">
                <Icon name="qr_code_2" className="text-[36px]" />
              </span>
            )}
            <div className="space-y-2">
              <label className="inline-flex h-10 cursor-pointer items-center gap-2 rounded-xl border border-line px-3 text-sm font-semibold hover:bg-canvas">
                <Icon name="upload" className="text-[18px]" /> Subir imagen
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  className="hidden"
                  onChange={async (e) => {
                    const f = e.target.files?.[0]
                    if (f) setA({ ...a, qrImage: await imageToDataUrl(f, 520) })
                  }}
                />
              </label>
              {a.qrImage && (
                <Button size="sm" variant="ghost" onClick={() => setA({ ...a, qrImage: '' })}>
                  Quitar
                </Button>
              )}
            </div>
          </div>
          {fields.qrImage && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.qrImage}</p>}
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={a.active} onChange={(e) => setA({ ...a, active: e.target.checked })} /> Mostrar en el cobro
        </label>
      </div>
    </Modal>
  )
}

/** De dónde viene el pago: una venta o un abono de fiado (los abonos digitales también se verifican aquí). */
function origin(p: { saleNumber: number | null; creditPayment?: boolean; customerName?: string }): string {
  return p.creditPayment ? 'Abono de fiado' : `Venta #${p.saleNumber}`
}
