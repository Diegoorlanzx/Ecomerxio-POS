import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Banner, Button, Icon, Spinner } from '../../components/ui'
import { dateTime, money, qty } from '../../lib/format'
import { publicApi, type PublicOrder } from '../../lib/onlineTypes'

const STEPS: { status: string; label: string; icon: string }[] = [
  { status: 'NUEVO', label: 'Recibido', icon: 'receipt_long' },
  { status: 'ACEPTADO', label: 'Aceptado', icon: 'thumb_up' },
  { status: 'PREPARANDO', label: 'En preparación', icon: 'inventory' },
  { status: 'LISTO', label: 'Listo', icon: 'check_circle' },
  { status: 'EN_CAMINO', label: 'En camino', icon: 'two_wheeler' },
  { status: 'ENTREGADO', label: 'Entregado', icon: 'task_alt' },
]

/** El cliente sigue su pedido con este enlace (se actualiza solo). */
export default function OrderTrackPage() {
  const { slug = '', token = '' } = useParams()
  const [o, setO] = useState<PublicOrder | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    const load = () =>
      publicApi<PublicOrder>(`/orders/${encodeURIComponent(token)}`)
        .then((r) => {
          if (!alive) return
          setO(r)
          setError('')
          document.title = `Pedido #${r.number} · ${r.storeName}`
        })
        .catch((e) => alive && setError(e.message))
    load()
    const t = setInterval(load, 15000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [token])

  if (error && !o) return <div className="mx-auto max-w-md p-6 pt-20 text-center font-semibold">{error}</div>
  if (!o) return <Spinner label="Cargando tu pedido…" />

  const steps = STEPS.filter((s) => o.fulfillment === 'DOMICILIO' || s.status !== 'EN_CAMINO')
  const current = steps.findIndex((s) => s.status === o.status)
  const cancelled = o.status === 'CANCELADO'
  const done = o.status === 'ENTREGADO'

  return (
    <div className="min-h-dvh bg-canvas px-4 py-6">
      <div className="mx-auto max-w-md space-y-4">
        <div className="text-center">
          <div className="text-xs font-bold tracking-wide text-muted uppercase">{o.storeName}</div>
          <h1 className="text-2xl font-bold">Pedido #{o.number}</h1>
          <p className="text-sm text-muted">Hecho el {dateTime(o.createdAt)}</p>
        </div>

        {cancelled ? (
          <Banner>
            La tienda canceló el pedido{o.cancelReason ? `: ${o.cancelReason}` : ''}.
          </Banner>
        ) : (
          <div className="rounded-2xl bg-card p-4 shadow-tier1">
            <ol className="space-y-3">
              {steps.map((s, i) => {
                const reached = i <= current
                return (
                  <li key={s.status} className="flex items-center gap-3">
                    <span className={`flex h-9 w-9 items-center justify-center rounded-full ${reached ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}>
                      <Icon name={s.icon} className="text-[20px]" />
                    </span>
                    <span className={`text-sm ${i === current ? 'font-bold' : reached ? 'font-semibold' : 'text-muted'}`}>{s.label}</span>
                    {i === current && !done && <span className="ml-auto h-2 w-2 animate-pulse rounded-full bg-primary" />}
                  </li>
                )
              })}
            </ol>
            {o.status === 'NUEVO' && <p className="mt-3 text-xs text-muted">La tienda va a confirmar tu pedido en unos minutos. Esta página se actualiza sola.</p>}
            {o.status === 'LISTO' && o.fulfillment === 'RECOGER' && <p className="mt-3 text-sm font-semibold text-success-ink">¡Ya puedes recogerlo en la tienda!</p>}
          </div>
        )}

        {!cancelled && !done && o.payInfo.length > 0 && (
          <div className="space-y-3 rounded-2xl bg-card p-4 shadow-tier1">
            <h2 className="font-semibold">Paga con {o.payLabel}</h2>
            {o.payInfo.map((p, i) => (
              <div key={i} className="flex items-center gap-3">
                {p.qrImage && <img src={p.qrImage} alt="QR de pago" className="h-28 w-28 rounded-lg border border-line object-contain" />}
                <div className="text-sm">
                  <div className="font-semibold">{p.label}</div>
                  {p.accountRef && <div className="num text-lg font-bold">{p.accountRef}</div>}
                  {p.holder && <div className="text-muted">A nombre de {p.holder}</div>}
                </div>
              </div>
            ))}
            <p className="num text-sm">
              Valor a pagar: <span className="font-bold">{money(o.total)}</span>. Envía el comprobante por WhatsApp para confirmarlo.
            </p>
          </div>
        )}

        {o.whatsappUrl && !done && !cancelled && (
          <a href={o.whatsappUrl} target="_blank" rel="noreferrer" className="block">
            <Button variant="secondary" icon="chat" className="w-full">
              Enviar el pedido por WhatsApp
            </Button>
          </a>
        )}

        <div className="rounded-2xl bg-card p-4 shadow-tier1">
          <ul className="space-y-1.5 text-sm">
            {o.lines.map((l) => (
              <li key={l.id} className="num flex justify-between gap-2">
                <span>
                  {qty(l.quantity)} × {l.productName}
                  {l.presentationName !== 'Unidad' ? ` (${l.presentationName})` : ''}
                </span>
                <span>{money(l.lineTotal)}</span>
              </li>
            ))}
            {o.deliveryFee > 0 && (
              <li className="num flex justify-between">
                <span>Domicilio{o.zoneName ? ` (${o.zoneName})` : ''}</span>
                <span>{money(o.deliveryFee)}</span>
              </li>
            )}
            <li className="num flex justify-between border-t border-line-soft pt-1.5 text-base font-bold">
              <span>Total</span>
              <span>{money(o.total)}</span>
            </li>
          </ul>
          <div className="mt-3 text-sm text-muted">
            {o.fulfillment === 'DOMICILIO' ? (
              <>
                <Icon name="location_on" className="mr-1 align-[-4px] text-[18px]" />
                {o.address}
              </>
            ) : (
              <>
                <Icon name="storefront" className="mr-1 align-[-4px] text-[18px]" />
                Recoges en la tienda
              </>
            )}
            <span className="block">Pago: {o.payLabel}</span>
          </div>
        </div>
        <Link to={`/t/${slug}`} className="block text-center text-sm font-semibold text-primary">
          Volver a la tienda
        </Link>
      </div>
    </div>
  )
}
