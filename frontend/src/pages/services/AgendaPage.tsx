import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Banner, Button, EmptyState, Field, Icon, InfoTip, Modal, SelectField, Spinner, TextArea, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import type { SaleDto } from '../../lib/posTypes'
import {
  addDays,
  ampm,
  APPOINTMENT_STATUS,
  hhmm,
  longDate,
  minutesOf,
  todayIso,
  type AgendaDto,
  type AppointmentDto,
  type ServiceDto,
  type SlotDto,
} from '../../lib/servicesTypes'

const PX_PER_MIN = 1.4

/**
 * Agenda del día por profesional: cada columna es una persona con su horario; las citas se ven como bloques. Tocar un
 * espacio libre agenda ahí; tocar una cita permite mandar el recordatorio, confirmarla o pasarla a la caja.
 */
export default function AgendaPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const date = params.get('fecha') ?? todayIso()
  const [agenda, setAgenda] = useState<AgendaDto | null>(null)
  const [services, setServices] = useState<ServiceDto[]>([])
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<Partial<AppointmentDto> | null>(null)
  const [open, setOpen] = useState<AppointmentDto | null>(null)

  const load = useCallback(() => {
    api<AgendaDto>(`/services/agenda?date=${date}`)
      .then((a) => (setAgenda(a), setError('')))
      .catch((e) => setError(e.message))
  }, [date])
  useEffect(() => {
    load()
    const id = setInterval(load, 30_000) // citas que llegan desde la tienda en línea
    return () => clearInterval(id)
  }, [load])
  useEffect(
    () =>
      void api<ServiceDto[]>('/services/catalog')
        .then(setServices)
        .catch(() => {}),
    [],
  )

  const setDate = (d: string) => setParams(d === todayIso() ? {} : { fecha: d })

  // Rango visible: desde la primera hora de trabajo (o cita) hasta la última.
  const [startMin, endMin] = useMemo(() => {
    if (!agenda) return [8 * 60, 18 * 60]
    const mins = agenda.staff.flatMap((s) => s.ranges.flatMap((r) => [minutesOf('0000-00-00T' + r.start), minutesOf('0000-00-00T' + r.end)]))
    agenda.appointments.forEach((a) => mins.push(minutesOf(a.start), minutesOf(a.end)))
    if (!mins.length) return [8 * 60, 18 * 60]
    return [Math.floor(Math.min(...mins) / 60) * 60, Math.ceil(Math.max(...mins) / 60) * 60]
  }, [agenda])

  if (error) return <Banner>{error}</Banner>
  if (!agenda) return <Spinner />

  const dayAppointments = agenda.appointments.filter((a) => a.start.slice(0, 10) === date)
  const active = dayAppointments.filter((a) => a.status !== 'CANCELADA')
  const toRemind = active.filter((a) => !a.reminderSentAt && a.whatsappUrl && a.status === 'AGENDADA').length
  const hours: number[] = []
  for (let m = startMin; m < endMin; m += 60) hours.push(m)

  function newAt(staffId: number, minute: number) {
    const step = agenda!.slotMinutes || 30
    const rounded = Math.floor(minute / step) * step
    setEditing({ staffId, start: `${date}T${hhmm(rounded)}:00` })
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight">
            Agenda
            <InfoTip text="Cada columna es un profesional con su horario. Toca un espacio libre para agendar, o una cita para mandar el recordatorio por WhatsApp, confirmarla o pasarla a la caja para cobrar el servicio y los productos." />
          </h1>
          <p className="text-sm text-muted first-letter:uppercase">
            {longDate(date)} · {active.length} {active.length === 1 ? 'cita' : 'citas'}
            {toRemind > 0 && <span className="font-semibold text-warning-ink"> · {toRemind} sin recordatorio</span>}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/ordenes-servicio" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
            <Icon name="build" className="text-[20px] text-primary" /> Órdenes
          </Link>
          {can('FINANZAS_VER') && (
            <Link to="/comisiones" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
              <Icon name="percent" className="text-[20px] text-wallet" /> Comisiones
            </Link>
          )}
          {can('CONFIG_EDITAR') && (
            <Link to="/servicios/configurar" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
              <Icon name="settings" className="text-[20px] text-muted" /> Configurar
            </Link>
          )}
          <Button icon="event_available" onClick={() => setEditing({ start: `${date}T09:00:00` })} disabled={!agenda.staff.length}>
            Nueva cita
          </Button>
        </div>
      </div>

      <div className="flex items-center gap-2" data-guide="agenda-fecha">
        <Button variant="secondary" size="sm" icon="chevron_left" onClick={() => setDate(addDays(date, -1))}>
          <span className="sr-only">Día anterior</span>
        </Button>
        <Button variant="secondary" size="sm" onClick={() => setDate(todayIso())}>
          Hoy
        </Button>
        <Button variant="secondary" size="sm" icon="chevron_right" onClick={() => setDate(addDays(date, 1))}>
          <span className="sr-only">Día siguiente</span>
        </Button>
        <input
          type="date"
          value={date}
          onChange={(e) => e.target.value && setDate(e.target.value)}
          className="h-9 rounded-lg border border-line bg-card px-2 text-sm"
        />
      </div>

      {!agenda.staff.length ? (
        <EmptyState
          icon="groups"
          title="Primero crea a tus profesionales"
          text="Cada persona que atiende (estilista, barbero, técnico) con su horario y su comisión. Después marca tus servicios con su duración."
          action={can('CONFIG_EDITAR') ? <Button onClick={() => navigate('/servicios/configurar')}>Configurar servicios</Button> : undefined}
        />
      ) : (
        <div className="overflow-x-auto rounded-2xl border border-line bg-card shadow-tier1" data-guide="agenda-grilla">
          <div className="flex min-w-max">
            <div className="sticky left-0 z-10 w-14 shrink-0 border-r border-line-soft bg-card">
              <div className="h-11 border-b border-line-soft" />
              <div className="relative" style={{ height: (endMin - startMin) * PX_PER_MIN }}>
                {hours.map((m) => (
                  <div key={m} className="absolute right-1.5 -translate-y-1/2 text-[11px] text-muted" style={{ top: (m - startMin) * PX_PER_MIN }}>
                    {m === startMin ? '' : ampm(hhmm(m)).replace(' a. m.', 'a').replace(' p. m.', 'p')}
                  </div>
                ))}
              </div>
            </div>
            {agenda.staff.map((s) => (
              <div key={s.id} className="w-48 shrink-0 border-r border-line-soft last:border-r-0 sm:w-56">
                <div className="flex h-11 items-center gap-2 border-b border-line-soft px-3">
                  <span className="h-3 w-3 rounded-full" style={{ background: s.color }} />
                  <span className="truncate text-sm font-semibold">{s.name}</span>
                </div>
                <div
                  className="relative cursor-pointer bg-slate-100/70"
                  style={{ height: (endMin - startMin) * PX_PER_MIN }}
                  onClick={(e) => {
                    const y = e.clientY - e.currentTarget.getBoundingClientRect().top
                    newAt(s.id, startMin + y / PX_PER_MIN)
                  }}
                >
                  {s.ranges.map((r, i) => {
                    const a = minutesOf('0000-00-00T' + r.start)
                    const b = minutesOf('0000-00-00T' + r.end)
                    return (
                      <div
                        key={i}
                        className="absolute inset-x-0 bg-card"
                        style={{
                          top: (a - startMin) * PX_PER_MIN,
                          height: (b - a) * PX_PER_MIN,
                        }}
                      />
                    )
                  })}
                  {hours.map((m) => (
                    <div key={m} className="pointer-events-none absolute inset-x-0 border-t border-line-soft" style={{ top: (m - startMin) * PX_PER_MIN }} />
                  ))}
                  {dayAppointments
                    .filter((a) => a.staffId === s.id)
                    .map((a) => {
                      const top = (minutesOf(a.start) - startMin) * PX_PER_MIN
                      const st = APPOINTMENT_STATUS[a.status]
                      const paid = a.saleStatus === 'PAGADA'
                      return (
                        <button
                          key={a.id}
                          type="button"
                          onClick={(e) => (e.stopPropagation(), setOpen(a))}
                          className={`absolute inset-x-1 overflow-hidden rounded-lg border-l-4 bg-white px-2 py-1 text-left text-xs shadow-tier1 ${a.status === 'CANCELADA' || a.status === 'NO_ASISTIO' ? 'opacity-50' : ''}`}
                          style={{
                            top: top + 1,
                            height: Math.max(26, a.minutes * PX_PER_MIN - 2),
                            borderLeftColor: a.staffColor,
                          }}
                        >
                          <div className="flex items-center gap-1 font-bold">
                            <span className="truncate">{a.customerName}</span>
                            {a.source === 'ONLINE' && <Icon name="language" className="text-[13px] text-primary" />}
                            {a.reminderSentAt && <Icon name="mark_chat_read" className="text-[13px] text-success-ink" />}
                          </div>
                          <div className="truncate text-muted">
                            {ampm(a.start.slice(11, 16))} · {a.serviceName}
                          </div>
                          {a.minutes >= 40 && (
                            <span
                              className={`mt-0.5 inline-block rounded px-1.5 text-[10px] font-semibold ${paid ? 'bg-success-soft text-success-ink' : st.tone}`}
                            >
                              {paid ? 'Cobrada' : st.label}
                            </span>
                          )}
                        </button>
                      )
                    })}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {agenda.bookingEnabled && agenda.bookingUrlSlug && (
        <p className="text-xs text-muted">
          <Icon name="language" className="align-middle text-[14px]" /> Tus clientes piden cita en{' '}
          <a className="font-semibold text-primary" href={`/t/${agenda.bookingUrlSlug}/citas`} target="_blank" rel="noreferrer">
            {location.host}/t/{agenda.bookingUrlSlug}/citas
          </a>
        </p>
      )}

      {editing && (
        <AppointmentEditor
          initial={editing}
          services={services}
          staff={agenda.staff}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            load()
          }}
        />
      )}
      {open && (
        <AppointmentDetail
          a={open}
          onClose={() => setOpen(null)}
          onEdit={() => {
            setEditing(open)
            setOpen(null)
          }}
          onChanged={(a) => {
            setOpen(a)
            load()
          }}
        />
      )}
    </div>
  )
}

function AppointmentDetail({
  a,
  onClose,
  onEdit,
  onChanged,
}: {
  a: AppointmentDto
  onClose: () => void
  onEdit: () => void
  onChanged: (a: AppointmentDto) => void
}) {
  const toast = useToast()
  const navigate = useNavigate()
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const paid = a.saleStatus === 'PAGADA'
  const closed = a.status === 'CANCELADA' || a.status === 'NO_ASISTIO'

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key)
    setError('')
    try {
      await fn()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo')
    } finally {
      setBusy('')
    }
  }
  const status = (s: string) =>
    run(s, async () =>
      onChanged(
        await api<AppointmentDto>(`/services/appointments/${a.id}/status`, {
          body: { status: s },
        }),
      ),
    )

  return (
    <Modal
      open
      title={a.customerName}
      onClose={onClose}
      footer={
        paid ? (
          <div className="flex w-full items-center justify-center gap-2 text-sm font-semibold text-success-ink">
            <Icon name="check_circle" /> Cita cobrada
          </div>
        ) : closed ? (
          <Button variant="secondary" className="w-full" icon="undo" loading={busy === 'AGENDADA'} onClick={() => status('AGENDADA')}>
            Volver a agendar
          </Button>
        ) : (
          <Button
            className="w-full"
            icon="point_of_sale"
            loading={busy === 'charge'}
            onClick={() =>
              run('charge', async () => {
                const sale = await api<SaleDto>(`/services/appointments/${a.id}/charge`, { method: 'POST' })
                toast('Cita en la caja: agrega productos si lleva y cobra')
                navigate(`/pos?cuenta=${sale.id}`)
              })
            }
          >
            {a.saleId ? 'Ir a cobrar en la caja' : 'Atender y cobrar en la caja'}
          </Button>
        )
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid grid-cols-2 gap-3 text-sm">
          <Info label="Servicio" value={a.serviceName} />
          <Info label="Precio" value={money(a.price)} />
          <Info label="Hora" value={`${ampm(a.start.slice(11, 16))} – ${ampm(a.end.slice(11, 16))}`} />
          <Info label="Con" value={a.staffName} />
          <Info label="Celular" value={a.customerPhone ?? '—'} />
          <Info label="Estado" value={paid ? 'Cobrada' : APPOINTMENT_STATUS[a.status].label} />
        </div>
        {a.notes && <p className="rounded-xl bg-canvas p-3 text-sm">{a.notes}</p>}
        <p className="text-xs text-muted">
          {a.source === 'ONLINE' ? 'Pedida desde la tienda en línea' : `Agendada por ${a.createdBy ?? '—'}`}
          {a.reminderSentAt && ' · recordatorio enviado'}
        </p>
        {!paid && !closed && (
          <div className="grid grid-cols-2 gap-2" data-guide="cita-acciones">
            {a.whatsappUrl && (
              <Button
                variant="secondary"
                icon="chat"
                className="col-span-2"
                loading={busy === 'remind'}
                onClick={() =>
                  run('remind', async () => {
                    window.open(a.whatsappUrl!, '_blank', 'noopener')
                    onChanged(await api<AppointmentDto>(`/services/appointments/${a.id}/reminder`, { method: 'POST' }))
                  })
                }
              >
                {a.reminderSentAt ? 'Recordar otra vez por WhatsApp' : 'Mandar recordatorio por WhatsApp'}
              </Button>
            )}
            {a.status === 'AGENDADA' && (
              <Button variant="secondary" icon="thumb_up" loading={busy === 'CONFIRMADA'} onClick={() => status('CONFIRMADA')}>
                Confirmó
              </Button>
            )}
            {!a.saleId && (
              <Button variant="secondary" icon="edit_calendar" onClick={onEdit}>
                Mover / editar
              </Button>
            )}
            <Button variant="secondary" icon="person_off" loading={busy === 'NO_ASISTIO'} onClick={() => status('NO_ASISTIO')}>
              No vino
            </Button>
            <Button variant="secondary" icon="event_busy" loading={busy === 'CANCELADA'} onClick={() => status('CANCELADA')}>
              Cancelar
            </Button>
          </div>
        )}
      </div>
    </Modal>
  )
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs font-semibold text-muted uppercase">{label}</div>
      <div className="font-semibold">{value}</div>
    </div>
  )
}

