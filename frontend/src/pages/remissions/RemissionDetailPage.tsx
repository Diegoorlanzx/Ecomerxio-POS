import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, Field, Icon, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty } from '../../lib/format'
import { REMISSION_STATUS, type Remission } from '../../lib/remissionTypes'
import InvoiceRemissions from './InvoiceRemissions'
import RemissionDocument from './RemissionDocument'

const wa = (n?: string | null) => {
  const d = (n ?? '').replace(/\D/g, '')
  return d.length === 10 ? `57${d}` : d
}

export default function RemissionDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { me, can } = useAuth()
  const toast = useToast()
  const [r, setR] = useState<Remission | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<'deliver' | 'return' | 'cancel' | 'invoice' | null>(null)
  const currency = me?.tenant.currency ?? 'COP'

  const load = useCallback(() => {
    api<Remission>(`/remissions/${id}`)
      .then((x) => {
        setR(x)
        setError('')
      })
      .catch((e) => setError(e.message))
  }, [id])
  useEffect(load, [load])

  async function act(path: string, body: unknown, msg: string): Promise<boolean> {
    setBusy(true)
    setError('')
    try {
      setR(await api<Remission>(`/remissions/${id}/${path}`, { method: 'POST', body: body ?? {} }))
      toast(msg)
      return true
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo completar')
      return false
    } finally {
      setBusy(false)
    }
  }

  if (!r) return error ? <Banner>{error}</Banner> : <Spinner />

  const st = REMISSION_STATUS[r.status] ?? { label: r.status, tone: 'neutral' as const }
  const canCreate = can('REMISIONES_CREAR')

  function share() {
    const items = r!.lines.map((l) => `• ${qty(Number(l.quantity))} ${l.description}`).join('\n')
    const text = `Hola ${r!.customerName}, ${me?.tenant.name ?? ''} te ${r!.status === 'BORRADOR' ? 'enviará' : 'entregó'} la remisión ${r!.code}:\n${items}${
      r!.showPrices ? `\nValor: ${money(r!.total, currency)}` : ''
    }`
    window.open(`https://wa.me/${wa(r!.customerPhone)}?text=${encodeURIComponent(text)}`, '_blank', 'noopener')
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <Link to="/remisiones" className="flex h-10 w-10 items-center justify-center rounded-xl text-muted hover:bg-line-soft" aria-label="Volver">
          <Icon name="arrow_back" />
        </Link>
        <h1 className="num text-2xl font-bold tracking-tight">{r.code}</h1>
        <Chip tone={st.tone}>{st.label}</Chip>
        <span className="flex-1" />
        <Button variant="secondary" icon="print" onClick={() => window.print()}>
          Imprimir / PDF
        </Button>
      </div>
      {error && <Banner>{error}</Banner>}

      <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
        <div className="min-w-0">
          <RemissionDocument r={r} />
        </div>

        <aside className="space-y-3 print:hidden">
          {r.status === 'ENTREGADA' && (
            <Card className="p-4">
              <div className="text-xs font-bold tracking-wide text-muted uppercase">Por facturar</div>
              <div className="num text-2xl font-bold">{money(r.pendingTotal, currency)}</div>
              {Number(r.pendingTotal) !== Number(r.total) && <div className="text-xs text-muted">De {money(r.total, currency)} remitidos, sin lo que el cliente devolvió.</div>}
            </Card>
          )}

          <Card className="space-y-2 p-4">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Acciones</div>
            {r.status === 'BORRADOR' && canCreate && (
              <>
                <Button icon="local_shipping" className="w-full" onClick={() => setDialog('deliver')}>
                  Entregar al cliente
                </Button>
                <Button variant="secondary" icon="edit" className="w-full" onClick={() => navigate(`/remisiones/${r.id}/editar`)}>
                  Editar
                </Button>
              </>
            )}
            {r.status === 'ENTREGADA' && canCreate && can('POS_VENDER') && (
              <Button icon="point_of_sale" className="w-full" onClick={() => setDialog('invoice')}>
                Facturar
              </Button>
            )}
            {r.status === 'ENTREGADA' && canCreate && (
              <Button variant="secondary" icon="assignment_return" className="w-full" onClick={() => setDialog('return')}>
                El cliente devolvió algo
              </Button>
            )}
            {r.status !== 'ANULADA' && (
              <Button variant="soft" icon="chat" className="w-full" onClick={share}>
                Enviar por WhatsApp
              </Button>
            )}
            {['BORRADOR', 'ENTREGADA'].includes(r.status) && canCreate && (
              <Button variant="ghost" icon="cancel" className="w-full text-danger-ink" onClick={() => setDialog('cancel')}>
                Anular
              </Button>
            )}
            {r.status === 'FACTURADA' && <p className="text-xs text-muted">Para corregirla, anula o devuelve la venta: la remisión vuelve a quedar por facturar.</p>}
          </Card>

          <Card className="space-y-2 p-4 text-sm">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Historia</div>
            <Event icon="add_circle" text={`Creada por ${r.createdBy ?? '—'}`} at={r.createdAt} />
            {r.quoteCode && (
              <Link to={`/cotizaciones/${r.quoteId}`} className="flex items-center gap-2 text-primary">
                <Icon name="request_quote" className="text-[18px]" /> Viene de la cotización {r.quoteCode}
              </Link>
            )}
            {r.deliveredAt && (
              <Event icon="local_shipping" text={`Entregada por ${r.deliveredBy}${r.receivedBy ? ` · recibió ${r.receivedBy}` : ''}`} at={r.deliveredAt} />
            )}
            {r.lines.some((l) => Number(l.returnedQty) > 0) && (
              <Event
                icon="assignment_return"
                text={`Devolvió: ${r.lines
                  .filter((l) => Number(l.returnedQty) > 0)
                  .map((l) => `${qty(Number(l.returnedQty))} ${l.description}`)
                  .join(', ')}`}
              />
            )}
            {r.saleNumber && (
              <Event icon="point_of_sale" text={`Facturada en la venta #${r.saleNumber}${r.invoicedWith.length ? ` junto con ${r.invoicedWith.join(', ')}` : ''}`} at={r.invoicedAt} />
            )}
            {r.cancelledAt && <Event icon="cancel" text={`Anulada: ${r.cancelReason}`} at={r.cancelledAt} />}
          </Card>
        </aside>
      </div>

      {dialog === 'deliver' && (
        <DeliverDialog r={r} busy={busy} onClose={() => setDialog(null)} onSave={(b) => act('deliver', b, 'Remisión entregada: la mercancía salió del inventario').then((ok) => ok && setDialog(null))} />
      )}
      {dialog === 'return' && (
        <ReturnDialog r={r} busy={busy} onClose={() => setDialog(null)} onSave={(b) => act('returns', b, 'Devolución registrada: volvió al inventario').then((ok) => ok && setDialog(null))} />
      )}
      {dialog === 'cancel' && (
        <CancelDialog delivered={r.status === 'ENTREGADA'} busy={busy} onClose={() => setDialog(null)} onSave={(reason) => act('cancel', { reason }, 'Remisión anulada').then((ok) => ok && setDialog(null))} />
      )}
      {dialog === 'invoice' && (
        <InvoiceRemissions
          ids={[r.id]}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null)
            load()
            toast(`${r.code} facturada`)
          }}
        />
      )}
    </div>
  )
}

