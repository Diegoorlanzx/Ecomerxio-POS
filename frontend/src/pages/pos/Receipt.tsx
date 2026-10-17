import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { dateTime, money, qty } from '../../lib/format'
import { METHOD_LABELS, type Receipt } from '../../lib/posTypes'
import QrCode from '../../components/QrCode'
import { storeUrl } from '../../lib/onlineTypes'
import { acquirePrintRoot } from '../../lib/host'

/** Comprobante de venta con formato de tiquetera (58 u 80 mm). */
export function ReceiptView({ r, provisional = false }: { r: Receipt; provisional?: boolean }) {
  const s = r.sale
  const cur = r.currency
  return (
    <div className="receipt num mx-auto bg-white px-3 py-4 font-mono text-[12px] leading-snug text-black" style={{ width: r.widthMm === 58 ? 220 : 300 }}>
      <div className="text-center">
        {r.logo && <img src={r.logo} alt="" className="mx-auto mb-1 h-12 object-contain grayscale" />}
        <div className="text-[14px] font-bold">{r.storeName}</div>
        {r.legalName && <div>{r.legalName}</div>}
        {r.taxId && <div>NIT {r.taxId}</div>}
        {r.taxRegime && <div>{r.taxRegime}</div>}
        {(r.address || r.city) && <div>{[r.address, r.city].filter(Boolean).join(' · ')}</div>}
        {r.phone && <div>Tel. {r.phone}</div>}
        {r.header && <div className="mt-1 whitespace-pre-line">{r.header}</div>}
      </div>
      <div className="my-2 border-t border-dashed border-black" />
      <div className="flex justify-between">
        <span>{provisional ? 'TIQUETE PROVISIONAL' : `Venta #${s.number}`}</span>
        <span>{dateTime(s.completedAt ?? s.createdAt)}</span>
      </div>
      <div>Atendió: {s.userName}</div>
      {s.customerName && (
        <div>
          Cliente: {s.customerName}
          {s.customerDoc ? ` · ${s.customerDoc}` : ''}
        </div>
      )}
      {s.status === 'ANULADA' && <div className="mt-1 text-center font-bold">*** VENTA ANULADA ***</div>}
      <div className="my-2 border-t border-dashed border-black" />
      {s.lines.map((l) => (
        <div key={l.id ?? `${l.presentationId}-${l.quantity}`} className="mb-1">
          <div>
            {l.productName}
            {l.presentationName !== 'Unidad' ? ` (${l.presentationName})` : ''}
          </div>
          {l.serials && l.serials.length > 0 && <div>  S/N: {l.serials.join(', ')}</div>}
          <div className="flex justify-between">
            <span>
              {qty(l.quantity)} x {money(l.unitPrice, cur)}
            </span>
            <span>{money(l.gross, cur)}</span>
          </div>
          {l.discount > 0 && (
            <div className="flex justify-between">
              <span>  {l.promoName ? l.promoName : 'Descuento'}</span>
              <span>-{money(l.discount, cur)}</span>
            </div>
          )}
        </div>
      ))}
      <div className="my-2 border-t border-dashed border-black" />
      <Row label="Subtotal" value={money(s.subtotal, cur)} />
      {s.discountTotal > 0 && <Row label="Descuentos" value={`-${money(s.discountTotal, cur)}`} />}
      {r.showTax && s.taxTotal > 0 && <Row label="Impuestos incluidos" value={money(s.taxTotal, cur)} />}
      <div className="mt-1 flex justify-between text-base font-bold">
        <span>TOTAL</span>
        <span>{money(s.total, cur)}</span>
      </div>
      <div className="my-2 border-t border-dashed border-black" />
      {s.payments.map((p, i) => (
        <div key={p.id ?? i}>
          <Row label={`${METHOD_LABELS[p.method] ?? p.method}${p.reference ? ` (${p.reference})` : ''}`} value={money(p.received ?? p.amount, cur)} />
        </div>
      ))}
      {s.changeAmount > 0 && <Row label="Cambio" value={money(s.changeAmount, cur)} bold />}
      {s.refundedTotal > 0 && <Row label="Devuelto" value={`-${money(s.refundedTotal, cur)}`} />}
      {provisional && <div className="mt-2 text-center">Venta sin internet: se enviará al reconectar.</div>}
      {r.footer && <div className="mt-3 text-center whitespace-pre-line">{r.footer}</div>}
      {r.onlineSlug && (
        <div className="mt-3 flex flex-col items-center">
          <QrCode text={storeUrl(r.onlineSlug)} size={96} />
          <div className="text-[10px]">Pide en línea: {storeUrl(r.onlineSlug).replace(/^https?:\/\//, '')}</div>
        </div>
      )}
      <div className="mt-2 text-center text-[10px]">Documento equivalente no válido como factura electrónica</div>
    </div>
  )
}

function Row({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between ${bold ? 'font-bold' : ''}`}>
      <span>{label}</span>
      <span>{value}</span>
    </div>
  )
}

/** Copia oculta para imprimir: con @media print solo se ve el comprobante. */
export function PrintArea({ r, provisional }: { r: Receipt | null; provisional?: boolean }) {
  if (!r) return null
  return <PrintPortal r={r} provisional={provisional} />
}

function PrintPortal({ r, provisional }: { r: Receipt; provisional?: boolean }) {
  // Sola imprime desde el <body>; incrustada en otra página usa una hoja propia (ver acquirePrintRoot).
  const [target, setTarget] = useState<Element | DocumentFragment | null>(null)
  useEffect(() => {
    const sheet = acquirePrintRoot()
    setTarget(sheet.root)
    return sheet.release
  }, [])
  if (!target) return null
  return createPortal(
    <div id="print-area" aria-hidden>
      <style>{`@page { size: ${r.widthMm}mm auto; margin: 0 }`}</style>
      <ReceiptView r={r} provisional={provisional} />
    </div>,
    target,
  )
}
