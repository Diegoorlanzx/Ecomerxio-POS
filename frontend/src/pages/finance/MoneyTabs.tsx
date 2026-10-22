import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, StatCard, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { FinanceDashboard, Ledger, MoneyAccount, MoneyOverview, Projection, TaxCalendar, TaxItem, TaxSettings } from '../../lib/financeTypes'
import { money, pct, shortDate } from '../../lib/format'
import { METHOD_LABELS } from '../../lib/posTypes'

const today = () => new Date().toLocaleDateString('en-CA')
const KIND = { BANCO: { label: 'Banco', icon: 'account_balance' }, BILLETERA: { label: 'Billetera', icon: 'account_balance_wallet' }, OTRA: { label: 'Otra', icon: 'savings' } } as const
const DOC_LABELS: Record<string, string> = { ACEPTADO: 'Aceptados', PENDIENTE: 'Pendientes', EN_PROCESO: 'En proceso', DESCONOCIDO: 'Por verificar', RECHAZADO: 'Rechazados', BLOQUEADO: 'Bloqueados', ANULADO: 'Anulados' }
const TAX_STATUS = {
  PENDIENTE: { label: 'Pendiente', tone: 'neutral' as const },
  PROXIMA: { label: 'Próxima', tone: 'warning' as const },
  VENCIDA: { label: 'Vencida', tone: 'danger' as const },
  HECHA: { label: 'Hecha', tone: 'success' as const },
}

function Section({ title, link, children }: { title: string; link?: { to: string; label: string }; children: React.ReactNode }) {
  return (
    <Card className="min-w-0 p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="font-semibold">{title}</h3>
        {link && (
          <Link to={link.to} className="shrink-0 text-xs font-semibold whitespace-nowrap text-primary">
            {link.label}
          </Link>
        )}
      </div>
      {children}
    </Card>
  )
}

function KV({ k, v, tone }: { k: string; v: React.ReactNode; tone?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2 py-1 text-sm">
      <span className="min-w-0 text-muted">{k}</span>
      <span className={`num shrink-0 text-right font-semibold ${tone ?? ''}`}>{v}</span>
    </div>
  )
}

// ---------------- Tablero ----------------

