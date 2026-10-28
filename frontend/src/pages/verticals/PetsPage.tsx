import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Modal, SelectField, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { shortDate } from '../../lib/format'
import { useAuth } from '../../lib/auth'

interface Vaccine {
  id: number
  vaccine: string
  appliedOn: string
  nextDue?: string
}
interface Pet {
  id: number
  ownerName?: string
  ownerPhone?: string
  name: string
  species: string
  breed?: string
  sex?: string
  birthDate?: string
  notes?: string
  vaccines: Vaccine[]
  nextDue?: string
}
interface Due {
  petId: number
  petName: string
  ownerName?: string
  ownerPhone?: string
  vaccine: string
  nextDue: string
}

const wa = (phone: string | undefined, text: string) => {
  const digits = (phone ?? '').replace(/\D/g, '')
  if (digits.length < 10) return null
  return `https://wa.me/${digits.length === 10 ? '57' + digits : digits}?text=${encodeURIComponent(text)}`
}

/** Veterinaria: mascotas con su dueño y sus vacunas; las próximas dosis para recordarlas por WhatsApp. */
export default function PetsPage() {
  const toast = useToast()
  const { me } = useAuth()
  const [q, setQ] = useState('')
  const [pets, setPets] = useState<Pet[] | null>(null)
  const [due, setDue] = useState<Due[]>([])
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [vaccineFor, setVaccineFor] = useState<Pet | null>(null)

  const load = useCallback(() => {
    api<Pet[]>(`/pets?q=${encodeURIComponent(q)}`).then(setPets).catch((e) => setError(e.message))
    api<Due[]>('/pets/vaccines/due?days=15').then(setDue).catch(() => {})
  }, [q])
  useEffect(() => {
    const id = window.setTimeout(load, 250)
    return () => window.clearTimeout(id)
  }, [load])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Mascotas</h1>
          <p className="text-sm text-muted">Ficha de cada mascota con su dueño y sus vacunas. Recuerda las próximas dosis por WhatsApp.</p>
        </div>
        <Button icon="add" onClick={() => setCreating(true)}>
          Nueva mascota
        </Button>
      </div>
      {due.length > 0 && (
        <Card className="space-y-2 p-4">
          <div className="text-sm font-semibold">Vacunas por aplicar (próximos 15 días)</div>
          {due.map((d) => {
            const link = wa(d.ownerPhone, `Hola ${d.ownerName ?? ''}, te recordamos que a ${d.petName} le toca la vacuna ${d.vaccine} el ${shortDate(d.nextDue)}. Te esperamos en ${me?.tenant.name ?? 'la veterinaria'}.`)
            return (
              <div key={`${d.petId}-${d.vaccine}`} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span>
                  <b>{d.petName}</b> · {d.vaccine} · {shortDate(d.nextDue)} · {d.ownerName}
                </span>
                {link && (
                  <a href={link} target="_blank" rel="noopener noreferrer" className="font-semibold text-primary">
                    Recordar por WhatsApp
                  </a>
                )}
              </div>
            )
          })}
        </Card>
      )}
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar por mascota, dueño o celular" className="h-11 w-full rounded-xl border border-line bg-card px-3 text-sm" />
      {error && <Banner>{error}</Banner>}
      {!pets ? (
        <Spinner />
      ) : pets.length === 0 ? (
        <EmptyState icon="pets" title="Sin mascotas" text="Registra la primera mascota con su dueño y sus vacunas." />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {pets.map((p) => (
            <Card key={p.id} className="space-y-2 p-4 text-sm">
              <div className="flex items-center justify-between gap-2">
                <span className="text-base font-bold">{p.name}</span>
                <Chip>{p.species}</Chip>
              </div>
              <div className="text-muted">
                {[p.breed, p.sex, p.birthDate ? `nació ${shortDate(p.birthDate)}` : null].filter(Boolean).join(' · ')}
                <br />
                Dueño: {p.ownerName ?? '—'} {p.ownerPhone ? `· ${p.ownerPhone}` : ''}
              </div>
              <div>
                {p.vaccines.length === 0 ? (
                  <span className="text-muted">Sin vacunas registradas.</span>
                ) : (
                  p.vaccines.slice(0, 4).map((v) => (
                    <div key={v.id}>
                      {v.vaccine} · {shortDate(v.appliedOn)}
                      {v.nextDue ? ` → próxima ${shortDate(v.nextDue)}` : ''}
                    </div>
                  ))
                )}
              </div>
              <Button size="sm" variant="soft" icon="vaccines" onClick={() => setVaccineFor(p)}>
                Registrar vacuna
              </Button>
            </Card>
          ))}
        </div>
      )}
      {creating && (
        <PetModal
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false)
            toast('Mascota registrada')
            load()
          }}
        />
      )}
      {vaccineFor && (
        <VaccineModal
          pet={vaccineFor}
          onClose={() => setVaccineFor(null)}
          onSaved={() => {
            setVaccineFor(null)
            toast('Vacuna registrada')
            load()
          }}
        />
      )}
    </div>
  )
}

function PetModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({ ownerName: '', ownerPhone: '', name: '', species: 'Perro', breed: '', sex: '', birthDate: '', notes: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value })
  async function save() {
    setBusy(true)
    try {
      await api('/pets', { body: { ...f, birthDate: f.birthDate || undefined } })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields({ ...e.fields, _: Object.keys(e.fields).length ? '' : e.message })
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal open title="Nueva mascota" onClose={onClose} footer={<Button className="w-full" loading={busy} onClick={save}>Guardar</Button>}>
      <div className="grid gap-3 sm:grid-cols-2">
        {fields._ && <div className="sm:col-span-2"><Banner>{fields._}</Banner></div>}
        <Field label="Nombre de la mascota" value={f.name} onChange={set('name')} error={fields.name} autoFocus />
        <SelectField label="Especie" value={f.species} onChange={set('species')}>
          {['Perro', 'Gato', 'Ave', 'Conejo', 'Otro'].map((s) => (
            <option key={s}>{s}</option>
          ))}
        </SelectField>
        <Field label="Raza" value={f.breed} onChange={set('breed')} />
        <SelectField label="Sexo" value={f.sex} onChange={set('sex')}>
          <option value="">—</option>
          <option>Macho</option>
          <option>Hembra</option>
        </SelectField>
        <Field label="Fecha de nacimiento" type="date" value={f.birthDate} onChange={set('birthDate')} error={fields.birthDate} />
        <Field label="Dueño" value={f.ownerName} onChange={set('ownerName')} error={fields.ownerName} />
        <Field label="Celular del dueño" inputMode="tel" value={f.ownerPhone} onChange={set('ownerPhone')} hint="Para recordarle las vacunas" />
        <Field label="Notas" value={f.notes} onChange={set('notes')} placeholder="Alergias, carácter…" />
      </div>
    </Modal>
  )
}

function VaccineModal({ pet, onClose, onSaved }: { pet: Pet; onClose: () => void; onSaved: () => void }) {
  const today = new Date().toLocaleDateString('en-CA')
  const [f, setF] = useState({ vaccine: '', appliedOn: today, nextDue: '', notes: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  async function save() {
    setBusy(true)
    try {
      await api(`/pets/${pet.id}/vaccines`, { body: { ...f, nextDue: f.nextDue || undefined } })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields(e.fields)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal open title={`Vacuna de ${pet.name}`} onClose={onClose} footer={<Button className="w-full" loading={busy} onClick={save}>Guardar</Button>}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Vacuna" placeholder="Rabia, triple felina…" value={f.vaccine} onChange={set('vaccine')} error={fields.vaccine} autoFocus />
        <Field label="Aplicada el" type="date" max={today} value={f.appliedOn} onChange={set('appliedOn')} error={fields.appliedOn} />
        <Field label="Próxima dosis" type="date" min={today} value={f.nextDue} onChange={set('nextDue')} error={fields.nextDue} />
        <Field label="Notas" value={f.notes} onChange={set('notes')} />
      </div>
    </Modal>
  )
}