function Event({ icon, text, at }: { icon: string; text: string; at?: string | null }) {
  return (
    <div className="flex items-start gap-2">
      <Icon name={icon} className="mt-0.5 text-[18px] text-muted" />
      <div className="min-w-0 flex-1">
        <div>{text}</div>
        {at && <div className="text-xs text-muted">{dateTime(at)}</div>}
      </div>
    </div>
  )
}

function DeliverDialog({ r, onClose, onSave, busy }: { r: Remission; onClose: () => void; onSave: (b: { receivedBy: string; receivedDoc: string }) => void; busy: boolean }) {
  const [receivedBy, setReceivedBy] = useState('')
  const [receivedDoc, setReceivedDoc] = useState('')
  return (
    <Modal
      open
      title={`Entregar ${r.code}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Volver
          </Button>
          <Button icon="local_shipping" className="flex-1" loading={busy} onClick={() => onSave({ receivedBy, receivedDoc })}>
            Entregar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">La mercancía sale del inventario y la remisión ya no se puede editar. Si el cliente devuelve algo después, lo registras aquí mismo.</p>
        <Field label="Quién recibe (opcional)" value={receivedBy} onChange={(e) => setReceivedBy(e.target.value)} placeholder="Nombre de quien firma" />
        <Field label="Cédula de quien recibe (opcional)" inputMode="numeric" value={receivedDoc} onChange={(e) => setReceivedDoc(e.target.value.replace(/\D/g, ''))} />
        <p className="text-xs text-muted">Si lo dejas vacío, el papel sale con el espacio en blanco para llenarlo a mano.</p>
      </div>
    </Modal>
  )
}

function ReturnDialog({
  r,
  onClose,
  onSave,
  busy,
}: {
  r: Remission
  onClose: () => void
  onSave: (b: { lines: { lineId: number; quantity: number }[]; reason: string }) => void
  busy: boolean
}) {
  const open = r.lines.filter((l) => Number(l.pendingQty) > 0 && l.productId)
  const [q, setQ] = useState<Record<number, string>>({})
  const [reason, setReason] = useState('')
  const picked = open
    .map((l) => ({ lineId: l.id, quantity: Number((q[l.id] ?? '').replace(',', '.')) || 0, max: Number(l.pendingQty) }))
    .filter((x) => x.quantity > 0)
  const over = picked.some((x) => x.quantity > x.max)
  return (
    <Modal
      open
      title="Devolución del cliente"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Volver
          </Button>
          <Button
            icon="assignment_return"
            className="flex-1"
            disabled={!picked.length || over || !reason.trim()}
            loading={busy}
            onClick={() => onSave({ lines: picked.map(({ lineId, quantity }) => ({ lineId, quantity })), reason })}
          >
            Registrar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">Lo devuelto vuelve al inventario y no se le factura al cliente.</p>
        {open.map((l) => (
          <div key={l.id} className="flex items-center gap-3 rounded-xl border border-line p-3">
            <div className="min-w-0 flex-1 text-sm">
              <div className="font-semibold">{l.description}</div>
              <div className="text-xs text-muted">Tiene {qty(Number(l.pendingQty))}</div>
            </div>
            <input
              inputMode="decimal"
              placeholder="0"
              value={q[l.id] ?? ''}
              onChange={(e) => setQ({ ...q, [l.id]: e.target.value })}
              aria-label={`Devuelve de ${l.description}`}
              className={`num h-10 w-24 rounded-lg border bg-card px-2 text-right ${Number((q[l.id] ?? '').replace(',', '.')) > Number(l.pendingQty) ? 'border-danger' : 'border-line'}`}
            />
          </div>
        ))}
        {open.length === 0 && <p className="text-sm">No hay productos del inventario por devolver.</p>}
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: sobró en la obra" />
      </div>
    </Modal>
  )
}

function CancelDialog({ delivered, onClose, onSave, busy }: { delivered: boolean; onClose: () => void; onSave: (reason: string) => void; busy: boolean }) {
  const [reason, setReason] = useState('')
  return (
    <Modal
      open
      title="Anular remisión"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Volver
          </Button>
          <Button variant="danger" className="flex-1" disabled={!reason.trim()} loading={busy} onClick={() => onSave(reason)}>
            Anular
          </Button>
        </>
      }
    >
      <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: el cliente no recibió" />
      {delivered && <p className="mt-2 text-xs text-muted">Lo que el cliente todavía tenía vuelve al inventario: anula solo si la mercancía regresó.</p>}
    </Modal>
  )
}
