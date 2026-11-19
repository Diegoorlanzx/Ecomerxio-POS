import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, useToast } from '../../components/ui'
import { apiBlob, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { download, money, qty, shortDate } from '../../lib/format'
import { http } from '../../lib/http'
import type { InvoicePreview, InvoicePreviewLine, Supplier, SupplierInvoiceDto } from '../../lib/purchasingTypes'
import { UNIT_LABELS } from '../../lib/posTypes'

const MATCHED_BY = { MEMORIA: 'Recordado', CODIGO_BARRAS: 'Código de barras', SKU: 'SKU' } as const
/** Códigos de unidad de la DIAN más comunes → texto corto. */
const UNITS: Record<string, string> = { '94': 'und', NIU: 'und', KGM: 'kg', GRM: 'g', LBR: 'lb', LTR: 'L', MLT: 'mL', MTR: 'm', BX: 'caja', PK: 'paquete' }
const unitLabel = (code?: string) => (code ? (UNITS[code] ?? code) : 'und')

/** Unidad de la DIAN → unidad de venta del producto nuevo. */
const UNIT_FROM_DIAN: Record<string, string> = { KGM: 'KILOGRAMO', GRM: 'GRAMO', LBR: 'LIBRA', LTR: 'LITRO', MLT: 'MILILITRO', MTR: 'METRO' }

interface NewProductDraft {
  name: string
  price: string
  unit: string
}

interface LineChoice {
  productId?: number
  productName?: string
  packFactor: string
  skip: boolean
  /** Si está, el producto no existe y se crea con estos datos al recibir. */
  create?: NewProductDraft
}

/** "2.600" (miles con punto) → 2600; "2600,5" o "2600.5" → 2600.5. */
const toNumber = (s: string) => {
  const x = s.trim().replace(/[$\s]/g, '')
  return Number(/^\d{1,3}(\.\d{3})+$/.test(x) ? x.replace(/\./g, '') : x.replace(',', '.'))
}
const draftReady = (d?: NewProductDraft) => !!d && d.name.trim() !== '' && d.price.trim() !== '' && toNumber(d.price) >= 0 && !Number.isNaN(toNumber(d.price))

/** Costo de una unidad de inventario según la factura (con IVA si el negocio no lo descuenta). */
function unitCost(l: InvoicePreviewLine, factor: number, withTax: boolean) {
  const units = l.quantity * (factor || 1)
  return units > 0 ? (l.lineTotal + (withTax ? l.tax : 0)) / units : 0
}

function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ''))
    r.onerror = () => reject(new Error('No se pudo leer el archivo'))
    r.readAsDataURL(file)
  })
}

/**
 * Fase 13E: la factura electrónica del proveedor (el XML o el ZIP que llega al correo) se convierte en una recepción.
 * Primero se ve qué trae y qué producto es cada línea; nada se guarda hasta confirmar. Requiere internet.
 */
