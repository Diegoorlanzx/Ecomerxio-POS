import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Banner, Button, Field, Icon, Spinner, TextArea } from '../../components/ui'
import { dateTime } from '../../lib/format'
import { appUrl } from '../../lib/host'
import { publicApi } from '../../lib/onlineTypes'
import { dayLabel, type PublicQuote } from '../../lib/quoteTypes'
import QuoteDocument from '../quotes/QuoteDocument'

/** /c/:token — el cliente ve la cotización o prefactura sin cuenta, la acepta o confirma sus datos. */
export default function QuotePublicPage() {
  const { token } = useParams()
  const [q, setQ] = useState<PublicQuote | null>(null)
  const [error, setError] = useState('')
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  const [mode, setMode] = useState<'accept' | 'reject' | 'wrong' | null>(null)
  const [busy, setBusy] = useState(false)
  const [fieldError, setFieldError] = useState('')

  useEffect(() => {
    publicApi<PublicQuote>(`/quotes/${token}`)
      .then(setQ)
      .catch((e) => setError(e.message))
  }, [token])

  async function send(path: string, body: unknown) {
    setBusy(true)
    setFieldError('')
    try {
      setQ(await publicApi<PublicQuote>(`/quotes/${token}/${path}`, body))
      setMode(null)
    } catch (e) {
      const err = e as { message: string; fields?: Record<string, string> }
      setFieldError(Object.values(err.fields ?? {})[0] ?? err.message)
    } finally {
      setBusy(false)
    }
  }

  if (error) {
    return (
      <div className="mx-auto max-w-md p-6">
        <Banner>{error}</Banner>
      </div>
    )
  }
  if (!q) return <Spinner />

  const pf = q.kind === 'PREFACTURA'
  const open = pf ? ['BORRADOR', 'APROBADA'].includes(q.status) : ['BORRADOR', 'ENVIADA'].includes(q.status)
  const wa = (q.storeWhatsapp ?? q.storePhone ?? '').replace(/\D/g, '')
  const waLink = wa ? `https://wa.me/${wa.length === 10 ? `57${wa}` : wa}?text=${encodeURIComponent(`Hola, te escribo por la ${pf ? 'prefactura' : 'cotización'} ${q.code}`)}` : null

  return (
    <div className="min-h-dvh bg-canvas px-4 py-6">
      <div className="mx-auto max-w-3xl space-y-4">
        <QuoteDocument q={q} />

        <div className="space-y-3 rounded-2xl border border-line bg-card p-5 shadow-tier1 print:hidden">
          {/* ---------------- Cotización */}
          {!pf && q.status === 'VENCIDA' && (
            <Banner tone="warning">Esta cotización venció el {dayLabel(q.validUntil)}. Escríbele a la tienda para pedir una actualizada.</Banner>
          )}
          {!pf && q.status === 'ACEPTADA' && (
            <Banner tone="success">
              Aceptaste esta cotización{q.decidedBy ? ` (${q.decidedBy.replace(' (enlace)', '')})` : ''}. La tienda ya fue avisada y te contactará.
              <span className="block text-xs">{dateTime(q.decidedAt)}</span>
            </Banner>
          )}
          {!pf && q.status === 'RECHAZADA' && <Banner tone="warning">Esta cotización fue rechazada.</Banner>}
          {!pf && ['CONVERTIDA', 'COBRADA'].includes(q.status) && <Banner tone="success">Esta cotización ya fue aceptada y está en proceso.</Banner>}
          {!pf && open && !mode && (
            <>
              <div className="text-sm">
                ¿Estás de acuerdo con esta cotización?{q.validUntil ? ` Es válida hasta el ${dayLabel(q.validUntil)}.` : ''}
              </div>
              <div className="flex flex-wrap gap-2">
                <Button icon="thumb_up" className="flex-1" onClick={() => setMode('accept')}>
                  Aceptar cotización
                </Button>
                <Button variant="secondary" icon="thumb_down" onClick={() => setMode('reject')}>
                  No me interesa
                </Button>
              </div>
            </>
          )}
          {!pf && (mode === 'accept' || mode === 'reject') && (
            <div className="space-y-3">
              <Field label="Tu nombre" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" error={fieldError && !name.trim() ? fieldError : undefined} />
              <TextArea label={mode === 'accept' ? 'Comentario (opcional)' : '¿Por qué? (opcional)'} value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
              {fieldError && name.trim() && <Banner>{fieldError}</Banner>}
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => setMode(null)}>
                  Volver
                </Button>
                <Button className="flex-1" loading={busy} disabled={!name.trim()} onClick={() => send('decision', { accepted: mode === 'accept', name, note })}>
                  {mode === 'accept' ? 'Confirmar aceptación' : 'Enviar respuesta'}
                </Button>
              </div>
              {mode === 'accept' && (
                <p className="text-xs text-muted">
                  Al aceptar quedan registrados tu nombre, la fecha y la hora como constancia de la aceptación de esta oferta. La tienda te contactará para
                  el pago y la entrega.
                </p>
              )}
            </div>
          )}

          {/* ---------------- Prefactura */}
          {pf && q.status === 'FACTURADA' && <Banner tone="success">Tu factura ya fue expedida con estos datos.</Banner>}
          {pf && open && q.clientCheck === 'CONFIRMADO' && !mode && (
            <Banner tone="success">
              Confirmaste que tus datos están correctos. ¡Gracias!
              <span className="block text-xs">{dateTime(q.clientCheckAt)}</span>
            </Banner>
          )}
          {pf && open && q.clientCheck === 'CON_ERROR' && !mode && (
            <Banner tone="warning">Nos avisaste de un error. La tienda lo corregirá antes de facturar.</Banner>
          )}
          {pf && open && !mode && (
            <>
              <div className="text-sm">
                <span className="font-semibold">Revisa tus datos antes de que te facturen:</span> nombre o razón social, documento, dígito de verificación y
                correo donde recibirás la factura electrónica.
              </div>
              <div className="flex flex-wrap gap-2">
                <Button icon="verified" className="flex-1" loading={busy} onClick={() => send('check', { correct: true })}>
                  Mis datos están correctos
                </Button>
                <Button variant="secondary" icon="report" onClick={() => setMode('wrong')}>
                  Hay un error
                </Button>
              </div>
            </>
          )}
          {pf && mode === 'wrong' && (
            <div className="space-y-3">
              <TextArea label="¿Qué dato está mal y cuál es el correcto?" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} error={fieldError} />
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => setMode(null)}>
                  Volver
                </Button>
                <Button className="flex-1" loading={busy} disabled={!note.trim()} onClick={() => send('check', { correct: false, note })}>
                  Avisar a la tienda
                </Button>
              </div>
            </div>
          )}

          <div className="flex flex-wrap gap-2 border-t border-line-soft pt-3">
            <Button variant="ghost" icon="print" onClick={() => window.print()}>
              Descargar PDF
            </Button>
            {waLink && (
              <a href={waLink} target="_blank" rel="noreferrer">
                <Button variant="ghost" icon="chat">
                  Escribir a la tienda
                </Button>
              </a>
            )}
          </div>
          <p className="text-[11px] text-muted">
            Tus datos los trata {q.storeName} para atender esta {pf ? 'compra y expedir tu factura' : 'cotización'}. La plataforma Ecomerxio POS los
            procesa por cuenta de la tienda.{' '}
            <a href={appUrl('/legal/privacidad')} target="_blank" rel="noreferrer" className="underline">
              Privacidad
            </a>
          </p>
        </div>
        <p className="flex items-center justify-center gap-1 text-center text-xs text-muted print:hidden">
          <Icon name="bolt" className="text-[16px]" /> Hecho con Ecomerxio POS
        </p>
      </div>
    </div>
  )
}
