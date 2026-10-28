import { useEffect, useState } from 'react'
import { Banner, Button, Modal, Spinner } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { scaleFor, type CartLine } from '../../lib/cart'
import type { Receipt, SaleDto, StoreSettings } from '../../lib/posTypes'
import type { Remission } from '../../lib/remissionTypes'
import CheckoutModal, { type CheckoutPayload } from '../pos/CheckoutModal'
import { PrintArea, ReceiptView } from '../pos/Receipt'

/**
 * Factura en la caja una o varias remisiones entregadas del mismo cliente, por lo que quedó en su poder (sin lo
 * devuelto). Con "Fiado" queda en la cartera del cliente, como el corte de fin de mes.
 */
export default function InvoiceRemissions({ ids, onClose, onDone }: { ids: number[]; onClose: () => void; onDone: () => void }) {
  const { me } = useAuth()
  const currency = me?.tenant.currency ?? 'COP'
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [docs, setDocs] = useState<Remission[] | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([api<StoreSettings>('/settings/pos'), Promise.all(ids.map((id) => api<Remission>(`/remissions/${id}`)))])
      .then(([s, d]) => {
        setSettings(s)
        setDocs(d)
      })
      .catch((e) => setError(e.message))
  }, [ids])

  async function submit(p: CheckoutPayload): Promise<string | null> {
    try {
      const sale = await api<SaleDto>('/remissions/invoice', { body: { remissionIds: ids, payments: p.payments, electronicInvoice: !!p.electronicInvoice } })
      try {
        setReceipt(await api<Receipt>(`/pos/sales/${sale.id}/receipt`))
      } catch {
        onDone()
      }
      return null
    } catch (e) {
      return e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo facturar'
    }
  }

  if (receipt) {
    return (
      <Modal
        open
        title="Remisiones facturadas"
        onClose={onDone}
        footer={
          <>
            <Button variant="secondary" icon="print" className="flex-1" onClick={() => window.print()}>
              Imprimir
            </Button>
            <Button className="flex-1" onClick={onDone}>
              Listo
            </Button>
          </>
        }
      >
        <div className="rounded-xl bg-line-soft p-3">
          <ReceiptView r={receipt} />
        </div>
        <PrintArea r={receipt} />
      </Modal>
    )
  }

  if (error || !settings || !docs) {
    return (
      <Modal open title="Facturar remisiones" onClose={onClose}>
        {error ? <Banner>{error}</Banner> : <Spinner />}
      </Modal>
    )
  }

  const lines: CartLine[] = docs.flatMap((r) =>
    r.lines
      .filter((l) => Number(l.pendingQty) > 0)
      .map((l) => ({
        key: `${r.id}-${l.id}`,
        productId: l.productId ?? 0,
        presentationId: l.presentationId ?? 0,
        name: `${l.description} · ${r.code}`,
        presentationName: l.presentationName ?? '',
        factor: Number(l.factor),
        quantity: Number(l.pendingQty),
        listPrice: Number(l.unitPrice),
        unitPrice: Number(l.unitPrice),
        minPrice: 0,
        discountPct: Number(l.discountPct),
        taxRate: Number(l.taxRate),
        fixedTax: Number(l.fixedTax),
        unit: 'UNIDAD',
      })),
  )
  const first = docs[0]
  return (
    <CheckoutModal
      lines={lines}
      settings={settings}
      promotions={[]}
      categoryParents={new Map()}
      currency={currency}
      scale={scaleFor(currency)}
      title={docs.length === 1 ? `Facturar ${first.code}` : `Facturar ${docs.length} remisiones`}
      offline={false}
      fixedPrices
      initialInvoice={!!me?.invoicingEnabled && !!first.customerId}
      initialCustomer={first.customerId ? { customerId: first.customerId, name: first.customerName, docNumber: first.customerDoc ?? undefined } : undefined}
      onClose={onClose}
      onSubmit={submit}
    />
  )
}
