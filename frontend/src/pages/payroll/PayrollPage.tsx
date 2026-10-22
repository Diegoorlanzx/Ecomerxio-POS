import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, shortDate } from '../../lib/format'
import { acquirePrintRoot } from '../../lib/host'
import { DOC_STATUS, type ElectronicDocumentDto } from '../../lib/fiscalTypes'

/** Fase 16F: nómina. Empleados, valores legales del año, liquidación del mes, desprendibles y nómina electrónica. */

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre']
const CONTRACTS: Record<string, string> = { '1': 'Término fijo', '2': 'Término indefinido', '3': 'Obra o labor', '4': 'Aprendizaje', '5': 'Práctica' }
const ID_TYPES: Record<string, string> = { CC: 'Cédula de ciudadanía', CE: 'Cédula de extranjería', TI: 'Tarjeta de identidad', PP: 'Pasaporte', PEP: 'PEP', NIT: 'NIT' }
const PAY: Record<string, string> = { '10': 'Efectivo', '47': 'Transferencia', '42': 'Consignación' }
const OVERTIME: Record<string, string> = {
  '1': 'Hora extra diurna',
  '2': 'Hora extra nocturna',
  '3': 'Recargo nocturno',
  '4': 'Extra diurna dominical/festiva',
  '5': 'Recargo dominical/festivo',
  '6': 'Extra nocturna dominical/festiva',
  '7': 'Recargo nocturno dominical/festivo',
}
const KINDS: Record<string, { label: string; unit?: 'horas' | 'días'; codes?: Record<string, string> }> = {
  HORA: { label: 'Horas extra o recargos', unit: 'horas', codes: OVERTIME },
  INCAPACIDAD: { label: 'Incapacidad', unit: 'días', codes: { '1': 'Común', '2': 'Profesional', '3': 'Laboral' } },
  VACACIONES: { label: 'Vacaciones', unit: 'días' },
  LICENCIA: { label: 'Licencia', unit: 'días', codes: { '2': 'Remunerada', '1': 'Maternidad o paternidad', '3': 'No remunerada' } },
  BONIFICACION: { label: 'Bonificación', codes: { '1': 'Salarial', '2': 'No salarial' } },
  COMISION: { label: 'Comisiones' },
  AUXILIO: { label: 'Auxilio', codes: { '2': 'No salarial', '1': 'Salarial' } },
  OTRO_DEVENGO: { label: 'Otro pago (no salarial)' },
  PRESTAMO: { label: 'Descuento de préstamo' },
  LIBRANZA: { label: 'Libranza' },
  RETENCION: { label: 'Retención en la fuente' },
  OTRA_DEDUCCION: { label: 'Otra deducción' },
}

interface Params {
  year: number
  minimumWage: number
  transportAid: number
  monthlyHours: number
  sundaySurchargePct: number
  exonerated: boolean
  confirmedBy?: string
}

interface Employee {
  id: number
  docType: string
  docNumber: string
  firstName: string
  otherNames?: string | null
  firstSurname: string
  secondSurname?: string | null
  email?: string | null
  phone?: string | null
  address?: string | null
  municipalityCode?: string | null
  contractType: string
  integralSalary: boolean
  highRisk: boolean
  salary: number
  arlClass: number
  startDate: string
  endDate?: string | null
  eps?: string | null
  pensionFund?: string | null
  severanceFund?: string | null
  compensationFund?: string | null
  paymentMethod: string
  bankName?: string | null
  accountType?: string | null
  accountNumber?: string | null
  active: boolean
}

interface Item {
  key: string
  code: string
  label: string
  quantity?: number | null
  percentage?: number | null
  amount: number
  description?: string | null
}

interface Payslip {
  id: number
  employeeId: number
  employeeName: string
  docNumber: string
  daysWorked: number
  contributionBase: number
  totalEarned: number
  totalDeductions: number
  netPay: number
  employerCost: number
  provisions: number
  detail: { earnings: Item[]; deductions: Item[]; employer: Record<string, number>; provisions: Record<string, number> }
  deletedBeforeDian: boolean
  document?: ElectronicDocumentDto | null
}

