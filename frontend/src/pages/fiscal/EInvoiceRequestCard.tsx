import { useCallback, useEffect, useRef, useState } from 'react'
import { Banner, Button, Card, Chip, Icon, Spinner, useToast } from '../../components/ui'
import { api, apiUpload, ApiError } from '../../lib/api'
import { whatsappLink } from '../../lib/brand'
import { dateTime } from '../../lib/format'

type Kind = 'RUT' | 'CEDULA' | 'CAMARA' | 'OTRO'
type Status = 'ENVIADA' | 'CORREGIR' | 'APROBADA' | 'ACTIVA'

interface RequestView {
  status?: Status | null
  adminComment?: string
  submittedAt?: string
  reviewedAt?: string
  documents: { id: number; kind: Kind; filename: string; contentType: string; sizeBytes: number; uploadedAt: string }[]
  requiredKinds: Kind[]
  missing: string[]
  canSubmit: boolean
  einvoiceAddon: boolean
}

const KIND_LABEL: Record<Kind, { title: string; hint: string }> = {
  RUT: { title: 'RUT actualizado', hint: 'PDF descargado de la DIAN, con la responsabilidad de facturador si ya la tienes.' },
  CEDULA: { title: 'Cédula del representante legal', hint: 'Por ambos lados, en un PDF o una foto legible.' },
  CAMARA: { title: 'Cámara de comercio', hint: 'Certificado de existencia y representación de menos de 30 días.' },
  OTRO: { title: 'Otro documento (opcional)', hint: 'Lo que te pidamos aparte, por ejemplo la resolución de numeración.' },
}

const STATUS: Record<Status, { label: string; tone: 'primary' | 'warning' | 'success'; text: string }> = {
  ENVIADA: { label: 'En revisión', tone: 'primary', text: 'Recibimos tu solicitud y estamos revisando tus documentos. Te avisamos en la campana de notificaciones.' },
  CORREGIR: { label: 'Corregir', tone: 'warning', text: 'Necesitamos que corrijas algo antes de seguir.' },
  APROBADA: { label: 'Documentos aprobados', tone: 'success', text: 'Tus documentos están en orden. Te contactamos para el pago de tu paquete de facturación y la habilitación ante la DIAN.' },
  ACTIVA: { label: 'Activa', tone: 'success', text: 'La facturación electrónica está activa para tu tienda.' },
}

