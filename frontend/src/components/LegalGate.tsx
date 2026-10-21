import { useState } from 'react'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { APP_NAME } from '../lib/brand'
import { asset } from '../lib/host'
import { LEGAL_VERSION, legalDoc } from '../lib/legal'
import LegalDocView from './LegalDocView'
import { Banner, Button, Icon } from './ui'

/**
 * Pantalla que no deja usar la app hasta aceptar los documentos de la versión vigente: al dueño los cinco (es su
 * contrato), a los empleados la autorización de sus datos. Se ve la primera vez y cada vez que cambian.
 */
export default function LegalGate({ pending }: { pending: string[] }) {
  const { me, refresh, signOut } = useAuth()
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const [open, setOpen] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const docs = pending.map((k) => legalDoc(k)).filter((d) => !!d)
  const all = docs.every((d) => checked[d.key])
  const firstTime = !me?.owner

  async function accept() {
    setBusy(true)
    setError('')
    try {
      await api('/legal/accept', { body: { documents: pending, version: LEGAL_VERSION } })
      await refresh()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar. Revisa tu conexión.')
      if (e instanceof ApiError && e.status === 409) setTimeout(() => window.location.reload(), 2500)
    } finally {
      setBusy(false)
    }
  }

  const viewing = open ? legalDoc(open) : null

  return (
    <div className="min-h-dvh bg-canvas px-4 py-8">
      <div className="mx-auto max-w-2xl space-y-5">
        <div className="flex items-center gap-2.5">
          <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-9 w-9" />
          <span className="text-lg font-bold tracking-tight">{APP_NAME}</span>
        </div>
        <div className="rounded-2xl border border-line bg-card p-5 shadow-tier1 sm:p-6">
          <h1 className="text-xl font-bold tracking-tight">{firstTime ? 'Antes de empezar' : 'Actualizamos nuestros documentos'}</h1>
          <p className="mt-1 text-sm text-muted">
            {firstTime
              ? 'Para usar la app necesitamos tu autorización para tratar tus datos personales.'
              : 'Para seguir usando la app, lee y acepta la versión vigente. Ábrelos para ver el resumen y el texto completo.'}
          </p>
          {error && (
            <div className="mt-3">
              <Banner>{error}</Banner>
            </div>
          )}
          <ul className="mt-4 space-y-2">
            {docs.map((d) => (
              <li key={d.key} className="flex items-start gap-3 rounded-xl border border-line p-3">
                <input
                  id={`legal-${d.key}`}
                  type="checkbox"
                  checked={!!checked[d.key]}
                  onChange={(e) => setChecked((c) => ({ ...c, [d.key]: e.target.checked }))}
                  className="mt-1 h-5 w-5 accent-[var(--color-primary)]"
                />
                <label htmlFor={`legal-${d.key}`} className="min-w-0 flex-1 text-sm">
                  {d.key === 'PRIVACIDAD' ? 'Autorizo el tratamiento de mis datos según la ' : 'Leí y acepto: '}
                  <span className="font-semibold">{d.title}</span>
                </label>
                <button type="button" onClick={() => setOpen(d.key)} className="shrink-0 text-sm font-semibold text-primary hover:underline">
                  Leer
                </button>
              </li>
            ))}
          </ul>
          <div className="mt-5 flex flex-wrap gap-2">
            <Button variant="ghost" icon="logout" onClick={() => signOut()}>
              Salir
            </Button>
            <span className="flex-1" />
            <Button icon="check" disabled={!all} loading={busy} onClick={accept}>
              Aceptar y continuar
            </Button>
          </div>
          <p className="mt-3 text-xs text-muted">
            Guardamos la fecha, la hora y el equipo desde donde aceptas, como prueba para ti y para nosotros.
          </p>
        </div>
      </div>
      {viewing && (
        <div className="fixed inset-0 z-[70] flex items-end justify-center bg-navy/60 sm:items-center sm:p-4" onClick={() => setOpen(null)}>
          <div
            role="dialog"
            aria-modal
            aria-label={viewing.title}
            onClick={(e) => e.stopPropagation()}
            className="flex max-h-[92vh] w-full flex-col rounded-t-3xl border border-slate-300 bg-card shadow-tier3 sm:max-w-3xl sm:rounded-2xl"
          >
            <div className="flex items-center justify-between border-b border-line-soft px-5 py-4">
              <h2 className="text-lg font-semibold tracking-tight">{viewing.title}</h2>
              <button onClick={() => setOpen(null)} aria-label="Cerrar" className="flex h-10 w-10 items-center justify-center rounded-xl text-muted hover:bg-line-soft">
                <Icon name="close" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <LegalDocView doc={viewing} compact />
            </div>
            <div className="flex gap-2 border-t border-line-soft px-5 py-4 pb-safe">
              <Button
                className="flex-1"
                icon="check"
                onClick={() => {
                  setChecked((c) => ({ ...c, [viewing.key]: true }))
                  setOpen(null)
                }}
              >
                {viewing.key === 'PRIVACIDAD' ? 'Autorizo' : 'Acepto'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
