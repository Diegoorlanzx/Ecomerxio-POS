import { useRef, useState } from 'react'
import { Banner, Button, Field, Icon, Modal, SelectField, TextArea, Toggle } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { imageToDataUrl, money } from '../../lib/format'
import { UNIT_LABELS, type Category, type PresentationDto, type ProductDto } from '../../lib/posTypes'

interface PresRow extends PresentationDto {
  key: string
}

const newKey = () => Math.random().toString(36).slice(2)

/** Crear o editar un producto con sus presentaciones (unidad, paquete, caja...). */
export default function ProductForm({
  product,
  categories,
  defaultTax,
  onClose,
  onSaved,
}: {
  product: ProductDto | null
  categories: Category[]
  defaultTax: { name: string; rate: number }
  onClose: () => void
  onSaved: (p: ProductDto, created: boolean) => void
}) {
  const { can } = useAuth()
  const seeCosts = can('COSTOS_VER')
  const editPrices = can('PRODUCTOS_PRECIOS') || !product
  const fileRef = useRef<HTMLInputElement>(null)
  const [f, setF] = useState({
    name: product?.name ?? '',
    sku: product?.sku ?? '',
    brand: product?.brand ?? '',
    description: product?.description ?? '',
    categoryId: product?.categoryId ? String(product.categoryId) : '',
    unit: product?.unit ?? 'UNIDAD',
    trackStock: product?.trackStock ?? true,
    cost: product?.cost !== undefined ? String(product.cost) : '',
    initialStock: '',
    stockMin: String(product?.stockMin ?? 0),
    stockMax: String(product?.stockMax ?? 0),
    safetyStock: String(product?.safetyStock ?? 0),
    taxName: product?.taxName ?? defaultTax.name,
    taxRate: String(product?.taxRate ?? defaultTax.rate),
    fixedTax: String(product?.fixedTax ?? 0),
    favorite: product?.favorite ?? false,
    photo: product?.photo,
    priceChangeReason: '',
  })
  const [rows, setRows] = useState<PresRow[]>(
    product
      ? product.presentations.filter((p) => p.active !== false).map((p) => ({ ...p, key: newKey() }))
      : [{ key: newKey(), name: 'Unidad', factor: 1, barcode: '', price: 0, minPrice: 0, isBase: true }],
  )
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setF({ ...f, [k]: e.target.value })
  const setRow = (key: string, patch: Partial<PresRow>) => setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  const base = rows.find((r) => r.isBase)
  const cost = Number(f.cost || 0)
  const priceChanged = product?.presentations.some((p) => {
    const r = rows.find((x) => x.id === p.id)
    return r && (Number(r.price) !== Number(p.price) || Number(r.minPrice) !== Number(p.minPrice))
  })

  async function pickPhoto(file: File) {
    try {
      const data = await imageToDataUrl(file)
      if (data.length > 400_000) {
        setFields({ ...fields, photo: 'la foto sigue muy pesada; prueba con otra' })
        return
      }
      setF({ ...f, photo: data })
    } catch {
      setFields({ ...fields, photo: 'no se pudo leer la imagen' })
    }
  }

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    const num = (v: string) => (v === '' ? undefined : Number(v.replace(',', '.')))
    const body = {
      name: f.name,
      sku: f.sku || undefined,
      brand: f.brand,
      description: f.description,
      categoryId: f.categoryId ? Number(f.categoryId) : undefined,
      unit: f.unit,
      trackStock: f.trackStock,
      cost: seeCosts ? num(f.cost) : undefined,
      initialStock: product ? undefined : num(f.initialStock),
      stockMin: num(f.stockMin),
      stockMax: num(f.stockMax),
      safetyStock: num(f.safetyStock),
      taxName: f.taxName,
      taxRate: num(f.taxRate),
      fixedTax: num(f.fixedTax),
      favorite: f.favorite,
      photo: f.photo,
      priceChangeReason: f.priceChangeReason || undefined,
      presentations: rows.map((r) => ({
        id: r.id,
        name: r.name,
        factor: Number(String(r.factor).replace(',', '.')),
        barcode: r.barcode || undefined,
        price: Number(r.price),
        minPrice: Number(r.minPrice || 0),
        isBase: r.isBase,
      })),
    }
    try {
      const saved = product
        ? await api<ProductDto>(`/products/${product.id}`, { method: 'PUT', body })
        : await api<ProductDto>('/products', { body })
      onSaved(saved, !product)
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        setError(Object.keys(e.fields).length ? (e.fields.presentations ?? '') : e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  const parents = categories.filter((c) => !c.parentId)

  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={product ? `Editar ${product.name}` : 'Nuevo producto'}
      footer={
        <>
          <Button variant="secondary" className="flex-1 sm:flex-none" onClick={onClose}>
            Cancelar
          </Button>
          <div className="hidden flex-1 sm:block" />
          <Button className="flex-1 sm:flex-none" icon="save" onClick={save} loading={saving}>
            Guardar producto
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {error && <Banner>{error}</Banner>}
        <section className="flex gap-4">
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            className="relative flex h-24 w-24 shrink-0 items-center justify-center overflow-hidden rounded-2xl border border-dashed border-line bg-canvas text-muted hover:border-primary"
            aria-label="Foto del producto"
          >
            {f.photo ? <img src={f.photo} alt="" className="h-full w-full object-contain" /> : <Icon name="add_a_photo" className="text-[28px]" />}
          </button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && pickPhoto(e.target.files[0])} />
          <div className="flex-1 space-y-3">
            <Field label="Nombre" value={f.name} onChange={set('name')} error={fields.name} placeholder="Ej: Cerveza Águila Light 330ml lata" />
            <div className="flex flex-wrap items-center gap-2">
              {f.photo && (
                <Button size="sm" variant="ghost" icon="delete" onClick={() => setF({ ...f, photo: undefined })}>
                  Quitar foto
                </Button>
              )}
              {fields.photo && <span className="text-xs font-medium text-danger-ink">{fields.photo}</span>}
            </div>
          </div>
        </section>

        <section className="grid gap-3 sm:grid-cols-3">
          <Field label="SKU (código interno)" value={f.sku} onChange={set('sku')} error={fields.sku} hint={product ? undefined : 'Vacío = se genera solo'} />
          <Field label="Marca" value={f.brand} onChange={set('brand')} error={fields.brand} />
          <SelectField label="Categoría" value={f.categoryId} onChange={set('categoryId')} error={fields.categoryId}>
            <option value="">Sin categoría</option>
            {parents.map((p) => (
              <optgroup key={p.id} label={p.name}>
                <option value={p.id}>{p.name}</option>
                {categories
                  .filter((c) => c.parentId === p.id)
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      {p.name} › {c.name}
                    </option>
                  ))}
              </optgroup>
            ))}
          </SelectField>
          <SelectField label="Unidad de medida" value={f.unit} onChange={set('unit')} error={fields.unit}>
            {Object.entries(UNIT_LABELS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </SelectField>
          <TextArea label="Descripción" value={f.description} onChange={set('description')} className="sm:col-span-2" rows={2} />
        </section>

        <section>
          <div className="mb-2 flex items-center justify-between">
            <div>
              <h3 className="font-semibold">Presentaciones y precios</h3>
              <p className="text-xs text-muted">La unidad base es la que cuenta el inventario. Una caja x24 descuenta 24 unidades.</p>
            </div>
            <Button
              size="sm"
              variant="soft"
              icon="add"
              disabled={!editPrices}
              onClick={() => setRows([...rows, { key: newKey(), name: '', factor: 6, barcode: '', price: 0, minPrice: 0, isBase: false }])}
            >
              Agregar
            </Button>
          </div>
          {fields.presentations && <p className="mb-2 text-xs font-medium text-danger-ink">{fields.presentations}</p>}
          <div className="space-y-2">
            {rows.map((r) => {
              const cost4 = cost * Number(r.factor || 0)
              const m = Number(r.price) > 0 && seeCosts && cost > 0 ? ((Number(r.price) - cost4) / Number(r.price)) * 100 : null
              return (
                <div key={r.key} className={`rounded-xl border p-3 ${r.isBase ? 'border-primary/40 bg-primary-tint/50' : 'border-line'}`}>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-[1.4fr_0.7fr_1.3fr_1fr_1fr_auto]">
                    <Field label={r.isBase ? 'Unidad base' : 'Nombre'} value={r.name} onChange={(e) => setRow(r.key, { name: e.target.value })} />
                    <Field
                      label="Contiene"
                      inputMode="decimal"
                      value={String(r.factor)}
                      disabled={r.isBase}
                      onChange={(e) => setRow(r.key, { factor: e.target.value as unknown as number })}
                    />
                    <Field label="Código de barras" value={r.barcode ?? ''} onChange={(e) => setRow(r.key, { barcode: e.target.value })} className="col-span-2 sm:col-span-1" />
                    <Field
                      label="Precio venta"
                      inputMode="numeric"
                      value={String(r.price)}
                      disabled={!editPrices}
                      onChange={(e) => setRow(r.key, { price: Number(e.target.value.replace(/\D/g, '')) })}
                    />
                    <Field
                      label="Precio mínimo"
                      inputMode="numeric"
                      value={String(r.minPrice)}
                      disabled={!editPrices}
                      onChange={(e) => setRow(r.key, { minPrice: Number(e.target.value.replace(/\D/g, '')) })}
                    />
                    <div className="col-span-2 flex items-end justify-end sm:col-span-1">
                      {!r.isBase && (
                        <Button size="md" variant="ghost" icon="delete" aria-label="Quitar presentación" onClick={() => setRows(rows.filter((x) => x.key !== r.key))} />
                      )}
                    </div>
                  </div>
                  {m !== null && (
                    <p className={`num mt-1.5 text-xs font-semibold ${m < 10 ? 'text-danger-ink' : m < 20 ? 'text-warning-ink' : 'text-success-ink'}`}>
                      Costo {money(cost4)} · margen {m.toFixed(1)}% (antes de impuestos)
                    </p>
                  )}
                </div>
              )
            })}
          </div>
          {product && priceChanged && (
            <Field
              className="mt-3"
              label="Motivo del cambio de precio"
              value={f.priceChangeReason}
              onChange={set('priceChangeReason')}
              placeholder="Ej: subió el proveedor"
            />
          )}
          {base && !base.barcode && <p className="mt-2 text-xs text-muted">Tip: con el código de barras el producto se agrega a la venta con el lector.</p>}
        </section>

        <section className="grid gap-3 sm:grid-cols-4">
          {seeCosts && (
            <Field
              label={`Costo por ${UNIT_LABELS[f.unit]?.toLowerCase() ?? 'unidad'}`}
              inputMode="decimal"
              value={f.cost}
              onChange={set('cost')}
              error={fields.cost}
              hint={product ? 'Cambia solo si el costo real cambió' : undefined}
            />
          )}
          {!product && (
            <Field label="Existencia inicial" inputMode="decimal" value={f.initialStock} onChange={set('initialStock')} error={fields.initialStock} disabled={!f.trackStock} />
          )}
          <Field label="Stock mínimo" inputMode="decimal" value={f.stockMin} onChange={set('stockMin')} error={fields.stockMin} disabled={!f.trackStock} />
          <Field label="Stock máximo" inputMode="decimal" value={f.stockMax} onChange={set('stockMax')} error={fields.stockMax} disabled={!f.trackStock} hint="0 = sin máximo" />
          <Field label="Stock de seguridad" inputMode="decimal" value={f.safetyStock} onChange={set('safetyStock')} error={fields.safetyStock} disabled={!f.trackStock} />
        </section>

        <section className="grid gap-3 sm:grid-cols-3">
          <Field label="Impuesto" value={f.taxName} onChange={set('taxName')} error={fields.taxName} hint="IVA, INC, ICUI…" />
          <Field label="Tarifa %" inputMode="decimal" value={f.taxRate} onChange={set('taxRate')} error={fields.taxRate} />
          <Field label="Impuesto fijo por unidad" inputMode="decimal" value={f.fixedTax} onChange={set('fixedTax')} error={fields.fixedTax} hint="Ej: impuesto a la bolsa" />
        </section>

        <section className="divide-y divide-line-soft rounded-xl border border-line px-3">
          <Toggle checked={f.trackStock} onChange={(v) => setF({ ...f, trackStock: v })} label="Controlar inventario" hint="Apágalo para servicios o productos que no se cuentan" />
          <Toggle checked={f.favorite} onChange={(v) => setF({ ...f, favorite: v })} label="Favorito en la caja" hint="Aparece con la estrella para venderlo más rápido" />
        </section>
      </div>
    </Modal>
  )
}
