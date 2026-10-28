import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, EmptyState, Field, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { shortDate } from '../../lib/format'

interface Optical {
  id: number
  patientName: string
  patientDoc?: string
  patientPhone?: string
  examDate: string
  optometrist?: string
  odSphere?: number
  odCylinder?: number
  odAxis?: number
  oiSphere?: number
  oiCylinder?: number
  oiAxis?: number
  addition?: number
  pupillaryDistance?: number
  lensType?: string
  notes?: string
}

const sign = (v?: number) => (v === undefined || v === null ? '—' : `${v > 0 ? '+' : ''}${Number(v).toFixed(2)}`)

/** Óptica: fórmula de cada paciente (ojo derecho OD e izquierdo OI) para los trabajos de laboratorio. */
export default function OpticalPage() {
  const toast = useToast()
  const [q, setQ] = useState('')
  const [rows, setRows] = useState<Optical[] | null>(null)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)

  const load = useCallback(() => {
    api<Optical[]>(`/optical-prescriptions?q=${encodeURIComponent(q)}`).then(setRows).catch((e) => setError(e.message))
  }, [q])
  useEffect(() => {
    const id = window.setTimeout(load, 250)
    return () => window.clearTimeout(id)
  }, [load])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Fórmulas ópticas</h1>
          <p className="text-sm text-muted">
            La fórmula de cada paciente. Para el trabajo del laboratorio crea una orden en <Link to="/ordenes-servicio" className="text-primary">Trabajos de laboratorio</Link>.
          </p>
        </div>
        <Button icon="add" onClick={() => setCreating(true)}>
          Nueva fórmula
        </Button>
      </div>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar por paciente o documento" className="h-11 w-full rounded-xl border border-line bg-card px-3 text-sm" />
      {error && <Banner>{error}</Banner>}
      {!rows ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <EmptyState icon="visibility" title="Sin fórmulas" text="Registra la fórmula del examen visual de cada paciente." />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {rows.map((r) => (
            <Card key={r.id} className="space-y-2 p-4 text-sm">
              <div className="flex justify-between gap-2">
                <span className="font-bold">{r.patientName}</span>
                <span className="text-muted">{shortDate(r.examDate)}</span>
              </div>
              <table className="w-full text-center tabular-nums">
                <thead className="text-xs text-muted">
                  <tr>
                    <th />
                    <th>Esfera</th>
                    <th>Cilindro</th>
                    <th>Eje</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td className="text-left font-semibold">OD</td>
                    <td>{sign(r.odSphere)}</td>
                    <td>{sign(r.odCylinder)}</td>
                    <td>{r.odAxis ?? '—'}°</td>
                  </tr>
                  <tr>
                    <td className="text-left font-semibold">OI</td>
                    <td>{sign(r.oiSphere)}</td>
                    <td>{sign(r.oiCylinder)}</td>
                    <td>{r.oiAxis ?? '—'}°</td>
                  </tr>
                </tbody>
              </table>
              <div className="text-muted">
                Adición {sign(r.addition)} · DP {r.pupillaryDistance ?? '—'} mm{r.lensType ? ` · ${r.lensType}` : ''}
                {r.optometrist ? ` · ${r.optometrist}` : ''}
              </div>
            </Card>
          ))}
        </div>
      )}
      {creating && (
        <OpticalModal
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false)
            toast('Fórmula guardada')
            load()
          }}
        />
      )}
    </div>
  )
}

function OpticalModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const today = new Date().toLocaleDateString('en-CA')
  const [f, setF] = useState<Record<string, string>>({ patientName: '', patientDoc: '', patientPhone: '', examDate: today, optometrist: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const num = (v?: string) => (v === undefined || v.trim() === '' ? undefined : Number(v.replace(',', '.')))
  async function save() {
    setBusy(true)
    try {
      await api('/optical-prescriptions', {
        body: {
          ...f,
          odSphere: num(f.odSphere),
          odCylinder: num(f.odCylinder),
          odAxis: num(f.odAxis),
          oiSphere: num(f.oiSphere),
          oiCylinder: num(f.oiCylinder),
          oiAxis: num(f.oiAxis),
          addition: num(f.addition),
          pupillaryDistance: num(f.pupillaryDistance),
        },
      })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields(e.fields)
    } finally {
      setBusy(false)
    }
  }
  const eye = (prefix: 'od' | 'oi', label: string) => (
    <div className="grid grid-cols-3 gap-2">
      <Field label={`${label} esfera`} inputMode="decimal" value={f[`${prefix}Sphere`] ?? ''} onChange={set(`${prefix}Sphere`)} error={fields[`${prefix}Sphere`]} />
      <Field label="Cilindro" inputMode="decimal" value={f[`${prefix}Cylinder`] ?? ''} onChange={set(`${prefix}Cylinder`)} error={fields[`${prefix}Cylinder`]} />
      <Field label="Eje (0–180)" inputMode="numeric" value={f[`${prefix}Axis`] ?? ''} onChange={set(`${prefix}Axis`)} error={fields[`${prefix}Axis`]} />
    </div>
  )
  return (
    <Modal open wide title="Nueva fórmula óptica" onClose={onClose} footer={<Button className="w-full" loading={busy} onClick={save}>Guardar</Button>}>
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Paciente" value={f.patientName} onChange={set('patientName')} error={fields.patientName} autoFocus />
          <Field label="Documento" value={f.patientDoc} onChange={set('patientDoc')} />
          <Field label="Celular" inputMode="tel" value={f.patientPhone} onChange={set('patientPhone')} />
          <Field label="Fecha del examen" type="date" max={today} value={f.examDate} onChange={set('examDate')} error={fields.examDate} />
          <Field label="Optómetra" value={f.optometrist} onChange={set('optometrist')} />
          <Field label="Tipo de lente" placeholder="Progresivo, monofocal…" value={f.lensType ?? ''} onChange={set('lensType')} />
        </div>
        {eye('od', 'OD')}
        {eye('oi', 'OI')}
        <div className="grid grid-cols-2 gap-2">
          <Field label="Adición" inputMode="decimal" value={f.addition ?? ''} onChange={set('addition')} error={fields.addition} />
          <Field label="Distancia pupilar (mm)" inputMode="decimal" value={f.pupillaryDistance ?? ''} onChange={set('pupillaryDistance')} />
        </div>
      </div>
    </Modal>
  )
}