/** Fase 15: tablero financiero único. */
export function DashboardTab() {
  const [d, setD] = useState<FinanceDashboard | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<FinanceDashboard>('/finance/dashboard').then(setD).catch((e) => setError(e.message))
  }, [])
  if (error) return <Banner>{error}</Banner>
  if (!d) return <Spinner />
  const r = d.results
  const change = r.salesChangePct
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="Ventas del mes"
          icon="point_of_sale"
          value={money(r.sales)}
          sub={change == null ? 'Sin datos del mes anterior' : `${change >= 0 ? '+' : ''}${pct(change)} frente a los mismos días del mes pasado`}
          help="Ventas netas desde el día 1 hasta hoy, comparadas con el mismo número de días del mes anterior."
        />
        <StatCard
          label="Utilidad estimada"
          icon="trending_up"
          tone={r.netProfit < 0 ? 'danger' : 'success'}
          value={money(r.netProfit)}
          sub={`Margen bruto ${pct(r.grossMarginPct)} · gastos ${money(r.expenses)}`}
          help="Ventas sin impuestos menos el costo de lo vendido menos los gastos registrados del mes. Es una estimación: no incluye impuestos por pagar."
        />
        <StatCard
          label="Dinero disponible"
          icon="account_balance_wallet"
          tone="wallet"
          value={money(d.money.total)}
          sub={`Caja ${money(d.money.cash)} · cuentas ${money(d.money.inAccounts)}`}
          help="El efectivo esperado en las cajas abiertas más el saldo de tus cuentas de banco y billeteras."
        />
        <StatCard
          label="Punto más bajo (8 semanas)"
          icon="show_chart"
          tone={d.outlook.lowest < 0 ? 'danger' : 'navy'}
          value={money(d.outlook.lowest)}
          sub={d.outlook.negativeWeeks ? `${d.outlook.negativeWeeks} semanas en negativo` : `Semana del ${shortDate(d.outlook.lowestWeek)}`}
          help="Según el flujo proyectado: el saldo más bajo que tendría el negocio en las próximas 8 semanas si todo sigue como va."
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Section title="Cobrar y pagar" link={{ to: '/finanzas/por-pagar', label: 'Ver por pagar' }}>
          <KV k="Te deben (fiado)" v={money(d.debts.receivables)} />
          <KV k="…de eso, vencido" v={money(d.debts.receivablesOverdue)} tone={d.debts.receivablesOverdue > 0 ? 'text-danger-ink' : ''} />
          <KV k="Debes a proveedores" v={money(d.debts.payables)} />
          <KV k="…vencido" v={money(d.debts.payablesOverdue)} tone={d.debts.payablesOverdue > 0 ? 'text-danger-ink' : ''} />
          <KV k="…vence esta semana" v={money(d.debts.payablesThisWeek)} />
        </Section>
        <Section title="Cuentas" link={{ to: '/finanzas?tab=cuentas', label: 'Ver cuentas' }}>
          {d.money.accounts.length === 0 ? (
            <p className="text-sm text-muted">Registra tus cuentas de banco y billeteras para ver su saldo sin digitar cada movimiento.</p>
          ) : (
            d.money.accounts.map((a) => <KV key={a.id} k={a.name} v={money(a.balance)} tone={a.balance < 0 ? 'text-danger-ink' : ''} />)
          )}
          <KV k="Efectivo en cajas abiertas" v={money(d.money.cash)} />
        </Section>
        <Section title="Pagos y conciliación" link={{ to: '/pagos', label: 'Ver pagos' }}>
          <KV k="Pagos digitales por verificar" v={`${d.reconciliation.pendingCount} · ${money(d.reconciliation.pendingAmount)}`} tone={d.reconciliation.pendingCount ? 'text-warning-ink' : ''} />
          {d.reconciliation.lastAt ? (
            <>
              <KV k="Última conciliación" v={`${METHOD_LABELS[d.reconciliation.lastMethod ?? ''] ?? d.reconciliation.lastMethod} · ${shortDate(d.reconciliation.lastAt)}`} />
              <KV k="Con diferencias / sin cruzar" v={`${d.reconciliation.lastInconsistent ?? 0} / ${d.reconciliation.lastUnmatched ?? 0}`} tone={(d.reconciliation.lastInconsistent ?? 0) > 0 ? 'text-danger-ink' : ''} />
            </>
          ) : (
            <p className="py-1 text-sm text-muted">Todavía no has conciliado un extracto.</p>
          )}
        </Section>
        <Section title="Facturación electrónica" link={{ to: '/facturacion?tab=documentos', label: 'Ver documentos' }}>
          {!d.documents.enabled ? (
            <p className="text-sm text-muted">No está activa.</p>
          ) : (
            <>
              {Object.entries(d.documents.byStatus).map(([k, v]) => (
                <KV key={k} k={DOC_LABELS[k] ?? k} v={v} />
              ))}
              {d.documents.problems > 0 && <KV k="Con problemas" v={d.documents.problems} tone="text-danger-ink" />}
              {d.documents.simulated && <p className="mt-1 text-xs text-muted">Proveedor simulado: los documentos no tienen validez.</p>}
            </>
          )}
        </Section>
        <Section title="Obligaciones próximas" link={{ to: '/finanzas?tab=calendario', label: 'Ver calendario' }}>
          {d.obligations.length === 0 ? (
            <p className="text-sm text-muted">Nada en los próximos días. Configura el calendario tributario para recibir recordatorios.</p>
          ) : (
            d.obligations.map((o) => (
              <div key={o.id} className="flex items-center justify-between gap-2 py-1 text-sm">
                <span className="min-w-0 truncate">{o.name}</span>
                <Chip tone={TAX_STATUS[o.status].tone}>{o.status === 'VENCIDA' ? 'Vencida' : o.daysLeft === 0 ? 'Hoy' : `${o.daysLeft} d`}</Chip>
              </div>
            ))
          )}
        </Section>
        <Section title="Alertas">
          {d.alerts.length === 0 ? (
            <p className="text-sm text-muted">Sin alertas.</p>
          ) : (
            d.alerts.map((a, i) => (
              <Link key={i} to={a.link} className="flex items-start gap-2 py-1 text-sm hover:text-primary">
                <Icon name={a.severity === 'MEDIA' ? 'info' : 'warning'} className={`text-[18px] ${a.severity === 'MEDIA' ? 'text-muted' : 'text-danger-ink'}`} />
                <span className="min-w-0 truncate">{a.title}</span>
              </Link>
            ))
          )}
        </Section>
      </div>
    </div>
  )
}

