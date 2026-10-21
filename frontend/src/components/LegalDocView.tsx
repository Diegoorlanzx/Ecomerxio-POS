import { LEGAL_DATE, LEGAL_VERSION, type LegalDoc } from '../lib/legal'
import { Icon } from './ui'

/** Un documento legal completo: resumen en lenguaje sencillo + texto con las cláusulas importantes destacadas. */
export default function LegalDocView({ doc, compact = false }: { doc: LegalDoc; compact?: boolean }) {
  return (
    <article className="legal-doc space-y-5 text-sm leading-relaxed">
      {!compact && (
        <header>
          <h1 className="text-2xl font-bold tracking-tight">{doc.title}</h1>
          <p className="mt-1 text-xs text-muted">
            Versión {LEGAL_VERSION} · vigente desde el {LEGAL_DATE}
          </p>
        </header>
      )}
      <div className="rounded-2xl border border-blue-200 bg-primary-tint p-4">
        <div className="mb-2 flex items-center gap-2 font-semibold text-primary">
          <Icon name="lightbulb" className="text-[20px]" /> En pocas palabras
        </div>
        <ul className="list-disc space-y-1 pl-5">
          {doc.summary.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ul>
        <p className="mt-2 text-xs text-muted">Este resumen ayuda a entender el documento, pero lo que vale es el texto completo.</p>
      </div>
      {doc.sections.map((s) => (
        <section key={s.title} className="space-y-2">
          <h2 className="text-base font-semibold">{s.title}</h2>
          {s.blocks.map((b, i) =>
            Array.isArray(b) ? (
              <ul key={i} className="list-disc space-y-1 pl-5">
                {b.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            ) : b.startsWith('!') ? (
              <p key={i} className="rounded-xl border border-warning/40 bg-warning-soft p-3 font-semibold text-warning-ink">
                {b.slice(1)}
              </p>
            ) : (
              <p key={i}>{b}</p>
            ),
          )}
        </section>
      ))}
    </article>
  )
}