interface PeriodSummary {
  id: number
  year: number
  month: number
  status: 'BORRADOR' | 'APROBADA'
  paymentDate?: string
  employees: number
  totalNet: number
  totalCost: number
  approvedBy?: string
}

interface Novelty {
  id: number
  employeeId: number
  kind: string
  code?: string
  quantity?: number
  percentage?: number
  amount?: number
  description?: string
}

interface PeriodDetail {
  period: PeriodSummary
  payslips: Payslip[]
  novelties: Novelty[]
  parameters: Params | null
}

const fullName = (e: Employee) => [e.firstName, e.otherNames, e.firstSurname, e.secondSurname].filter(Boolean).join(' ')
const errorOf = (e: unknown, fallback: string) => (e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : fallback)

export default function PayrollPage() {
  const [tab, setTab] = useState<'mes' | 'empleados' | 'valores'>('mes')
  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Nómina</h2>
        <p className="text-sm text-muted">
          Liquida el mes, imprime los desprendibles y transmite la nómina electrónica. No reemplaza la planilla PILA: los aportes se pagan en tu operador.
        </p>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'mes', label: 'Nómina del mes' },
          { value: 'empleados', label: 'Empleados' },
          { value: 'valores', label: 'Valores del año' },
        ]}
      />
      {tab === 'mes' && <Periods onGoParams={() => setTab('valores')} />}
      {tab === 'empleados' && <Employees />}
      {tab === 'valores' && <ParamsTab />}
      <p className="text-xs text-muted">Los cálculos usan las tarifas generales de ley. Revísalos con tu contador antes de pagar.</p>
    </div>
  )
}

// ---------------------------------------------------------------- Valores del año

