import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Modal, SelectField, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'

type Tab = 'resumen' | 'mapa' | 'cuentas' | 'diario' | 'balance'

interface Status {
  enabled: boolean
  startDate: string | null
  entries: number
  pending: number
}
interface Account {
  id: number
  code: string
  name: string
  nature: 'DEBITO' | 'CREDITO'
  active: boolean
  hasMovements: boolean
}
interface MapRow {
  key: string
  label: string
  accountCode: string | null
  accountName: string | null
}
interface Pending {
  id: number
  sourceType: string
  sourceId: number
  entryDate: string
  description: string
  reason: string
}
interface Line {
  accountCode: string
  accountName: string | null
  thirdPartyId: string | null
  thirdPartyName: string | null
  debit: number
  credit: number
  description: string | null
}
interface Entry {
  id: number
  number: number
  entryType: string
  entryDate: string
  saleNumber: number | null
  description: string
  total: number
  reversalOf: number | null
  lines: Line[]
}
interface TrialRow {
  code: string
  name: string
  nature: string
  opening: number
  debit: number
  credit: number
  closing: number
}
interface TrialBalance {
  from: string
  to: string
  rows: TrialRow[]
  totalDebit: number
  totalCredit: number
  balanced: boolean
}

const today = () => new Date().toLocaleDateString('en-CA')
const monthStart = () => today().slice(0, 8) + '01'

/**
 * Contabilidad automática — Fase 14, primer bloque: ventas, costo, devoluciones y anulaciones generan su comprobante
 * cuadrado. El plan de cuentas y el mapa son una propuesta que debe validar un contador.
 */
export default function AccountingPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) || 'resumen'
  const setTab = (t: Tab) => setParams(t === 'resumen' ? {} : { tab: t }, { replace: true })
  const [status, setStatus] = useState<Status | null>(null)
  const [error, setError] = useState('')
  const editable = can('CONTABILIDAD_EDITAR')

  const reload = useCallback(() => {
    api<Status>('/accounting/status').then(setStatus).catch((e) => setError(e.message))
  }, [])
  useEffect(reload, [reload])

  if (error) return <Banner>{error}</Banner>
  if (!status) return <Spinner />

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-xl font-bold">Contabilidad</h1>
        <p className="text-sm text-muted">Cada venta, devolución y anulación se contabiliza sola, con partida doble.</p>
      </div>
      <Banner tone="warning">
        El plan de cuentas y el mapa contable son una <b>propuesta</b>: un contador público debe revisarlos antes de usar estos informes como
        contabilidad oficial.
      </Banner>
      <div className="overflow-x-auto">
        <Tabs<Tab>
          value={tab}
          onChange={setTab}
          items={[
            { value: 'resumen', label: 'Resumen', count: status.pending || undefined },
            { value: 'mapa', label: 'Mapa contable' },
            { value: 'cuentas', label: 'Plan de cuentas' },
            { value: 'diario', label: 'Libro diario' },
            { value: 'balance', label: 'Balance de prueba' },
          ]}
        />
      </div>
      {tab === 'resumen' && <Summary status={status} editable={editable} onChanged={reload} />}
      {tab === 'mapa' && <AccountMap editable={editable} />}
      {tab === 'cuentas' && <Accounts editable={editable} />}
      {tab === 'diario' && <Journal />}
      {tab === 'balance' && <Trial />}
    </div>
  )
}

