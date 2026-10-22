import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Modal, Spinner, Toggle, useToast } from '../../components/ui'
import { api, apiBlob, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { download, money, shortDate } from '../../lib/format'

const today = () => new Date().toLocaleDateString('en-CA')
const yearStart = () => today().slice(0, 5) + '01-01'
const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre']
const monthLabel = (p: string) => `${MONTHS[Number(p.slice(5, 7)) - 1]} ${p.slice(0, 4)}`

// ---------------- Notas manuales ----------------

interface NoteLine {
  accountCode: string
  thirdPartyId: string
  thirdPartyName: string
  debit: string
  credit: string
}

const emptyLine = (): NoteLine => ({
  accountCode: '',
  thirdPartyId: '',
  thirdPartyName: '',
  debit: '',
  credit: '',
})
const num = (s: string) => Number(s.replace(/\D/g, '') || 0)

/** Notas contables manuales: saldos iniciales, depreciaciones, préstamos, aportes y correcciones. Tienen que cuadrar. */
export function NotesTab({ accounts, editable }: { accounts: { code: string; name: string; active: boolean }[]; editable: boolean }) {
  const toast = useToast()
  const [date, setDate] = useState(today())
  const [description, setDescription] = useState('')
  const [lines, setLines] = useState<NoteLine[]>([emptyLine(), emptyLine()])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const debit = lines.reduce((s, l) => s + num(l.debit), 0)
  const credit = lines.reduce((s, l) => s + num(l.credit), 0)
  const set = (i: number, patch: Partial<NoteLine>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)))

  async function save() {
    setSaving(true)
    setError('')
    try {
      const e = await api<{ number: number }>('/accounting/notes', {
        body: {
          date,
          description,
          lines: lines
            .filter((l) => l.accountCode && (num(l.debit) || num(l.credit)))
            .map((l) => ({
              accountCode: l.accountCode,
              thirdPartyId: l.thirdPartyId || undefined,
              thirdPartyName: l.thirdPartyName || undefined,
              debit: num(l.debit),
              credit: num(l.credit),
            })),
        },
      })
      toast(`Nota #${e.number} registrada`)
      setDescription('')
      setLines([emptyLine(), emptyLine()])
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  if (!editable) return <Banner tone="primary">Solo quien puede editar la contabilidad registra notas manuales.</Banner>
  return (
    <Card className="space-y-4 p-4">
      <div>
        <h3 className="font-semibold">Nota contable manual</h3>
        <p className="text-sm text-muted">
          Para lo que no sale de una operación: <b>saldos iniciales</b> (caja, bancos, inventario, deudas y capital al empezar), depreciaciones, préstamos,
          aportes de socios o correcciones. Tiene que cuadrar y no puede caer en un mes cerrado. Si te equivocas, se reversa desde el libro diario.
        </p>
      </div>
      {error && <Banner>{error}</Banner>}
      <div className="grid gap-3 sm:grid-cols-[180px_1fr]">
        <Field label="Fecha" type="date" value={date} max={today()} onChange={(e) => setDate(e.target.value)} />
        <Field label="Descripción" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Ej: saldos iniciales al empezar" />
      </div>
      <datalist id="cuentas-nota">
        {accounts
          .filter((a) => a.active)
          .map((a) => (
            <option key={a.code} value={a.code}>{`${a.code} ${a.name}`}</option>
          ))}
      </datalist>
      <div className="space-y-2">
        {lines.map((l, i) => (
          <div key={i} className="grid grid-cols-2 gap-2 rounded-xl border border-line p-2 sm:grid-cols-[130px_1fr_1fr_120px_120px_auto] sm:items-end">
            <Field label="Cuenta" list="cuentas-nota" value={l.accountCode} onChange={(e) => set(i, { accountCode: e.target.value.trim() })} />
            <Field label="Tercero (NIT/CC)" value={l.thirdPartyId} onChange={(e) => set(i, { thirdPartyId: e.target.value })} />
            <Field label="Nombre del tercero" value={l.thirdPartyName} onChange={(e) => set(i, { thirdPartyName: e.target.value })} />
            <Field
              label="Débito"
              inputMode="numeric"
              value={l.debit ? num(l.debit).toLocaleString('es-CO') : ''}
              onChange={(e) => set(i, { debit: e.target.value, credit: '' })}
            />
            <Field
              label="Crédito"
              inputMode="numeric"
              value={l.credit ? num(l.credit).toLocaleString('es-CO') : ''}
              onChange={(e) => set(i, { credit: e.target.value, debit: '' })}
            />
            <Button
              size="sm"
              variant="ghost"
              icon="delete"
              onClick={() => setLines(lines.filter((_, j) => j !== i))}
              disabled={lines.length <= 2}
              title="Quitar línea"
            >
              <span className="sr-only">Quitar</span>
            </Button>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button size="sm" variant="secondary" icon="add" onClick={() => setLines([...lines, emptyLine()])}>
          Agregar línea
        </Button>
        <span className={`num text-sm font-semibold ${debit === credit && debit > 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
          Débitos {money(debit)} · Créditos {money(credit)} {debit === credit && debit > 0 ? '· cuadra' : '· no cuadra'}
        </span>
      </div>
      <Button icon="save" onClick={save} loading={saving} disabled={!description.trim() || debit !== credit || debit === 0}>
        Registrar nota
      </Button>
    </Card>
  )
}

// ---------------- Cierres ----------------

interface PeriodDto {
  period: string
  closed: boolean
  entries: number
  pending: number
  closedAt?: string
  closedBy?: string
  reopenedAt?: string
  reopenedBy?: string
  reopenReason?: string
  canClose: boolean
}
interface Periods {
  months: PeriodDto[]
  years: {
    year: number
    closed: boolean
    entryId?: number
    canClose: boolean
  }[]
}

export function ClosingTab({ editable }: { editable: boolean }) {
  const { me } = useAuth()
  const toast = useToast()
  const [p, setP] = useState<Periods | null>(null)
  const [reopening, setReopening] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const load = useCallback(() => {
    api<Periods>('/accounting/periods')
      .then(setP)
      .catch(() => {})
  }, [])
  useEffect(load, [load])

  async function act(key: string, path: string, body?: unknown, ok?: string) {
    setBusy(key)
    try {
      setP(await api<Periods>(path, { body: body ?? {} }))
      if (ok) toast(ok)
      return true
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo', 'danger')
      return false
    } finally {
      setBusy(null)
    }
  }

  if (!p) return <Spinner />
  return (
    <div className="space-y-4">
      <Banner tone="primary">
        Cerrar un mes lo protege: lo que llegue después con fecha de ese mes (una venta sin internet, un gasto atrasado) se registra el primer día del siguiente
        mes abierto, con su fecha original. Solo el dueño reabre un mes, con un motivo que queda en la auditoría.
      </Banner>
      <Card className="divide-y divide-line-soft">
        {p.months.map((m) => (
          <div key={m.period} className="flex flex-wrap items-center gap-3 px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="font-semibold capitalize">{monthLabel(m.period)}</div>
              <div className="text-xs text-muted">
                {m.entries} comprobantes
                {m.pending > 0 && <span className="text-danger-ink"> · {m.pending} pendientes por contabilizar</span>}
                {m.closed && m.closedBy && ` · cerrado por ${m.closedBy} el ${shortDate(m.closedAt)}`}
                {!m.closed && m.reopenedBy && ` · reabierto por ${m.reopenedBy}: ${m.reopenReason}`}
              </div>
            </div>
            <Chip tone={m.closed ? 'success' : 'neutral'}>{m.closed ? 'Cerrado' : 'Abierto'}</Chip>
            {editable && m.canClose && (
              <Button
                size="sm"
                variant="secondary"
                icon="lock"
                loading={busy === m.period}
                onClick={() => act(m.period, `/accounting/periods/${m.period}/close`, undefined, 'Mes cerrado')}
              >
                Cerrar
              </Button>
            )}
            {editable && m.closed && me?.owner && (
              <Button size="sm" variant="ghost" icon="lock_open" onClick={() => (setReopening(m.period), setReason(''))}>
                Reabrir
              </Button>
            )}
          </div>
        ))}
      </Card>
      <Card className="p-4">
        <h3 className="mb-1 font-semibold">Cierre de año</h3>
        <p className="mb-3 text-sm text-muted">
          Lleva ingresos, costos y gastos del año a la utilidad (o pérdida) del ejercicio, con fecha 31 de diciembre. Pide los 12 meses cerrados; después, los
          meses de ese año ya no se reabren. Hazlo con tu contador.
        </p>
        {p.years.map((y) => (
          <div key={y.year} className="flex items-center justify-between gap-2 py-1.5 text-sm">
            <span className="font-semibold">{y.year}</span>
            {y.closed ? (
              <Chip tone="success">Cerrado</Chip>
            ) : y.canClose && editable ? (
              <Button
                size="sm"
                variant="secondary"
                icon="event_available"
                loading={busy === `y${y.year}`}
                onClick={() => act(`y${y.year}`, `/accounting/years/${y.year}/close`, undefined, 'Año cerrado')}
              >
                Cerrar el año
              </Button>
            ) : (
              <span className="text-xs text-muted">{y.year === new Date().getFullYear() ? 'En curso' : 'Abierto'}</span>
            )}
          </div>
        ))}
      </Card>
      <Modal
        open={!!reopening}
        title={reopening ? `Reabrir ${monthLabel(reopening)}` : ''}
        onClose={() => setReopening(null)}
        footer={
          <Button
            className="flex-1"
            icon="lock_open"
            loading={busy === 'reopen'}
            disabled={!reason.trim()}
            onClick={async () => {
              if (await act('reopen', `/accounting/periods/${reopening}/reopen`, { reason }, 'Mes reabierto')) setReopening(null)
            }}
          >
            Reabrir
          </Button>
        }
      >
        <Field
          label="¿Por qué se reabre?"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Ej: falta registrar una factura del proveedor"
          autoFocus
        />
      </Modal>
    </div>
  )
}

// ---------------- Terceros ----------------

interface ThirdParty {
  id: string
  name: string
  kind: 'CLIENTE' | 'PROVEEDOR' | 'CLIENTE_PROVEEDOR' | 'OTRO'
  debit: number
  credit: number
  balance: number
}
interface ThirdLine {
  date: string
  number: number
  entryType: string
  accountCode: string
  description: string
  debit: number
  credit: number
}
const KIND_LABEL = {
  CLIENTE: 'Cliente',
  PROVEEDOR: 'Proveedor',
  CLIENTE_PROVEEDOR: 'Cliente y proveedor',
  OTRO: 'Otro',
}

export function ThirdPartiesTab({ canExport }: { canExport: boolean }) {
  const [from, setFrom] = useState(yearStart())
  const [to, setTo] = useState(today())
  const [list, setList] = useState<ThirdParty[] | null>(null)
  const [open, setOpen] = useState<ThirdParty | null>(null)
  const [lines, setLines] = useState<ThirdLine[] | null>(null)

  useEffect(() => {
    setList(null)
    api<ThirdParty[]>(`/accounting/third-parties?from=${from}&to=${to}`)
      .then(setList)
      .catch(() => setList([]))
  }, [from, to])
  useEffect(() => {
    if (!open) return
    setLines(null)
    api<ThirdLine[]>(`/accounting/third-parties/${encodeURIComponent(open.id)}/ledger?from=${from}&to=${to}`)
      .then(setLines)
      .catch(() => setLines([]))
  }, [open, from, to])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid grid-cols-2 gap-3 sm:max-w-md">
          <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        {canExport && (
          <Button
            variant="secondary"
            icon="download"
            onClick={async () => download(`terceros_${from}_${to}.csv`, await apiBlob(`/accounting/export/third-parties?from=${from}&to=${to}`))}
          >
            Terceros en CSV
          </Button>
        )}
      </div>
      <p className="text-sm text-muted">
        Clientes y proveedores con movimiento. El saldo es lo que te deben (positivo) o lo que debes, incluidas las retenciones por pagar (negativo). Las ventas
        sin cliente identificado van al tercero «Consumidor final» (222222222222).
      </p>
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="groups" title="Sin terceros" text="En este período no hay movimientos con clientes o proveedores identificados." />
      ) : (
        <Card className="divide-y divide-line-soft">
          {list.map((t) => (
            <button key={t.id} type="button" onClick={() => setOpen(t)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas">
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{t.name}</div>
                <div className="text-xs text-muted">
                  {t.id} · {KIND_LABEL[t.kind]}
                </div>
              </div>
              <span className={`num font-bold ${t.balance < 0 ? 'text-danger-ink' : ''}`}>{money(t.balance)}</span>
            </button>
          ))}
        </Card>
      )}
      <Modal open={!!open} wide title={open ? `${open.name} (${open.id})` : ''} onClose={() => setOpen(null)}>
        {!lines ? (
          <Spinner />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-muted">
                <th className="py-1">Fecha</th>
                <th>Comp.</th>
                <th>Cuenta</th>
                <th>Detalle</th>
                <th className="text-right">Débito</th>
                <th className="text-right">Crédito</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((l, i) => (
                <tr key={i} className="border-t border-line-soft">
                  <td className="py-1.5 whitespace-nowrap">{l.date}</td>
                  <td className="num">#{l.number}</td>
                  <td className="num">{l.accountCode}</td>
                  <td className="text-muted">{l.description}</td>
                  <td className="num text-right">{l.debit > 0 ? money(l.debit) : ''}</td>
                  <td className="num text-right">{l.credit > 0 ? money(l.credit) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Modal>
    </div>
  )
}

// ---------------- Retenciones ----------------

interface WhSettings {
  uvt: number
  retentionAgent: boolean
  purchasesBaseUvt: number
  purchasesBase: number
  purchasesRateDeclarant: number
  purchasesRateOther: number
  reteivaAgent: boolean
  reteivaRate: number
  reteicaPerMil: number | null
  configured: boolean
  hints: string[]
}
interface WhReport {
  totals: Record<string, number>
  rows: {
    kind: string
    supplierName: string
    supplierId?: string
    base: number
    amount: number
    receipts: number
  }[]
}
const WH_LABEL: Record<string, string> = {
  RETEFUENTE: 'Retención en la fuente',
  RETEIVA: 'ReteIVA',
  RETEICA: 'ReteICA',
}

export function WithholdingsTab({ editable }: { editable: boolean }) {
  const toast = useToast()
  const [s, setS] = useState<WhSettings | null>(null)
  const [from, setFrom] = useState(today().slice(0, 8) + '01')
  const [to, setTo] = useState(today())
  const [report, setReport] = useState<WhReport | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api<WhSettings>('/accounting/withholdings/settings')
      .then(setS)
      .catch(() => {})
  }, [])
  useEffect(() => {
    api<WhReport>(`/accounting/withholdings/report?from=${from}&to=${to}`)
      .then(setReport)
      .catch(() => {})
  }, [from, to])

  async function save() {
    if (!s) return
    setSaving(true)
    try {
      setS(
        await api<WhSettings>('/accounting/withholdings/settings', {
          method: 'PUT',
          body: s,
        }),
      )
      toast('Retenciones guardadas')
    } catch (e) {
      toast(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo guardar', 'danger')
    } finally {
      setSaving(false)
    }
  }

  if (!s) return <Spinner />
  const n = (v: string) => Number(v.replace(',', '.') || 0)
  return (
    <div className="space-y-4">
      {s.hints.map((h) => (
        <Banner key={h} tone="warning">
          {h}
        </Banner>
      ))}
      <Card className="space-y-3 p-4">
        <h3 className="font-semibold">Reglas para proponer retenciones en las compras</h3>
        <p className="text-sm text-muted">
          Al recibir mercancía el sistema propone la retención según estas reglas y los datos tributarios del proveedor (declarante, autorretenedor, régimen
          simple, responsable de IVA). Lo retenido baja lo que le debes y queda por declarar. Cada compra se puede revisar en Finanzas → Por pagar.
        </p>
        <Field label="Valor de la UVT" inputMode="decimal" value={String(s.uvt)} onChange={(e) => setS({ ...s, uvt: n(e.target.value) })} className="sm:w-48" />
        <Toggle checked={s.retentionAgent} onChange={(v) => setS({ ...s, retentionAgent: v })} label="Practico retención en la fuente en compras" />
        {s.retentionAgent && (
          <div className="grid gap-3 sm:grid-cols-3">
            <Field
              label="Base mínima (UVT)"
              inputMode="decimal"
              value={String(s.purchasesBaseUvt)}
              onChange={(e) => setS({ ...s, purchasesBaseUvt: n(e.target.value) })}
              hint={`= ${money(s.purchasesBaseUvt * s.uvt)}`}
            />
            <Field
              label="Tarifa a declarantes (%)"
              inputMode="decimal"
              value={String(s.purchasesRateDeclarant)}
              onChange={(e) => setS({ ...s, purchasesRateDeclarant: n(e.target.value) })}
            />
            <Field
              label="Tarifa a no declarantes (%)"
              inputMode="decimal"
              value={String(s.purchasesRateOther)}
              onChange={(e) => setS({ ...s, purchasesRateOther: n(e.target.value) })}
            />
          </div>
        )}
        <Toggle checked={s.reteivaAgent} onChange={(v) => setS({ ...s, reteivaAgent: v })} label="Practico ReteIVA (gran contribuyente o agente designado)" />
        {s.reteivaAgent && (
          <Field
            label="ReteIVA (% del IVA)"
            inputMode="decimal"
            value={String(s.reteivaRate)}
            onChange={(e) => setS({ ...s, reteivaRate: n(e.target.value) })}
            className="sm:w-48"
          />
        )}
        <Field
          label="ReteICA (por mil, vacío si no aplica)"
          inputMode="decimal"
          value={s.reteicaPerMil == null ? '' : String(s.reteicaPerMil)}
          onChange={(e) =>
            setS({
              ...s,
              reteicaPerMil: e.target.value === '' ? null : n(e.target.value),
            })
          }
          className="sm:w-64"
        />
        {editable && (
          <Button icon="save" onClick={save} loading={saving}>
            Guardar reglas
          </Button>
        )}
      </Card>
      <Card className="space-y-3 p-4">
        <h3 className="font-semibold">Retenido en el período (para declarar)</h3>
        <div className="grid grid-cols-2 gap-3 sm:max-w-md">
          <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        {!report ? (
          <Spinner />
        ) : (
          <>
            <div className="grid gap-2 sm:grid-cols-3">
              {Object.entries(report.totals).map(([k, v]) => (
                <div key={k} className="rounded-xl bg-canvas p-3">
                  <div className="text-xs text-muted">{WH_LABEL[k] ?? k}</div>
                  <div className="num text-lg font-bold">{money(v)}</div>
                </div>
              ))}
            </div>
            {report.rows.length > 0 && (
              <table className="w-full text-sm">
                <tbody>
                  {report.rows.map((r, i) => (
                    <tr key={i} className="border-t border-line-soft">
                      <td className="py-1.5">{WH_LABEL[r.kind] ?? r.kind}</td>
                      <td>
                        {r.supplierName}
                        {r.supplierId ? ` (${r.supplierId})` : ''}
                      </td>
                      <td className="num text-right text-muted">base {money(r.base)}</td>
                      <td className="num text-right font-semibold">{money(r.amount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </Card>
    </div>
  )
}

// ---------------- Retenciones de una compra (desde Finanzas → Por pagar) ----------------

interface ReceiptWh {
  receiptId: number
  number: number
  supplierName: string
  total: number
  paid: number
  withheld: number
  payable: number
  suggestedBase: number
  invoiceTax: number
  items: {
    kind: string
    base: number
    rate: number
    amount: number
    confirmed: boolean
  }[]
}

export function ReceiptWithholdingsModal({ receiptId, onClose, onSaved }: { receiptId: number; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const [v, setV] = useState<ReceiptWh | null>(null)
  const [items, setItems] = useState<{ kind: string; base: string; rate: string }[]>([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    api<ReceiptWh>(`/accounting/receipts/${receiptId}/withholdings`)
      .then((x) => {
        setV(x)
        setItems(
          x.items.map((i) => ({
            kind: i.kind,
            base: String(i.base),
            rate: String(i.rate),
          })),
        )
      })
      .catch((e) => setError(e.message))
  }, [receiptId])

  const amount = (b: string, r: string) => Math.round((Number(b || 0) * Number(r.replace(',', '.') || 0)) / 100)
  const total = items.reduce((s, i) => s + amount(i.base, i.rate), 0)
  const missing = ['RETEFUENTE', 'RETEIVA', 'RETEICA'].filter((k) => !items.some((i) => i.kind === k))

  async function save() {
    setSaving(true)
    setError('')
    try {
      await api(`/accounting/receipts/${receiptId}/withholdings`, {
        method: 'PUT',
        body: {
          items: items.map((i) => ({
            kind: i.kind,
            base: Number(i.base || 0),
            rate: Number(i.rate.replace(',', '.') || 0),
          })),
        },
      })
      toast('Retenciones guardadas')
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={v ? `Retenciones · recepción #${v.number}` : 'Retenciones'}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save} loading={saving} disabled={!v}>
          Guardar · retener {money(total)}
        </Button>
      }
    >
      {!v ? (
        error ? (
          <Banner>{error}</Banner>
        ) : (
          <Spinner />
        )
      ) : (
        <div className="space-y-3 text-sm">
          {error && <Banner>{error}</Banner>}
          <p className="text-muted">
            {v.supplierName} · factura por {money(v.total)}
            {v.paid > 0 && ` · ya pagado ${money(v.paid)}`}. Base sugerida {money(v.suggestedBase)}
            {v.invoiceTax > 0 && ` · IVA de la factura ${money(v.invoiceTax)}`}.
          </p>
          {items.length === 0 && <p className="text-muted">Sin retenciones en esta compra.</p>}
          {items.map((i, idx) => (
            <div key={i.kind} className="grid grid-cols-[1fr_120px_90px_auto] items-end gap-2">
              <div className="pb-3 font-semibold">{WH_LABEL[i.kind]}</div>
              <Field
                label="Base"
                inputMode="numeric"
                value={i.base}
                onChange={(e) => setItems(items.map((x, j) => (j === idx ? { ...x, base: e.target.value.replace(/[^\d.]/g, '') } : x)))}
              />
              <Field
                label="%"
                inputMode="decimal"
                value={i.rate}
                onChange={(e) => setItems(items.map((x, j) => (j === idx ? { ...x, rate: e.target.value } : x)))}
              />
              <Button size="sm" variant="ghost" icon="delete" onClick={() => setItems(items.filter((_, j) => j !== idx))} title="Quitar">
                <span className="sr-only">Quitar</span>
              </Button>
            </div>
          ))}
          <div className="flex flex-wrap gap-2">
            {missing.map((k) => (
              <Button
                key={k}
                size="sm"
                variant="secondary"
                icon="add"
                onClick={() =>
                  setItems([
                    ...items,
                    {
                      kind: k,
                      base: String(k === 'RETEIVA' ? v.invoiceTax : v.suggestedBase),
                      rate: k === 'RETEIVA' ? '15' : '',
                    },
                  ])
                }
              >
                {WH_LABEL[k]}
              </Button>
            ))}
          </div>
          <p className="text-xs text-muted">Lo retenido se descuenta de lo que le debes al proveedor y no puede pasar de lo que falta por pagar.</p>
        </div>
      )}
    </Modal>
  )
}
