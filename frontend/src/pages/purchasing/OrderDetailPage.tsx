import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, Field, Icon, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty } from '../../lib/format'
import { ORDER_STATUS, type PurchaseOrder, type Supplier } from '../../lib/purchasingTypes'
import { OrderEditor, ReceiveModal } from './OrderDialogs'

export default function OrderDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const toast = useToast()
  const [o, setO] = useState<PurchaseOrder | null>(null)
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const [editing, setEditing] = useState(false)
  const [receiving, setReceiving] = useState(false)
  const [sendResult, setSendResult] = useState<{ message: string; whatsappUrl?: string; mailtoUrl?: string; emailed: boolean } | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<PurchaseOrder>(`/purchases/${id}`).then(setO).catch((e) => setError(e.message))
  }, [id])
  useEffect(() => {
    load()
    api<Supplier[]>('/suppliers').then(setSuppliers).catch(() => {})
  }, [load])

  async function act(path: string, body: unknown, msg: string) {
    try {
      const r = await api<PurchaseOrder>(`/purchases/${id}/${path}`, { body: body ?? {} })
      setO(r)
      toast(msg)
    } catch (e) {
      toast(e instanceof ApiError ? e.fields.reason ?? e.message : 'No se pudo', 'danger')
    }
  }

  async function send(via: string) {
    try {
      const r = await api<{ message: string; whatsappUrl?: string; mailtoUrl?: string; emailed: boolean }>(`/purchases/${id}/send`, { body: { via } })
      setSendResult(r)
      if (via === 'WHATSAPP' && r.whatsappUrl) window.open(r.whatsappUrl, '_blank', 'noopener')
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo enviar', 'danger')
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!o) return <Spinner />
  const st = ORDER_STATUS[o.status]
  const open = o.status === 'APROBADA' || o.status === 'PARCIAL'

  return (
    <div className="space-y-4">
      <Link to="/compras" className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Compras
      </Link>
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="num text-[22px] font-semibold tracking-tight">Pedido #{o.number}</h2>
              <Chip tone={st.tone}>{st.label}</Chip>
              {o.late && <Chip tone="danger" icon="schedule">Retrasado</Chip>}
              {o.source === 'SUGERIDA' && <Chip tone="wallet">Desde sugerencias</Chip>}
            </div>
            <Link to={`/proveedores/${o.supplierId}`} className="font-semibold text-primary hover:underline">
              {o.supplierName}
            </Link>
            <div className="mt-1 text-xs text-muted">
              Creado por {o.createdBy} · {dateTime(o.createdAt)}
              {o.approvedBy ? ` · aprobado por ${o.approvedBy}` : ''}
              {o.sentAt ? ` · enviado por ${o.sentVia?.toLowerCase()} ${dateTime(o.sentAt)}` : ''}
              {o.expectedDate ? ` · llega el ${o.expectedDate}` : ''}
            </div>
            {o.cancelReason && <div className="mt-1 text-sm text-danger-ink">Cancelado: {o.cancelReason}</div>}
          </div>
          {o.estimatedTotal !== undefined && (
            <div className="num text-right">
              <div className="text-[11px] font-bold tracking-wide text-muted uppercase">Total estimado</div>
              <div className="text-2xl font-bold">{money(o.estimatedTotal)}</div>
              {o.receivedTotal ? <div className="text-xs text-muted">recibido {money(o.receivedTotal)}</div> : null}
            </div>
          )}
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          {(o.status === 'BORRADOR' || (o.status === 'POR_APROBAR' && can('COMPRAS_APROBAR'))) && (
            <Button variant="secondary" icon="edit" onClick={() => setEditing(true)}>
              Modificar
            </Button>
          )}
          {o.status === 'BORRADOR' && (
            <Button icon="send" onClick={() => act('submit', null, can('COMPRAS_APROBAR') ? 'Pedido aprobado' : 'Enviado a aprobación')}>
              {can('COMPRAS_APROBAR') ? 'Aprobar pedido' : 'Enviar a aprobación'}
            </Button>
          )}
          {o.status === 'POR_APROBAR' && can('COMPRAS_APROBAR') && (
            <Button icon="verified" onClick={() => act('approve', null, 'Pedido aprobado')}>
              Aprobar
            </Button>
          )}
          {open && (
            <>
              <Button variant="soft" icon="chat" onClick={() => send('WHATSAPP')}>
                Enviar por WhatsApp
              </Button>
              <Button variant="soft" icon="mail" onClick={() => send('EMAIL')}>
                Enviar por correo
              </Button>
              {can('COMPRAS_RECIBIR') && (
                <Button icon="inventory" onClick={() => setReceiving(true)}>
                  Recibir mercancía
                </Button>
              )}
            </>
          )}
          {o.status !== 'RECIBIDA' && o.status !== 'CANCELADA' && (
            <Button variant="ghost" icon="cancel" className="text-danger-ink" onClick={() => setCancelling(true)}>
              Cancelar
            </Button>
          )}
        </div>
      </Card>

      <Card className="overflow-x-auto">
        <table className="num w-full min-w-[680px] text-sm [&_td]:px-2 [&_th]:px-2 [&_th]:py-2">
          <thead className="bg-canvas text-left text-[11px] tracking-wide text-muted uppercase">
            <tr>
              <th className="px-4 py-2">Producto</th>
              <th>Pedido</th>
              <th className="text-right">Costo</th>
              <th className="text-right">Llegó</th>
              <th className="text-right">No llegó</th>
              <th className="text-right">Pendiente</th>
              <th className="pr-4 text-right">Subtotal</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line-soft">
            {o.lines.map((l) => (
              <tr key={l.id}>
                <td className="px-4 py-2.5">
                  <div className="font-semibold">{l.productName}</div>
                  {l.reason && <div className="max-w-md truncate text-xs text-muted" title={l.reason}>{l.reason}</div>}
                </td>
                <td>
                  {qty(l.packsOrdered)} {l.packName}
                  <div className="text-xs text-muted">{qty(l.unitsOrdered)} u</div>
                </td>
                <td className="text-right">{l.costPerPack !== undefined ? money(l.costPerPack) : '—'}</td>
                <td className="text-right text-success-ink">{qty(l.unitsReceived)}</td>
                <td className={`text-right ${l.unitsRejected > 0 ? 'font-bold text-danger-ink' : ''}`}>{qty(l.unitsRejected)}</td>
                <td className="text-right">{qty(l.unitsPending)}</td>
                <td className="pr-4 text-right font-semibold">{l.subtotal !== undefined ? money(l.subtotal) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {o.receipts.length > 0 && (
        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Recepciones</h3>
          <ul className="divide-y divide-line-soft text-sm">
            {o.receipts.map((r) => (
              <li key={r.id} className="num flex flex-wrap items-center justify-between gap-2 py-2">
                <span>
                  Recepción #{r.number} · {r.receivedBy} · {dateTime(r.receivedAt)}
                  {r.invoiceNumber ? ` · factura ${r.invoiceNumber}` : ''}
                </span>
                <span className="flex items-center gap-2">
                  {r.onTime === true && <Chip tone="success">A tiempo</Chip>}
                  {r.onTime === false && <Chip tone="danger">Tarde</Chip>}
                  {r.total !== undefined && <b>{money(r.total)}</b>}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {o.notes && <Banner tone="primary">{o.notes}</Banner>}

      {editing && (
        <OrderEditor
          suppliers={suppliers}
          order={o}
          onClose={() => setEditing(false)}
          onSaved={(x) => {
            setEditing(false)
            setO(x)
            toast('Pedido actualizado')
          }}
        />
      )}
      {receiving && (
        <ReceiveModal
          order={o}
          onClose={() => setReceiving(false)}
          onDone={(x) => {
            setReceiving(false)
            setO(x)
            toast(x.status === 'RECIBIDA' ? 'Pedido recibido completo' : 'Recepción parcial registrada')
          }}
        />
      )}
      {sendResult && (
        <Modal open title="Pedido listo para enviar" onClose={() => setSendResult(null)}>
          <div className="space-y-3">
            {sendResult.emailed && <Banner tone="success">Enviado por correo al proveedor.</Banner>}
            <pre className="rounded-xl bg-canvas p-3 text-sm whitespace-pre-wrap">{sendResult.message}</pre>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" icon="content_copy" onClick={() => navigator.clipboard?.writeText(sendResult.message).then(() => toast('Mensaje copiado'))}>
                Copiar
              </Button>
              {sendResult.whatsappUrl && (
                <a href={sendResult.whatsappUrl} target="_blank" rel="noreferrer">
                  <Button variant="soft" icon="chat">
                    Abrir WhatsApp
                  </Button>
                </a>
              )}
              {sendResult.mailtoUrl && (
                <a href={sendResult.mailtoUrl}>
                  <Button variant="soft" icon="mail">
                    Abrir correo
                  </Button>
                </a>
              )}
            </div>
          </div>
        </Modal>
      )}
      {cancelling && (
        <Modal
          open
          title={`Cancelar pedido #${o.number}`}
          onClose={() => setCancelling(false)}
          footer={
            <>
              <Button variant="secondary" className="flex-1" onClick={() => setCancelling(false)}>
                Volver
              </Button>
              <Button
                variant="danger"
                className="flex-1"
                disabled={!reason.trim()}
                onClick={async () => {
                  await act('cancel', { reason }, 'Pedido cancelado')
                  setCancelling(false)
                }}
              >
                Cancelar pedido
              </Button>
            </>
          }
        >
          <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} />
        </Modal>
      )}
    </div>
  )
}
