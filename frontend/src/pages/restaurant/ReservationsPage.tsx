import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Banner, Button, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { SaleDto } from '../../lib/posTypes'
import { RESERVATION_STATUS, type Floor, type Reservation } from '../../lib/restaurantTypes'

/** Fecha local "2026-11-05" (sin pasar por UTC, para no cambiar de día en la noche). */
const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const hour = (iso: string) => new Date(iso).toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' })

/** Enlace de WhatsApp para confirmar la reserva con el cliente. */
function whatsappLink(r: Reservation, store: string) {
  if (!r.phone) return null
  let digits = r.phone.replace(/\D/g, '')
  if (digits.length === 10) digits = '57' + digits
  const day = new Date(r.reservedAt).toLocaleDateString('es-CO', { weekday: 'long', day: 'numeric', month: 'long' })
  const text = `Hola ${r.name}, te escribimos de ${store} para confirmar tu reserva para ${r.people} ${r.people === 1 ? 'persona' : 'personas'} el ${day} a las ${hour(r.reservedAt)}${r.tableName ? ` (${r.tableName})` : ''}. ¿Nos confirmas? ¡Te esperamos!`
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`
}

/**
 * Reservas de mesa por día: se anotan con hora, personas y mesa (opcional), se confirman por WhatsApp y, cuando llega
 * el grupo, "Llegaron" abre la mesa con el número de personas. El plano muestra la próxima reserva de cada mesa.
 */
export default function ReservationsPage() {
  const { me } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const [day, setDay] = useState(() => isoDay(new Date()))
  const [list, setList] = useState<Reservation[] | null>(null)
  const [tables, setTables] = useState<Floor['tables']>([])
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<Reservation | 'new' | null>(null)
  const [seating, setSeating] = useState<Reservation | null>(null)

  const load = useCallback(() => {
    api<Reservation[]>(`/restaurant/reservations?date=${day}`)
      .then((r) => (setList(r), setError('')))
      .catch((e) => setError(e.message))
  }, [day])
  useEffect(() => load(), [load])
  useEffect(() => void api<Floor>('/restaurant/floor').then((f) => setTables(f.tables)).catch(() => {}), [])

  async function status(r: Reservation, s: 'CONFIRMADA' | 'NO_LLEGO' | 'CANCELADA') {
    try {
      await api(`/restaurant/reservations/${r.id}/status`, { body: { status: s } })
      toast(`Reserva de ${r.name}: ${RESERVATION_STATUS[s].label.toLowerCase()}`)
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo', 'danger')
    }
  }

  async function seat(r: Reservation, tableId?: number) {
    if (!tableId && !r.tableId) return setSeating(r)
    try {
      const acc = await api<SaleDto>(`/restaurant/reservations/${r.id}/seat`, { body: { tableId: tableId ?? r.tableId } })
      toast(`Bienvenidos, ${r.name}: mesa abierta`)
      navigate(`/pos?cuenta=${acc.id}`)
    } catch (e) {
      toast(e instanceof ApiError ? e.fields.tableId ?? e.message : 'No se pudo abrir la mesa', 'danger')
    }
  }

  const shift = (days: number) => {
    const d = new Date(day + 'T12:00:00')
    d.setDate(d.getDate() + days)
    setDay(isoDay(d))
  }
  const people = useMemo(() => (list ?? []).filter((r) => r.status === 'PENDIENTE' || r.status === 'CONFIRMADA').reduce((s, r) => s + r.people, 0), [list])
  const isToday = day === isoDay(new Date())

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Reservas</h1>
          <p className="text-sm text-muted">
            {new Date(day + 'T12:00:00').toLocaleDateString('es-CO', { weekday: 'long', day: 'numeric', month: 'long' })}
            {list ? ` · ${people} ${people === 1 ? 'persona esperada' : 'personas esperadas'}` : ''}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" icon="chevron_left" onClick={() => shift(-1)}>
            <span className="sr-only">Día anterior</span>
          </Button>
          <input type="date" value={day} onChange={(e) => e.target.value && setDay(e.target.value)} className="h-10 rounded-xl border border-line bg-card px-3 text-sm" aria-label="Día" />
          <Button size="sm" variant="secondary" icon="chevron_right" onClick={() => shift(1)}>
            <span className="sr-only">Día siguiente</span>
          </Button>
          {!isToday && (
            <Button size="sm" variant="secondary" onClick={() => setDay(isoDay(new Date()))}>
              Hoy
            </Button>
          )}
          <Button icon="add" onClick={() => setEditing('new')}>
            Nueva reserva
          </Button>
        </div>
      </div>

      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="event_seat" title="Sin reservas este día" text="Anota las reservas que te hacen por teléfono o WhatsApp para guardarles la mesa." />
      ) : (
        <ul className="space-y-2">
          {list.map((r) => {
            const st = RESERVATION_STATUS[r.status]
            const open = r.status === 'PENDIENTE' || r.status === 'CONFIRMADA'
            const wa = open ? whatsappLink(r, me?.tenant.name ?? 'el restaurante') : null
            return (
              <li key={r.id} className="rounded-2xl bg-card p-3 shadow-tier1 ring-1 ring-line">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="flex items-start gap-3">
                    <div className="num w-16 shrink-0 text-lg font-extrabold">{hour(r.reservedAt)}</div>
                    <div>
                      <div className="font-bold">
                        {r.name} <span className="font-normal text-muted">· {r.people} {r.people === 1 ? 'persona' : 'personas'}</span>
                      </div>
                      <div className="text-xs text-muted">
                        {r.tableName ?? 'Sin mesa asignada'}
                        {r.phone ? ` · ${r.phone}` : ''}
                        {r.notes ? ` · ${r.notes}` : ''}
                      </div>
                    </div>
                  </div>
                  <Chip tone={st.tone}>{st.label}</Chip>
                </div>
                {open && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Button size="sm" icon="restaurant" onClick={() => seat(r)}>
                      Llegaron
                    </Button>
                    {r.status === 'PENDIENTE' && (
                      <Button size="sm" variant="secondary" icon="check" onClick={() => status(r, 'CONFIRMADA')}>
                        Confirmada
                      </Button>
                    )}
                    {wa && (
                      <a href={wa} target="_blank" rel="noreferrer" className="inline-flex h-9 items-center gap-1.5 rounded-xl bg-success-soft px-3 text-sm font-semibold text-success-ink">
                        <Icon name="chat" className="text-[18px]" /> WhatsApp
                      </a>
                    )}
                    <Button size="sm" variant="secondary" icon="edit" onClick={() => setEditing(r)}>
                      Editar
                    </Button>
                    <Button size="sm" variant="secondary" onClick={() => status(r, 'NO_LLEGO')}>
                      No llegó
                    </Button>
                    <Button size="sm" variant="secondary" onClick={() => status(r, 'CANCELADA')}>
                      Cancelar
                    </Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {editing && (
        <ReservationModal
          reservation={editing === 'new' ? null : editing}
          day={day}
          tables={tables}
          onClose={() => setEditing(null)}
          onSaved={(r) => {
            setEditing(null)
            toast(`Reserva de ${r.name} guardada`)
            const d = isoDay(new Date(r.reservedAt))
            if (d !== day) setDay(d)
            else load()
          }}
        />
      )}
      {seating && (
        <Modal open title={`¿En qué mesa se sienta ${seating.name}?`} onClose={() => setSeating(null)}>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {tables
              .filter((t) => t.accounts.length === 0)
              .map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => (setSeating(null), void seat(seating, t.id))}
                  className="rounded-xl border-2 border-line bg-card p-3 text-left text-sm"
                >
                  <div className="font-bold">{t.name}</div>
                  <div className="text-xs text-muted">{t.seats} puestos</div>
                </button>
              ))}
          </div>
          {tables.every((t) => t.accounts.length > 0) && <p className="text-sm text-muted">No hay mesas libres en este momento.</p>}
        </Modal>
      )}
    </div>
  )
}

function ReservationModal({
  reservation,
  day,
  tables,
  onClose,
  onSaved,
}: {
  reservation: Reservation | null
  day: string
  tables: Floor['tables']
  onClose: () => void
  onSaved: (r: Reservation) => void
}) {
  const start = reservation ? new Date(reservation.reservedAt) : null
  const [name, setName] = useState(reservation?.name ?? '')
  const [phone, setPhone] = useState(reservation?.phone ?? '')
  const [people, setPeople] = useState(String(reservation?.people ?? 2))
  const [date, setDate] = useState(start ? isoDay(start) : day)
  const [time, setTime] = useState(start ? `${String(start.getHours()).padStart(2, '0')}:${String(start.getMinutes()).padStart(2, '0')}` : '19:00')
  const [duration, setDuration] = useState(String(reservation?.durationMin ?? 90))
  const [tableId, setTableId] = useState(reservation?.tableId ? String(reservation.tableId) : '')
  const [notes, setNotes] = useState(reservation?.notes ?? '')
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState('')

  async function save() {
    setBusy(true)
    setErrors({})
    setError('')
    try {
      const body = {
        name,
        phone: phone.trim() || null,
        people: Number(people) || 1,
        reservedAt: new Date(`${date}T${time}:00`).toISOString(),
        durationMin: Number(duration) || 90,
        tableId: tableId ? Number(tableId) : null,
        notes: notes.trim() || null,
      }
      const r = reservation
        ? await api<Reservation>(`/restaurant/reservations/${reservation.id}`, { method: 'PUT', body })
        : await api<Reservation>('/restaurant/reservations', { body })
      onSaved(r)
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      } else setError('No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title={reservation ? `Reserva de ${reservation.name}` : 'Nueva reserva'}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="event_available" loading={busy} onClick={save}>
          Guardar reserva
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="A nombre de" value={name} onChange={(e) => setName(e.target.value)} error={errors.name} maxLength={120} />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Celular (para confirmar)" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} error={errors.phone} />
          <Field label="Personas" inputMode="numeric" value={people} onChange={(e) => setPeople(e.target.value.replace(/\D/g, ''))} error={errors.people} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Día" type="date" value={date} onChange={(e) => setDate(e.target.value)} error={errors.reservedAt} />
          <Field label="Hora" type="time" value={time} onChange={(e) => setTime(e.target.value)} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <SelectField label="Mesa" value={tableId} onChange={(e) => setTableId(e.target.value)} error={errors.tableId}>
            <option value="">Asignar al llegar</option>
            {tables.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({t.seats} puestos)
              </option>
            ))}
          </SelectField>
          <SelectField label="Duración" value={duration} onChange={(e) => setDuration(e.target.value)} error={errors.durationMin}>
            {[60, 90, 120, 150, 180].map((m) => (
              <option key={m} value={m}>
                {m >= 120 ? `${m / 60} horas` : `${m} min`}
              </option>
            ))}
          </SelectField>
        </div>
        <Field label="Notas (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Cumpleaños, silla para bebé, terraza…" maxLength={300} />
      </div>
    </Modal>
  )
}
