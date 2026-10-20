import { useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Confirm, EmptyState, Icon, useToast } from '../../components/ui'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import {
  discardItem,
  exportQueue,
  isSale,
  recentSent,
  retryItem,
  syncPending,
  useOnline,
  useOutbox,
  type OutboxItem,
  type SentRecord,
} from '../../lib/offline/queue'
import { useStorageInfo } from '../../lib/offline/storage'

const when = (iso: string) => new Date(iso).toLocaleString('es-CO', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
const mb = (b: number) => `${(b / 1024 / 1024).toFixed(1)} MB`

function describe(i: OutboxItem): string {
  return isSale(i) ? `Venta ${money(i.receipt.total)}` : i.label
}

/** Lo que este equipo hizo y todavía no llega a la nube, en el orden en que se enviará. */
export default function OutboxPage() {
  const { me } = useAuth()
  const toast = useToast()
  const online = useOnline()
  const outbox = useOutbox(me?.tenant.id)
  const storage = useStorageInfo()
  const [sent, setSent] = useState<SentRecord[]>([])
  const [busy, setBusy] = useState(false)
  const [discard, setDiscard] = useState<OutboxItem | null>(null)
  const tenantId = me?.tenant.id

  useEffect(() => {
    if (tenantId) void recentSent(tenantId).then(setSent)
  }, [tenantId, outbox.items.length])

  if (!tenantId) return null

  async function sendNow() {
    setBusy(true)
    try {
      const n = await syncPending(tenantId!)
      toast(n ? `${n} enviado(s)` : online ? 'No se pudo enviar todavía' : 'Sin internet: se enviará al volver', n ? 'success' : 'danger')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-xl font-bold">Pendientes por enviar</h1>
        <p className="text-sm text-muted">
          Todo lo que haces queda guardado primero en este equipo y se envía a la nube en el mismo orden. Si no hay internet, espera aquí y se
          envía solo al volver.
        </p>
      </div>

      {!online && <Banner tone="warning">Sin internet. Puedes seguir trabajando: nada de esto se pierde aunque se apague el equipo.</Banner>}

      <div className="flex flex-wrap gap-2">
        <Button icon="cloud_upload" loading={busy || outbox.syncing} disabled={!outbox.items.length} onClick={sendNow}>
          Enviar ahora
        </Button>
        <Button variant="secondary" icon="download" disabled={!outbox.items.length} onClick={async () => toast(`${await exportQueue(tenantId)} en el archivo`)}>
          Descargar respaldo
        </Button>
      </div>

      {outbox.items.length === 0 ? (
        <EmptyState icon="cloud_done" title="Todo está en la nube" text="No hay nada de este equipo esperando para enviarse." />
      ) : (
        <Card className="divide-y divide-line-soft">
          {outbox.items.map((i, n) => (
            <div key={i.clientRef} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <span className="num w-6 text-xs text-muted">{n + 1}</span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold">{describe(i)}</span>
                  {i.status === 'ERROR' ? (
                    <Chip tone="danger">Rechazado</Chip>
                  ) : i.status === 'SENDING' ? (
                    <Chip tone="primary">Enviando</Chip>
                  ) : (
                    <Chip tone="warning">Por enviar</Chip>
                  )}
                </div>
                <div className="text-xs text-muted">
                  {when(i.createdAt)}
                  {i.attempts > 0 && ` · ${i.attempts} intento(s)`}
                </div>
                {i.error && <div className="mt-1 text-xs font-medium text-danger-ink">{i.error}</div>}
              </div>
              {i.status === 'ERROR' && (
                <Button size="sm" variant="soft" onClick={() => retryItem(i.clientRef)}>
                  Reintentar
                </Button>
              )}
              {i.status !== 'SENDING' && (
                <Button size="sm" variant="ghost" className="text-danger-ink" onClick={() => setDiscard(i)}>
                  Descartar
                </Button>
              )}
            </div>
          ))}
        </Card>
      )}

      {sent.length > 0 && (
        <section>
          <h2 className="mb-2 text-xs font-bold tracking-wider text-muted uppercase">Enviado desde este equipo (últimos 7 días)</h2>
          <Card className="divide-y divide-line-soft">
            {sent.slice(0, 30).map((s) => (
              <div key={s.clientRef} className="flex items-center gap-3 px-4 py-2 text-sm">
                <Icon name="cloud_done" className="text-[18px] text-success-ink" />
                <span className="min-w-0 flex-1 truncate">
                  {s.label}
                  {s.number ? ` #${s.number}` : ''}
                  {s.total != null ? ` · ${money(s.total)}` : ''}
                </span>
                <span className="text-xs text-muted">{when(s.sentAt)}</span>
              </div>
            ))}
          </Card>
        </section>
      )}

      {storage && (
        <p className="text-xs text-muted">
          Espacio de la app en este equipo: {mb(storage.usage)} de {mb(storage.quota)}.{' '}
          {storage.persisted ? 'Protegido: el navegador no lo borrará.' : 'Instala la app para que el navegador nunca borre estos datos.'}
        </p>
      )}

      <Confirm
        open={!!discard}
        danger
        title="¿Descartar esta operación?"
        text={discard ? `"${describe(discard)}" no llegará a la nube y se perderá. Hazlo solo si ya la registraste de otra forma.` : ''}
        confirmLabel="Descartar"
        onClose={() => setDiscard(null)}
        onConfirm={async () => {
          if (discard) await discardItem(discard.clientRef)
          setDiscard(null)
        }}
      />
    </div>
  )
}