// ---------------- Cuentas ----------------

export function AccountsTab() {
  const { can } = useAuth()
  const toast = useToast()
  const [data, setData] = useState<MoneyOverview | null>(null)
  const [editing, setEditing] = useState<MoneyAccount | 'new' | null>(null)
  const [ledger, setLedger] = useState<MoneyAccount | null>(null)
  const [moving, setMoving] = useState<MoneyAccount | null>(null)
  const [error, setError] = useState('')
  const load = useCallback(() => {
    api<MoneyOverview>('/finance/accounts').then(setData).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])
  const edit = can('FINANZAS_GASTOS')

  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner />
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-2xl text-sm text-muted">
          Cada cuenta suma sola lo que cobras y pagas por sus medios (p. ej. transferencias y datáfono → tu banco). Anota a mano solo consignaciones, retiros, traslados y comisiones.
        </p>
        {edit && (
          <Button icon="add" onClick={() => setEditing('new')}>
            Nueva cuenta
          </Button>
        )}
      </div>
      {data.unassignedMethods.length > 0 && (
        <Banner tone="warning">
          Cobraste por {data.unassignedMethods.map((m) => METHOD_LABELS[m] ?? m).join(', ')} y ese medio no cae en ninguna cuenta: asígnalo para que el saldo cuadre.
        </Banner>
      )}
      {data.accounts.length === 0 ? (
        <Card>
          <EmptyState icon="account_balance" title="Sin cuentas" text="Crea tu cuenta de banco o tu billetera (Nequi, Daviplata) con el saldo de hoy." />
        </Card>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {data.accounts.map((a) => (
              <Card key={a.id} className={`p-4 ${a.active ? '' : 'opacity-60'}`}>
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5 font-semibold">
                      <Icon name={KIND[a.kind].icon} className="text-[18px] text-wallet" />
                      <span className="truncate">{a.name}</span>
                    </div>
                    <div className="text-xs text-muted">{a.methods.length ? a.methods.map((m) => METHOD_LABELS[m] ?? m).join(' · ') : 'Sin medios asignados'}</div>
                  </div>
                  {!a.active && <Chip>Inactiva</Chip>}
                </div>
                <div className={`num mt-2 text-2xl font-bold ${a.balance < 0 ? 'text-danger-ink' : ''}`}>{money(a.balance)}</div>
                <div className="mt-1 text-xs text-muted">
                  Este mes: entró {money(a.monthIn)} · salió {money(a.monthOut)}
                  {a.pendingVerification > 0 && <span className="text-warning-ink"> · {money(a.pendingVerification)} por verificar</span>}
                </div>
                {a.lastReconciliation && (
                  <div className="mt-1 text-xs text-muted">
                    Conciliado hasta {a.lastReconciliation.dateTo ?? shortDate(a.lastReconciliation.createdAt)}
                    {a.lastReconciliation.inconsistent + a.lastReconciliation.unmatched > 0 && ` · ${a.lastReconciliation.inconsistent + a.lastReconciliation.unmatched} por revisar`}
                  </div>
                )}
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button size="sm" variant="secondary" icon="receipt_long" onClick={() => setLedger(a)}>
                    Movimientos
                  </Button>
                  {edit && a.active && (
                    <Button size="sm" variant="soft" icon="swap_horiz" onClick={() => setMoving(a)}>
                      Anotar
                    </Button>
                  )}
                  {edit && (
                    <Button size="sm" variant="ghost" icon="edit" onClick={() => setEditing(a)}>
                      Editar
                    </Button>
                  )}
                </div>
              </Card>
            ))}
          </div>
          <p className="text-sm">
            Total en cuentas activas: <b className="num">{money(data.total)}</b>
          </p>
        </>
      )}
      {editing && (
        <AccountForm
          account={editing === 'new' ? null : editing}
          available={data.availableMethods}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            toast('Cuenta guardada')
            load()
          }}
        />
      )}
      {ledger && <LedgerModal account={ledger} canEdit={edit} onClose={() => (setLedger(null), load())} />}
      {moving && (
        <MoveForm
          account={moving}
          others={data.accounts.filter((x) => x.active && x.id !== moving.id)}
          onClose={() => setMoving(null)}
          onSaved={() => {
            setMoving(null)
            toast('Movimiento anotado')
            load()
          }}
        />
      )}
    </div>
  )
}