function ParamsTab() {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState<Params[] | null>(null)
  const [f, setF] = useState({ year: String(new Date().getFullYear()), minimumWage: '', transportAid: '', monthlyHours: '', sundaySurchargePct: '', exonerated: false })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const load = useCallback(() => {
    api<Params[]>('/payroll/parameters').then(setList).catch(() => setList([]))
  }, [])
  useEffect(load, [load])

  function edit(p: Params) {
    setF({
      year: String(p.year),
      minimumWage: String(p.minimumWage),
      transportAid: String(p.transportAid),
      monthlyHours: String(p.monthlyHours),
      sundaySurchargePct: String(p.sundaySurchargePct),
      exonerated: p.exonerated,
    })
  }

  async function save() {
    setSaving(true)
    setFields({})
    try {
      await api('/payroll/parameters', {
        method: 'PUT',
        body: {
          year: Number(f.year),
          minimumWage: Number(f.minimumWage.replace(/\D/g, '')),
          transportAid: Number(f.transportAid.replace(/\D/g, '')),
          monthlyHours: Number(f.monthlyHours),
          sundaySurchargePct: Number(f.sundaySurchargePct),
          exonerated: f.exonerated,
        },
      })
      toast('Valores guardados')
      load()
    } catch (e) {
      if (e instanceof ApiError) setFields(e.fields)
      toast(errorOf(e, 'No se pudo guardar'), 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card className="space-y-3 p-4">
        <h3 className="font-semibold">Confirmar los valores de un año</h3>
        <p className="text-xs text-muted">
          Escríbelos tal como salen en el decreto del año (o pídeselos a tu contador). La app no los inventa: sin ellos no liquida.
        </p>
        <fieldset disabled={!can('NOMINA_GESTIONAR')} className="grid gap-3 sm:grid-cols-2">
          <Field label="Año" inputMode="numeric" value={f.year} onChange={(e) => setF({ ...f, year: e.target.value })} error={fields.year} />
          <Field label="Salario mínimo mensual" inputMode="numeric" value={f.minimumWage} onChange={(e) => setF({ ...f, minimumWage: e.target.value })} error={fields.minimumWage} />
          <Field label="Auxilio de transporte" inputMode="numeric" value={f.transportAid} onChange={(e) => setF({ ...f, transportAid: e.target.value })} error={fields.transportAid} />
          <Field
            label="Horas de la jornada al mes"
            inputMode="decimal"
            value={f.monthlyHours}
            onChange={(e) => setF({ ...f, monthlyHours: e.target.value })}
            error={fields.monthlyHours}
            hint="Base de la hora ordinaria (cambia con la reducción de la jornada)"
          />
          <Field
            label="Recargo dominical/festivo (%)"
            inputMode="decimal"
            value={f.sundaySurchargePct}
            onChange={(e) => setF({ ...f, sundaySurchargePct: e.target.value })}
            error={fields.sundaySurchargePct}
          />
          <div className="sm:col-span-2">
            <Toggle
              checked={f.exonerated}
              onChange={(v) => setF({ ...f, exonerated: v })}
              label="Exonerado de salud del empleador, SENA e ICBF"
              hint="Personas jurídicas, para empleados que ganan menos de 10 mínimos (art. 114-1 del Estatuto Tributario). Confírmalo con tu contador."
            />
          </div>
        </fieldset>
        {can('NOMINA_GESTIONAR') && (
          <Button loading={saving} onClick={save}>
            Guardar valores
          </Button>
        )}
      </Card>
      <Card>
        <div className="border-b border-line-soft px-4 py-3 text-sm font-semibold">Años confirmados</div>
        {!list ? (
          <Spinner />
        ) : list.length === 0 ? (
          <EmptyState icon="calendar_month" title="Ningún año confirmado" text="Confirma el año en curso para poder liquidar." />
        ) : (
          <div className="divide-y divide-line-soft">
            {list.map((p) => (
              <button key={p.year} type="button" onClick={() => edit(p)} className="flex w-full items-center justify-between px-4 py-3 text-left text-sm hover:bg-canvas">
                <span>
                  <b>{p.year}</b> · mínimo {money(p.minimumWage)} · auxilio {money(p.transportAid)} · {p.monthlyHours} h
                  {p.exonerated ? ' · exonerado' : ''}
                </span>
                <span className="text-xs text-muted">{p.confirmedBy}</span>
              </button>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------- Empleados

const emptyEmployee = (): Employee => ({
  id: 0,
  docType: 'CC',
  docNumber: '',
  firstName: '',
  firstSurname: '',
  contractType: '2',
  integralSalary: false,
  highRisk: false,
  salary: 0,
  arlClass: 1,
  startDate: new Date().toLocaleDateString('en-CA'),
  paymentMethod: '10',
  active: true,
})

function Employees() {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState<Employee[] | null>(null)
  const [editing, setEditing] = useState<Employee | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const load = useCallback(() => {
    api<Employee[]>('/payroll/employees').then(setList).catch(() => setList([]))
  }, [])
  useEffect(load, [load])
  const e = editing
  const set = (patch: Partial<Employee>) => setEditing(e && { ...e, ...patch })

  async function save() {
    if (!e) return
    setSaving(true)
    setFields({})
    try {
      const body = { ...e, id: undefined, salary: Number(String(e.salary).replace(/\D/g, '')) }
      if (e.id) await api(`/payroll/employees/${e.id}`, { method: 'PUT', body })
      else await api('/payroll/employees', { body })
      toast(e.id ? 'Empleado actualizado' : 'Empleado creado')
      setEditing(null)
      load()
    } catch (err) {
      if (err instanceof ApiError) setFields(err.fields)
      else toast('No se pudo guardar', 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-3">
      {can('NOMINA_GESTIONAR') && (
        <Button icon="person_add" onClick={() => setEditing(emptyEmployee())}>
          Nuevo empleado
        </Button>
      )}
      <Card>
        {!list ? (
          <Spinner />
        ) : list.length === 0 ? (
          <EmptyState icon="badge" title="Sin empleados" text="Un empleado no necesita usuario del POS para estar en la nómina." />
        ) : (
          <div className="divide-y divide-line-soft">
            {list.map((x) => (
              <button key={x.id} type="button" onClick={() => setEditing(x)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas">
                <Icon name="badge" className="text-[22px] text-muted" />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold">{fullName(x)}</span>
                  <span className="block text-xs text-muted">
                    {x.docType} {x.docNumber} · {CONTRACTS[x.contractType]} · desde {shortDate(x.startDate)}
                    {x.endDate ? ` hasta ${shortDate(x.endDate)}` : ''}
                  </span>
                </span>
                <span className="num text-sm font-semibold">{money(x.salary)}</span>
                {!x.active && <Chip>Inactivo</Chip>}
              </button>
            ))}
          </div>
        )}
      </Card>
      <Modal
        open={!!e}
        wide
        title={e?.id ? fullName(e) : 'Nuevo empleado'}
        onClose={() => setEditing(null)}
        footer={
          can('NOMINA_GESTIONAR') ? (
            <Button className="w-full" loading={saving} onClick={save}>
              Guardar
            </Button>
          ) : undefined
        }
      >
        {e && (
          <fieldset disabled={!can('NOMINA_GESTIONAR')} className="grid gap-3 sm:grid-cols-2">
            <SelectField label="Tipo de documento" value={e.docType} onChange={(v) => set({ docType: v.target.value })} error={fields.docType}>
              {Object.entries(ID_TYPES).map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </SelectField>
            <Field label="Número de documento" value={e.docNumber} onChange={(v) => set({ docNumber: v.target.value })} error={fields.docNumber} />
            <Field label="Primer nombre" value={e.firstName} onChange={(v) => set({ firstName: v.target.value })} error={fields.firstName} />
            <Field label="Otros nombres" value={e.otherNames ?? ''} onChange={(v) => set({ otherNames: v.target.value })} />
            <Field label="Primer apellido" value={e.firstSurname} onChange={(v) => set({ firstSurname: v.target.value })} error={fields.firstSurname} />
            <Field label="Segundo apellido" value={e.secondSurname ?? ''} onChange={(v) => set({ secondSurname: v.target.value })} />
            <Field label="Dirección" value={e.address ?? ''} onChange={(v) => set({ address: v.target.value })} hint="La pide la nómina electrónica" />
            <Field
              label="Municipio (código DANE)"
              inputMode="numeric"
              value={e.municipalityCode ?? ''}
              onChange={(v) => set({ municipalityCode: v.target.value.replace(/\D/g, '').slice(0, 5) })}
              error={fields.municipalityCode}
              hint="Ej: 11001 Bogotá, 05001 Medellín"
            />
            <Field label="Celular" value={e.phone ?? ''} onChange={(v) => set({ phone: v.target.value })} />
            <Field label="Correo" type="email" value={e.email ?? ''} onChange={(v) => set({ email: v.target.value })} error={fields.email} />
            <SelectField label="Tipo de contrato" value={e.contractType} onChange={(v) => set({ contractType: v.target.value })}>
              {Object.entries(CONTRACTS).map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </SelectField>
            <Field label="Salario mensual" inputMode="numeric" value={String(e.salary || '')} onChange={(v) => set({ salary: Number(v.target.value.replace(/\D/g, '')) })} error={fields.salary} />
            <Field label="Fecha de ingreso" type="date" value={e.startDate} onChange={(v) => set({ startDate: v.target.value })} error={fields.startDate} />
            <Field label="Fecha de retiro" type="date" value={e.endDate ?? ''} onChange={(v) => set({ endDate: v.target.value || null })} error={fields.endDate} />
            <SelectField label="Clase de riesgo ARL" value={String(e.arlClass)} onChange={(v) => set({ arlClass: Number(v.target.value) })}>
              {[1, 2, 3, 4, 5].map((n) => (
                <option key={n} value={n}>
                  Clase {n}
                </option>
              ))}
            </SelectField>
            <SelectField label="Forma de pago" value={e.paymentMethod} onChange={(v) => set({ paymentMethod: v.target.value })} error={fields.paymentMethod}>
              {Object.entries(PAY).map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </SelectField>
            {e.paymentMethod !== '10' && (
              <>
                <Field label="Banco" value={e.bankName ?? ''} onChange={(v) => set({ bankName: v.target.value })} />
                <SelectField label="Tipo de cuenta" value={e.accountType ?? ''} onChange={(v) => set({ accountType: v.target.value || null })}>
                  <option value="">Elige…</option>
                  <option value="2">Ahorros</option>
                  <option value="3">Corriente</option>
                  <option value="1">Nómina</option>
                </SelectField>
                <Field label="Número de cuenta" value={e.accountNumber ?? ''} onChange={(v) => set({ accountNumber: v.target.value })} error={fields.accountNumber} />
              </>
            )}
            <Field label="EPS" value={e.eps ?? ''} onChange={(v) => set({ eps: v.target.value })} />
            <Field label="Fondo de pensiones" value={e.pensionFund ?? ''} onChange={(v) => set({ pensionFund: v.target.value })} />
            <Field label="Fondo de cesantías" value={e.severanceFund ?? ''} onChange={(v) => set({ severanceFund: v.target.value })} />
            <Field label="Caja de compensación" value={e.compensationFund ?? ''} onChange={(v) => set({ compensationFund: v.target.value })} />
            <div className="space-y-1 sm:col-span-2">
              <Toggle checked={e.integralSalary} onChange={(v) => set({ integralSalary: v })} label="Salario integral" hint="Desde 13 salarios mínimos; no causa cesantías ni prima." />
              <Toggle checked={e.highRisk} onChange={(v) => set({ highRisk: v })} label="Pensión de alto riesgo" />
              <Toggle checked={e.active} onChange={(v) => set({ active: v })} label="Activo" />
            </div>
          </fieldset>
        )}
      </Modal>
    </div>
  )
}

// ---------------------------------------------------------------- Nómina del mes

function Periods({ onGoParams }: { onGoParams: () => void }) {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState<PeriodSummary[] | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const now = new Date()
  const [ym, setYm] = useState({ year: String(now.getFullYear()), month: String(now.getMonth() + 1) })
  const [error, setError] = useState('')
  const load = useCallback(() => {
    api<PeriodSummary[]>('/payroll/periods').then(setList).catch(() => setList([]))
  }, [])
  useEffect(load, [load])

  async function create() {
    setError('')
    try {
      const d = await api<PeriodDetail>('/payroll/periods', { body: { year: Number(ym.year), month: Number(ym.month) } })
      toast('Nómina creada')
      load()
      setOpen(d.period.id)
    } catch (e) {
      setError(errorOf(e, 'No se pudo crear'))
    }
  }

  if (open) return <PeriodView id={open} onBack={() => (setOpen(null), load())} />
  return (
    <div className="space-y-3">
      {can('NOMINA_GESTIONAR') && (
        <Card className="flex flex-wrap items-end gap-2 p-4">
          <SelectField label="Mes" value={ym.month} onChange={(e) => setYm({ ...ym, month: e.target.value })}>
            {MONTHS.map((m, i) => (
              <option key={m} value={i + 1}>
                {m}
              </option>
            ))}
          </SelectField>
          <Field label="Año" inputMode="numeric" value={ym.year} onChange={(e) => setYm({ ...ym, year: e.target.value })} />
          <Button icon="add" onClick={create}>
            Liquidar el mes
          </Button>
        </Card>
      )}
      {error && (
        <Banner>
          {error}{' '}
          {error.includes('valores') && (
            <button type="button" className="font-semibold underline" onClick={onGoParams}>
              Confirmarlos
            </button>
          )}
        </Banner>
      )}
      <Card>
        {!list ? (
          <Spinner />
        ) : list.length === 0 ? (
          <EmptyState icon="payments" title="Aún no hay nóminas" text="Crea los empleados, confirma los valores del año y liquida el mes." />
        ) : (
          <div className="divide-y divide-line-soft">
            {list.map((p) => (
              <button key={p.id} type="button" onClick={() => setOpen(p.id)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas">
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold">
                    {MONTHS[p.month - 1][0].toUpperCase() + MONTHS[p.month - 1].slice(1)} de {p.year}
                  </span>
                  <span className="block text-xs text-muted">
                    {p.employees} empleados · costo total {money(p.totalCost)}
                  </span>
                </span>
                <span className="num text-sm font-semibold">{money(p.totalNet)}</span>
                <Chip tone={p.status === 'APROBADA' ? 'success' : 'warning'}>{p.status === 'APROBADA' ? 'Aprobada' : 'Borrador'}</Chip>
              </button>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

function PeriodView({ id, onBack }: { id: number; onBack: () => void }) {
  const { can } = useAuth()
  const toast = useToast()
  const [d, setD] = useState<PeriodDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [novFor, setNovFor] = useState<Payslip | null>(null)
  const [slip, setSlip] = useState<Payslip | null>(null)
  useEffect(() => {
    api<PeriodDetail>(`/payroll/periods/${id}`).then(setD).catch(() => {})
  }, [id])

  async function act(path: string, body: unknown, msg: string, method: 'POST' | 'DELETE' = 'POST') {
    setBusy(true)
    try {
      setD(await api<PeriodDetail>(path, method === 'DELETE' ? { method } : { body: body ?? {} }))
      toast(msg)
    } catch (e) {
      toast(errorOf(e, 'No se pudo'), 'danger')
    } finally {
      setBusy(false)
    }
  }

  if (!d) return <Spinner />
  const p = d.period
  const draft = p.status === 'BORRADOR'
  const manage = can('NOMINA_GESTIONAR')
  return (
    <div className="space-y-3">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Nóminas
      </button>
      <Card className="flex flex-wrap items-center gap-3 p-4">
        <div className="min-w-0 flex-1">
          <div className="text-lg font-semibold">
            Nómina de {MONTHS[p.month - 1]} de {p.year}
          </div>
          <div className="text-xs text-muted">
            {p.status === 'APROBADA' ? `Aprobada por ${p.approvedBy} · pago ${p.paymentDate ? shortDate(p.paymentDate) : ''}` : 'Borrador: carga las novedades y aprueba.'}
          </div>
        </div>
        <div className="num text-right">
          <div className="text-xs text-muted">Neto a pagar</div>
          <div className="text-xl font-extrabold">{money(p.totalNet)}</div>
        </div>
        {manage && draft && (
          <Button loading={busy} icon="task_alt" onClick={() => act(`/payroll/periods/${p.id}/approve`, {}, 'Nómina aprobada')}>
            Aprobar
          </Button>
        )}
        {manage && !draft && (
          <Button loading={busy} icon="send" onClick={() => act(`/payroll/periods/${p.id}/transmit`, {}, 'Nómina electrónica enviada')}>
            Transmitir a la DIAN
          </Button>
        )}
      </Card>
      <Card>
        <div className="divide-y divide-line-soft">
          {d.payslips.map((s) => {
            const nov = d.novelties.filter((n) => n.employeeId === s.employeeId)
            return (
              <div key={s.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-semibold">{s.employeeName}</div>
                  <div className="text-xs text-muted">
                    {s.daysWorked} días · devengado {money(s.totalEarned)} · deducciones {money(s.totalDeductions)}
                    {nov.length > 0 ? ` · ${nov.length} novedades` : ''}
                  </div>
                  {s.document && (
                    <div className="mt-1 flex items-center gap-1.5 text-xs">
                      <span>Nómina electrónica {s.document.fullNumber}</span>
                      <Chip tone={DOC_STATUS[s.document.status].tone}>{DOC_STATUS[s.document.status].label}</Chip>
                      {s.deletedBeforeDian && <Chip tone="danger">Eliminada ante la DIAN</Chip>}
                    </div>
                  )}
                </div>
                <div className="num text-sm font-bold">{money(s.netPay)}</div>
                {manage && draft && (
                  <Button size="sm" variant="soft" icon="add" onClick={() => setNovFor(s)}>
                    Novedad
                  </Button>
                )}
                <Button size="sm" variant="secondary" icon="description" onClick={() => setSlip(s)}>
                  Desprendible
                </Button>
                {manage && s.document?.status === 'ACEPTADO' && !s.deletedBeforeDian && (
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-danger-ink"
                    onClick={() => {
                      const reason = window.prompt('¿Por qué se elimina ante la DIAN esta nómina electrónica?')
                      if (reason) act(`/payroll/payslips/${s.id}/delete-electronic`, { reason }, 'Nota de eliminación enviada')
                    }}
                  >
                    Eliminar ante la DIAN
                  </Button>
                )}
                {nov.length > 0 && (
                  <ul className="w-full space-y-1 pl-2 text-xs">
                    {nov.map((n) => (
                      <li key={n.id} className="flex items-center gap-2">
                        <span>
                          {KINDS[n.kind]?.label}
                          {n.code && KINDS[n.kind]?.codes ? ` · ${KINDS[n.kind].codes![n.code]}` : ''}
                          {n.quantity ? ` · ${n.quantity} ${KINDS[n.kind]?.unit ?? ''}` : ''}
                          {n.amount ? ` · ${money(n.amount)}` : ''}
                          {n.description ? ` · ${n.description}` : ''}
                        </span>
                        {manage && draft && (
                          <button
                            type="button"
                            aria-label="Quitar novedad"
                            className="text-danger-ink"
                            onClick={() => act(`/payroll/periods/${p.id}/novelties/${n.id}`, undefined, 'Novedad quitada', 'DELETE')}
                          >
                            <Icon name="close" className="text-[16px]" />
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )
          })}
          {d.payslips.length === 0 && <EmptyState icon="badge" title="Nadie con contrato en este mes" />}
        </div>
      </Card>
      {novFor && (
        <NoveltyModal
          payslip={novFor}
          periodId={p.id}
          sundayPct={d.parameters?.sundaySurchargePct ?? 0}
          onClose={() => setNovFor(null)}
          onDone={(nd) => {
            setNovFor(null)
            setD(nd)
            toast('Novedad agregada y nómina recalculada')
          }}
        />
      )}
      {slip && <PayslipModal slip={slip} period={p} onClose={() => setSlip(null)} />}
    </div>
  )
}

function NoveltyModal({ payslip, periodId, sundayPct, onClose, onDone }: {
  payslip: Payslip
  periodId: number
  sundayPct: number
  onClose: () => void
  onDone: (d: PeriodDetail) => void
}) {
  const [f, setF] = useState({ kind: 'HORA', code: '1', quantity: '', amount: '', description: '' })
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const k = KINDS[f.kind]

  async function save() {
    setSaving(true)
    setError('')
    try {
      onDone(
        await api<PeriodDetail>(`/payroll/periods/${periodId}/novelties`, {
          body: {
            employeeId: payslip.employeeId,
            kind: f.kind,
            code: k.codes ? f.code : undefined,
            quantity: k.unit ? Number(f.quantity.replace(',', '.')) : undefined,
            amount: f.amount ? Number(f.amount.replace(/\D/g, '')) : undefined,
            description: f.description || undefined,
          },
        }),
      )
    } catch (e) {
      setError(errorOf(e, 'No se pudo agregar'))
    } finally {
      setSaving(false)
    }
  }

  const surchargeHint =
    f.kind === 'HORA'
      ? `Recargo usado: ${{ '1': 25, '2': 75, '3': 35, '4': 25 + sundayPct, '5': sundayPct, '6': 75 + sundayPct, '7': 35 + sundayPct }[f.code] ?? 0} %`
      : undefined
  return (
    <Modal
      open
      title={`Novedad · ${payslip.employeeName}`}
      onClose={onClose}
      footer={
        <Button className="w-full" loading={saving} onClick={save}>
          Agregar
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <SelectField
          label="Tipo"
          value={f.kind}
          onChange={(e) => setF({ ...f, kind: e.target.value, code: Object.keys(KINDS[e.target.value].codes ?? { '': '' })[0] })}
        >
          {Object.entries(KINDS).map(([key, v]) => (
            <option key={key} value={key}>
              {v.label}
            </option>
          ))}
        </SelectField>
        {k.codes && (
          <SelectField label="Clase" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} hint={surchargeHint}>
            {Object.entries(k.codes).map(([c, l]) => (
              <option key={c} value={c}>
                {l}
              </option>
            ))}
          </SelectField>
        )}
        {k.unit && <Field label={`Cantidad (${k.unit})`} inputMode="decimal" value={f.quantity} onChange={(e) => setF({ ...f, quantity: e.target.value })} />}
        <Field
          label={k.unit ? 'Valor (opcional: se calcula solo)' : 'Valor'}
          inputMode="numeric"
          value={f.amount}
          onChange={(e) => setF({ ...f, amount: e.target.value })}
        />
        <Field label="Detalle (opcional)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

/** Desprendible de pago imprimible. */
function PayslipModal({ slip, period, onClose }: { slip: Payslip; period: PeriodSummary; onClose: () => void }) {
  const { me } = useAuth()
  const [target, setTarget] = useState<Element | DocumentFragment | null>(null)
  useEffect(() => {
    const sheet = acquirePrintRoot()
    setTarget(sheet.root)
    return sheet.release
  }, [])
  const body = (
    <div className="mx-auto max-w-md space-y-3 bg-white p-4 text-[13px] text-black">
      <div className="text-center">
        <div className="text-base font-bold">{me?.tenant.name}</div>
        <div>
          Desprendible de pago · {MONTHS[period.month - 1]} {period.year}
        </div>
      </div>
      <div>
        <b>{slip.employeeName}</b> · {slip.docNumber}
        <div>Días trabajados: {slip.daysWorked}</div>
      </div>
      <Section title="Devengado" items={slip.detail.earnings} total={slip.totalEarned} />
      <Section title="Deducciones" items={slip.detail.deductions} total={slip.totalDeductions} />
      <div className="flex justify-between border-t border-black pt-2 text-base font-bold">
        <span>NETO A PAGAR</span>
        <span>{money(slip.netPay)}</span>
      </div>
      {slip.document && <div className="text-center text-[11px]">Nómina electrónica {slip.document.fullNumber}</div>}
      <div className="mt-8 border-t border-black pt-1 text-center text-[11px]">Recibí conforme · Firma y cédula</div>
    </div>
  )
  return (
    <Modal
      open
      title="Desprendible de pago"
      onClose={onClose}
      footer={
        <Button className="w-full" icon="print" onClick={() => window.print()}>
          Imprimir
        </Button>
      }
    >
      <div className="rounded-xl bg-line-soft p-3">{body}</div>
      <div className="mt-3 text-xs text-muted">
        Aportes del empleador: {money(slip.employerCost)} · Provisiones del mes: {money(slip.provisions)} · IBC {money(slip.contributionBase)}
      </div>
      {target &&
        createPortal(
          <div id="print-area" aria-hidden>
            {body}
          </div>,
          target,
        )}
    </Modal>
  )
}

function Section({ title, items, total }: { title: string; items: Item[]; total: number }) {
  return (
    <div>
      <div className="font-bold">{title}</div>
      {items.map((i, n) => (
        <div key={n} className="flex justify-between">
          <span>
            {i.label}
            {i.quantity ? ` (${i.quantity})` : ''}
          </span>
          <span>{money(i.amount)}</span>
        </div>
      ))}
      <div className="flex justify-between border-t border-dashed border-black font-semibold">
        <span>Total {title.toLowerCase()}</span>
        <span>{money(total)}</span>
      </div>
    </div>
  )
}
