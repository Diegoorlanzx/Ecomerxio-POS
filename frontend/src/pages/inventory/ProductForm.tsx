import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Field, Icon, Modal, SelectField, TextArea, Toggle } from '../../components/ui'
import { HelpTip } from '../../components/guide'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { imageToDataUrl, money } from '../../lib/format'
import { UNIT_LABELS, type Category, type PresentationDto, type ProductDto } from '../../lib/posTypes'
import type { CatalogItem } from '../../lib/purchasingTypes'

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
  const { can, has } = useAuth()
  const seeCosts = can('COSTOS_VER')
  const kind = product?.productType ?? 'SIMPLE'
  const isParent = kind === 'PADRE'
  const isVariant = kind === 'VARIANTE'
  const isCombo = kind === 'COMBO'
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
    taxClass: product?.taxClass ?? '',
    favorite: product?.favorite ?? false,
    // En droguería los productos nuevos llevan lote y vencimiento.
    trackLots: product?.trackLots ?? has('DROGUERIA'),
    trackSerials: product?.trackSerials ?? false,
    warrantyMonths: String(product?.warrantyMonths ?? 0),
    plu: product?.plu ?? '',
    photo: product?.photo,
    priceChangeReason: '',
    invimaCode: product?.invimaCode ?? '',
    activeIngredient: product?.activeIngredient ?? '',
    requiresPrescription: product?.requiresPrescription ?? false,
    controlled: product?.controlled ?? false,
    icaCode: product?.icaCode ?? '',
    adultsOnly: product?.adultsOnly ?? false,
    membershipDays: product?.membershipDays ? String(product.membershipDays) : '',
  })
  const extras = (has('AGRO') || has('MAYORES_EDAD') || has('MEMBRESIAS')) && !isParent
  const pharmacy = has('DROGUERIA') && !isParent && !isCombo
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
      // Solo aplica con tarifa 0 (con IVA el producto es gravado); vacío = sin clasificar.
      taxClass: Number(f.taxRate) > 0 ? undefined : f.taxClass,
      favorite: f.favorite,
      trackLots: f.trackLots,
      trackSerials: has('SERIALES') || product?.trackSerials ? f.trackSerials : undefined,
      warrantyMonths: num(f.warrantyMonths),
      plu: f.plu.trim() || (product?.plu ? '' : undefined),
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
      if (pharmacy) {
        const info = await api<Pick<ProductDto, 'invimaCode' | 'activeIngredient' | 'requiresPrescription' | 'controlled'>>(`/products/${saved.id}/pharmacy`, {
          method: 'PUT',
          body: { invimaCode: f.invimaCode, activeIngredient: f.activeIngredient, requiresPrescription: f.requiresPrescription, controlled: f.controlled },
        })
        Object.assign(saved, info)
      }
      if (has('AGRO') || has('MAYORES_EDAD')) {
        Object.assign(saved, await api<Pick<ProductDto, 'icaCode' | 'adultsOnly'>>(`/products/${saved.id}/extras`, { method: 'PUT', body: { icaCode: f.icaCode, adultsOnly: f.adultsOnly } }))
      }
      if (has('MEMBRESIAS')) {
        const r = await api<{ membershipDays?: number }>(`/products/${saved.id}/membership`, { method: 'PUT', body: { days: f.membershipDays ? Number(f.membershipDays) : null } })
        saved.membershipDays = r.membershipDays ?? null
      }
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
            <Field
              label="Nombre"
              value={f.name}
              onChange={set('name')}
              error={fields.name}
              disabled={isVariant}
              hint={isVariant ? `Variante de ${product?.parentName}: el nombre se edita en el producto principal` : undefined}
              placeholder="Ej: Cerveza Águila Light 330ml lata"
            />
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

        {isParent && (
          <Banner tone="primary">
            Este producto agrupa {product?.variants?.length ?? 0} variantes. El precio, el código de barras y el stock van en cada variante
            (botón “Variantes” en el inventario). Lo que cambies aquí (nombre, categoría, marca, impuestos, garantía) lo heredan todas.
          </Banner>
        )}
        {isCombo && (
          <Banner tone="primary">
            Combo: no tiene stock propio. Al venderlo se descuentan {product?.components?.map((c) => `${c.quantity} ${c.name}`).join(', ')}.
          </Banner>
        )}
        <section className={isParent ? 'hidden' : ''}>
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
                      helpTip={r.isBase ? <HelpTip title="Margen de ganancia" text="El margen % es la parte del precio de venta que es ganancia: (precio − costo) / precio × 100. Por ejemplo: costo $800, precio $1.000 → margen 20%." /> : undefined}
                    />
                    <Field
                      label="Precio mínimo"
                      inputMode="numeric"
                      value={String(r.minPrice)}
                      disabled={!editPrices}
                      onChange={(e) => setRow(r.key, { minPrice: Number(e.target.value.replace(/\D/g, '')) })}
                      helpTip={r.isBase ? <HelpTip title="Precio mínimo" text="El precio más bajo al que puedes vender con descuento. En la caja, si el cajero intenta cobrar menos de este valor, el sistema bloquea o pide autorización." /> : undefined}
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

        <section className={`grid gap-3 sm:grid-cols-4 ${isParent || isCombo ? 'hidden' : ''}`}>
          {seeCosts && (
            <Field
              label={`Costo por ${UNIT_LABELS[f.unit]?.toLowerCase() ?? 'unidad'}`}
              inputMode="decimal"
              value={f.cost}
              onChange={set('cost')}
              error={fields.cost}
              hint={product ? 'Cambia solo si el costo real cambió' : undefined}
              helpTip={<HelpTip title="Costo de compra" text="Precio al que le compraste el producto al proveedor. Se usa para calcular la utilidad: precio de venta − costo = ganancia." />}
            />
          )}
          {!product && (
            <Field
              label="Existencia inicial"
              inputMode="decimal"
              value={f.initialStock}
              onChange={set('initialStock')}
              error={fields.initialStock}
              disabled={!f.trackStock || f.trackSerials}
              hint={f.trackSerials ? 'Con serial: registra cada serial después de crear' : undefined}
            />
          )}
          <Field
            label="Stock mínimo"
            inputMode="decimal"
            value={f.stockMin}
            onChange={set('stockMin')}
            error={fields.stockMin}
            disabled={!f.trackStock}
            helpTip={<HelpTip title="Stock mínimo" text="Cuando las existencias bajen de este número, el sistema activa una alerta de reposición para que no se agote." />}
          />
          <Field label="Stock máximo" inputMode="decimal" value={f.stockMax} onChange={set('stockMax')} error={fields.stockMax} disabled={!f.trackStock} hint="0 = sin máximo" />
          <Field
            label="Stock de seguridad"
            inputMode="decimal"
            value={f.safetyStock}
            onChange={set('safetyStock')}
            error={fields.safetyStock}
            disabled={!f.trackStock}
            helpTip={<HelpTip title="Stock de seguridad" text="Unidades de reserva para no quedarte sin producto si el proveedor se demora o se vende más de lo normal. La reposición automática pide antes para conservarlas. Si lo dejas en 0, el sistema calcula un colchón según lo que vendes." />}
          />
        </section>

        <section className="grid gap-3 sm:grid-cols-3">
          <Field label="Impuesto" value={f.taxName} onChange={set('taxName')} error={fields.taxName} hint="IVA, INC, ICUI…" />
          <Field label="Tarifa %" inputMode="decimal" value={f.taxRate} onChange={set('taxRate')} error={fields.taxRate} />
          <Field label="Impuesto fijo por unidad" inputMode="decimal" value={f.fixedTax} onChange={set('fixedTax')} error={fields.fixedTax} hint="Ej: impuesto a la bolsa" />
          {!(Number(f.taxRate) > 0) && (
            <SelectField
              label="Con tarifa 0 %, es…"
              value={f.taxClass}
              onChange={set('taxClass')}
              error={fields.taxClass}
              hint="Para la factura electrónica. Si dudas, pregúntale a tu contador."
              className="sm:col-span-3"
            >
              <option value="">Sin clasificar</option>
              <option value="EXCLUIDO">Excluido (no causa IVA)</option>
              <option value="EXENTO">Exento (IVA 0 %, con derecho a devolución)</option>
            </SelectField>
          )}
        </section>

        {(has('SERIALES') || has('BASCULA') || f.trackSerials) && !isCombo && (
          <section className="grid gap-3 sm:grid-cols-3">
            {(has('SERIALES') || f.trackSerials) && f.trackSerials && (
              <Field
                label="Garantía (meses)"
                inputMode="numeric"
                value={f.warrantyMonths}
                onChange={set('warrantyMonths')}
                error={fields.warrantyMonths}
                hint="Cuenta desde el día de la venta"
              />
            )}
            {has('BASCULA') && !isParent && (
              <Field
                label="Código en la báscula (PLU)"
                inputMode="numeric"
                value={f.plu}
                onChange={set('plu')}
                error={fields.plu}
                hint="El número del producto en la balanza de etiquetas"
                helpTip={<HelpTip title="Código PLU (báscula)" text="Número de 4–6 dígitos que programas en tu báscula. Cuando la báscula imprime la etiqueta, el lector identifica el producto y el peso automáticamente." />}
              />
            )}
          </section>
        )}

        {extras && (
          <section className="space-y-3 rounded-xl border border-line p-3">
            {has('AGRO') && <Field label="Registro ICA" placeholder="ICA 1234" value={f.icaCode} onChange={set('icaCode')} hint="Registro del Instituto Colombiano Agropecuario (insumos y medicamentos veterinarios)" />}
            {has('MEMBRESIAS') && (
              <Field
                label="Días de membresía que da"
                inputMode="numeric"
                placeholder="30"
                value={f.membershipDays}
                onChange={set('membershipDays')}
                hint="Mensualidad = 30, trimestre = 90. Al venderlo a un socio se suman a su membresía. Vacío = producto normal."
              />
            )}
            {has('MAYORES_EDAD') && (
              <Toggle checked={f.adultsOnly} onChange={(v) => setF({ ...f, adultsOnly: v })} label="Solo para mayores de edad" hint="Licor, cigarrillos, vapeadores: la caja pide confirmar la edad antes de cobrar" />
            )}
          </section>
        )}

        {pharmacy && (
          <section className="space-y-3 rounded-xl border border-line p-3">
            <div className="text-sm font-semibold">Droguería</div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Registro sanitario INVIMA" placeholder="INVIMA 2019M-0012345" value={f.invimaCode} onChange={set('invimaCode')} error={fields.invimaCode} />
              <Field label="Principio activo" placeholder="Acetaminofén" value={f.activeIngredient} onChange={set('activeIngredient')} hint="En la caja se puede buscar por principio activo" />
            </div>
            <div className="divide-y divide-line-soft">
              <Toggle
                checked={f.requiresPrescription || f.controlled}
                onChange={(v) => setF({ ...f, requiresPrescription: v, controlled: v ? f.controlled : false })}
                label="Se vende con fórmula médica"
                hint="Al cobrar se registran el médico, su registro, la fecha de la fórmula y el paciente"
              />
              <Toggle
                checked={f.controlled}
                onChange={(v) => setF({ ...f, controlled: v, requiresPrescription: v ? true : f.requiresPrescription })}
                label="Medicamento de control especial"
                hint="Queda aparte en el libro de fórmulas para las visitas de control"
              />
            </div>
          </section>
        )}

        <section className="divide-y divide-line-soft rounded-xl border border-line px-3">
          {!isParent && !isCombo && (
            <Toggle
              checked={f.trackStock}
              onChange={(v) => setF({ ...f, trackStock: v })}
              label="Controlar inventario"
              hint="Apágalo para servicios o productos que no se cuentan"
            />
          )}
          <Toggle checked={f.favorite} onChange={(v) => setF({ ...f, favorite: v })} label="Favorito en la caja" hint="Aparece con la estrella para venderlo más rápido" />
          {(has('LOTES') || f.trackLots) && !isParent && !isCombo && !f.trackSerials && (
            <Toggle
              checked={f.trackLots && f.trackStock}
              onChange={(v) => setF({ ...f, trackLots: v })}
              label="Controlar lotes y vencimientos"
              hint="Para perecederos: al recibir se anota lote y fecha, y se vende primero lo que vence antes"
            />
          )}
          {(has('SERIALES') || f.trackSerials) && !isCombo && f.unit === 'UNIDAD' && (
            <Toggle
              checked={f.trackSerials}
              onChange={(v) => setF({ ...f, trackSerials: v, trackStock: v ? true : f.trackStock, trackLots: v ? false : f.trackLots })}
              label="Cada unidad lleva serial o IMEI"
              hint="Celulares, electrodomésticos, herramientas: se registra a quién se vendió cada uno y su garantía"
            />
          )}
        </section>
        {product && <ProductSuppliers productId={product.id} />}
      </div>
    </Modal>
  )
}