function AppointmentEditor({
  initial,
  services,
  staff,
  onClose,
  onSaved,
}: {
  initial: Partial<AppointmentDto>
  services: ServiceDto[]
  staff: AgendaDto['staff']
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const activeServices = services.filter((s) => s.active)
  const [serviceId, setServiceId] = useState<number>(initial.serviceId ?? activeServices[0]?.productId ?? 0)
  const [staffId, setStaffId] = useState<number>(initial.staffId ?? staff[0]?.id ?? 0)
  const [day, setDay] = useState((initial.start ?? todayIso()).slice(0, 10))
  const [time, setTime] = useState((initial.start ?? 'T09:00').slice(11, 16))
  const [name, setName] = useState(initial.customerName ?? '')
  const [phone, setPhone] = useState(initial.customerPhone ?? '')
  const [price, setPrice] = useState(initial.price != null ? String(initial.price) : '')
  const [notes, setNotes] = useState(initial.notes ?? '')
  const [slots, setSlots] = useState<SlotDto[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const service = services.find((s) => s.productId === serviceId)

  useEffect(() => {
    if (!serviceId || !staffId || !day) return
    api<SlotDto[]>(`/services/slots?serviceId=${serviceId}&staffId=${staffId}&date=${day}`)
      .then(setSlots)
      .catch(() => setSlots([]))
  }, [serviceId, staffId, day])

  async function save() {
    setBusy(true)
    setError('')
    try {
      const body = {
        staffId,
        serviceId,
        customerName: name,
        customerPhone: phone,
        start: `${day}T${time}:00`,
        price: price === '' ? undefined : Number(price),
        notes,
      }
      if (initial.id)
        await api(`/services/appointments/${initial.id}`, {
          method: 'PUT',
          body,
        })
      else await api('/services/appointments', { body })
      toast(initial.id ? 'Cita actualizada' : 'Cita agendada')
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }

  if (!activeServices.length)
    return (
      <Modal open title="Nueva cita" onClose={onClose}>
        <EmptyState icon="content_cut" title="No hay servicios" text="En Configurar → Servicios marca qué productos son servicios y cuánto duran." />
      </Modal>
    )

  const free = slots.some((s) => s.time === time)
  return (
    <Modal
      open
      wide
      title={initial.id ? 'Mover o editar la cita' : 'Nueva cita'}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="event_available" loading={busy} onClick={save}>
          {initial.id ? 'Guardar cambios' : 'Agendar'}
        </Button>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Servicio" value={serviceId} onChange={(e) => (setServiceId(Number(e.target.value)), setPrice(''))}>
            {activeServices.map((s) => (
              <option key={s.productId} value={s.productId}>
                {s.name} · {s.minutes} min · {money(s.price)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Con" value={staffId} onChange={(e) => setStaffId(Number(e.target.value))}>
            {staff.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </SelectField>
          <Field label="Día" type="date" value={day} onChange={(e) => setDay(e.target.value)} />
          <Field
            label="Hora"
            type="time"
            value={time}
            onChange={(e) => setTime(e.target.value)}
            hint={slots.length && !free ? 'Esa hora no está libre en su horario' : undefined}
          />
        </div>
        {slots.length > 0 && (
          <div>
            <div className="mb-1.5 text-xs font-semibold text-muted uppercase">Horas libres</div>
            <div className="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto">
              {slots.map((s) => (
                <button
                  key={s.time}
                  type="button"
                  onClick={() => setTime(s.time)}
                  className={`rounded-lg border px-2.5 py-1 text-xs font-semibold ${s.time === time ? 'border-primary bg-primary text-white' : 'border-line bg-card'}`}
                >
                  {ampm(s.time)}
                </button>
              ))}
            </div>
          </div>
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Cliente" value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre" />
          <Field label="Celular (para el recordatorio)" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="300 123 4567" />
          <Field
            label="Precio"
            inputMode="numeric"
            value={price}
            onChange={(e) => setPrice(e.target.value.replace(/[^\d]/g, ''))}
            placeholder={service ? String(service.price) : ''}
            hint="Vacío = el precio del servicio"
          />
        </div>
        <TextArea label="Nota (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={500} />
      </div>
    </Modal>
  )
}
