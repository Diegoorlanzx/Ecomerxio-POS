import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, pct, qty, shortDate } from '../../lib/format'
import type { ProductDto } from '../../lib/posTypes'
import { ORDER_STATUS, type CatalogItem, type PurchaseOrder, type Supplier, type SupplierPrice, type SupplierReturn } from '../../lib/purchasingTypes'
import { SupplierForm } from './SuppliersPage'

export default function SupplierDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const toast = useToast()
  const [s, setS] = useState<Supplier | null>(null)
  const [tab, setTab] = useState<'catalog' | 'orders' | 'prices' | 'returns'>('catalog')
  const [catalog, setCatalog] = useState<CatalogItem[]>([])
  const [orders, setOrders] = useState<PurchaseOrder[]>([])
  const [prices, setPrices] = useState<SupplierPrice[]>([])
  const [returns, setReturns] = useState<SupplierReturn[]>([])
  const [item, setItem] = useState<CatalogItem | ProductDto | null>(null)
  const [editing, setEditing] = useState(false)
  const [returning, setReturning] = useState(false)
  const [error, setError] = useState('')
  const edit = can('PROVEEDORES_EDITAR')

  const load = useCallback(() => {
    api<Supplier>(`/suppliers/${id}`).then(setS).catch((e) => setError(e.message))
    api<CatalogItem[]>(`/suppliers/${id}/catalog`).then(setCatalog).catch(() => {})
    api<PurchaseOrder[]>(`/suppliers/${id}/orders`).then(setOrders).catch(() => {})
    api<SupplierPrice[]>(`/suppliers/${id}/price-history`).then(setPrices).catch(() => {})
    api<SupplierReturn[]>(`/suppliers/${id}/returns`).then(setReturns).catch(() => {})
  }, [id])
  useEffect(load, [load])

  if (error) return <Banner>{error}</Banner>
  if (!s) return <Spinner />
  const m = s.metrics

  return (
    <div className="space-y-4">
      <Link to="/proveedores" className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Proveedores
      </Link>
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-[22px] font-semibold tracking-tight">{s.name}</h2>
              {!s.active && <Chip>Inactivo</Chip>}
            </div>
            <div className="text-sm text-muted">
              {[s.taxId && `NIT ${s.taxId}`, s.contactName, s.phone, s.email, s.city].filter(Boolean).join(' · ')}
            </div>
            <div className="mt-1 text-xs text-muted">
              {s.paymentTerms ?? 'Sin condiciones registradas'} · {s.creditDays ? `${s.creditDays} días de crédito` : 'de contado'}
              {s.orderDays ? ` · pedidos ${s.orderDays.toLowerCase()}` : ''}
              {s.deliveryDays ? ` · entrega ${s.deliveryDays.toLowerCase()}` : ''}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {s.whatsapp && (
              <a href={`https://wa.me/${s.whatsapp.replace(/\D/g, '').replace(/^3/, '573')}`} target="_blank" rel="noreferrer">
                <Button variant="soft" icon="chat">
                  WhatsApp
                </Button>
              </a>
            )}
            {edit && (
              <Button variant="secondary" icon="edit" onClick={() => setEditing(true)}>
                Editar
              </Button>
            )}
          </div>
        </div>
        <div className="num mt-4 grid grid-cols-2 gap-2 sm:grid-cols-5">
          <Info label="Saldo pendiente" value={money(m.balance)} tone={m.balance > 0 ? 'text-warning-ink' : ''} />
          <Info label="Comprado" value={money(m.purchasedTotal)} sub={`${m.receipts} entregas`} />
          <Info label="Tiempo de entrega" value={`${qty(m.effectiveLeadDays)} días`} sub={m.avgLeadDays !== undefined && m.avgLeadDays !== null ? `medido ${qty(m.avgLeadDays)} d` : s.leadTimeDays !== undefined && s.leadTimeDays !== null ? 'configurado' : 'el de la tienda'} />
          <Info label="Entregas a tiempo" value={m.onTimePct !== undefined && m.onTimePct !== null ? pct(m.onTimePct) : '—'} />
          <Info label="Cumplimiento" value={m.fillRatePct !== undefined && m.fillRatePct !== null ? pct(m.fillRatePct) : '—'} sub="de lo pedido llegó" />
        </div>
      </Card>

      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'catalog', label: 'Productos', count: catalog.length },
          { value: 'orders', label: 'Compras', count: orders.length },
          { value: 'prices', label: 'Precios' },
          { value: 'returns', label: 'Devoluciones', count: returns.length },
        ]}
      />

      {tab === 'catalog' && (
        <div className="space-y-3">
          {edit && <ProductPicker onPick={setItem} placeholder="Agregar un producto que vende este proveedor…" exclude={catalog.map((c) => c.productId)} />}
          {catalog.length === 0 ? (
            <Card>
              <EmptyState icon="inventory_2" title="Sin productos asociados" text="Agrega lo que le compras, con su presentación y costo, para que el sistema sugiera los pedidos." />
            </Card>
          ) : (
            <Card className="divide-y divide-line-soft">
              {catalog.map((c) => (
                <div key={c.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="truncate font-semibold">{c.productName}</span>
                      {c.isPrimary ? <Chip tone="primary">Principal</Chip> : <Chip>Alternativo</Chip>}
                    </div>
                    <div className="num text-xs text-muted">
                      {c.packName} ({qty(c.packFactor)} u) · mín {qty(c.minPacks)} · múltiplos de {qty(c.multiplePacks)} · stock {qty(c.productStock)}
                    </div>
                  </div>
                  {c.costPerPack !== undefined && (
                    <div className="num text-right">
                      <div className="font-bold">{money(c.costPerPack)}</div>
                      <div className="text-xs text-muted">{money(c.unitCost)} c/u</div>
                    </div>
                  )}
                  {edit && (
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" icon="edit" onClick={() => setItem(c)} aria-label="Editar" />
                      <Button
                        size="sm"
                        variant="ghost"
                        icon="delete"
                        aria-label="Quitar"
                        onClick={async () => {
                          await api(`/supplier-products/${c.id}`, { method: 'DELETE' })
                          toast('Producto quitado del proveedor')
                          load()
                        }}
                      />
                    </div>
                  )}
                </div>
              ))}
            </Card>
          )}
        </div>
      )}

      {tab === 'orders' &&
        (orders.length === 0 ? (
          <Card>
            <EmptyState icon="receipt_long" title="Sin compras a este proveedor" />
          </Card>
        ) : (
          <Card className="divide-y divide-line-soft">
            {orders.map((o) => (
              <Link key={o.id} to={`/compras/${o.id}`} className="flex items-center gap-3 px-4 py-3 hover:bg-canvas">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="num font-semibold">Pedido #{o.number}</span>
                    <Chip tone={ORDER_STATUS[o.status].tone}>{ORDER_STATUS[o.status].label}</Chip>
                    {o.late && <Chip tone="danger">Retrasado</Chip>}
                  </div>
                  <div className="text-xs text-muted">
                    {shortDate(o.createdAt)} · {o.lines.length} productos {o.expectedDate ? `· llega ${o.expectedDate}` : ''}
                  </div>
                </div>
                {o.estimatedTotal !== undefined && <span className="num font-bold">{money(o.estimatedTotal)}</span>}
              </Link>
            ))}
          </Card>
        ))}

      {tab === 'prices' &&
        (prices.length === 0 ? (
          <Card>
            <EmptyState icon="sell" title="Sin cambios de precio registrados" />
          </Card>
        ) : (
          <Card className="divide-y divide-line-soft">
            {prices.map((h, i) => {
              const up = h.oldCostPerUnit !== undefined && h.oldCostPerUnit !== null && h.newCostPerUnit > h.oldCostPerUnit
              return (
                <div key={i} className="flex items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">{h.productName}</div>
                    <div className="text-xs text-muted">
                      {h.source === 'COMPRA' ? 'Según factura' : 'Cambio manual'} · {h.userName ?? 'Sistema'} · {dateTime(h.createdAt)}
                    </div>
                  </div>
                  <div className={`num text-sm font-semibold ${up ? 'text-danger-ink' : ''}`}>
                    {h.oldCostPerUnit !== undefined && h.oldCostPerUnit !== null ? money(h.oldCostPerUnit) : '—'} → {money(h.newCostPerUnit)} c/u
                  </div>
                </div>
              )
            })}
          </Card>
        ))}

      {tab === 'returns' && (
        <div className="space-y-3">
          {can('INVENTARIO_AJUSTAR') && (
            <Button variant="secondary" icon="assignment_return" onClick={() => setReturning(true)}>
              Devolver mercancía
            </Button>
          )}
          {returns.length === 0 ? (
            <Card>
              <EmptyState icon="assignment_return" title="Sin devoluciones" />
            </Card>
          ) : (
            <Card className="divide-y divide-line-soft">
              {returns.map((r) => (
                <div key={r.id} className="flex items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">
                      {qty(r.quantity)} × {r.productName}
                    </div>
                    <div className="text-xs text-muted">
                      {r.reason} · {r.userName} · {dateTime(r.createdAt)}
                    </div>
                  </div>
                  <span className="num text-sm font-semibold">{money(r.quantity * r.unitCost)}</span>
                </div>
              ))}
            </Card>
          )}
        </div>
      )}

      {editing && (
        <SupplierForm
          supplier={s}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false)
            toast('Proveedor actualizado')
            load()
          }}
        />
      )}
      {item && (
        <CatalogItemForm
          supplierId={s.id}
          item={item}
          onClose={() => setItem(null)}
          onSaved={() => {
            setItem(null)
            toast('Catálogo actualizado')
            load()
          }}
        />
      )}
      {returning && (
        <ReturnForm
          supplierId={s.id}
          catalog={catalog}
          onClose={() => setReturning(false)}
          onSaved={() => {
            setReturning(false)
            toast('Devolución registrada')
            load()
          }}
        />
      )}
    </div>
  )
}

