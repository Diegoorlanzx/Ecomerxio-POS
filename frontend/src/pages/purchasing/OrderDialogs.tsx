import { useEffect, useState } from 'react'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Field, Icon, Modal, SelectField, TextArea } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { ProductDto } from '../../lib/posTypes'
import type { CatalogItem, PurchaseOrder, Supplier } from '../../lib/purchasingTypes'

interface EditLine {
  productId: number
  name: string
  packName: string
  packFactor: number
  packs: string
  costPerPack: string
}

/** Crear o editar una orden de compra a mano. */
export function OrderEditor({ suppliers, order, onClose, onSaved }: { suppliers: Supplier[]; order?: PurchaseOrder; onClose: () => void; onSaved: (o: PurchaseOrder) => void }) {
  const [supplierId, setSupplierId] = useState<number | ''>(order?.supplierId ?? '')
  const [catalog, setCatalog] = useState<CatalogItem[]>([])
  const [lines, setLines] = useState<EditLine[]>(
    order?.lines.map((l) => ({ productId: l.productId, name: l.productName, packName: l.packName, packFactor: l.packFactor, packs: String(l.packsOrdered), costPerPack: String(l.costPerPack ?? 0) })) ?? [],
  )
  const [expectedDate, setExpectedDate] = useState(order?.expectedDate ?? '')
  const [notes, setNotes] = useState(order?.notes ?? '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (supplierId) api<CatalogItem[]>(`/suppliers/${supplierId}/catalog`).then(setCatalog).catch(() => setCatalog([]))
  }, [supplierId])

  function add(p: ProductDto | CatalogItem) {
    const productId = 'packName' in p ? p.productId : p.id
    if (lines.some((l) => l.productId === productId)) return
    const c = 'packName' in p ? p : catalog.find((x) => x.productId === p.id)
    setLines([
      ...lines,
      {
        productId,
        name: 'packName' in p ? p.productName : p.name,
        packName: c?.packName ?? 'Unidad',
        packFactor: c?.packFactor ?? 1,
        packs: String(c?.minPacks ?? 1),
        costPerPack: String(c?.costPerPack ?? ('lastCost' in p ? p.lastCost ?? 0 : 0)),
      },
    ])
  }

  const total = lines.reduce((s, l) => s + Number(l.packs || 0) * Number(l.costPerPack || 0), 0)

  async function save() {
    if (!supplierId) return setError('Elige el proveedor')
    setSaving(true)
    setError('')
    const body = {
      supplierId,
      expectedDate: expectedDate || undefined,
      notes: notes || undefined,
      lines: lines.map((l) => ({ productId: l.productId, packName: l.packName, packFactor: l.packFactor, packs: Number(l.packs.replace(',', '.')), costPerPack: Number(l.costPerPack.replace(/\D/g, '')) })),
    }
    try {
      onSaved(order ? await api<PurchaseOrder>(`/purchases/${order.id}`, { method: 'PUT', body }) : await api<PurchaseOrder>('/purchases', { body }))
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={order ? `Editar pedido #${order.number}` : 'Nueva orden de compra'}
      onClose={onClose}
      footer={
        <>
          <span className="num flex-1 self-center font-bold">Total estimado {money(total)}</span>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button onClick={save} loading={saving} disabled={!lines.length}>
            Guardar borrador
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Proveedor" value={supplierId} disabled={!!order} onChange={(e) => (setSupplierId(e.target.value ? Number(e.target.value) : ''), setLines([]))}>
            <option value="">Elige…</option>
            {suppliers.filter((s) => s.active).map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </SelectField>
          <Field label="Fecha prevista de entrega" type="date" value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} hint="Vacía = se calcula con el tiempo de entrega" />
        </div>
        {supplierId && (
          <>
            <ProductPicker onPick={add} exclude={lines.map((l) => l.productId)} />
            {catalog.filter((c) => !lines.some((l) => l.productId === c.productId)).length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {catalog
                  .filter((c) => !lines.some((l) => l.productId === c.productId))
                  .slice(0, 12)
                  .map((c) => (
                    <button key={c.id} type="button" onClick={() => add(c)} className="rounded-full bg-primary-tint px-3 py-1 text-xs font-semibold text-primary">
                      + {c.productName}
                    </button>
                  ))}
              </div>
            )}
          </>
        )}
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          {lines.length === 0 && <p className="p-4 text-sm text-muted">Agrega productos al pedido.</p>}
          {lines.map((l, i) => (
            <div key={l.productId} className="grid grid-cols-[1fr_auto] items-end gap-2 p-3 sm:grid-cols-[1.5fr_1fr_0.7fr_1fr_auto]">
              <div className="col-span-2 text-sm font-semibold sm:col-span-1 sm:self-center">{l.name}</div>
              <Field label="Presentación" value={l.packName} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, packName: e.target.value } : x)))} />
              <Field label="Cantidad" inputMode="decimal" value={l.packs} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, packs: e.target.value } : x)))} hint={`${qty(Number(l.packs || 0) * l.packFactor)} u`} />
              <Field label="Costo c/u pres." inputMode="numeric" value={l.costPerPack} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, costPerPack: e.target.value } : x)))} />
              <button type="button" aria-label="Quitar" onClick={() => setLines(lines.filter((_, j) => j !== i))} className="flex h-12 w-10 items-center justify-center text-muted hover:text-danger-ink">
                <Icon name="delete" />
              </button>
            </div>
          ))}
        </div>
        <TextArea label="Notas para el proveedor" value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} />
      </div>
    </Modal>
  )
}