function Summary({ status, editable, onChanged }: { status: Status; editable: boolean; onChanged: () => void }) {
  const toast = useToast()
  const [start, setStart] = useState(status.startDate ?? today())
  const [pending, setPending] = useState<Pending[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const loadPending = useCallback(() => {
    api<Pending[]>('/accounting/pending').then(setPending).catch(() => setPending([]))
  }, [])
  useEffect(loadPending, [loadPending])

  async function enable(on: boolean) {
    setBusy(true)
    setError('')
    try {
      await api('/accounting/enable', { body: { enabled: on, startDate: on ? start : null } })
      toast(on ? 'Contabilidad activada' : 'Contabilidad desactivada')
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo cambiar')
    } finally {
      setBusy(false)
    }
  }

  async function process() {
    setBusy(true)
    try {
      const r = await api<{ posted: number; stillPending: number }>('/accounting/pending/process', { body: {} })
      toast(r.stillPending ? `${r.posted} contabilizadas; ${r.stillPending} siguen pendientes` : `${r.posted} contabilizadas`, r.stillPending ? 'danger' : 'success')
      loadPending()
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      {error && <Banner>{error}</Banner>}
      <Card className="space-y-3 p-5">
        <Toggle
          checked={status.enabled}
          onChange={(v) => editable && enable(v)}
          label="Contabilidad automática activada"
          hint={
            status.enabled
              ? `Desde el ${status.startDate}: ${status.entries} comprobante(s).`
              : 'Al activarla se carga el plan de cuentas propuesto y el mapa contable. No se contabiliza hacia atrás.'
          }
        />
        {!status.enabled && editable && (
          <Field label="Contabilizar desde" type="date" value={start} onChange={(e) => setStart(e.target.value)} hint="Las operaciones anteriores no se contabilizan." />
        )}
      </Card>
      <Card className="space-y-3 p-5">
        <div className="flex items-center justify-between gap-2">
          <h2 className="font-semibold">Pendientes por contabilizar</h2>
          {pending && pending.length > 0 && editable && (
            <Button size="sm" loading={busy} onClick={process}>
              Volver a intentar
            </Button>
          )}
        </div>
        {!pending ? (
          <Spinner />
        ) : pending.length === 0 ? (
          <p className="text-sm text-muted">No hay operaciones pendientes.</p>
        ) : (
          <>
            <p className="text-sm text-muted">
              Les falta una cuenta en el <b>mapa contable</b>. Asígnala allí y vuelve a intentar: el sistema nunca inventa una cuenta.
            </p>
            <ul className="divide-y divide-line-soft">
              {pending.map((p) => (
                <li key={p.id} className="py-2 text-sm">
                  <div className="font-semibold">
                    {p.description} · {p.entryDate}
                  </div>
                  <div className="text-muted">{p.reason}</div>
                </li>
              ))}
            </ul>
          </>
        )}
      </Card>
    </div>
  )
}

function AccountMap({ editable }: { editable: boolean }) {
  const toast = useToast()
  const [rows, setRows] = useState<MapRow[] | null>(null)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [changes, setChanges] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api<MapRow[]>('/accounting/map').then(setRows).catch((e) => setError(e.message))
    api<Account[]>('/accounting/accounts').then(setAccounts).catch(() => {})
  }, [])

  async function save() {
    setBusy(true)
    setError('')
    try {
      setRows(await api<MapRow[]>('/accounting/map', { method: 'PUT', body: changes }))
      setChanges({})
      toast('Mapa contable guardado')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }

  if (!rows) return error ? <Banner>{error}</Banner> : <Spinner />
  return (
    <div className="space-y-4">
      {error && <Banner>{error}</Banner>}
      <p className="text-sm text-muted">Qué cuenta usa cada cosa. Si una queda sin cuenta, sus operaciones van a pendientes.</p>
      <Card className="divide-y divide-line-soft">
        {rows.map((r) => {
          const value = changes[r.key] ?? r.accountCode ?? ''
          return (
            <div key={r.key} data-guide={`map-${r.key}`} className="grid items-center gap-2 p-4 sm:grid-cols-[1fr_1.2fr]">
              <div>
                <div className="text-sm font-semibold">{r.label}</div>
                <div className="text-xs text-muted">{r.key}</div>
              </div>
              <SelectField label="Cuenta" value={value} disabled={!editable} onChange={(e) => setChanges({ ...changes, [r.key]: e.target.value })}>
                <option value="">Sin asignar</option>
                {accounts
                  .filter((a) => a.active)
                  .map((a) => (
                    <option key={a.code} value={a.code}>
                      {a.code} · {a.name}
                    </option>
                  ))}
              </SelectField>
            </div>
          )
        })}
      </Card>
      {editable && Object.keys(changes).length > 0 && (
        <div className="flex justify-end">
          <Button icon="save" loading={busy} onClick={save}>
            Guardar mapa
          </Button>
        </div>
      )}
    </div>
  )
}

function Accounts({ editable }: { editable: boolean }) {
  const toast = useToast()
  const [list, setList] = useState<Account[] | null>(null)
  const [editing, setEditing] = useState<{ id: number | null; code: string; name: string; nature: string; active: boolean; locked: boolean } | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    api<Account[]>('/accounting/accounts').then(setList).catch(() => setList([]))
  }, [])
  useEffect(load, [load])

  async function save() {
    if (!editing) return
    setBusy(true)
    setFields({})
    try {
      const body = { code: editing.code, name: editing.name, nature: editing.nature, active: editing.active }
      await api(editing.id ? `/accounting/accounts/${editing.id}` : '/accounting/accounts', { method: editing.id ? 'PUT' : 'POST', body })
      toast('Cuenta guardada')
      setEditing(null)
      load()
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { name: e.message })
    } finally {
      setBusy(false)
    }
  }

  if (!list) return <Spinner />
  return (
    <div className="space-y-4">
      {editable && (
        <div className="flex justify-end">
          <Button icon="add" onClick={() => (setFields({}), setEditing({ id: null, code: '', name: '', nature: 'DEBITO', active: true, locked: false }))}>
            Nueva cuenta
          </Button>
        </div>
      )}
      {list.length === 0 ? (
        <EmptyState icon="menu_book" title="Sin plan de cuentas" text="Se carga al activar la contabilidad." />
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line-soft">
            {list.map((a) => (
              <li key={a.id} className="flex items-center gap-3 px-4 py-2.5">
                <span className="num w-16 text-sm font-semibold">{a.code}</span>
                <span className="min-w-0 flex-1 truncate text-sm">{a.name}</span>
                <Chip>{a.nature === 'DEBITO' ? 'Débito' : 'Crédito'}</Chip>
                {!a.active && <Chip tone="neutral">Inactiva</Chip>}
                {editable && (
                  <Button
                    size="sm"
                    variant="ghost"
                    icon="edit"
                    onClick={() => (setFields({}), setEditing({ id: a.id, code: a.code, name: a.name, nature: a.nature, active: a.active, locked: a.hasMovements }))}
                  >
                    <span className="sr-only">Editar</span>
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}
      <Modal
        open={!!editing}
        title={editing?.id ? 'Editar cuenta' : 'Nueva cuenta'}
        onClose={() => setEditing(null)}
        footer={
          <Button className="w-full" loading={busy} onClick={save}>
            Guardar
          </Button>
        }
      >
        {editing && (
          <div className="space-y-4">
            <Field
              label="Código"
              inputMode="numeric"
              value={editing.code}
              disabled={editing.locked}
              onChange={(e) => setEditing({ ...editing, code: e.target.value.replace(/\D/g, '') })}
              error={fields.code}
              hint={editing.locked ? 'Tiene movimientos: el código no se cambia.' : undefined}
            />
            <Field label="Nombre" value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} error={fields.name} />
            <SelectField label="Naturaleza" value={editing.nature} onChange={(e) => setEditing({ ...editing, nature: e.target.value })}>
              <option value="DEBITO">Débito (activos, costos y gastos)</option>
              <option value="CREDITO">Crédito (pasivos, patrimonio e ingresos)</option>
            </SelectField>
            <Toggle checked={editing.active} onChange={(v) => setEditing({ ...editing, active: v })} label="Activa" />
          </div>
        )}
      </Modal>
    </div>
  )
}

function RangePicker({ from, to, onChange }: { from: string; to: string; onChange: (from: string, to: string) => void }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:max-w-md">
      <Field label="Desde" type="date" value={from} onChange={(e) => onChange(e.target.value, to)} />
      <Field label="Hasta" type="date" value={to} onChange={(e) => onChange(from, e.target.value)} />
    </div>
  )
}

const TYPE: Record<string, string> = { VENTA: 'Venta', DEVOLUCION: 'Devolución', ANULACION: 'Anulación' }

function Journal() {
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [list, setList] = useState<Entry[] | null>(null)

  useEffect(() => {
    setList(null)
    api<Entry[]>(`/accounting/journal?from=${from}&to=${to}`).then(setList).catch(() => setList([]))
  }, [from, to])

  return (
    <div className="space-y-4">
      <RangePicker from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="menu_book" title="Sin comprobantes" text="En este período no hay operaciones contabilizadas." />
      ) : (
        list.map((e) => (
          <Card key={e.id} data-guide={`entry-${e.entryType}`} className="overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line-soft px-4 py-2.5">
              <div className="text-sm font-semibold">
                #{e.number} · {TYPE[e.entryType] ?? e.entryType} · {e.description}
              </div>
              <div className="text-xs text-muted">{e.entryDate}</div>
            </div>
            <table className="w-full text-sm">
              <tbody>
                {e.lines.map((l, i) => (
                  <tr key={i} className="border-b border-line-soft last:border-0">
                    <td className="num px-4 py-1.5">{l.accountCode}</td>
                    <td className="px-2 py-1.5 text-muted">{l.accountName}</td>
                    <td className="num px-2 py-1.5 text-right">{l.debit > 0 ? money(l.debit) : ''}</td>
                    <td className="num px-4 py-1.5 text-right">{l.credit > 0 ? money(l.credit) : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        ))
      )}
    </div>
  )
}

function Trial() {
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [tb, setTb] = useState<TrialBalance | null>(null)

  useEffect(() => {
    setTb(null)
    api<TrialBalance>(`/accounting/trial-balance?from=${from}&to=${to}`).then(setTb).catch(() => {})
  }, [from, to])

  return (
    <div className="space-y-4">
      <RangePicker from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
      {!tb ? (
        <Spinner />
      ) : (
        <>
          <Chip tone={tb.balanced ? 'success' : 'danger'}>{tb.balanced ? 'Cuadra: débitos = créditos' : 'NO cuadra: revisa con soporte'}</Chip>
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted uppercase">
                  <th className="px-4 py-2">Cuenta</th>
                  <th className="px-2 py-2 text-right">Saldo inicial</th>
                  <th className="px-2 py-2 text-right">Débitos</th>
                  <th className="px-2 py-2 text-right">Créditos</th>
                  <th className="px-4 py-2 text-right">Saldo final</th>
                </tr>
              </thead>
              <tbody>
                {tb.rows.map((r) => (
                  <tr key={r.code} className="border-b border-line-soft">
                    <td className="px-4 py-1.5">
                      <span className="num font-semibold">{r.code}</span> <span className="text-muted">{r.name}</span>
                    </td>
                    <td className="num px-2 py-1.5 text-right">{money(r.opening)}</td>
                    <td className="num px-2 py-1.5 text-right">{money(r.debit)}</td>
                    <td className="num px-2 py-1.5 text-right">{money(r.credit)}</td>
                    <td className="num px-4 py-1.5 text-right font-semibold">{money(r.closing)}</td>
                  </tr>
                ))}
                <tr className="font-bold">
                  <td className="px-4 py-2">Totales</td>
                  <td />
                  <td className="num px-2 py-2 text-right">{money(tb.totalDebit)}</td>
                  <td className="num px-2 py-2 text-right">{money(tb.totalCredit)}</td>
                  <td />
                </tr>
              </tbody>
            </table>
          </Card>
        </>
      )}
    </div>
  )
}
