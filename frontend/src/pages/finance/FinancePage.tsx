import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, StatCard, Tabs, useToast } from '../../components/ui'
import { api, apiBlob, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import {
  FLOW_LABELS,
  type Amount,
  type FinanceCategory,
  type FinanceEntry,
  type FinanceSummary,
  type MonthlyReport,
  type Payables,
  type SupplierPayment,
} from '../../lib/financeTypes'
import { download, money, pct } from '../../lib/format'
import { METHOD_LABELS } from '../../lib/posTypes'
import { AccountsTab, DashboardTab, ProjectionTab, TaxCalendarTab } from './MoneyTabs'
import { ReceiptWithholdingsModal } from '../accounting/AccountingExtraTabs'

type Tab = 'tablero' | 'resumen' | 'movimientos' | 'por-pagar' | 'cuentas' | 'flujo' | 'calendario' | 'mensual'

const today = () => new Date().toLocaleDateString('en-CA')
const firstOfMonth = () => today().slice(0, 8) + '01'

/** Gastos, ingresos, cuentas por pagar y resultados del negocio. */
export default function FinancePage() {
  const { can } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const tab: Tab = location.pathname.endsWith('/por-pagar') ? 'por-pagar' : ((new URLSearchParams(location.search).get('tab') as Tab) ?? (can('FINANZAS_VER') ? 'tablero' : 'movimientos'))
  const setTab = (t: Tab) => navigate(t === 'por-pagar' ? '/finanzas/por-pagar' : `/finanzas?tab=${t}`, { replace: true })
  const items: { value: Tab; label: string }[] = []
  if (can('FINANZAS_VER')) items.push({ value: 'tablero', label: 'Tablero' }, { value: 'resumen', label: 'Resumen' })
  items.push({ value: 'movimientos', label: 'Gastos e ingresos' })
  items.push({ value: 'por-pagar', label: 'Por pagar' })
  if (can('FINANZAS_VER')) {
    items.push({ value: 'cuentas', label: 'Bancos y billeteras' }, { value: 'flujo', label: 'Flujo proyectado' })
    items.push({ value: 'calendario', label: 'Calendario tributario' }, { value: 'mensual', label: 'Mensual' })
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Gastos y finanzas</h1>
        <p className="text-sm text-muted">Cuánto entra, cuánto sale y cuánto deja el negocio.</p>
      </div>
      <div className="no-scrollbar overflow-x-auto">
        <div className="w-max min-w-full [&_button]:whitespace-nowrap">
          <Tabs value={tab} onChange={setTab} items={items} />
        </div>
      </div>
      {tab === 'tablero' && <DashboardTab />}
      {tab === 'resumen' && <SummaryTab />}
      {tab === 'cuentas' && <AccountsTab />}
      {tab === 'flujo' && <ProjectionTab />}
      {tab === 'calendario' && <TaxCalendarTab />}
      {tab === 'movimientos' && <EntriesTab />}
      {tab === 'por-pagar' && <PayablesTab />}
      {tab === 'mensual' && <MonthlyTab />}
    </div>
  )
}

function Range({ from, to, onChange }: { from: string; to: string; onChange: (f: string, t: string) => void }) {
  const presets: [string, () => [string, string]][] = [
    ['Hoy', () => [today(), today()]],
    ['7 días', () => [new Date(Date.now() - 6 * 86400000).toLocaleDateString('en-CA'), today()]],
    ['Este mes', () => [firstOfMonth(), today()]],
  ]
  return (
    <div className="flex flex-wrap items-end gap-2">
      <Field label="Desde" type="date" value={from} onChange={(e) => onChange(e.target.value, to)} className="w-40" />
      <Field label="Hasta" type="date" value={to} onChange={(e) => onChange(from, e.target.value)} className="w-40" />
      {presets.map(([label, fn]) => (
        <Button key={label} size="sm" variant="ghost" className="mb-1.5" onClick={() => onChange(...fn())}>
          {label}
        </Button>
      ))}
    </div>
  )
}

function Bars({ items, tone, total }: { items: Amount[]; tone: string; total: number }) {
  if (!items.length) return <p className="py-3 text-sm text-muted">Nada en este período.</p>
  return (
    <div className="space-y-2">
      {items.map((a) => (
        <div key={a.label}>
          <div className="flex justify-between text-sm">
            <span>{FLOW_LABELS[a.label] ?? METHOD_LABELS[a.label] ?? a.label}</span>
            <span className="num font-semibold">{money(a.amount)}</span>
          </div>
          <div className="mt-1 h-2 overflow-hidden rounded-full bg-line-soft">
            <div className={`h-full rounded-full ${tone}`} style={{ width: `${total > 0 ? Math.max(2, (a.amount / total) * 100) : 0}%` }} />
          </div>
        </div>
      ))}
    </div>
  )
}

function Row({ k, v, strong, tone }: { k: string; v: number; strong?: boolean; tone?: string }) {
  return (
    <div className={`flex justify-between py-1.5 text-sm ${strong ? 'border-t border-line-soft pt-2 font-bold' : ''}`}>
      <span className={strong ? '' : 'text-muted'}>{k}</span>
      <span className={`num ${tone ?? ''}`}>{money(v)}</span>
    </div>
  )
}

// ---------------- Resumen ----------------

function SummaryTab() {
  const { can } = useAuth()
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(today)
  const [s, setS] = useState<FinanceSummary | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setS(null)
    setError('')
    api<FinanceSummary>(`/finance/summary?from=${from}&to=${to}`)
      .then(setS)
      .catch((e) => setError(e.message))
  }, [from, to])

  async function exportCsv() {
    download(`finanzas_${from}_${to}.csv`, await apiBlob(`/finance/export?from=${from}&to=${to}`))
  }

  const maxDay = useMemo(() => Math.max(1, ...(s?.days ?? []).map((d) => Math.max(d.inflow, d.outflow))), [s])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <Range from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        {can('REPORTES_EXPORTAR') && (
          <Button variant="secondary" icon="download" onClick={exportCsv} className="mb-1.5">
            Exportar a Excel
          </Button>
        )}
      </div>
      {error && <Banner>{error}</Banner>}
      {!s ? (
        !error && <Spinner />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Ventas netas" help="Lo vendido en el período menos devoluciones y anulaciones, incluido lo fiado." value={money(s.netSales)} icon="point_of_sale" sub={`${s.salesCount} ventas`} />
            <StatCard label="Utilidad bruta" help="Ventas sin impuestos − costo de la mercancía vendida." value={money(s.grossProfit)} icon="trending_up" tone="success" sub={`Margen ${pct(s.grossMarginPct)}`} />
            <StatCard label="Gastos" help="Gastos registrados en el período: los de la caja (se pasan solos) y los que anotas aquí, como arriendo o servicios." value={money(s.expenses)} icon="shopping_bag" tone="danger" />
            <StatCard label="Utilidad neta estimada" help="Utilidad bruta − gastos + otros ingresos. Es lo que queda para el negocio después de pagar todo." value={money(s.netProfit)} icon="savings" tone={s.netProfit >= 0 ? 'navy' : 'danger'} sub={`Margen ${pct(s.netMarginPct)}`} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <h3 className="mb-2 font-semibold">Estado de resultados</h3>
              <Row k="Ventas brutas" v={s.grossSales} />
              <Row k="Devoluciones" v={-s.refunds} />
              <Row k="Impuestos incluidos" v={-s.taxes} />
              <Row k="Ventas sin impuestos" v={s.revenueExTax} strong />
              <Row k="Costo de lo vendido" v={-s.costOfSales} />
              <Row k="Utilidad bruta" v={s.grossProfit} strong tone="text-success-ink" />
              <Row k="Gastos operativos" v={-s.expenses} />
              <Row k="Utilidad neta estimada" v={s.netProfit} strong tone={s.netProfit >= 0 ? 'text-success-ink' : 'text-danger-ink'} />
              <p className="mt-2 text-xs text-muted">Estimada con el costo registrado de cada producto. Los otros ingresos (aportes, préstamos) no cuentan como utilidad.</p>
            </Card>
            <Card className="p-4">
              <h3 className="mb-2 font-semibold">Flujo de caja</h3>
              <div className="mb-3 grid grid-cols-3 gap-2 text-center">
                <div className="rounded-xl bg-success-soft p-2 text-success-ink">
                  <div className="text-[11px] font-bold uppercase">Entró</div>
                  <div className="num font-bold">{money(s.inflow)}</div>
                </div>
                <div className="rounded-xl bg-danger-soft p-2 text-danger-ink">
                  <div className="text-[11px] font-bold uppercase">Salió</div>
                  <div className="num font-bold">{money(s.outflow)}</div>
                </div>
                <div className="rounded-xl bg-primary-tint p-2 text-primary-active">
                  <div className="text-[11px] font-bold uppercase">Neto</div>
                  <div className="num font-bold">{money(s.netCashFlow)}</div>
                </div>
              </div>
              <div className="flex h-32 items-end gap-[3px]" aria-label="Entradas y salidas por día">
                {s.days.map((d) => (
                  <div key={d.date} className="group relative flex h-full flex-1 items-end gap-[1px]" title={`${d.date}: entró ${money(d.inflow)}, salió ${money(d.outflow)}`}>
                    <div className="flex-1 rounded-t bg-success" style={{ height: `${(d.inflow / maxDay) * 100}%` }} />
                    <div className="flex-1 rounded-t bg-danger/70" style={{ height: `${(d.outflow / maxDay) * 100}%` }} />
                  </div>
                ))}
              </div>
              <div className="mt-1 flex justify-between text-[11px] text-muted">
                <span>{s.from}</span>
                <span>{s.to}</span>
              </div>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="p-4">
              <h3 className="mb-3 font-semibold">De dónde entró</h3>
              <Bars items={s.inflowByMethod} tone="bg-success" total={s.inflow} />
            </Card>
            <Card className="p-4">
              <h3 className="mb-3 font-semibold">En qué se gastó</h3>
              <Bars items={s.expensesByCategory} tone="bg-danger" total={s.expenses} />
            </Card>
            <Card className="space-y-2 p-4">
              <h3 className="font-semibold">Pendientes</h3>
              <Row k="Te deben (fiado)" v={s.receivables} />
              <Row k="Debes a proveedores" v={s.payables} />
              <Row k="Pagos por verificar" v={s.pendingVerification} />
              <Row k="Fiado en el período" v={s.creditSales} />
              <Row k="Abonos recibidos" v={s.creditCollected} />
              <Row k="Compras recibidas" v={s.purchases} />
              {s.purchaseBudget > 0 && (
                <div className="rounded-xl bg-canvas p-2 text-xs">
                  Presupuesto de compras: <b className="num">{money(s.purchaseBudget)}</b> · usado {pct((s.purchases / s.purchaseBudget) * 100)}
                </div>
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  )
}

// ---------------- Gastos e ingresos ----------------

function EntriesTab() {
  const { can } = useAuth()
  const toast = useToast()
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(today)
  const [kind, setKind] = useState('')
  const [entries, setEntries] = useState<FinanceEntry[] | null>(null)
  const [categories, setCategories] = useState<FinanceCategory[]>([])
  const [creating, setCreating] = useState<'GASTO' | 'INGRESO' | null>(null)
  const [voiding, setVoiding] = useState<FinanceEntry | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<FinanceEntry[]>(`/finance/entries?from=${from}&to=${to}${kind ? `&kind=${kind}` : ''}`)
      .then(setEntries)
      .catch((e) => setError(e.message))
  }, [from, to, kind])
  useEffect(load, [load])
  useEffect(() => {
    api<FinanceCategory[]>('/finance/categories').then(setCategories).catch(() => {})
  }, [])

  const live = (entries ?? []).filter((e) => !e.voided)
  const exp = live.filter((e) => e.kind === 'GASTO').reduce((s, e) => s + e.amount, 0)
  const inc = live.filter((e) => e.kind === 'INGRESO').reduce((s, e) => s + e.amount, 0)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <Range from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        {can('FINANZAS_GASTOS') && (
          <div className="mb-1.5 flex gap-2">
            <Button variant="secondary" icon="add_card" onClick={() => setCreating('INGRESO')}>
              Ingreso
            </Button>
            <Button icon="shopping_bag" onClick={() => setCreating('GASTO')}>
              Registrar gasto
            </Button>
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <StatCard label="Gastos" help="Gastos registrados en el período: los de la caja (se pasan solos) y los que anotas aquí, como arriendo o servicios." value={money(exp)} icon="shopping_bag" tone="danger" />
        <StatCard label="Otros ingresos" help="Dinero que entró y no es por ventas: arriendos que cobras, intereses, recuperaciones…" value={money(inc)} icon="add_card" tone="success" />
      </div>
      <div className="w-72">
        <Tabs
          value={kind}
          onChange={setKind}
          items={[
            { value: '', label: 'Todo' },
            { value: 'GASTO', label: 'Gastos' },
            { value: 'INGRESO', label: 'Ingresos' },
          ]}
        />
      </div>
      {error && <Banner>{error}</Banner>}
      {!entries ? (
        <Spinner />
      ) : entries.length === 0 ? (
        <Card>
          <EmptyState icon="account_balance_wallet" title="Sin movimientos en este período" text="Los gastos de la caja también aparecen aquí automáticamente." />
        </Card>
      ) : (
        <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
          {entries.map((e) => (
            <div key={e.id} className={`flex items-center gap-3 px-3 py-2.5 ${e.voided ? 'opacity-50' : ''}`}>
              <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${e.kind === 'GASTO' ? 'bg-danger-soft text-danger-ink' : 'bg-success-soft text-success-ink'}`}>
                <Icon name={e.kind === 'GASTO' ? 'shopping_bag' : 'add_card'} className="text-[20px]" />
              </span>
              <div className="min-w-0 flex-1 text-sm">
                <div className="truncate font-semibold">{e.description}</div>
                <div className="text-xs text-muted">
                  {e.entryDate} · {e.categoryName ?? 'Sin categoría'} · {e.paidFrom === 'CAJA' ? 'De la caja' : e.paidFrom === 'BANCO' ? 'Banco' : 'Otro'} · {e.createdBy}
                  {e.voided && ` · anulado por ${e.voidedBy}`}
                </div>
              </div>
              <span className={`num text-sm font-bold ${e.kind === 'GASTO' ? 'text-danger-ink' : 'text-success-ink'} ${e.voided ? 'line-through' : ''}`}>
                {e.kind === 'GASTO' ? '−' : '+'}
                {money(e.amount)}
              </span>
              {can('FINANZAS_GASTOS') && !e.voided && (
                <button type="button" aria-label="Anular" onClick={() => setVoiding(e)} className="flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-line-soft">
                  <Icon name="block" className="text-[18px]" />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      {creating && (
        <EntryModal
          kind={creating}
          categories={categories.filter((c) => c.kind === creating)}
          onClose={() => setCreating(null)}
          onCategory={(c) => setCategories([...categories, c])}
          onSaved={() => {
            setCreating(null)
            toast(creating === 'GASTO' ? 'Gasto registrado' : 'Ingreso registrado')
            load()
          }}
        />
      )}
      {voiding && (
        <VoidModal
          entry={voiding}
          onClose={() => setVoiding(null)}
          onDone={() => {
            setVoiding(null)
            toast('Movimiento anulado')
            load()
          }}
        />
      )}
    </div>
  )
}

function EntryModal({
  kind,
  categories,
  onClose,
  onSaved,
  onCategory,
}: {
  kind: 'GASTO' | 'INGRESO'
  categories: FinanceCategory[]
  onClose: () => void
  onSaved: () => void
  onCategory: (c: FinanceCategory) => void
}) {
  const [e, setE] = useState({ categoryId: '', description: '', amount: '', entryDate: today(), method: 'TRANSFERENCIA', paidFrom: 'BANCO', reference: '' })
  const [newCat, setNewCat] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function addCategory() {
    try {
      const c = await api<FinanceCategory>('/finance/categories', { method: 'POST', body: { kind, name: newCat, fixed: false } })
      onCategory(c)
      setE({ ...e, categoryId: String(c.id) })
      setNewCat('')
    } catch (err) {
      if (err instanceof ApiError) setFields({ categoryId: Object.values(err.fields)[0] ?? err.message })
    }
  }

  async function save() {
    setLoading(true)
    setFields({})
    setError('')
    try {
      await api('/finance/entries', {
        method: 'POST',
        body: {
          kind,
          categoryId: e.categoryId ? Number(e.categoryId) : undefined,
          description: e.description,
          amount: Number(e.amount),
          entryDate: e.entryDate,
          method: e.paidFrom === 'CAJA' ? 'EFECTIVO' : e.method,
          paidFrom: e.paidFrom,
          reference: e.reference || undefined,
        },
      })
      onSaved()
    } catch (err) {
      if (err instanceof ApiError) Object.keys(err.fields).length ? setFields(err.fields) : setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title={kind === 'GASTO' ? 'Registrar gasto' : 'Registrar ingreso'}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={loading}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3">
        <Field label="Descripción" value={e.description} onChange={(x) => setE({ ...e, description: x.target.value })} error={fields.description} autoFocus placeholder={kind === 'GASTO' ? 'Ej: arriendo de octubre' : 'Ej: aporte del dueño'} />
        <div className="grid grid-cols-2 gap-2">
          <Field label="Valor" inputMode="numeric" value={e.amount ? Number(e.amount).toLocaleString('es-CO') : ''} onChange={(x) => setE({ ...e, amount: x.target.value.replace(/\D/g, '') })} error={fields.amount} />
          <Field label="Fecha" type="date" value={e.entryDate} onChange={(x) => setE({ ...e, entryDate: x.target.value })} disabled={e.paidFrom === 'CAJA'} />
        </div>
        <SelectField label="Categoría" value={e.categoryId} onChange={(x) => setE({ ...e, categoryId: x.target.value })} error={fields.categoryId}>
          <option value="">{kind === 'GASTO' ? 'Otros gastos' : 'Otros ingresos'}</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
              {c.fixed ? ' (fijo)' : ''}
            </option>
          ))}
        </SelectField>
        <div className="flex gap-2">
          <input value={newCat} onChange={(x) => setNewCat(x.target.value)} placeholder="Nueva categoría" className="h-10 flex-1 rounded-xl border border-line px-3 text-sm outline-none focus:border-primary" />
          <Button size="sm" variant="ghost" icon="add" onClick={addCategory} disabled={!newCat.trim()}>
            Crear
          </Button>
        </div>
        <div>
          <span className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">{kind === 'GASTO' ? '¿De dónde salió el dinero?' : '¿A dónde entró?'}</span>
          <div className="grid grid-cols-3 gap-2">
            {(['CAJA', 'BANCO', 'OTRO'] as const).map((p) => (
              <button
                key={p}
                type="button"
                onClick={() => setE({ ...e, paidFrom: p, entryDate: p === 'CAJA' ? today() : e.entryDate })}
                className={`h-11 rounded-xl border text-sm font-semibold ${e.paidFrom === p ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}
              >
                {p === 'CAJA' ? 'Caja (turno)' : p === 'BANCO' ? 'Banco' : 'Otro'}
              </button>
            ))}
          </div>
          {fields.paidFrom && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.paidFrom}</p>}
          {e.paidFrom === 'CAJA' && <p className="mt-1 text-xs text-muted">Se registra en efectivo en tu turno abierto y cuenta en el arqueo.</p>}
        </div>
        {e.paidFrom !== 'CAJA' && (
          <div className="grid grid-cols-2 gap-2">
            <SelectField label="Medio" value={e.method} onChange={(x) => setE({ ...e, method: x.target.value })}>
              {['TRANSFERENCIA', 'NEQUI', 'DAVIPLATA', 'BREB', 'TARJETA', 'EFECTIVO', 'OTRO'].map((m) => (
                <option key={m} value={m}>
                  {METHOD_LABELS[m]}
                </option>
              ))}
            </SelectField>
            <Field label="Referencia" value={e.reference} onChange={(x) => setE({ ...e, reference: x.target.value })} />
          </div>
        )}
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

function VoidModal({ entry, onClose, onDone }: { entry: FinanceEntry; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  async function submit() {
    setLoading(true)
    try {
      await api(`/finance/entries/${entry.id}/void`, { method: 'POST', body: { reason } })
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo anular')
    } finally {
      setLoading(false)
    }
  }
  return (
    <Modal
      open
      title="Anular movimiento"
      onClose={onClose}
      footer={
        <Button variant="danger" className="flex-1" onClick={submit} loading={loading} disabled={!reason.trim()}>
          Anular
        </Button>
      }
    >
      <div className="space-y-3">
        <p className="text-sm">
          {entry.description} · <b className="num">{money(entry.amount)}</b>
        </p>
        {entry.fromCashRegister && <Banner tone="warning">Salió de la caja: el valor vuelve a tu turno abierto como un ajuste.</Banner>}
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

// ---------------- Por pagar ----------------

function PayablesTab() {
  const { can } = useAuth()
  const toast = useToast()
  const [p, setP] = useState<Payables | null>(null)
  const [history, setHistory] = useState<SupplierPayment[]>([])
  const [paying, setPaying] = useState<{ supplierId: number; name: string; balance: number } | null>(null)
  const [withholding, setWithholding] = useState<number | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Payables>('/finance/payables')
      .then(setP)
      .catch((e) => setError(e.message))
    api<SupplierPayment[]>(`/finance/supplier-payments?from=${new Date(Date.now() - 60 * 86400000).toLocaleDateString('en-CA')}`)
      .then(setHistory)
      .catch(() => {})
  }, [])
  useEffect(load, [load])

  if (error) return <Banner>{error}</Banner>
  if (!p) return <Spinner />
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <StatCard label="Debes" help="Lo que les debes hoy a los proveedores por compras recibidas a crédito." value={money(p.total)} icon="local_shipping" tone="navy" />
        <StatCard label="Vencido" help="Facturas de proveedores que ya pasaron su fecha de pago." value={money(p.overdue)} icon="event_busy" tone={p.overdue ? 'danger' : 'success'} />
        <StatCard label="Vence esta semana" help="Lo que te toca pagar a proveedores en los próximos 7 días." value={money(p.dueThisWeek)} icon="schedule" tone="primary" />
      </div>
      {p.bySupplier.length === 0 ? (
        <Card>
          <EmptyState icon="task_alt" title="No le debes a ningún proveedor" text="Las facturas aparecen aquí cuando recibes un pedido sin pagarlo de contado." />
        </Card>
      ) : (
        <div className="space-y-3">
          {p.bySupplier.map((s) => (
            <Card key={s.supplierId} className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold">{s.supplierName}</div>
                  <div className="text-xs text-muted">
                    {s.invoices === 1 ? "1 factura" : `${s.invoices} facturas`} {s.nextDue ? `· próxima ${s.nextDue}` : ''}
                  </div>
                </div>
                <div className="text-right">
                  <div className="num text-lg font-bold">{money(s.balance)}</div>
                  {s.overdue > 0 && <Chip tone="danger">{money(s.overdue)} vencido</Chip>}
                </div>
              </div>
              <div className="mt-3 divide-y divide-line-soft rounded-xl border border-line">
                {p.invoices
                  .filter((i) => i.supplierId === s.supplierId)
                  .map((i) => (
                    <div key={i.receiptId} className="flex items-center gap-3 px-3 py-2 text-sm">
                      <span className="flex-1">
                        Recepción #{i.receiptNumber}
                        {i.invoiceNumber ? ` · fact. ${i.invoiceNumber}` : ''}
                        <span className="block text-xs text-muted">
                          Total {money(i.total)} · pagado {money(i.paid)}
                          {Number(i.withheld ?? 0) > 0 && ` · retenido ${money(Number(i.withheld))}`}
                          {can('FINANZAS_GASTOS') && (
                            <button type="button" className="ml-2 font-semibold text-primary" onClick={() => setWithholding(i.receiptId)}>
                              Retenciones
                            </button>
                          )}
                        </span>
                      </span>
                      <span className="text-right">
                        <span className="num block font-semibold">{money(i.balance)}</span>
                        {i.dueDate && (
                          <span className={`text-xs ${i.daysToDue < 0 ? 'font-semibold text-danger-ink' : i.daysToDue <= 3 ? 'text-warning-ink' : 'text-muted'}`}>
                            {i.daysToDue < 0 ? `vencida hace ${-i.daysToDue} d` : i.daysToDue === 0 ? 'vence hoy' : `vence en ${i.daysToDue} d`}
                          </span>
                        )}
                      </span>
                    </div>
                  ))}
              </div>
              {can('FINANZAS_GASTOS') && (
                <div className="mt-3 flex justify-end">
                  <Button size="sm" icon="payments" onClick={() => setPaying({ supplierId: s.supplierId, name: s.supplierName, balance: s.balance })}>
                    Registrar pago
                  </Button>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
      {withholding && <ReceiptWithholdingsModal receiptId={withholding} onClose={() => setWithholding(null)} onSaved={() => (setWithholding(null), load())} />}
      {history.length > 0 && (
        <div>
          <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">Pagos a proveedores (60 días)</h2>
          <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
            {history.map((h) => (
              <div key={h.id} className="flex items-center gap-3 px-3 py-2.5 text-sm">
                <Icon name="outbox" className="text-[20px] text-muted" />
                <div className="min-w-0 flex-1">
                  <div className="font-semibold">{h.supplierName}</div>
                  <div className="text-xs text-muted">
                    {h.paidOn} · {METHOD_LABELS[h.method] ?? h.method} · {h.paidFrom === 'CAJA' ? 'de la caja' : h.paidFrom.toLowerCase()} · {h.userName}
                  </div>
                </div>
                <span className="num font-bold">{money(h.amount)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      {paying && (
        <SupplierPayModal
          {...paying}
          onClose={() => setPaying(null)}
          onDone={(r) => {
            setPaying(null)
            toast(`Pago aplicado: ${r.appliedTo.join(' · ')}`)
            load()
          }}
        />
      )}
    </div>
  )
}

function SupplierPayModal({ supplierId, name, balance, onClose, onDone }: { supplierId: number; name: string; balance: number; onClose: () => void; onDone: (r: SupplierPayment) => void }) {
  const [amount, setAmount] = useState(String(Math.round(balance)))
  const [paidFrom, setPaidFrom] = useState<'BANCO' | 'CAJA' | 'OTRO'>('BANCO')
  const [method, setMethod] = useState('TRANSFERENCIA')
  const [reference, setReference] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit() {
    setLoading(true)
    setFields({})
    setError('')
    try {
      onDone(
        await api<SupplierPayment>('/finance/supplier-payments', {
          method: 'POST',
          body: { supplierId, amount: Number(amount), paidFrom, method: paidFrom === 'CAJA' ? 'EFECTIVO' : method, reference: reference || undefined },
        }),
      )
    } catch (e) {
      if (e instanceof ApiError) Object.keys(e.fields).length ? setFields(e.fields) : setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title={`Pagar a ${name}`}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="check" onClick={submit} loading={loading} disabled={!Number(amount)}>
          Pagar {amount ? money(Number(amount)) : ''}
        </Button>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">
          Debes <b className="num text-ink">{money(balance)}</b>. Se abona primero a las facturas que vencen antes.
        </p>
        <Field label="Valor" inputMode="numeric" value={amount ? Number(amount).toLocaleString('es-CO') : ''} onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} error={fields.amount} />
        <div className="grid grid-cols-3 gap-2">
          {(['BANCO', 'CAJA', 'OTRO'] as const).map((p) => (
            <button key={p} type="button" onClick={() => setPaidFrom(p)} className={`h-11 rounded-xl border text-sm font-semibold ${paidFrom === p ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}>
              {p === 'CAJA' ? 'Caja (turno)' : p === 'BANCO' ? 'Banco' : 'Otro'}
            </button>
          ))}
        </div>
        {paidFrom === 'CAJA' ? (
          <p className="text-xs text-muted">Sale en efectivo de tu turno abierto y cuenta en el arqueo.</p>
        ) : (
          <div className="grid grid-cols-2 gap-2">
            <SelectField label="Medio" value={method} onChange={(e) => setMethod(e.target.value)}>
              {['TRANSFERENCIA', 'NEQUI', 'DAVIPLATA', 'BREB', 'EFECTIVO', 'OTRO'].map((m) => (
                <option key={m} value={m}>
                  {METHOD_LABELS[m]}
                </option>
              ))}
            </SelectField>
            <Field label="Referencia" value={reference} onChange={(e) => setReference(e.target.value)} />
          </div>
        )}
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

// ---------------- Mensual ----------------

function Change({ v }: { v?: number | null }) {
  if (v === null || v === undefined) return <span className="text-xs text-muted">sin mes anterior</span>
  return (
    <span className={`text-xs font-semibold ${v >= 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
      <Icon name={v >= 0 ? 'arrow_upward' : 'arrow_downward'} className="align-[-3px] text-[14px]" /> {pct(Math.abs(v))} vs. mes anterior
    </span>
  )
}

function MonthlyTab() {
  const [month, setMonth] = useState(() => today().slice(0, 7))
  const [r, setR] = useState<MonthlyReport | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    setR(null)
    api<MonthlyReport>(`/finance/monthly?month=${month}`)
      .then(setR)
      .catch((e) => setError(e.message))
  }, [month])

  const rows: [string, keyof MonthlyReport['current']][] = [
    ['Ventas netas', 'netSales'],
    ['Costo de lo vendido', 'costOfSales'],
    ['Utilidad bruta', 'grossProfit'],
    ['Gastos', 'expenses'],
    ['Utilidad neta estimada', 'netProfit'],
    ['Entradas de dinero', 'inflow'],
    ['Salidas de dinero', 'outflow'],
    ['Fiado del mes', 'creditSales'],
    ['Abonos recibidos', 'creditCollected'],
    ['Compras recibidas', 'purchases'],
    ['Pagos a proveedores', 'supplierPaid'],
  ]

  return (
    <div className="space-y-4">
      <Field label="Mes" type="month" value={month} onChange={(e) => setMonth(e.target.value)} className="w-48" />
      {error && <Banner>{error}</Banner>}
      {!r ? (
        !error && <Spinner />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3">
            <StatCard label="Ventas del mes" help="Ventas netas del mes, comparadas con el mes anterior." value={money(r.current.netSales)} icon="point_of_sale" sub={<Change v={r.salesChangePct} />} />
            <StatCard label="Utilidad neta" help="Lo que dejó el mes después de costos y gastos, comparado con el mes anterior." value={money(r.current.netProfit)} icon="savings" tone="navy" sub={<Change v={r.profitChangePct} />} />
          </div>
          <Card className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line-soft text-left text-xs text-muted uppercase">
                  <th className="px-4 py-2.5">Concepto</th>
                  <th className="px-4 py-2.5 text-right">{r.month}</th>
                  <th className="px-4 py-2.5 text-right">Mes anterior</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-soft">
                {rows.map(([label, key]) => (
                  <tr key={key}>
                    <td className="px-4 py-2">{label}</td>
                    <td className="num px-4 py-2 text-right font-semibold">{money(r.current[key] as number)}</td>
                    <td className="num px-4 py-2 text-right text-muted">{money(r.previous[key] as number)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <Card className="p-4">
            <h3 className="mb-3 font-semibold">Gastos por categoría</h3>
            <Bars items={r.current.expensesByCategory} tone="bg-danger" total={r.current.expenses} />
          </Card>
        </>
      )}
    </div>
  )
}