function AccountForm({ account, available, onClose, onSaved }: { account: MoneyAccount | null; available: string[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    name: account?.name ?? '',
    kind: account?.kind ?? 'BANCO',
    methods: account?.methods ?? [],
    openingBalance: String(account?.openingBalance ?? ''),
    openingDate: account?.openingDate ?? today(),
    active: account?.active ?? true,
  })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const choices = Array.from(new Set([...(account?.methods ?? []), ...available]))

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const body = { ...f, openingBalance: Number(f.openingBalance.replace(/[^\d-]/g, '') || 0) }
      if (account) await api(`/finance/accounts/${account.id}`, { method: 'PUT', body })
      else await api('/finance/accounts', { body })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) Object.keys(e.fields).length ? setFields(e.fields) : setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={account ? `Editar ${account.name}` : 'Nueva cuenta'}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={saving}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} error={fields.name} placeholder="Ej: Bancolombia ahorros" autoFocus />
        <SelectField label="Tipo" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as MoneyAccount['kind'] })}>
          <option value="BANCO">Banco</option>
          <option value="BILLETERA">Billetera (Nequi, Daviplata…)</option>
          <option value="OTRA">Otra</option>
        </SelectField>
        <div>
          <span className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">¿Qué cobros y pagos caen aquí?</span>
          <div className="flex flex-wrap gap-2">
            {choices.map((m) => {
              const on = f.methods.includes(m)
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => setF({ ...f, methods: on ? f.methods.filter((x) => x !== m) : [...f.methods, m] })}
                  className={`h-9 rounded-full px-3 text-xs font-semibold ${on ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}
                >
                  {METHOD_LABELS[m] ?? m}
                </button>
              )
            })}
          </div>
          {fields.methods ? <p className="mt-1 text-xs font-medium text-danger-ink">{fields.methods}</p> : <p className="mt-1 text-xs text-muted">Un medio va a una sola cuenta. Los que ya usa otra cuenta no aparecen.</p>}
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field
            label="Saldo en esa fecha"
            inputMode="numeric"
            value={f.openingBalance}
            onChange={(e) => setF({ ...f, openingBalance: e.target.value.replace(/[^\d-]/g, '') })}
            hint="Lo que dice el extracto o la app"
          />
          <Field label="Fecha del saldo" type="date" value={f.openingDate} onChange={(e) => setF({ ...f, openingDate: e.target.value })} />
        </div>
        {account && <Toggle checked={f.active} onChange={(v) => setF({ ...f, active: v })} label="Cuenta activa" />}
      </div>
    </Modal>
  )
}

function MoveForm({ account, others, onClose, onSaved }: { account: MoneyAccount; others: MoneyAccount[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({ type: 'CONSIGNACION', amount: '', moveDate: today(), toAccountId: '', note: '', negative: false })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const hints: Record<string, string> = {
    CONSIGNACION: 'Efectivo que llevaste al banco. Si salió de una caja abierta, regístralo también como retiro en Caja.',
    RETIRO: 'Dinero que sacaste de la cuenta (cajero, retiro para la caja o para el dueño).',
    TRASLADO: 'Pasaste plata de esta cuenta a otra tuya.',
    COMISION: 'Cuota de manejo, 4×1000, comisión del datáfono u otro costo bancario.',
    AJUSTE: 'Para cuadrar con el extracto. Explica la diferencia.',
  }

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const amount = Number(f.amount || 0) * (f.type === 'AJUSTE' && f.negative ? -1 : 1)
      await api('/finance/account-moves', {
        body: { accountId: account.id, type: f.type, amount, moveDate: f.moveDate, toAccountId: f.type === 'TRASLADO' ? Number(f.toAccountId) || undefined : undefined, note: f.note || undefined },
      })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) Object.keys(e.fields).length ? setFields(e.fields) : setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={`Anotar en ${account.name}`}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={saving} disabled={!Number(f.amount)}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <SelectField label="Qué pasó" value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
          <option value="CONSIGNACION">Consignación (entra)</option>
          <option value="RETIRO">Retiro (sale)</option>
          <option value="TRASLADO">Traslado a otra cuenta</option>
          <option value="COMISION">Comisión o costo bancario (sale)</option>
          <option value="AJUSTE">Ajuste al extracto</option>
        </SelectField>
        <p className="text-xs text-muted">{hints[f.type]}</p>
        {f.type === 'TRASLADO' && (
          <SelectField label="Cuenta que recibe" value={f.toAccountId} onChange={(e) => setF({ ...f, toAccountId: e.target.value })} error={fields.toAccountId}>
            <option value="">Elige…</option>
            {others.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </SelectField>
        )}
        <div className="grid grid-cols-2 gap-2">
          <Field label="Valor" inputMode="numeric" value={f.amount ? Number(f.amount).toLocaleString('es-CO') : ''} onChange={(e) => setF({ ...f, amount: e.target.value.replace(/\D/g, '') })} error={fields.amount} />
          <Field label="Fecha" type="date" value={f.moveDate} max={today()} onChange={(e) => setF({ ...f, moveDate: e.target.value })} error={fields.moveDate} />
        </div>
        {f.type === 'AJUSTE' && <Toggle checked={f.negative} onChange={(v) => setF({ ...f, negative: v })} label="El extracto tiene menos (resta)" />}
        <Field label="Nota" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} error={fields.note} />
      </div>
    </Modal>
  )
}

function LedgerModal({ account, canEdit, onClose }: { account: MoneyAccount; canEdit: boolean; onClose: () => void }) {
  const toast = useToast()
  const [from, setFrom] = useState(today().slice(0, 8) + '01')
  const [to, setTo] = useState(today())
  const [l, setL] = useState<Ledger | null>(null)
  const load = useCallback(() => {
    setL(null)
    api<Ledger>(`/finance/accounts/${account.id}/ledger?from=${from}&to=${to}`).then(setL).catch(() => {})
  }, [account.id, from, to])
  useEffect(load, [load])

  async function voidMove(id: number) {
    try {
      await api(`/finance/account-moves/${id}/void`, { body: {} })
      toast('Movimiento anulado')
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo anular', 'danger')
    }
  }

  return (
    <Modal open wide title={`Movimientos de ${account.name}`} onClose={onClose}>
      <div className="space-y-3 text-sm">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        {!l ? (
          <Spinner />
        ) : (
          <>
            <div className="grid grid-cols-2 gap-2 rounded-xl bg-canvas p-3 sm:grid-cols-4">
              <KV k="Saldo inicial" v={money(l.openingBalance)} />
              <KV k="Entró" v={money(l.in)} tone="text-success-ink" />
              <KV k="Salió" v={money(l.out)} tone="text-danger-ink" />
              <KV k="Saldo final" v={money(l.closingBalance)} />
            </div>
            {l.lines.length === 0 ? (
              <p className="py-6 text-center text-muted">Sin movimientos en estas fechas.</p>
            ) : (
              <ul className="divide-y divide-line-soft">
                {l.lines.map((x, i) => (
                  <li key={i} className="flex items-center gap-3 py-2">
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{x.concept}</div>
                      <div className="text-xs text-muted">
                        {x.date} · saldo {money(x.balance)}
                      </div>
                    </div>
                    <span className={`num font-semibold ${x.amount < 0 ? 'text-danger-ink' : 'text-success-ink'}`}>{money(x.amount)}</span>
                    {canEdit && x.refType === 'MOVE' && x.refId && !x.concept.startsWith('Traslado desde') && (
                      <button type="button" className="text-xs text-danger-ink" onClick={() => voidMove(x.refId!)}>
                        Anular
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {l.truncated && <p className="text-xs text-muted">Se muestran los 2.000 movimientos más recientes: acorta las fechas para ver el resto.</p>}
          </>
        )}
      </div>
    </Modal>
  )
}

// ---------------- Flujo proyectado ----------------

export function ProjectionTab() {
  const [weeks, setWeeks] = useState(8)
  const [p, setP] = useState<Projection | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    setP(null)
    api<Projection>(`/finance/projection?weeks=${weeks}`).then(setP).catch((e) => setError(e.message))
  }, [weeks])
  if (error) return <Banner>{error}</Banner>
  if (!p) return <Spinner />
  const max = Math.max(1, ...p.weeks.map((w) => Math.abs(w.closing)), Math.abs(p.start))
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <p className="max-w-2xl text-sm text-muted">Cuánto dinero tendrías cada semana si las ventas siguen como van, cobras el fiado que vence y pagas lo que debes a tiempo.</p>
        <SelectField label="Semanas" value={weeks} onChange={(e) => setWeeks(Number(e.target.value))} className="w-32">
          {[4, 8, 12].map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </SelectField>
      </div>
      {p.negativeWeeks > 0 && (
        <Banner>
          En {p.negativeWeeks} {p.negativeWeeks === 1 ? 'semana' : 'semanas'} el dinero no alcanzaría: el punto más bajo es {money(p.lowest)} la semana del {shortDate(p.lowestWeek)}. Revisa pagos que puedas mover o fiado por cobrar.
        </Banner>
      )}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Hoy tienes" icon="account_balance_wallet" tone="wallet" value={money(p.start)} sub={`Caja ${money(p.cash)} · cuentas ${money(p.accounts)}`} />
        <StatCard label="Ventas al día" icon="point_of_sale" value={money(p.avgDailySales)} sub="Promedio de los últimos 28 días" />
        <StatCard label="Gastos fijos al mes" icon="event_repeat" tone="navy" value={money(p.monthlyFixed)} sub="Promedio de 3 meses" />
        <StatCard label="Vencido" icon="warning" tone={p.overduePayables > 0 ? 'danger' : 'navy'} value={money(p.overduePayables)} sub={`a proveedores · te deben ${money(p.overdueReceivables)}`} />
      </div>
      <Card className="divide-y divide-line-soft">
        {p.weeks.map((w) => (
          <div key={w.index} className="grid gap-2 px-4 py-3 sm:grid-cols-[140px_1fr_120px] sm:items-center">
            <div>
              <div className="text-sm font-semibold">Semana {w.index}</div>
              <div className="text-xs text-muted">
                {shortDate(w.start)} – {shortDate(w.end)}
              </div>
            </div>
            <div className="min-w-0">
              <div className="h-2.5 overflow-hidden rounded-full bg-line-soft">
                <div className={`h-full rounded-full ${w.closing < 0 ? 'bg-danger' : 'bg-primary'}`} style={{ width: `${Math.max(2, (Math.abs(w.closing) / max) * 100)}%` }} />
              </div>
              <div className="mt-1 text-xs text-muted">
                +{money(w.sales + w.receivables)} entra · −{money(w.payables + w.fixedExpenses + w.restock)} sale
                {w.payables > 0 && ` (proveedores ${money(w.payables)})`}
              </div>
            </div>
            <div className={`num text-right font-bold ${w.closing < 0 ? 'text-danger-ink' : ''}`}>{money(w.closing)}</div>
          </div>
        ))}
      </Card>
      <Card className="p-4">
        <h3 className="mb-2 font-semibold">Cómo se calcula</h3>
        <ul className="list-disc space-y-1 pl-5 text-sm text-muted">
          {p.assumptions.map((a) => (
            <li key={a}>{a}</li>
          ))}
        </ul>
      </Card>
    </div>
  )
}

// ---------------- Calendario tributario ----------------

export function TaxCalendarTab() {
  const { can } = useAuth()
  const toast = useToast()
  const [c, setC] = useState<TaxCalendar | null>(null)
  const [s, setS] = useState<TaxSettings | null>(null)
  const [configuring, setConfiguring] = useState(false)
  const [dating, setDating] = useState<TaxItem | null>(null)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const edit = can('FINANZAS_GASTOS')

  const load = useCallback(() => {
    api<TaxCalendar>('/finance/tax-calendar')
      .then((x) => {
        setC(x)
        setS(x.settings)
        setConfiguring(!x.settings.configured)
      })
      .catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  async function saveSettings() {
    if (!s) return
    setSaving(true)
    try {
      const x = await api<TaxCalendar>('/finance/tax-calendar/settings', { method: 'PUT', body: s })
      setC(x)
      setS(x.settings)
      setConfiguring(false)
      toast('Calendario actualizado')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    } finally {
      setSaving(false)
    }
  }

  async function toggleDone(i: TaxItem) {
    try {
      await api(`/finance/tax-calendar/items/${i.id}`, { method: 'PUT', body: { done: i.status !== 'HECHA' } })
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    }
  }

  async function remove(i: TaxItem) {
    try {
      await api(`/finance/tax-calendar/items/${i.id}`, { method: 'DELETE' })
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo quitar', 'danger')
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!c || !s) return <Spinner />
  return (
    <div className="space-y-4">
      <Banner tone="primary">
        Es un recordatorio: no liquida ni presenta impuestos. Las fechas marcadas como estimadas son la más temprana probable; pon la exacta del calendario DIAN (según el último dígito de tu NIT) o la de tu municipio.
      </Banner>
      {configuring ? (
        <Card className="space-y-3 p-4">
          <h3 className="font-semibold">¿Qué obligaciones tiene el negocio?</h3>
          {s.hints.map((h) => (
            <p key={h} className="text-sm text-muted">
              {h}
            </p>
          ))}
          <div className="grid gap-3 sm:grid-cols-3">
            <SelectField label="IVA" value={s.ivaPeriod} onChange={(e) => setS({ ...s, ivaPeriod: e.target.value as TaxSettings['ivaPeriod'] })}>
              <option value="NINGUNO">No declaro IVA</option>
              <option value="BIMESTRAL">Bimestral</option>
              <option value="CUATRIMESTRAL">Cuatrimestral</option>
            </SelectField>
            <SelectField label="ICA (municipio)" value={s.icaPeriod} onChange={(e) => setS({ ...s, icaPeriod: e.target.value as TaxSettings['icaPeriod'] })}>
              <option value="NINGUNO">No / no sé</option>
              <option value="BIMESTRAL">Bimestral</option>
              <option value="ANUAL">Anual</option>
            </SelectField>
            <SelectField label="Renta" value={s.incomeTax} onChange={(e) => setS({ ...s, incomeTax: e.target.value as TaxSettings['incomeTax'] })}>
              <option value="NINGUNO">No declaro renta</option>
              <option value="NATURAL">Persona natural</option>
              <option value="JURIDICA">Persona jurídica</option>
            </SelectField>
          </div>
          <Toggle checked={s.retentionAgent} onChange={(v) => setS({ ...s, retentionAgent: v })} label="Soy agente de retención en la fuente (declaración mensual)" />
          <Toggle checked={s.simpleRegime} onChange={(v) => setS({ ...s, simpleRegime: v })} label="Estoy en el régimen simple de tributación" />
          <Toggle checked={s.mercantileRegistry} onChange={(v) => setS({ ...s, mercantileRegistry: v })} label="Renuevo la matrícula mercantil (31 de marzo)" />
          <Field label="Avisarme con (días)" inputMode="numeric" value={String(s.reminderDays)} onChange={(e) => setS({ ...s, reminderDays: Number(e.target.value.replace(/\D/g, '') || 0) })} className="w-40" />
          {edit && (
            <div className="flex gap-2">
              <Button icon="save" onClick={saveSettings} loading={saving}>
                Guardar y armar el calendario
              </Button>
              {s.configured && (
                <Button variant="secondary" onClick={() => (setS(c.settings), setConfiguring(false))}>
                  Cancelar
                </Button>
              )}
            </div>
          )}
        </Card>
      ) : (
        <div className="flex flex-wrap gap-2">
          {edit && (
            <>
              <Button variant="secondary" icon="tune" onClick={() => setConfiguring(true)}>
                Cambiar obligaciones
              </Button>
              <Button variant="soft" icon="add" onClick={() => setAdding(true)}>
                Recordatorio propio
              </Button>
            </>
          )}
        </div>
      )}
      {c.items.length === 0 ? (
        !configuring && (
          <Card>
            <EmptyState icon="event" title="Sin fechas" text="Configura las obligaciones del negocio para armar el calendario." />
          </Card>
        )
      ) : (
        <Card className="divide-y divide-line-soft">
          {c.items.map((i) => (
            <div key={i.id} className={`flex flex-wrap items-center gap-3 px-4 py-3 ${i.status === 'HECHA' ? 'opacity-60' : ''}`}>
              <div className="w-20 shrink-0 text-center">
                <div className="num text-sm font-bold">{shortDate(i.dueDate)}</div>
                <div className="text-[10px] text-muted uppercase">{i.estimated ? 'estimada' : 'exacta'}</div>
              </div>
              <div className="min-w-0 flex-1">
                <div className="font-semibold">{i.name}</div>
                <div className="text-xs text-muted">
                  {i.periodLabel}
                  {i.note ? ` · ${i.note}` : ''}
                  {i.doneBy ? ` · hecha por ${i.doneBy}` : ''}
                </div>
              </div>
              <Chip tone={TAX_STATUS[i.status].tone}>
                {TAX_STATUS[i.status].label}
                {(i.status === 'PROXIMA' || i.status === 'PENDIENTE') && ` · ${i.daysLeft === 0 ? 'hoy' : `${i.daysLeft} d`}`}
              </Chip>
              {edit && (
                <div className="flex gap-1">
                  <Button size="sm" variant="ghost" icon="edit_calendar" onClick={() => setDating(i)} title="Poner la fecha exacta">
                    Fecha
                  </Button>
                  <Button size="sm" variant={i.status === 'HECHA' ? 'ghost' : 'soft'} icon={i.status === 'HECHA' ? 'undo' : 'check'} onClick={() => toggleDone(i)}>
                    {i.status === 'HECHA' ? 'Deshacer' : 'Hecha'}
                  </Button>
                  {i.custom && (
                    <Button size="sm" variant="ghost" icon="delete" onClick={() => remove(i)} title="Quitar">
                      <span className="sr-only">Quitar</span>
                    </Button>
                  )}
                </div>
              )}
            </div>
          ))}
        </Card>
      )}
      {dating && <DateModal item={dating} onClose={() => setDating(null)} onSaved={() => (setDating(null), load())} />}
      {adding && <CustomModal onClose={() => setAdding(false)} onSaved={() => (setAdding(false), load())} />}
    </div>
  )
}

function DateModal({ item, onClose, onSaved }: { item: TaxItem; onClose: () => void; onSaved: () => void }) {
  const [date, setDate] = useState(item.dueDate)
  const [note, setNote] = useState(item.note ?? '')
  const [saving, setSaving] = useState(false)
  async function save() {
    setSaving(true)
    try {
      await api(`/finance/tax-calendar/items/${item.id}`, { method: 'PUT', body: { dueDate: date, note } })
      onSaved()
    } finally {
      setSaving(false)
    }
  }
  return (
    <Modal
      open
      title={item.name}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={saving}>
          Guardar fecha exacta
        </Button>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">{item.periodLabel}. Copia la fecha del calendario oficial que te corresponde.</p>
        <Field label="Vence" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <Field label="Nota" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Ej: la presenta el contador" />
      </div>
    </Modal>
  )
}

function CustomModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({ name: '', dueDate: today(), note: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  async function save() {
    setSaving(true)
    setFields({})
    try {
      await api('/finance/tax-calendar/items', { body: { ...f, note: f.note || undefined } })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields(e.fields)
    } finally {
      setSaving(false)
    }
  }
  return (
    <Modal
      open
      title="Recordatorio propio"
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={saving}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        <Field label="Qué hay que hacer" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} error={fields.name} placeholder="Ej: pagar el impuesto predial" autoFocus />
        <Field label="Fecha" type="date" value={f.dueDate} onChange={(e) => setF({ ...f, dueDate: e.target.value })} error={fields.dueDate} />
        <Field label="Nota" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />
      </div>
    </Modal>
  )
}
