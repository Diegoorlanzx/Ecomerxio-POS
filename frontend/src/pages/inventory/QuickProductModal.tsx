import { useState } from 'react'
import { Banner, Button, Field, Modal, SelectField } from '../../components/ui'
import { CategoryPicker } from '../../components/CategoryPicker'
import { ApiError, http } from '../../lib/http'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import { UNIT_LABELS, type Category, type ProductDto } from '../../lib/posTypes'

/**
 * Producto rápido: lo esencial para crear UN producto en segundos (nombre, precio, código, cuántos hay,
 * categoría). Lo demás toma los valores de la tienda y se completa después en la ficha.
 * Se usa en Inventario y en la caja (al escanear un código que no existe).
 */
export function QuickProductModal({
  categories,
  defaultTax,
  initialBarcode = '',
  initialName = '',
  submitLabel = 'Crear producto',
  onClose,
  onCreated,
  onMore,
}: {
  categories: Category[]
  defaultTax: { name: string; rate: number }
  initialBarcode?: string
  initialName?: string
  submitLabel?: string
  onClose: () => void
  onCreated: (p: ProductDto) => void
  /** Guardar y abrir la ficha completa (solo en Inventario). */
  onMore?: (p: ProductDto) => void
}) {
  const { can } = useAuth()
  const seeCosts = can('COSTOS_VER')
  const [f, setF] = useState({ name: initialName, barcode: initialBarcode, price: '', cost: '', stock: '', unit: 'UNIDAD', categoryId: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  // Pesos: "4.500" = 4500 (punto de miles). Cantidades: "1,5" o "1.5" = 1,5.
  const num = (v: string) => (v.trim() === '' ? undefined : Number(v.replace(/\./g, '').replace(',', '.')))
  const qtyNum = (v: string) => (v.trim() === '' ? undefined : Number(v.replace(',', '.')))
  const price = num(f.price) ?? 0
  const cost = num(f.cost) ?? 0
  const margin = price > 0 && cost > 0 ? Math.round(((price - cost) / price) * 100) : null

  async function save(more: boolean) {
    const errs: Record<string, string> = {}
    if (!f.name.trim()) errs.name = 'escribe el nombre'
    if (!(price > 0)) errs.price = 'escribe el precio de venta'
    setFields(errs)
    if (Object.keys(errs).length) return
    setSaving(true)
    setError('')
    try {
      // Directo al servidor (no al buzón sin internet): el producto tiene que existir para venderlo.
      const p = await http<ProductDto>('/products', {
        body: {
          name: f.name.trim(),
          unit: f.unit,
          trackStock: true,
          cost: seeCosts ? num(f.cost) : undefined,
          initialStock: qtyNum(f.stock),
          taxName: defaultTax.name,
          taxRate: defaultTax.rate,
          categoryId: f.categoryId ? Number(f.categoryId) : undefined,
          presentations: [{ name: 'Unidad', factor: 1, barcode: f.barcode.trim() || undefined, price, minPrice: 0, isBase: true }],
        },
      })
      if (more && onMore) onMore(p)
      else onCreated(p)
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.status === 0) setError('Sin internet: para crear un producto hace falta conexión.')
        else {
          const fe = e.fields
          const mapped: Record<string, string> = {}
          for (const [k, v] of Object.entries(fe)) {
            if (k === 'name') mapped.name = v
            else if (k.includes('barcode')) mapped.barcode = v
            else if (k.includes('price')) mapped.price = v
            else if (k === 'categoryId') mapped.categoryId = v
            else if (k === 'initialStock') mapped.stock = v
            else if (k === 'cost') mapped.cost = v
            else setError(v)
          }
          setFields(mapped)
          if (!Object.keys(fe).length) setError(e.message)
        }
      } else setError('No se pudo crear el producto')
    } finally {
      setSaving(false)
    }
  }

  const submitOnEnter = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void save(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title="Producto rápido"
      footer={
        <>
          {onMore && (
            <Button variant="ghost" className="hidden sm:inline-flex" onClick={() => save(true)} disabled={saving}>
              Guardar y completar ficha
            </Button>
          )}
          <div className="hidden flex-1 sm:block" />
          <Button variant="secondary" className="flex-1 sm:flex-none" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1 sm:flex-none" icon="bolt" onClick={() => save(false)} loading={saving}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">Solo lo necesario para vender ya. Marca, foto, impuestos y stock mínimo los completas después en la ficha.</p>
        {error && <Banner>{error}</Banner>}
        <Field
          label="Código de barras"
          info="Escanéalo con el lector o la cámara. Si no tiene, déjalo vacío."
          value={f.barcode}
          autoFocus={!initialBarcode}
          inputMode="numeric"
          onChange={(e) => setF({ ...f, barcode: e.target.value })}
          // El lector termina con Enter: pasa al nombre en vez de guardar.
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              document.querySelector<HTMLInputElement>('[data-quick-name]')?.focus()
            }
          }}
          error={fields.barcode}
          placeholder="Opcional"
        />
        <Field
          data-quick-name
          label="Nombre"
          autoFocus={!!initialBarcode}
          value={f.name}
          onChange={(e) => setF({ ...f, name: e.target.value })}
          onKeyDown={submitOnEnter}
          error={fields.name}
          placeholder="Ej: Gaseosa Postobón manzana 400 ml"
        />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Precio de venta" inputMode="numeric" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} onKeyDown={submitOnEnter} error={fields.price} placeholder="$" />
          {seeCosts ? (
            <Field
              label="Costo (opcional)"
              inputMode="numeric"
              value={f.cost}
              onChange={(e) => setF({ ...f, cost: e.target.value })}
              onKeyDown={submitOnEnter}
              error={fields.cost}
              hint={margin !== null ? `Ganas ${money(price - cost)} (${margin} %)` : 'Lo que te costó cada uno'}
            />
          ) : (
            <span />
          )}
          <Field
            label="Cuántos hay"
            inputMode="decimal"
            value={f.stock}
            onChange={(e) => setF({ ...f, stock: e.target.value })}
            onKeyDown={submitOnEnter}
            error={fields.stock}
            hint="Vacío = 0"
          />
          <SelectField label="Se vende por" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })}>
            {Object.entries(UNIT_LABELS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </SelectField>
        </div>
        <CategoryPicker categories={categories} value={f.categoryId} onChange={(id) => setF({ ...f, categoryId: id })} error={fields.categoryId} />
      </div>
    </Modal>
  )
}
