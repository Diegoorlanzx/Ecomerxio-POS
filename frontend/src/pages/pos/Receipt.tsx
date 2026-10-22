import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { dateTime, money, qty, shortDate } from '../../lib/format'
import { METHOD_LABELS, type Receipt } from '../../lib/posTypes'
import QrCode from '../../components/QrCode'
import { storeUrl } from '../../lib/onlineTypes'
import { acquirePrintRoot } from '../../lib/host'
import { parseReceiptConfig, profileFor, type ReceiptConfig, type ReceiptProfileName } from '../../lib/receiptConfig'

/** Comprobante de venta con formato de tiquetera (58 u 80 mm). Qué muestra lo decide el diseño del recibo (Fase 16C). */
export function ReceiptView({
  r,
  provisional = false,
  design,
}: {
  r: Receipt
  provisional?: boolean
  /** Vista previa del diseñador: usa esta configuración y este perfil en vez de los guardados. */
  design?: { config: ReceiptConfig; profile: ReceiptProfileName }
}) {
  const s = r.sale
  const cur = r.currency
  const cfg = design?.config ?? parseReceiptConfig(r.config, r.showTax)
  const kind = design?.profile ?? profileFor(r)
  const p = cfg.profiles[kind]
  const ed = s.electronicDocument
  const showLines = p.productNames || p.quantities || p.unitPrice || p.lineTotal
  const groupTotal = s.lines.reduce((a, l) => a + Number(l.gross) - Number(l.discount ?? 0), 0)
  return (
    <div
      className={`receipt num mx-auto bg-white px-3 py-4 font-mono leading-snug text-black ${FONT[cfg.fontSize]}`}
      style={{ width: r.widthMm === 58 ? 220 : 300 }}
    >
      <div className="text-center">
        {p.logo && r.logo && <img src={r.logo} alt="" className="mx-auto mb-1 h-12 object-contain grayscale" />}
        {p.storeName && <div className="text-[1.15em] font-bold">{r.storeName}</div>}
        {p.legalData && (
          <>
            {r.legalName && <div>{r.legalName}</div>}
            {r.taxId && <div>NIT {r.taxId}</div>}
            {r.taxRegime && <div>{r.taxRegime}</div>}
          </>
        )}
        {p.address && (r.address || r.city) && <div>{[r.address, r.city].filter(Boolean).join(' · ')}</div>}
        {p.phone && r.phone && <div>Tel. {r.phone}</div>}
        {p.header && r.header && <div className="mt-1 whitespace-pre-line">{r.header}</div>}
      </div>
      <div className="my-2 border-t border-dashed border-black" />
      <div className="flex justify-between gap-2">
        <span>
          {s.practice
            ? 'PRÁCTICA · NO VÁLIDO'
            : s.status === 'ABIERTA'
              ? `PRECUENTA · ${s.accountLabel ?? ''}`
              : provisional
                ? 'TIQUETE PROVISIONAL'
                : p.saleNumber
                  ? `Venta #${s.number}`
                  : ''}
        </span>
        {p.dateTime && <span>{dateTime(s.completedAt ?? s.createdAt)}</span>}
      </div>
      {p.cashier && <div>Atendió: {s.userName}</div>}
      {p.customer && s.customerName && (
        <div>
          Cliente: {s.customerName}
          {s.customerDoc ? ` · ${s.customerDoc}` : ''}
        </div>
      )}
      {s.status === 'ANULADA' && <div className="mt-1 text-center font-bold">*** VENTA ANULADA ***</div>}
      {(p.groupAsVarios || showLines) && <div className="my-2 border-t border-dashed border-black" />}
      {p.groupAsVarios ? (
        <Row label={`Varios (${s.lines.length} ${s.lines.length === 1 ? 'producto' : 'productos'})`} value={money(groupTotal, cur)} />
      ) : (
        showLines &&
        s.lines.map((l) => (
          <div key={l.id ?? `${l.presentationId}-${l.quantity}`} className="mb-1">
            {p.productNames && (
              <div>
                {l.productName}
                {l.presentationName !== 'Unidad' ? ` (${l.presentationName})` : ''}
              </div>
            )}
            {p.productNames && l.modifiers && <div className="pl-2">{l.modifiers}</div>}
            {p.productNames && l.serials && l.serials.length > 0 && <div>  S/N: {l.serials.join(', ')}</div>}
            {(p.quantities || p.unitPrice || p.lineTotal) && (
              <div className="flex justify-between">
                <span>
                  {p.quantities && qty(l.quantity)}
                  {p.quantities && p.unitPrice && ' x '}
                  {p.unitPrice && money(l.unitPrice, cur)}
                </span>
                {p.lineTotal && <span>{money(l.gross, cur)}</span>}
              </div>
            )}
            {p.lineDiscount && l.discount > 0 && (
              <div className="flex justify-between">
                <span>  {l.promoName ? l.promoName : 'Descuento'}</span>
                <span>-{money(l.discount, cur)}</span>
              </div>
            )}
          </div>
        ))
      )}
      <div className="my-2 border-t border-dashed border-black" />
      {p.subtotal && <Row label="Subtotal" value={money(s.subtotal, cur)} />}
      {p.discounts && s.discountTotal > 0 && <Row label="Descuentos" value={`-${money(s.discountTotal, cur)}`} />}
      {p.taxes && s.taxTotal > 0 && <Row label="Impuestos incluidos" value={money(s.taxTotal, cur)} />}
      <div className="mt-1 flex justify-between text-[1.3em] font-bold">
        <span>TOTAL</span>
        <span>{money(s.total, cur)}</span>
      </div>
      {p.savings && s.discountTotal > 0 && <div className="text-center">¡Usted ahorró {money(s.discountTotal, cur)}!</div>}
      {(p.payments || (p.change && s.changeAmount > 0)) && <div className="my-2 border-t border-dashed border-black" />}
      {p.payments &&
        s.payments.map((pay, i) => (
          <div key={pay.id ?? i}>
            <Row label={`${METHOD_LABELS[pay.method] ?? pay.method}${pay.reference ? ` (${pay.reference})` : ''}`} value={money(pay.received ?? pay.amount, cur)} />
          </div>
        ))}
      {p.change && s.changeAmount > 0 && <Row label="Cambio" value={money(s.changeAmount, cur)} bold />}
      {p.creditBalance && r.creditBalance != null && <Row label="Saldo pendiente del fiado" value={money(r.creditBalance, cur)} bold />}
      {Number(s.tipAmount ?? 0) > 0 && (
        <>
          <Row label="Propina voluntaria" value={money(Number(s.tipAmount), cur)} />
          <Row label="Total con propina" value={money(Number(s.total) + Number(s.tipAmount), cur)} bold />
        </>
      )}
      {s.status === 'ABIERTA' && (
        <div className="mt-2 text-center">
          Precuenta: no es factura ni comprobante de pago.
          <br />
          La propina es voluntaria.
        </div>
      )}
      {s.refundedTotal > 0 && <Row label="Devuelto" value={`-${money(s.refundedTotal, cur)}`} />}
      {p.signature && (
        <div className="mt-6">
          <div className="border-t border-black" />
          <div className="text-center">Firma del cliente · C.C.</div>
        </div>
      )}
      {s.practice ? (
        <div className="mt-2 text-center font-bold">Venta de práctica: no se guardó ni descontó inventario.</div>
      ) : (
        provisional && <div className="mt-2 text-center">Venta sin internet: se enviará al reconectar.</div>
      )}
      {ed && (
        <div className="mt-2 flex flex-col items-center text-center">
          <div className="font-bold">
            {ed.documentLabel} {ed.fullNumber}
          </div>
          {ed.paymentForm === 'CREDITO' && <div>Forma de pago: crédito{ed.dueDate ? ` · vence ${shortDate(ed.dueDate)}` : ''}</div>}
          {ed.status === 'ACEPTADO' && ed.cufe ? (
            <>
              <div className="text-[10px] break-all">CUFE: {ed.cufe}</div>
              {ed.qrData && <QrCode text={ed.qrData} size={96} />}
            </>
          ) : (
            <div className="text-[10px]">Documento electrónico en proceso de validación.</div>
          )}
          {ed.simulated && <div className="text-[10px] font-bold">DOCUMENTO SIMULADO · SIN VALIDEZ</div>}
        </div>
      )}
      {p.footer && r.footer && <div className="mt-3 text-center whitespace-pre-line">{r.footer}</div>}
      {p.returnPolicy && cfg.returnPolicy && <div className="mt-2 text-center text-[10px] whitespace-pre-line">{cfg.returnPolicy}</div>}
      {p.onlineQr && r.onlineSlug && (
        <div className="mt-3 flex flex-col items-center">
          <QrCode text={storeUrl(r.onlineSlug)} size={96} />
          <div className="text-[10px]">Pide en línea: {storeUrl(r.onlineSlug).replace(/^https?:\/\//, '')}</div>
        </div>
      )}
      {/* Fase 16A: lo que no es factura lo dice siempre (no se puede quitar). */}
      {!ed && s.status !== 'ABIERTA' && <div className="mt-2 text-center text-[10px] font-bold">COMPROBANTE INTERNO · NO ES FACTURA</div>}
    </div>
  )
}

const FONT = { S: 'text-[11px]', M: 'text-[12px]', L: 'text-[14px]' } as const

/** Texto del recibo para enviarlo por WhatsApp. */
export function receiptText(r: Receipt): string {
  const s = r.sale
  const cur = r.currency
  const out = [`*${r.storeName}*`, `Venta #${s.number} · ${dateTime(s.completedAt ?? s.createdAt)}`, '']
  s.lines.forEach((l) => out.push(`${qty(l.quantity)} x ${l.productName} — ${money(l.gross, cur)}`))
  out.push('', `*TOTAL: ${money(s.total, cur)}*`)
  if (r.creditBalance != null) out.push(`Saldo pendiente del fiado: ${money(r.creditBalance, cur)}`)
  if (s.electronicDocument) out.push(`${s.electronicDocument.documentLabel} ${s.electronicDocument.fullNumber}`)
  else out.push('Comprobante interno · no es factura')
  if (r.footer) out.push('', r.footer)
  return out.join('\n')
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
      <style>{`@page { size: ${r.widthMm}mm auto; margin: 0 } .receipt-copy + .receipt-copy { break-before: page }`}</style>
      {Array.from({ length: Math.max(1, parseReceiptConfig(r.config, r.showTax).copies) }, (_, i) => (
        <div key={i} className="receipt-copy">
          <ReceiptView r={r} provisional={provisional} />
        </div>
      ))}
    </div>,
    target,
  )
}
