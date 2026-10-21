import { useEffect, useState } from 'react'
import LegalDocView from '../../components/LegalDocView'
import { Banner, Button, Card, EmptyState, Icon, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { appUrl } from '../../lib/host'
import { dateTime } from '../../lib/format'
import { LEGAL_DOCS, LEGAL_VERSION, legalDoc } from '../../lib/legal'

interface Acceptance {
  userName: string
  email: string
  document: string
  version: string
  acceptedAt: string
  ip?: string
  context: string
}

interface Status {
  version: string
  required: string[]
  pending: string[]
  history: Acceptance[]
}

const CONTEXT: Record<string, string> = { REGISTRO: 'Al crear la cuenta', ACTUALIZACION: 'Actualización', PRIMER_INGRESO: 'Primer ingreso' }

/** Más → Legal y privacidad: los documentos vigentes dentro del POS y quién los aceptó. */
export default function LegalCenterPage() {
  const [tab, setTab] = useState<string>('TERMINOS')
  const [status, setStatus] = useState<Status | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<Status>('/legal/status').then(setStatus).catch((e) => setError(e.message))
  }, [])

  const doc = legalDoc(tab)
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="flex-1 text-2xl font-bold tracking-tight">Legal y privacidad</h1>
        <Button variant="secondary" icon="print" onClick={() => window.print()}>
          Imprimir
        </Button>
      </div>
      <p className="text-sm text-muted">
        Versión vigente {LEGAL_VERSION}. Estos son los documentos que rigen el uso de la app; los aceptaste al crear la cuenta o al entrar después de
        un cambio.
      </p>
      {error && <Banner>{error}</Banner>}
      <div className="flex gap-2 overflow-x-auto pb-1 print:hidden">
        {[...LEGAL_DOCS.map((d) => ({ k: d.key as string, l: d.short })), { k: 'ACEPTACIONES', l: 'Aceptaciones' }].map((t) => (
          <button
            key={t.k}
            type="button"
            onClick={() => setTab(t.k)}
            className={`shrink-0 rounded-full border px-3 py-1.5 text-sm ${tab === t.k ? 'border-primary bg-primary-tint font-semibold text-primary' : 'border-line text-muted hover:border-primary/40'}`}
          >
            {t.l}
          </button>
        ))}
      </div>
      {tab === 'ACEPTACIONES' ? (
        !status ? (
          <Spinner />
        ) : status.history.length === 0 ? (
          <Card>
            <EmptyState icon="verified" title="Tus aceptaciones están al día" text="El registro completo de la tienda lo ve el dueño o quien tenga permiso de auditoría." />
          </Card>
        ) : (
          <Card className="overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted uppercase">
                <tr className="border-b border-line">
                  <th className="px-3 py-2">Fecha</th>
                  <th className="px-3 py-2">Usuario</th>
                  <th className="px-3 py-2">Documento</th>
                  <th className="px-3 py-2">Versión</th>
                  <th className="px-3 py-2">Cómo</th>
                  <th className="px-3 py-2">IP</th>
                </tr>
              </thead>
              <tbody>
                {status.history.map((a, i) => (
                  <tr key={i} className="border-b border-line-soft">
                    <td className="num px-3 py-2 whitespace-nowrap">{dateTime(a.acceptedAt)}</td>
                    <td className="px-3 py-2">
                      {a.userName}
                      <div className="text-xs text-muted">{a.email}</div>
                    </td>
                    <td className="px-3 py-2">{legalDoc(a.document)?.short ?? a.document}</td>
                    <td className="num px-3 py-2 whitespace-nowrap">{a.version}</td>
                    <td className="px-3 py-2">{CONTEXT[a.context] ?? a.context}</td>
                    <td className="num px-3 py-2 text-xs text-muted">{a.ip ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )
      ) : (
        doc && (
          <Card className="p-5 sm:p-8">
            <LegalDocView doc={doc} />
            <a href={appUrl(`/legal/${doc.slug}`)} target="_blank" rel="noreferrer" className="mt-6 inline-flex items-center gap-1 text-sm font-semibold text-primary print:hidden">
              <Icon name="open_in_new" className="text-[18px]" /> Enlace público para compartir
            </a>
          </Card>
        )
      )}
    </div>
  )
}