/** Proveedor principal y alternativos del producto (se administran desde cada proveedor). */
function ProductSuppliers({ productId }: { productId: number }) {
  const { can } = useAuth()
  const allowed = can('PROVEEDORES_VER') || can('COMPRAS_VER')
  const [items, setItems] = useState<CatalogItem[] | null>(null)
  useEffect(() => {
    if (allowed) api<CatalogItem[]>(`/products/${productId}/suppliers`).then(setItems).catch(() => setItems([]))
  }, [productId, allowed])
  if (!items) return null
  return (
    <section>
      <h3 className="mb-2 font-semibold">Proveedores</h3>
      {items.length === 0 ? (
        <p className="text-sm text-muted">
          Sin proveedor asignado. Agrégalo desde{' '}
          <Link to="/proveedores" className="font-semibold text-primary">
            Proveedores
          </Link>
          .
        </p>
      ) : (
        <ul className="divide-y divide-line-soft rounded-xl border border-line text-sm">
          {items.map((i) => (
            <li key={i.id} className="flex items-center justify-between gap-2 px-3 py-2">
              <Link to={`/proveedores/${i.supplierId}`} className="font-semibold hover:underline">
                {i.supplierName}
                <span className={`ml-1 text-xs ${i.isPrimary ? 'text-primary' : 'text-muted'}`}>· {i.isPrimary ? 'principal' : 'alternativo'}</span>
              </Link>
              {i.unitCost !== undefined && i.unitCost !== null && (
                <span className="num text-muted">
                  {money(i.unitCost)} c/u · {i.packName}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
