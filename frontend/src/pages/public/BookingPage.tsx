import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Banner, Button, Field, Icon, Spinner } from '../../components/ui'
import { money } from '../../lib/format'
import { publicApi, PublicError } from '../../lib/onlineTypes'
import { addDays, ampm, longDate, todayIso, type SlotDto } from '../../lib/servicesTypes'

interface BookingInfo {
  storeName: string
  services: { id: number; name: string; minutes: number; price: number }[]
  staff: { id: number; name: string; color: string; serviceIds: number[] }[]
  maxDays: number
}

interface BookingDone {
  id: number
  serviceName: string
  staffName: string
  start: string
  storeName: string
}

/** El cliente pide su cita desde el celular: servicio → con quién → día y hora libre → sus datos. */
export default function BookingPage() {
  const { slug = '' } = useParams()
  const [info, setInfo] = useState<BookingInfo | null>(null)
  const [error, setError] = useState('')
  const [serviceId, setServiceId] = useState<number | null>(null)
  const [staffId, setStaffId] = useState<number | null>(null) // null = cualquiera
  const [day, setDay] = useState(todayIso())
  const [slots, setSlots] = useState<SlotDto[] | null>(null)
  const [time, setTime] = useState('')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({})
  const [done, setDone] = useState<BookingDone | null>(null)
  const base = `/stores/${encodeURIComponent(slug)}/booking`

  useEffect(() => {
    publicApi<BookingInfo>(base)
      .then((i) => {
        setInfo(i)
        document.title = `Pide tu cita · ${i.storeName}`
      })
      .catch((e) => setError(e.message))
  }, [base])

  useEffect(() => {
    if (!serviceId) return
    setSlots(null)
    setTime('')
    publicApi<SlotDto[]>(`${base}/slots?serviceId=${serviceId}&date=${day}${staffId ? `&staffId=${staffId}` : ''}`)
      .then(setSlots)
      .catch(() => setSlots([]))
  }, [base, serviceId, staffId, day])

  const people = useMemo(
    () => (info?.staff ?? []).filter((s) => !serviceId || s.serviceIds.length === 0 || s.serviceIds.includes(serviceId)),
    [info, serviceId],
  )
  const days = useMemo(() => Array.from({ length: 14 }, (_, i) => addDays(todayIso(), i)), [])

  async function book() {
    setBusy(true)
    setFieldErr({})
    setError('')
    try {
      setDone(
        await publicApi<BookingDone>(base, {
          serviceId,
          staffId,
          date: day,
          time,
          name,
          phone,
          notes,
        }),
      )
    } catch (e) {
      if (e instanceof PublicError) {
        setFieldErr(e.fields ?? {})
        setError(e.message)
      } else setError('No se pudo pedir la cita')
    } finally {
      setBusy(false)
    }
  }

  if (error && !info) return <div className="mx-auto max-w-md p-6 pt-20 text-center font-semibold">{error}</div>
  if (!info) return <Spinner label="Cargando…" />

  if (done)
    return (
      <div className="min-h-dvh bg-canvas px-4 py-10">
        <div className="mx-auto max-w-md space-y-4 rounded-2xl border border-line bg-card p-6 text-center shadow-tier1">
          <Icon name="event_available" className="text-[48px] text-success" />
          <h1 className="text-2xl font-bold">¡Cita pedida!</h1>
          <p>
            <b>{done.serviceName}</b> con {done.staffName}
            <br />
            <span className="first-letter:uppercase">{longDate(done.start.slice(0, 10))}</span> a las {ampm(done.start.slice(11, 16))}
          </p>
          <p className="text-sm text-muted">{done.storeName} te escribirá por WhatsApp para recordártela. Si no puedes ir, avísales.</p>
          <Link to={`/t/${slug}`} className="inline-block text-sm font-semibold text-primary">
            Ver la tienda
          </Link>
        </div>
      </div>
    )

  const service = info.services.find((s) => s.id === serviceId)
  return (
    <div className="min-h-dvh bg-canvas px-4 py-6">
      <div className="mx-auto max-w-md space-y-5">
        <div className="text-center">
          <div className="text-xs font-bold tracking-wide text-muted uppercase">{info.storeName}</div>
          <h1 className="text-2xl font-bold">Pide tu cita</h1>
        </div>

        <section>
          <h2 className="mb-2 text-sm font-semibold">1. ¿Qué servicio?</h2>
          {info.services.length === 0 && <Banner tone="warning">Por ahora no hay servicios para agendar.</Banner>}
          <div className="space-y-2">
            {info.services.map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => (setServiceId(s.id), setStaffId(null))}
                className={`flex w-full items-center justify-between rounded-xl border-2 bg-card p-3 text-left ${serviceId === s.id ? 'border-primary' : 'border-line'}`}
              >
                <span>
                  <span className="block font-semibold">{s.name}</span>
                  <span className="text-xs text-muted">{s.minutes} min</span>
                </span>
                <span className="num font-bold">{money(s.price)}</span>
              </button>
            ))}
          </div>
        </section>

        {service && (
          <section>
            <h2 className="mb-2 text-sm font-semibold">2. ¿Con quién?</h2>
            <div className="flex flex-wrap gap-2">
              <Chip on={staffId === null} onClick={() => setStaffId(null)}>
                Con quien esté libre
              </Chip>
              {people.map((p) => (
                <Chip key={p.id} on={staffId === p.id} onClick={() => setStaffId(p.id)}>
                  <span className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full" style={{ background: p.color }} />
                  {p.name}
                </Chip>
              ))}
            </div>
          </section>
        )}

        {service && (
          <section>
            <h2 className="mb-2 text-sm font-semibold">3. ¿Qué día y a qué hora?</h2>
            <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1">
              {days.map((d) => {
                const date = new Date(d + 'T12:00:00')
                return (
                  <button
                    key={d}
                    type="button"
                    onClick={() => setDay(d)}
                    className={`flex w-14 shrink-0 flex-col items-center rounded-xl border-2 bg-card py-2 ${day === d ? 'border-primary' : 'border-line'}`}
                  >
                    <span className="text-[11px] text-muted uppercase">{date.toLocaleDateString('es-CO', { weekday: 'short' })}</span>
                    <span className="text-lg font-bold">{date.getDate()}</span>
                  </button>
                )
              })}
            </div>
            <div className="mt-3">
              {slots === null ? (
                <Spinner label="Buscando horas libres…" />
              ) : slots.length === 0 ? (
                <p className="text-sm text-muted">No hay horas libres ese día. Prueba otro.</p>
              ) : (
                <div className="grid grid-cols-4 gap-2">
                  {slots.map((s) => (
                    <button
                      key={s.time}
                      type="button"
                      onClick={() => setTime(s.time)}
                      className={`rounded-lg border-2 py-2 text-sm font-semibold ${time === s.time ? 'border-primary bg-primary text-white' : 'border-line bg-card'}`}
                    >
                      {ampm(s.time)}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </section>
        )}

        {time && (
          <section className="space-y-3">
            <h2 className="text-sm font-semibold">4. Tus datos</h2>
            <Field label="Nombre" value={name} onChange={(e) => setName(e.target.value)} error={fieldErr.name} autoComplete="name" />
            <Field
              label="Celular (WhatsApp)"
              inputMode="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              error={fieldErr.phone}
              autoComplete="tel"
            />
            <Field label="Algo que debamos saber (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={300} />
            {error && <Banner>{error}</Banner>}
            <Button className="w-full" size="lg" icon="event_available" loading={busy} onClick={book}>
              Pedir cita · {ampm(time)}
            </Button>
            <p className="text-center text-xs text-muted">Tus datos solo los usa {info.storeName} para tu cita.</p>
          </section>
        )}
      </div>
    </div>
  )
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex items-center rounded-xl border-2 bg-card px-3 py-2 text-sm font-semibold ${on ? 'border-primary' : 'border-line'}`}
    >
      {children}
    </button>
  )
}