export function ImportInvoiceModal({ suppliers, onClose, onDone }: { suppliers: Supplier[]; onClose: () => void; onDone: (inv: SupplierInvoiceDto) => void }) {
  const [file, setFile] = useState<{ name: string; content: string } | null>(null)
  const [preview, setPreview] = useState<InvoicePreview | null>(null)
  const [choices, setChoices] = useState<Record<number, LineChoice>>({})
  const [supplier, setSupplier] = useState<string>('')
  const [orderId, setOrderId] = useState<string>('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function pick(f: File | undefined) {
    if (!f) return
    setError('')
    setPreview(null)
    setBusy(true)
    try {
      const content = await readAsBase64(f)
      const p = await http<InvoicePreview>('/supplier-invoices/preview', { body: { fileName: f.name, contentBase64: content } })
      setFile({ name: f.name, content })
      setPreview(p)
      setSupplier(p.supplier ? String(p.supplier.id) : '')
      setOrderId('')
      const c: Record<number, LineChoice> = {}
      for (const l of p.lines) {
        c[l.index] = { productId: l.match?.productId, productName: l.match?.productName, packFactor: String(l.match?.packFactor ?? 1), skip: false }
      }
      setChoices(c)
    } catch (e) {
      setError(e instanceof ApiError || e instanceof Error ? e.message : 'No se pudo leer la factura')
    } finally {
      setBusy(false)
    }
  }

  const setLine = (i: number, patch: Partial<LineChoice>) => setChoices({ ...choices, [i]: { ...choices[i], ...patch } })
  const pending = preview
    ? preview.lines.filter((l) => {
        const c = choices[l.index]
        return !c?.skip && !c?.productId && !draftReady(c?.create)
      }).length
    : 0

  async function save() {
    if (!preview || !file) return
    setBusy(true)
    setError('')
    try {
      const inv = await http<SupplierInvoiceDto>('/supplier-invoices', {
        body: {
          fileName: file.name,
          contentBase64: file.content,
          supplierId: supplier && supplier !== 'new' ? Number(supplier) : undefined,
          createSupplier: supplier === 'new' || undefined,
          orderId: orderId ? Number(orderId) : undefined,
          lines: preview.lines.map((l) => {
            const c = choices[l.index]
            const create =
              !c.skip && !c.productId && c.create
                ? { name: c.create.name.trim(), price: toNumber(c.create.price), unit: c.create.unit }
                : undefined
            return { index: l.index, productId: c.skip ? undefined : c.productId, packFactor: Number(c.packFactor.replace(',', '.') || 1), skip: c.skip, create }
          }),
        },
      })
      onDone(inv)
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo importar')
    } finally {
      setBusy(false)
    }
  }

  const sameSupplier = preview?.supplier && supplier === String(preview.supplier.id)
  const creating = preview ? preview.lines.filter((l) => !choices[l.index]?.skip && !choices[l.index]?.productId && choices[l.index]?.create).length : 0
  const { can } = useAuth()
  const canCreate = can('PRODUCTOS_EDITAR')

  return (
    <Modal
      open
      wide
      title="Importar factura de proveedor"
      onClose={onClose}
      footer={
        preview ? (
          <>
            <span className="flex-1 self-center text-xs text-muted">
              {pending
                ? `Faltan ${pending} productos por elegir`
                : creating
                  ? `Listo para recibir · se crean ${creating} ${creating === 1 ? 'producto nuevo' : 'productos nuevos'}`
                  : 'Listo para recibir'}
            </span>
            <Button variant="secondary" onClick={onClose}>
              Cancelar
            </Button>
            <Button icon="inventory" onClick={save} loading={busy} disabled={!supplier || pending > 0}>
              Recibir mercancía
            </Button>
          </>
        ) : undefined
      }
    >
      <div className="space-y-4 text-sm">
        {error && <Banner>{error}</Banner>}
        {!preview && (
          <label className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed border-line px-4 py-10 text-center hover:bg-canvas">
            <Icon name={busy ? 'hourglass_top' : 'upload_file'} className="text-[36px] text-primary" />
            <span className="font-semibold">{busy ? 'Leyendo la factura…' : 'Elige el XML o el ZIP que te llegó al correo'}</span>
            <span className="text-xs text-muted">Se lee la factura electrónica (UBL de la DIAN). Nada se guarda hasta que confirmes.</span>
            <input type="file" accept=".xml,.zip,application/xml,text/xml,application/zip" className="hidden" onChange={(e) => pick(e.target.files?.[0])} />
          </label>
        )}
        {preview && (
          <>
            <div className="grid gap-2 rounded-xl bg-canvas p-3 sm:grid-cols-4">
              <Info label="Factura" value={preview.number} />
              <Info label="Proveedor" value={`${preview.supplierName ?? '—'}${preview.supplierTaxId ? ` · NIT ${preview.supplierTaxId}` : ''}`} />
              <Info label="Fecha / vence" value={`${preview.issueDate ?? '—'} / ${preview.dueDate ?? '—'}`} />
              <Info label="Total a pagar" value={money(preview.total)} />
            </div>
            {preview.warnings.map((w) => (
              <Banner key={w} tone="warning">
                {w}
              </Banner>
            ))}
            <div className="grid gap-3 sm:grid-cols-2">
              <SelectField label="Proveedor en Ecomerxio POS" value={supplier} onChange={(e) => (setSupplier(e.target.value), setOrderId(''))}>
                <option value="">Elige…</option>
                {!preview.supplier && <option value="new">Crear «{preview.supplierName ?? 'proveedor'}» con los datos de la factura</option>}
                {suppliers.filter((s) => s.active || s.id === preview.supplier?.id).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="¿Es de una orden de compra?"
                value={orderId}
                onChange={(e) => setOrderId(e.target.value)}
                disabled={!sameSupplier || !preview.openOrders.length}
                hint={sameSupplier && !preview.openOrders.length ? 'Este proveedor no tiene órdenes abiertas' : undefined}
              >
                <option value="">Sin orden (recepción directa)</option>
                {sameSupplier &&
                  preview.openOrders.map((o) => (
                    <option key={o.id} value={o.id}>
                      Orden #{o.number}
                      {o.expectedDate ? ` · llega ${o.expectedDate}` : ''}
                      {o.estimatedTotal !== undefined ? ` · ${money(o.estimatedTotal)}` : ''}
                    </option>
                  ))}
              </SelectField>
            </div>
            <p className="text-xs text-muted">
              {preview.costIncludesTax
                ? 'Como el negocio no es responsable de IVA, el IVA de la compra se suma al costo de cada producto.'
                : 'Como el negocio es responsable de IVA, el costo se toma sin IVA (el IVA es descontable).'}{' '}
              La cuenta por pagar queda por el total de la factura y con su fecha de vencimiento.
            </p>
            <div className="space-y-2">
              {preview.lines.map((l) => (
                <LineRow
                  key={l.index}
                  line={l}
                  choice={choices[l.index]}
                  withTax={preview.costIncludesTax}
                  canCreate={canCreate}
                  onChange={(patch) => setLine(l.index, patch)}
                />
              ))}
            </div>
            <Button variant="ghost" size="sm" icon="restart_alt" onClick={() => (setPreview(null), setFile(null))}>
              Elegir otro archivo
            </Button>
          </>
        )}
      </div>
    </Modal>
  )
}

function LineRow({
  line: l,
  choice: c,
  withTax,
  canCreate,
  onChange,
}: {
  line: InvoicePreviewLine
  choice: LineChoice
  withTax: boolean
  canCreate: boolean
  onChange: (patch: Partial<LineChoice>) => void
}) {
  const factor = Number(c.packFactor.replace(',', '.') || 0)
  const cost = unitCost(l, factor, withTax)
  // Precio sugerido: costo + 30 %, redondeado hacia arriba a $100.
  const suggested = cost > 0 ? Math.ceil((cost * 1.3) / 100) * 100 : 0

  function startCreate() {
    onChange({
      create: {
        name: l.description ?? '',
        price: suggested ? String(suggested) : '',
        unit: factor === 1 && l.unitCode ? (UNIT_FROM_DIAN[l.unitCode] ?? 'UNIDAD') : 'UNIDAD',
      },
    })
  }
  const setDraft = (patch: Partial<NewProductDraft>) => c.create && onChange({ create: { ...c.create, ...patch } })
  const ok = c.productId || draftReady(c.create)

  return (
    <div className={`rounded-xl border p-3 ${c.skip ? 'border-line-soft opacity-60' : ok ? 'border-line' : 'border-warning'}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="font-semibold">{l.description ?? `Línea ${l.index + 1}`}</div>
          <div className="text-xs text-muted">
            {[l.code && `Código ${l.code}`, l.barcode && `EAN ${l.barcode}`].filter(Boolean).join(' · ')}
            {l.code || l.barcode ? ' · ' : ''}
            {qty(l.quantity)} {unitLabel(l.unitCode)} × {money(l.unitPrice)} = {money(l.lineTotal)}
            {l.tax > 0 ? ` + IVA ${qty(l.taxRate)}% ${money(l.tax)}` : ''}
          </div>
        </div>
        <label className="flex items-center gap-1.5 text-xs text-muted">
          <input type="checkbox" checked={c.skip} onChange={(e) => onChange({ skip: e.target.checked })} className="accent-[var(--color-primary)]" />
          No recibir (flete, servicio…)
        </label>
      </div>
      {!c.skip && (
        <div className="mt-2 grid items-end gap-2 sm:grid-cols-[1fr_180px]">
          {c.productId ? (
            <div className="flex items-center gap-2 rounded-lg bg-canvas px-3 py-2">
              <Icon name="check_circle" className="text-[18px] text-success" />
              <span className="min-w-0 flex-1 truncate font-medium">{c.productName}</span>
              {l.match && l.match.productId === c.productId && <Chip>{MATCHED_BY[l.match.matchedBy]}</Chip>}
              <button type="button" className="text-xs font-semibold text-primary" onClick={() => onChange({ productId: undefined, productName: undefined })}>
                Cambiar
              </button>
            </div>
          ) : c.create ? (
            <div className="space-y-2 rounded-lg border border-primary/30 bg-primary-soft/40 p-3">
              <div className="flex items-center gap-2">
                <Icon name="add_box" className="text-[18px] text-primary" />
                <span className="flex-1 text-xs font-bold tracking-wide text-primary uppercase">Producto nuevo</span>
                <button type="button" className="text-xs font-semibold text-primary" onClick={() => onChange({ create: undefined })}>
                  Buscar uno que ya tengo
                </button>
              </div>
              <Field label="Nombre" value={c.create.name} onChange={(e) => setDraft({ name: e.target.value })} />
              <div className="grid gap-2 sm:grid-cols-2">
                <Field
                  label="Precio de venta (1 unidad)"
                  inputMode="decimal"
                  value={c.create.price}
                  onChange={(e) => setDraft({ price: e.target.value })}
                  hint={cost > 0 ? `Te cuesta ${money(cost)}${suggested ? ` · sugerido ${money(suggested)}` : ''}` : undefined}
                />
                <SelectField label="Se vende por" value={c.create.unit} onChange={(e) => setDraft({ unit: e.target.value })}>
                  {Object.entries(UNIT_LABELS).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
                </SelectField>
              </div>
              <p className="text-xs text-muted">
                {l.barcode && factor === 1 ? 'El costo, el IVA y el código de barras' : 'El costo y el IVA'} se toman de la factura. Las existencias entran al
                recibir.
              </p>
            </div>
          ) : (
            <div className="space-y-1.5">
              <ProductPicker placeholder="¿Qué producto es? Busca por nombre o código…" onPick={(p) => onChange({ productId: p.id, productName: p.name })} />
              {canCreate && (
                <button type="button" onClick={startCreate} className="flex items-center gap-1 text-xs font-semibold text-primary">
                  <Icon name="add" className="text-[16px]" />
                  No lo tengo: crear producto nuevo
                </button>
              )}
            </div>
          )}
          <Field
            label={`Unidades de inventario por ${unitLabel(l.unitCode)}`}
            inputMode="decimal"
            value={c.packFactor}
            onChange={(e) => onChange({ packFactor: e.target.value })}
            hint={factor > 0 ? `Entran ${qty(l.quantity * factor)} unidades` : undefined}
          />
        </div>
      )}
    </div>
  )
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className="truncate font-semibold">{value}</div>
    </div>
  )
}

/** Facturas de proveedores ya importadas, con su recepción y la copia del XML. */
export function SupplierInvoicesView({ suppliers }: { suppliers: Supplier[] }) {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState<SupplierInvoiceDto[] | null>(null)
  const [importing, setImporting] = useState(false)

  const load = useCallback(() => {
    http<SupplierInvoiceDto[]>('/supplier-invoices').then(setList).catch(() => setList([]))
  }, [])
  useEffect(load, [load])

  async function xml(x: SupplierInvoiceDto) {
    try {
      download(`factura-${x.invoiceNumber}.xml`, await apiBlob(`/supplier-invoices/${x.id}/xml`))
    } catch {
      toast('No se pudo descargar el XML', 'danger')
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">Sube el XML o el ZIP que te manda el proveedor: entra el inventario, el costo y la cuenta por pagar sin digitar.</p>
        {can('COMPRAS_RECIBIR') && (
          <Button icon="upload_file" onClick={() => setImporting(true)}>
            Importar factura
          </Button>
        )}
      </div>
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="description" title="Todavía no has importado facturas" text="Las facturas electrónicas de tus proveedores llegan al correo como XML o ZIP." />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {list.map((x) => (
            <div key={x.id} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="num font-semibold">{x.invoiceNumber}</span>
                  <Link to={`/proveedores/${x.supplierId}`} className="truncate font-semibold hover:underline">
                    {x.supplierName}
                  </Link>
                  {x.orderNumber && (
                    <Link to={`/compras/${x.orderId}`}>
                      <Chip tone="primary">Orden #{x.orderNumber}</Chip>
                    </Link>
                  )}
                </div>
                <div className="text-xs text-muted">
                  {x.issueDate ? `Emitida ${x.issueDate}` : shortDate(x.createdAt)}
                  {x.dueDate ? ` · vence ${x.dueDate}` : ''} · recepción #{x.receiptNumber} · {x.importedBy}
                </div>
              </div>
              <span className="num font-bold">{money(x.total)}</span>
              <button type="button" className="text-muted hover:text-primary" title="Descargar XML" onClick={() => xml(x)}>
                <Icon name="download" />
              </button>
            </div>
          ))}
        </Card>
      )}
      {importing && (
        <ImportInvoiceModal
          suppliers={suppliers}
          onClose={() => setImporting(false)}
          onDone={(inv) => {
            setImporting(false)
            toast(`Factura ${inv.invoiceNumber} recibida: inventario y cuenta por pagar actualizados`)
            load()
          }}
        />
      )}
    </div>
  )
}
