import { money, qty } from '../../lib/format'
import { MUNICIPALITIES } from '../../lib/fiscalTypes'
import { dayLabel, DOC_TYPES, type PublicQuote } from '../../lib/quoteTypes'

/**
 * La cotización o prefactura tal como la ve el cliente (enlace público, impresión y PDF). Lleva siempre la leyenda de
 * que no es factura: la prefactura no puede confundirse con un documento equivalente ni servir de soporte fiscal.
 */
export default function QuoteDocument({ q }: { q: PublicQuote }) {
  const pf = q.kind === 'PREFACTURA'
  const cur = q.currency
  const docType = DOC_TYPES.find(([v]) => v === q.customerDocType)?.[1] ?? q.customerDocType
  const mun = MUNICIPALITIES.find(([c]) => c === q.customerMunicipality)
  return (
    <div className="quote-doc relative overflow-hidden rounded-2xl border border-line bg-card p-5 text-sm sm:p-8 print:rounded-none print:border-0 print:p-0">
      {pf && (
        <div aria-hidden className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <span className="-rotate-[24deg] text-5xl font-black tracking-widest whitespace-nowrap text-danger/10 sm:text-7xl">NO ES FACTURA</span>
        </div>
      )}
      <header className="relative flex flex-wrap items-start gap-4 border-b border-line pb-4">
        {q.storeLogo && <img src={q.storeLogo} alt="" className="h-16 w-16 rounded-xl object-contain" />}
        <div className="min-w-0 flex-1">
          <div className="text-lg font-bold">{q.storeName}</div>
          {q.storeLegalName && q.storeLegalName !== q.storeName && <div>{q.storeLegalName}</div>}
          {q.storeTaxId && <div className="num">NIT {q.storeTaxId}</div>}
          <div className="text-xs text-muted">
            {[q.storeAddress, q.storeCity].filter(Boolean).join(', ')}
            {q.storePhone ? ` · Tel. ${q.storePhone}` : ''}
            {q.storeEmail ? ` · ${q.storeEmail}` : ''}
          </div>
        </div>
        <div className="text-right">
          <div className="text-xs font-bold tracking-[0.12em] text-muted uppercase">{pf ? 'Prefactura' : 'Cotización'}</div>
          <div className="num text-2xl font-bold">{q.code}</div>
          <div className="text-xs text-muted">Fecha: {dayLabel(q.issueDate)}</div>
          {!pf && q.validUntil && <div className="text-xs font-semibold">Válida hasta: {dayLabel(q.validUntil)}</div>}
        </div>
      </header>

      {pf && (
        <p className="relative mt-3 rounded-lg border border-danger/40 bg-danger-soft px-3 py-2 text-center text-xs font-bold text-danger-ink uppercase">
          Documento de revisión previo a la factura · No válido como factura de venta ni como documento equivalente
        </p>
      )}

      <section className="relative mt-4 grid gap-1 sm:grid-cols-2">
        <div>
          <div className="text-xs font-bold tracking-wide text-muted uppercase">{pf ? 'Datos para la factura' : 'Cliente'}</div>
          <div className="font-semibold">{q.customerName || '—'}</div>
          {q.customerDoc && (
            <div className="num">
              {docType} {q.customerDoc}
              {q.customerDv ? `-${q.customerDv}` : ''}
            </div>
          )}
          {q.customerPersonType && <div>{q.customerPersonType === 'JURIDICA' ? 'Persona jurídica' : 'Persona natural'}</div>}
        </div>
        <div className="sm:text-right">
          {q.customerEmail && <div>{q.customerEmail}</div>}
          {q.customerPhone && <div className="num">{q.customerPhone}</div>}
          {q.customerAddress && <div>{q.customerAddress}</div>}
          {(mun || q.customerMunicipality) && <div>{mun ? `${mun[1]} (${mun[2]})` : q.customerMunicipality}</div>}
        </div>
      </section>

      <div className="relative mt-4 overflow-x-auto">
        <table className="w-full min-w-[480px] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-muted uppercase">
              <th className="py-2 pr-2">Descripción</th>
              <th className="px-2 py-2 text-right">Cant.</th>
              <th className="px-2 py-2 text-right">Precio</th>
              <th className="px-2 py-2 text-right">Desc.</th>
              <th className="px-2 py-2 text-right">IVA</th>
              <th className="py-2 pl-2 text-right">Total</th>
            </tr>
          </thead>
          <tbody>
            {q.lines.map((l, i) => (
              <tr key={i} className="border-b border-line-soft align-top">
                <td className="py-2 pr-2">
                  {l.description}
                  {l.presentationName && <span className="text-muted"> ({l.presentationName})</span>}
                </td>
                <td className="num px-2 py-2 text-right">{qty(Number(l.quantity))}</td>
                <td className="num px-2 py-2 text-right">{money(Number(l.unitPrice), cur)}</td>
                <td className="num px-2 py-2 text-right">{Number(l.discountPct) ? `${Number(l.discountPct)} %` : '—'}</td>
                <td className="num px-2 py-2 text-right">{Number(l.taxRate)} %</td>
                <td className="num py-2 pl-2 text-right font-semibold">{money(Number(l.lineTotal), cur)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="relative mt-3 ml-auto w-full max-w-xs space-y-1 text-sm">
        <Row label="Subtotal" value={money(Number(q.subtotal), cur)} />
        {Number(q.discountTotal) > 0 && <Row label="Descuentos" value={`-${money(Number(q.discountTotal), cur)}`} />}
        <Row label={q.pricesIncludeTax ? 'IVA incluido' : 'IVA'} value={money(Number(q.taxTotal), cur)} />
        <div className="num flex justify-between border-t border-line pt-1 text-lg font-bold">
          <span>Total</span>
          <span>{money(Number(q.total), cur)}</span>
        </div>
      </div>

      {q.notes && (
        <section className="relative mt-4">
          <div className="text-xs font-bold tracking-wide text-muted uppercase">Notas</div>
          <p className="whitespace-pre-line">{q.notes}</p>
        </section>
      )}
      {q.terms && (
        <section className="relative mt-4">
          <div className="text-xs font-bold tracking-wide text-muted uppercase">Condiciones</div>
          <p className="text-xs whitespace-pre-line text-muted">{q.terms}</p>
        </section>
      )}
      <footer className="relative mt-6 border-t border-line pt-3 text-center text-[11px] text-muted">
        {pf ? 'Prefactura' : 'Cotización'} generada con Ecomerxio POS · Este documento no es una factura electrónica de venta.
      </footer>
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="num flex justify-between">
      <span className="text-muted">{label}</span>
      <span>{value}</span>
    </div>
  )
}