function Info({ label, value, sub, tone = '' }: { label: string; value: string; sub?: string; tone?: string }) {
  return (
    <div className="rounded-xl bg-canvas p-3">
      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className={`font-bold ${tone}`}>{value}</div>
      {sub && <div className="text-[11px] text-muted">{sub}</div>}
    </div>
  )
}

function CatalogItemForm({ supplierId, item, onClose, onSaved }: { supplierId: number; item: CatalogItem | ProductDto; onClose: () => void; onSaved: () => void }) {
  const existing = 'packName' in item ? item : null
  const product = 'packName' in item ? null : item
  const name = existing ? existing.productName : product!.name
  const [f, setF] = useState({
    packName: existing?.packName ?? 'Unidad',
    packFactor: String(existing?.packFactor ?? 1),
    costPerPack: String(existing?.costPerPack ?? (product?.lastCost ?? '')),
    minPacks: String(existing?.minPacks ?? 1),
    multiplePacks: String(existing?.multiplePacks ?? 1),
    supplierSku: existing?.supplierSku ?? '',
    isPrimary: existing?.isPrimary ?? false,
  })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const unit = Number(f.costPerPack || 0) / Math.max(Number(f.packFactor || 1), 0.001)

  async function save() {
    setSaving(true)
    setFields({})
    try {
      await api(`/suppliers/${supplierId}/catalog`, {
        body: {
          productId: existing ? existing.productId : product!.id,
          packName: f.packName,
          packFactor: Number(f.packFactor.replace(',', '.')),
          costPerPack: Number(f.costPerPack.replace(/\D/g, '')),
          minPacks: Number(f.minPacks.replace(',', '.')),
          multiplePacks: Number(f.multiplePacks.replace(',', '.')),
          supplierSku: f.supplierSku || undefined,
          isPrimary: f.isPrimary,
        },
      })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { packName: e.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={name}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving}>
            Guardar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">¿Cómo te lo vende este proveedor?</p>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Presentación de compra" value={f.packName} onChange={set('packName')} error={fields.packName} placeholder="Caja x24" />
          <Field label="Unidades que trae" inputMode="decimal" value={f.packFactor} onChange={set('packFactor')} error={fields.packFactor} />
          <Field label="Costo de la presentación" inputMode="numeric" value={f.costPerPack} onChange={set('costPerPack')} error={fields.costPerPack} hint={`= ${money(unit)} por unidad`} />
          <Field label="Código del proveedor" value={f.supplierSku} onChange={set('supplierSku')} />
          <Field label="Pedido mínimo (presentaciones)" inputMode="decimal" value={f.minPacks} onChange={set('minPacks')} error={fields.minPacks} />
          <Field label="Se pide en múltiplos de" inputMode="decimal" value={f.multiplePacks} onChange={set('multiplePacks')} error={fields.multiplePacks} />
        </div>
        <Toggle checked={f.isPrimary} onChange={(v) => setF({ ...f, isPrimary: v })} label="Proveedor principal de este producto" hint="Las sugerencias de compra usan el principal" />
      </div>
    </Modal>
  )
}

function ReturnForm({ supplierId, catalog, onClose, onSaved }: { supplierId: number; catalog: CatalogItem[]; onClose: () => void; onSaved: () => void }) {
  const [product, setProduct] = useState<{ id: number; name: string } | null>(null)
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  async function save() {
    if (!product) return
    setSaving(true)
    setError('')
    try {
      await api(`/suppliers/${supplierId}/returns`, { body: { productId: product.id, quantity: Number(quantity.replace(',', '.')), reason } })
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo registrar')
    } finally {
      setSaving(false)
    }
  }
  return (
    <Modal
      open
      title="Devolver mercancía al proveedor"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving} disabled={!product || !quantity || !reason}>
            Registrar devolución
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        {product ? (
          <div className="flex items-center justify-between rounded-xl bg-canvas px-3 py-2 text-sm">
            <b>{product.name}</b>
            <button type="button" className="text-primary" onClick={() => setProduct(null)}>
              Cambiar
            </button>
          </div>
        ) : (
          <>
            <ProductPicker onPick={(p) => setProduct({ id: p.id, name: p.name })} />
            {catalog.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {catalog.slice(0, 8).map((c) => (
                  <button key={c.id} type="button" onClick={() => setProduct({ id: c.productId, name: c.productName })} className="rounded-full bg-line-soft px-3 py-1 text-xs font-semibold">
                    {c.productName}
                  </button>
                ))}
              </div>
            )}
          </>
        )}
        <Field label="Cantidad (unidades)" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: llegó vencido, producto equivocado" />
        <p className="text-xs text-muted">Las unidades salen del inventario y quedan registradas a nombre del proveedor.</p>
      </div>
    </Modal>
  )
}