interface RecvLine {
  orderLineId?: number
  productId: number
  name: string
  pending: number
  received: string
  rejected: string
  rejectReason: string
  unitCost: string
  lotCode: string
  expiryDate: string
  trackLots: boolean
  trackSerials?: boolean
  /** Uno por línea o separados por espacio/coma. */
  serials?: string
}

const serialList = (t?: string) => [...new Set((t ?? '').split(/[\s,;]+/).map((x) => x.trim().toUpperCase()).filter(Boolean))]

/** Recibir mercancía de una orden: lo que llegó, lo que faltó o vino mal, costo real, lote y vencimiento. */
export function ReceiveModal({ order, onClose, onDone }: { order: PurchaseOrder; onClose: () => void; onDone: (o: PurchaseOrder) => void }) {
  const [lines, setLines] = useState<RecvLine[]>(
    order.lines
      .filter((l) => l.unitsPending > 0)
      .map((l) => ({
        orderLineId: l.id,
        productId: l.productId,
        name: l.productName,
        pending: l.unitsPending,
        received: String(l.unitsPending),
        rejected: '',
        rejectReason: 'FALTANTE',
        unitCost: l.costPerPack !== undefined ? String(Math.round((l.costPerPack / l.packFactor) * 100) / 100) : '',
        lotCode: '',
        expiryDate: '',
        trackLots: false,
      })),
  )
  const [invoiceNumber, setInvoiceNumber] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    Promise.all(lines.map((l) => api<ProductDto>(`/products/${l.productId}`).catch(() => null))).then((ps) =>
      setLines((ls) => ls.map((l, i) => ({ ...l, trackLots: !!ps[i]?.trackLots, trackSerials: !!ps[i]?.trackSerials }))),
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const set = (i: number, patch: Partial<RecvLine>) => setLines(lines.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  const total = lines.reduce((s, l) => s + Number(l.received || 0) * Number(l.unitCost || 0), 0)

  async function save() {
    setSaving(true)
    setError('')
    try {
      onDone(
        await api<PurchaseOrder>(`/purchases/${order.id}/receive`, {
          body: {
            invoiceNumber: invoiceNumber || undefined,
            lines: lines.map((l) => ({
              orderLineId: l.orderLineId,
              unitsReceived: Number(l.received.replace(',', '.') || 0),
              unitsRejected: Number(l.rejected.replace(',', '.') || 0),
              rejectReason: Number(l.rejected || 0) > 0 ? l.rejectReason : undefined,
              unitCost: l.unitCost ? Number(l.unitCost.replace(',', '.')) : undefined,
              lotCode: l.lotCode || undefined,
              expiryDate: l.expiryDate || undefined,
              serials: l.trackSerials ? serialList(l.serials) : undefined,
            })),
          },
        }),
      )
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo registrar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={`Recibir pedido #${order.number} · ${order.supplierName}`}
      onClose={onClose}
      footer={
        <>
          <span className="num flex-1 self-center font-bold">Valor recibido {money(total)}</span>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button icon="inventory" onClick={save} loading={saving}>
            Registrar recepción
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="Número de factura del proveedor" value={invoiceNumber} onChange={(e) => setInvoiceNumber(e.target.value)} />
        <p className="text-xs text-muted">Cantidades en unidades. Si algo no llegó o vino mal, anótalo en "No recibido": queda registrado para el cumplimiento del proveedor.</p>
        <div className="space-y-2">
          {lines.map((l, i) => (
            <div key={l.productId} className="rounded-xl border border-line p-3">
              <div className="mb-2 flex justify-between text-sm">
                <b>{l.name}</b>
                <span className="num text-muted">pendientes {qty(l.pending)}</span>
              </div>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Field label="Llegaron bien" inputMode="decimal" value={l.received} onChange={(e) => set(i, { received: e.target.value })} />
                <Field label="No recibido" inputMode="decimal" value={l.rejected} onChange={(e) => set(i, { rejected: e.target.value })} />
                <SelectField label="Motivo" value={l.rejectReason} onChange={(e) => set(i, { rejectReason: e.target.value })} disabled={!Number(l.rejected || 0)}>
                  <option value="FALTANTE">Faltó</option>
                  <option value="INCORRECTO">Producto equivocado</option>
                  <option value="DANADO">Llegó dañado</option>
                </SelectField>
                <Field label="Costo por unidad" inputMode="decimal" value={l.unitCost} onChange={(e) => set(i, { unitCost: e.target.value })} hint="Según la factura" />
                {l.trackLots && (
                  <>
                    <Field label="Lote" value={l.lotCode} onChange={(e) => set(i, { lotCode: e.target.value })} />
                    <Field label="Vence" type="date" value={l.expiryDate} onChange={(e) => set(i, { expiryDate: e.target.value })} />
                  </>
                )}
                {l.trackSerials && (
                  <TextArea
                    className="col-span-2 sm:col-span-4"
                    rows={2}
                    label={`Seriales / IMEI (${serialList(l.serials).length} de ${l.received || 0})`}
                    value={l.serials ?? ''}
                    onChange={(e) => set(i, { serials: e.target.value })}
                  />
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}

/** Recepción directa: el proveedor llegó con mercancía sin una orden previa. */
export function DirectReceiptModal({ suppliers, onClose, onDone }: { suppliers: Supplier[]; onClose: () => void; onDone: () => void }) {
  const [supplierId, setSupplierId] = useState<number | ''>('')
  const [lines, setLines] = useState<RecvLine[]>([])
  const [invoiceNumber, setInvoiceNumber] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const set = (i: number, patch: Partial<RecvLine>) => setLines(lines.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  const total = lines.reduce((s, l) => s + Number(l.received || 0) * Number(l.unitCost || 0), 0)

  async function save() {
    setSaving(true)
    setError('')
    try {
      await api('/receipts', {
        body: {
          supplierId,
          invoiceNumber: invoiceNumber || undefined,
          lines: lines.map((l) => ({
            productId: l.productId,
            unitsReceived: Number(l.received.replace(',', '.') || 0),
            unitCost: l.unitCost ? Number(l.unitCost.replace(',', '.')) : undefined,
            lotCode: l.lotCode || undefined,
            expiryDate: l.expiryDate || undefined,
            serials: l.trackSerials ? serialList(l.serials) : undefined,
          })),
        },
      })
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo registrar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      wide
      title="Recibir mercancía sin orden"
      onClose={onClose}
      footer={
        <>
          <span className="num flex-1 self-center font-bold">Valor {money(total)}</span>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button icon="inventory" onClick={save} loading={saving} disabled={!supplierId || !lines.length}>
            Registrar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Proveedor" value={supplierId} onChange={(e) => setSupplierId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">Elige…</option>
            {suppliers.filter((s) => s.active).map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </SelectField>
          <Field label="Número de factura" value={invoiceNumber} onChange={(e) => setInvoiceNumber(e.target.value)} />
        </div>
        <ProductPicker
          exclude={lines.map((l) => l.productId)}
          onPick={(p) =>
            setLines([
              ...lines,
              { productId: p.id, name: p.name, pending: 0, received: '1', rejected: '', rejectReason: 'FALTANTE', unitCost: String(p.lastCost ?? ''), lotCode: '', expiryDate: '', trackLots: p.trackLots, trackSerials: p.trackSerials },
            ])
          }
        />
        {lines.map((l, i) => (
          <div key={l.productId} className="rounded-xl border border-line p-3">
            <div className="mb-2 flex justify-between text-sm">
              <b>{l.name}</b>
              <button type="button" className="text-xs text-danger-ink" onClick={() => setLines(lines.filter((_, j) => j !== i))}>
                Quitar
              </button>
            </div>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Field label="Unidades" inputMode="decimal" value={l.received} onChange={(e) => set(i, { received: e.target.value })} />
              <Field label="Costo por unidad" inputMode="decimal" value={l.unitCost} onChange={(e) => set(i, { unitCost: e.target.value })} />
              {l.trackLots && (
                <>
                  <Field label="Lote" value={l.lotCode} onChange={(e) => set(i, { lotCode: e.target.value })} />
                  <Field label="Vence" type="date" value={l.expiryDate} onChange={(e) => set(i, { expiryDate: e.target.value })} />
                </>
              )}
              {l.trackSerials && (
                <TextArea
                  className="col-span-2 sm:col-span-4"
                  rows={2}
                  label={`Seriales / IMEI (${serialList(l.serials).length} de ${l.received || 0})`}
                  value={l.serials ?? ''}
                  onChange={(e) => set(i, { serials: e.target.value })}
                />
              )}
            </div>
          </div>
        ))}
      </div>
    </Modal>
  )
}
