import { useState } from 'react'
import { Button, Field, Modal } from '../../components/ui'

export interface PrescriptionData {
  doctorName: string
  doctorLicense: string
  prescriptionNumber?: string
  prescriptionDate: string
  patientName: string
  patientDoc?: string
}

/**
 * Droguería: antes de cobrar medicamentos que se venden con fórmula, se registran los datos de la fórmula. Quedan con la
 * venta en el libro de fórmulas despachadas.
 */
export default function PrescriptionModal({
  medicines,
  initial,
  onClose,
  onSave,
}: {
  medicines: string[]
  initial: PrescriptionData | null
  onClose: () => void
  onSave: (p: PrescriptionData) => void
}) {
  const today = new Date().toLocaleDateString('en-CA')
  const [f, setF] = useState<PrescriptionData>(
    initial ?? { doctorName: '', doctorLicense: '', prescriptionNumber: '', prescriptionDate: today, patientName: '', patientDoc: '' },
  )
  const set = (k: keyof PrescriptionData) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const ready = f.doctorName.trim() && f.doctorLicense.trim() && f.prescriptionDate && f.patientName.trim() && f.prescriptionDate <= today

  return (
    <Modal
      open
      title="Datos de la fórmula médica"
      onClose={onClose}
      footer={
        <Button className="w-full" icon="arrow_forward" disabled={!ready} onClick={() => onSave({ ...f, doctorName: f.doctorName.trim(), patientName: f.patientName.trim() })}>
          Continuar al cobro
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted">
          Se venden con fórmula: <b>{medicines.join(', ')}</b>. Revisa la fórmula y anota sus datos.
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Médico que formula" value={f.doctorName} onChange={set('doctorName')} autoFocus />
          <Field label="Registro médico" placeholder="RM 12345" value={f.doctorLicense} onChange={set('doctorLicense')} />
          <Field label="Fecha de la fórmula" type="date" max={today} value={f.prescriptionDate} onChange={set('prescriptionDate')} />
          <Field label="N.º de la fórmula (opcional)" value={f.prescriptionNumber ?? ''} onChange={set('prescriptionNumber')} />
          <Field label="Paciente" value={f.patientName} onChange={set('patientName')} />
          <Field label="Documento del paciente (opcional)" inputMode="numeric" value={f.patientDoc ?? ''} onChange={set('patientDoc')} />
        </div>
      </div>
    </Modal>
  )
}
