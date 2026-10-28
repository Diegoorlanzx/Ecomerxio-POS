import { useCallback, useEffect, useState } from 'react'
import { Banner, Card, Chip, EmptyState, Field, Spinner, Toggle } from '../../components/ui'
import { api } from '../../lib/api'
import { dateTime, download } from '../../lib/format'

interface Row {
  saleId: number
  saleNumber?: number
  soldAt?: string
  sellerName?: string
  doctorName?: string
  doctorLicense?: string
  prescriptionNumber?: string
  prescriptionDate?: string
  patientName?: string
  patientDoc?: string
  controlled: boolean
  products?: string
}

const iso = (d: Date) => d.toLocaleDateString('en-CA')

/** Droguería: libro de fórmulas despachadas (las ventas de medicamentos con fórmula), con filtro de control especial. */
export default function PrescriptionsPage() {
  const today = new Date()
  const [from, setFrom] = useState(iso(new Date(today.getFullYear(), today.getMonth(), 1)))
  const [to, setTo] = useState(iso(today))
  const [onlyControlled, setOnlyControlled] = useState(false)
  const [rows, setRows] = useState<Row[] | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    setRows(null)
    setError('')
    api<Row[]>(`/pharmacy/prescriptions?from=${from}&to=${to}&controlled=${onlyControlled}`)
      .then(setRows)
      .catch((e) => setError(e.message))
  }, [from, to, onlyControlled])
  useEffect(load, [load])

  function exportCsv() {
    if (!rows) return
    const q = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`
    const head = ['Venta', 'Fecha', 'Vendedor', 'Médico', 'Registro médico', 'N.º fórmula', 'Fecha fórmula', 'Paciente', 'Documento', 'Control especial', 'Medicamentos']
    const lines = rows.map((r) =>
      [r.saleNumber, r.soldAt ? dateTime(r.soldAt) : '', r.sellerName, r.doctorName, r.doctorLicense, r.prescriptionNumber, r.prescriptionDate, r.patientName, r.patientDoc, r.controlled ? 'Sí' : 'No', r.products]
        .map(q)
        .join(';'),
    )
    download(`formulas_${from}_${to}.csv`, new Blob(['﻿' + [head.map(q).join(';'), ...lines].join('\n')], { type: 'text/csv;charset=utf-8' }))
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Fórmulas despachadas</h1>
        <p className="text-sm text-muted">Cada venta de medicamentos con fórmula, con el médico, el paciente y lo que se entregó. Útil para las visitas de control.</p>
      </div>
      <Card className="flex flex-wrap items-end gap-3 p-4">
        <Field label="Desde" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        <Field label="Hasta" type="date" value={to} min={from} max={iso(today)} onChange={(e) => setTo(e.target.value)} />
        <div className="min-w-[220px] flex-1">
          <Toggle checked={onlyControlled} onChange={setOnlyControlled} label="Solo control especial" />
        </div>
        <button type="button" onClick={exportCsv} disabled={!rows?.length} className="h-11 rounded-xl border border-line px-4 text-sm font-semibold disabled:opacity-50">
          Descargar CSV
        </button>
      </Card>
      {error && <Banner>{error}</Banner>}
      {!rows && !error && <Spinner />}
      {rows && rows.length === 0 && <EmptyState icon="prescriptions" title="Sin fórmulas en estas fechas" text="Aparecen al vender medicamentos marcados “se vende con fórmula médica”." />}
      {rows && rows.length > 0 && (
        <div className="space-y-2">
          {rows.map((r) => (
            <Card key={r.saleId} className="space-y-1 p-4 text-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-semibold">
                  Venta #{r.saleNumber ?? r.saleId} · {r.soldAt ? dateTime(r.soldAt) : ''}
                </span>
                {r.controlled && <Chip tone="warning">Control especial</Chip>}
              </div>
              <div>{r.products}</div>
              <div className="text-muted">
                Médico: {r.doctorName ?? '—'} ({r.doctorLicense ?? 'sin registro'}) · Fórmula {r.prescriptionNumber ? `#${r.prescriptionNumber} ` : ''}
                del {r.prescriptionDate ?? '—'} · Paciente: {r.patientName ?? '—'}
                {r.patientDoc ? ` (${r.patientDoc})` : ''} · Vendió: {r.sellerName ?? '—'}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
