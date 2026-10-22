import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Banner, Button, EmptyState, Field, InfoTip, Modal, SelectField, Spinner, Tabs, TextArea, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, relative, shortDate } from '../../lib/format'
import { ORDER_STATUS, todayIso, type OrderDto, type OrderSummary, type StaffDto } from '../../lib/servicesTypes'

type Filter = 'ABIERTAS' | 'LISTO' | 'ENTREGADO' | 'TODAS'

/** Órdenes de servicio técnico: equipos recibidos, en qué van y cuáles están listos para entregar. */
export default function ServiceOrdersPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('ABIERTAS')
  const [list, setList] = useState<OrderSummary[] | null>(null)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [q, setQ] = useState('')

  const load = useCallback(() => {
    api<OrderSummary[]>(`/service-orders${filter === 'TODAS' ? '' : `?status=${filter}`}`)
      .then((l) => (setList(l), setError('')))
      .catch((e) => setError(e.message))
  }, [filter])
  useEffect(load, [load])

  if (error) return <Banner>{error}</Banner>
  const shown = (list ?? []).filter((o) => {
    const t = q.trim().toLowerCase()
    return !t || `${o.number} ${o.customerName} ${o.device} ${o.customerPhone ?? ''}`.toLowerCase().includes(t)
  })
  const today = todayIso()

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight">
            Órdenes de servicio
            <InfoTip text="Recibe el equipo con su estado y accesorios, escribe el diagnóstico, manda la cotización por WhatsApp, agrega repuestos del inventario y recibe anticipos. Al entregarlo se cobra el saldo y empieza la garantía." />
          </h1>
          <p className="text-sm text-muted">Celulares, computadores, electrodomésticos, motos…</p>
        </div>
        <Button icon="add" onClick={() => setCreating(true)}>
          Recibir equipo
        </Button>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <div className="shrink-0">
          <Tabs
            value={filter}
            onChange={setFilter}
            items={[
              { value: 'ABIERTAS', label: 'En taller' },
              { value: 'LISTO', label: 'Listas' },
              { value: 'ENTREGADO', label: 'Entregadas' },
              { value: 'TODAS', label: 'Todas' },
            ]}
          />
        </div>
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Buscar por número, cliente o equipo"
          className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-card px-3 text-sm"
        />
      </div>
      {!list ? (
        <Spinner />
      ) : shown.length === 0 ? (
        <EmptyState icon="build" title="No hay órdenes aquí" text="Cuando recibas un equipo para reparar, créale su orden." />
      ) : (
        <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card" data-guide="ordenes-lista">
          {shown.map((o) => {
            const st = ORDER_STATUS[o.status]
            const late = o.promisedAt && o.promisedAt < today && !['LISTO', 'ENTREGADO', 'CANCELADO'].includes(o.status)
            return (
              <li key={o.id}>
                <button
                  type="button"
                  onClick={() => navigate(`/ordenes-servicio/${o.id}`)}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas"
                >
                  <span className="num w-12 shrink-0 text-sm font-bold text-muted">#{o.number}</span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-semibold">{o.device}</div>
                    <div className="truncate text-xs text-muted">
                      {o.customerName}
                      {o.technicianName && ` · ${o.technicianName}`} · {relative(o.updatedAt)}
                      {o.promisedAt && <span className={late ? 'font-semibold text-danger-ink' : ''}> · prometido {shortDate(o.promisedAt)}</span>}
                    </div>
                  </div>
                  <div className="text-right">
                    <span className={`rounded-lg px-2 py-0.5 text-xs font-semibold ${st.tone}`}>{st.label}</span>
                    {o.total > 0 && <div className="num mt-0.5 text-xs text-muted">{money(o.total)}</div>}
                  </div>
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {creating && <ReceiveModal onClose={() => setCreating(false)} onDone={(o) => navigate(`/ordenes-servicio/${o.id}`)} />}
    </div>
  )
}

function ReceiveModal({ onClose, onDone }: { onClose: () => void; onDone: (o: OrderDto) => void }) {
  const toast = useToast()
  const [staff, setStaff] = useState<StaffDto[]>([])
  const [f, setF] = useState({
    customerName: '',
    customerPhone: '',
    device: '',
    brand: '',
    model: '',
    serial: '',
    problem: '',
    accessories: '',
    conditionNotes: '',
    technicianId: '',
    warrantyDays: '30',
    promisedAt: '',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(
    () =>
      void api<StaffDto[]>('/services/staff')
        .then((s) => setStaff(s.filter((x) => x.active)))
        .catch(() => {}),
    [],
  )
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })

  async function save() {
    setBusy(true)
    setError('')
    try {
      const o = await api<OrderDto>('/service-orders', {
        body: {
          ...f,
          technicianId: f.technicianId ? Number(f.technicianId) : null,
          warrantyDays: Number(f.warrantyDays) || 0,
          promisedAt: f.promisedAt || null,
        },
      })
      toast(`Orden #${o.number} creada`)
      onDone(o)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo crear')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      wide
      title="Recibir equipo"
      onClose={onClose}
      footer={
        <Button className="w-full" icon="inventory_2" loading={busy} onClick={save}>
          Crear orden
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Cliente" value={f.customerName} onChange={set('customerName')} />
          <Field label="Celular" inputMode="tel" value={f.customerPhone} onChange={set('customerPhone')} hint="Para avisarle por WhatsApp" />
          <Field label="Equipo" value={f.device} onChange={set('device')} placeholder="Celular, portátil, licuadora…" />
          <Field label="Marca" value={f.brand} onChange={set('brand')} />
          <Field label="Modelo" value={f.model} onChange={set('model')} />
          <Field label="Serial / IMEI" value={f.serial} onChange={set('serial')} />
        </div>
        <TextArea label="Falla que reporta el cliente" value={f.problem} onChange={set('problem')} />
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Accesorios que deja" value={f.accessories} onChange={set('accessories')} placeholder="Cargador, forro, SIM…" />
          <Field label="Estado físico" value={f.conditionNotes} onChange={set('conditionNotes')} placeholder="Rayones, golpe en la esquina…" />
          <SelectField label="Técnico" value={f.technicianId} onChange={set('technicianId')}>
            <option value="">Sin asignar</option>
            {staff.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </SelectField>
          <Field label="Fecha prometida" type="date" value={f.promisedAt} onChange={set('promisedAt')} />
          <Field label="Garantía (días)" inputMode="numeric" value={f.warrantyDays} onChange={set('warrantyDays')} />
        </div>
      </div>
    </Modal>
  )
}
