import { dateTime, money, qty } from '../../lib/format'
import { dayLabel, DOC_TYPES } from '../../lib/quoteTypes'
import type { Remission } from '../../lib/remissionTypes'

/**
 * La remisión impresa: viaja con la mercancía y la firma quien recibe. Lleva siempre la leyenda de que no es factura.
 * Sin precios cuando la tienda así lo elige (solo cantidades).
 */
export default function RemissionDocument({ r }: { r: Remission }) {
  const cur = r.store.currency
  const docType = DOC_TYPES.find(([v]) => v === r.customerDocType)?.[1] ?? r.customerDocType
  const prices = r.showPrices
  const returned = r.lines.some((l) => Number(l.returnedQty) > 0)
  return (
    <div className="relative overflow-hidden rounded-2xl border border-line bg-card p-5 text-sm sm:p-8 print:rounded-none print:border-0 print:p-0">
      {r.status === 'ANULADA' && (
        <div aria-hidden className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <span className="-rotate-[24deg] text-6xl font-black tracking-widest text-danger/15 sm:text-8xl">ANULADA</span>
        </div>
      )}
      <header className="relative flex flex-wrap items-start gap-4 border-b border-line pb-4">
        {r.store.logo && <img src={r.store.logo} alt="" className="h-16 w-16 rounded-xl object-contain" />}
        <div className="min-w-0 flex-1">
          <div className="text-lg font-bold">{r.store.name}</div>
          {r.store.legalName && r.store.legalName !== r.store.name && <div>{r.store.legalName}</div>}
          {r.store.taxId && <div className="num">NIT {r.store.taxId}</div>}
          <div className="text-xs text-muted">
            {[r.store.address, r.store.city].filter(Boolean).join(', ')}
            {r.store.phone ? ` · Tel. ${r.store.phone}` : ''}
            {r.store.email ? ` · ${r.store.email}` : ''}
          </div>
        </div>
        <div className="text-right">
          <div className="text-xs font-bold tracking-[0.12em] text-muted uppercase">Remisión</div>
          <div className="num text-2xl font-bold">{r.code}</div>
          <div className="text-xs text-muted">Fecha: {dayLabel(r.issueDate)}</div>
          {r.deliveredAt && <div className="text-xs font-semibold">Entregada: {dateTime(r.deliveredAt)}</div>}
        </div>
      </header>

      <section className="relative mt-4 grid gap-1 sm:grid-cols-2">
        <div>
          <div className="text-xs font-bold tracking-wide text-muted uppercase">Cliente</div>
          <div className="font-semibold">{r.customerName}</div>
          {r.customerDoc && (
            <div className="num">
              {docType} {r.customerDoc}
            </div>
          )}
          {r.customerPhone && <div className="num">{r.customerPhone}</div>}
        </div>
        {r.deliveryAddress && (
          <div className="sm:text-right">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Entregar en</div>
            <div>{r.deliveryAddress}</div>
          </div>
        )}
      </section>

      <div className="relative mt-4 overflow-x-auto">
        <table className={`w-full text-sm ${prices ? 'min-w-[480px]' : ''}`}>
          <thead>
            <tr className="border-b border-line text-left text-xs text-muted uppercase">
              <th className="py-2 pr-2">Descripción</th>
              <th className="px-2 py-2 text-right">Cant.</th>
              {returned && <th className="px-2 py-2 text-right">Devuelto</th>}
              {prices && (
                <>
                  <th className="px-2 py-2 text-right">Precio</th>
                  <th className="px-2 py-2 text-right">Desc.</th>
                  <th className="py-2 pl-2 text-right">Total</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {r.lines.map((l) => (
              <tr key={l.id} className="border-b border-line-soft align-top">
                <td className="py-2 pr-2">
                  {l.description}
                  {l.presentationName && l.presentationName !== 'Unidad' && <span className="text-muted"> ({l.presentationName})</span>}
                </td>
                <td className="num px-2 py-2 text-right">{qty(Number(l.quantity))}</td>
                {returned && <td className="num px-2 py-2 text-right">{Number(l.returnedQty) ? qty(Number(l.returnedQty)) : '—'}</td>}
                {prices && (
                  <>
                    <td className="num px-2 py-2 text-right">{money(Number(l.unitPrice), cur)}</td>
                    <td className="num px-2 py-2 text-right">{Number(l.discountPct) ? `${Number(l.discountPct)} %` : '—'}</td>
                    <td className="num py-2 pl-2 text-right font-semibold">{money(Number(l.lineTotal), cur)}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {prices && (
        <div className="relative mt-3 ml-auto w-full max-w-xs space-y-1 text-sm">
          <Row label="Subtotal" value={money(Number(r.subtotal), cur)} />
          {Number(r.discountTotal) > 0 && <Row label="Descuentos" value={`-${money(Number(r.discountTotal), cur)}`} />}
          <Row label={r.pricesIncludeTax ? 'IVA incluido' : 'IVA'} value={money(Number(r.taxTotal), cur)} />
          <div className="num flex justify-between border-t border-line pt-1 text-lg font-bold">
            <span>Total</span>
            <span>{money(Number(r.total), cur)}</span>
          </div>
        </div>
      )}

      {r.notes && (
        <section className="relative mt-4">
          <div className="text-xs font-bold tracking-wide text-muted uppercase">Observaciones</div>
          <p className="whitespace-pre-line">{r.notes}</p>
        </section>
      )}

      <section className="relative mt-10 grid gap-8 sm:grid-cols-2">
        <Signature label="Entrega" name={r.deliveredBy ?? undefined} />
        <Signature label="Recibí conforme" name={r.receivedBy ?? undefined} doc={r.receivedDoc ?? undefined} />
      </section>

      <footer className="relative mt-6 border-t border-line pt-3 text-center text-[11px] text-muted">
        Remisión generada con Ecomerxio POS · Este documento no es una factura de venta: la factura se expide aparte.
      </footer>
    </div>
  )
}

function Signature({ label, name, doc }: { label: string; name?: string; doc?: string }) {
  return (
    <div>
      <div className="h-12 border-b border-ink/60" />
      <div className="mt-1 text-xs font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className="text-xs">
        Nombre: {name ?? '______________________'}
        <br />
        C.C.: {doc ?? '______________________'}
      </div>
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