const kb = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1048576).toFixed(1)} MB`)

/**
 * Solicitud de facturación electrónica (opcional). La facturación se vende por paquetes: la tienda completa el asistente,
 * sube sus documentos y la envía; Ecomerxio verifica documentos y pago y la activa.
 */
export function EInvoiceRequestCard({ storeName, onChanged }: { storeName: string; onChanged: () => void }) {
  const toast = useToast()
  const [data, setData] = useState<RequestView | null>(null)
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState<Kind | null>(null)
  const [accepted, setAccepted] = useState(false)
  const [sending, setSending] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const pending = useRef<Kind>('RUT')

  const load = useCallback(() => {
    api<RequestView>('/einvoice/request').then(setData).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  function pick(kind: Kind) {
    pending.current = kind
    input.current?.click()
  }

  async function upload(file: File) {
    const kind = pending.current
    if (file.size > 5 * 1024 * 1024) {
      toast('El archivo pesa más de 5 MB', 'danger')
      return
    }
    setUploading(kind)
    try {
      await apiUpload('/einvoice/request/documents', file, { 'X-Doc-Kind': kind, 'X-Filename': encodeURIComponent(file.name) })
      toast(`${KIND_LABEL[kind].title}: subido`)
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo subir el archivo', 'danger')
    } finally {
      setUploading(null)
    }
  }

  async function remove(id: number) {
    try {
      setData(await api<RequestView>(`/einvoice/request/documents/${id}`, { method: 'DELETE' }))
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo quitar', 'danger')
    }
  }

  async function submit() {
    setSending(true)
    try {
      setData(await api<RequestView>('/einvoice/request/submit', { body: { acceptedTerms: accepted } }))
      toast('Solicitud enviada a Ecomerxio')
      onChanged()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo enviar', 'danger')
    } finally {
      setSending(false)
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner />
  const editable = !data.status || data.status === 'CORREGIR'
  const st = data.status ? STATUS[data.status] : null
  const kinds: Kind[] = [...data.requiredKinds, 'OTRO']

  return (
    <Card className="space-y-4 p-5">
      <input
        ref={input}
        type="file"
        accept="application/pdf,image/jpeg,image/png"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0]
          e.target.value = ''
          if (f) void upload(f)
        }}
      />
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="text-sm">
          <div className="font-semibold">Solicitud de activación</div>
          <div className="text-muted">
            {st ? st.text : 'La facturación electrónica se vende por paquetes. Sube tus documentos y envía la solicitud: la activamos cuando verifiquemos documentos y pago.'}
          </div>
        </div>
        {st && <Chip tone={st.tone}>{st.label}</Chip>}
      </div>

      {data.status === 'CORREGIR' && data.adminComment && (
        <Banner tone="warning">
          <b>Ecomerxio te pide:</b> {data.adminComment}
        </Banner>
      )}

      <div className="space-y-2">
        {kinds.map((k) => {
          const doc = data.documents.find((d) => d.kind === k)
          const required = data.requiredKinds.includes(k)
          return (
            <div key={k} className="flex flex-wrap items-center gap-3 rounded-xl border border-line p-3">
              <Icon name={doc ? 'check_circle' : required ? 'upload_file' : 'attach_file'} className={`text-[22px] ${doc ? 'text-success-ink' : 'text-muted'}`} />
              <div className="min-w-0 flex-1 text-sm">
                <div className="font-semibold">{KIND_LABEL[k].title}</div>
                <div className="truncate text-muted">{doc ? `${doc.filename} · ${kb(doc.sizeBytes)} · ${dateTime(doc.uploadedAt)}` : KIND_LABEL[k].hint}</div>
              </div>
              {editable && (
                <div className="flex gap-2">
                  {doc && (
                    <Button size="sm" variant="ghost" icon="delete" onClick={() => remove(doc.id)}>
                      Quitar
                    </Button>
                  )}
                  <Button size="sm" variant="soft" icon="upload" loading={uploading === k} onClick={() => pick(k)}>
                    {doc ? 'Cambiar' : 'Subir'}
                  </Button>
                </div>
              )}
            </div>
          )
        })}
        <p className="text-xs text-muted">PDF, JPG o PNG de hasta 5 MB. Tus documentos solo los ve el equipo de Ecomerxio para habilitar tu facturación.</p>
      </div>

      {editable && (
        <div className="space-y-3 border-t border-line pt-4">
          {data.missing.length > 0 && (
            <div className="text-sm text-muted">
              Para enviar falta: <b>{data.missing.join(', ')}</b>.
            </div>
          )}
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" className="mt-1 accent-[var(--color-primary)]" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} />
            <span>
              Autorizo a Ecomerxio a revisar estos documentos y a tramitar mi habilitación con el proveedor tecnológico. Entiendo que la facturación
              electrónica tiene un costo aparte (paquete) y que se activa después de verificar los documentos y el pago.
            </span>
          </label>
          <div className="flex flex-wrap items-center gap-3">
            <Button icon="send" loading={sending} disabled={!data.canSubmit || !accepted} onClick={submit}>
              {data.status === 'CORREGIR' ? 'Reenviar solicitud' : 'Solicitar activación'}
            </Button>
            <a
              href={whatsappLink(`Hola, tengo dudas sobre la facturación electrónica de Ecomerxio POS para mi tienda "${storeName}".`)}
              target="_blank"
              rel="noopener noreferrer"
              className="text-sm font-semibold text-primary"
            >
              ¿Dudas o precios de los paquetes? Escríbenos
            </a>
          </div>
        </div>
      )}
      {data.submittedAt && <p className="text-xs text-muted">Enviada el {dateTime(data.submittedAt)}{data.reviewedAt ? ` · revisada el ${dateTime(data.reviewedAt)}` : ''}.</p>}
    </Card>
  )
}
