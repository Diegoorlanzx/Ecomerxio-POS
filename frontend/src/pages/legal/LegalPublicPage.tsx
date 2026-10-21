import { Link, Navigate, useParams } from 'react-router-dom'
import LegalDocView from '../../components/LegalDocView'
import { Button, Icon } from '../../components/ui'
import { APP_NAME } from '../../lib/brand'
import { asset } from '../../lib/host'
import { LEGAL_DOCS, legalDoc } from '../../lib/legal'

/** /legal/:slug — pública (se enlaza desde el registro, la landing y el pie de los documentos). */
export default function LegalPublicPage() {
  const { slug } = useParams()
  const doc = legalDoc(slug ?? 'terminos')
  if (!doc) return <Navigate to="/legal/terminos" replace />
  return (
    <div className="min-h-dvh bg-canvas">
      <header className="border-b border-line bg-card print:hidden">
        <div className="mx-auto flex max-w-4xl items-center gap-3 px-4 py-3">
          <Link to="/" className="flex items-center gap-2">
            <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-8 w-8" />
            <span className="font-bold tracking-tight">{APP_NAME}</span>
          </Link>
          <span className="flex-1" />
          <Button variant="secondary" icon="print" onClick={() => window.print()}>
            Imprimir
          </Button>
        </div>
      </header>
      <div className="mx-auto grid max-w-4xl gap-6 px-4 py-6 md:grid-cols-[200px_1fr]">
        <nav className="space-y-1 print:hidden" aria-label="Documentos legales">
          {LEGAL_DOCS.map((d) => (
            <Link
              key={d.key}
              to={`/legal/${d.slug}`}
              className={`flex items-center gap-2 rounded-xl px-3 py-2 text-sm ${d.key === doc.key ? 'bg-primary-tint font-semibold text-primary' : 'text-muted hover:bg-line-soft'}`}
            >
              <Icon name="description" className="text-[18px]" />
              {d.short}
            </Link>
          ))}
        </nav>
        <div className="rounded-2xl border border-line bg-card p-5 shadow-tier1 sm:p-8">
          <LegalDocView doc={doc} />
        </div>
      </div>
    </div>
  )
}
