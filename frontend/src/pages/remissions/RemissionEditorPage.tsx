import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, Field, Icon, SelectField, Spinner, TextArea } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { calculate, scaleFor, type CartLine } from '../../lib/cart'
import { money } from '../../lib/format'
import { nitDigits } from '../../lib/nit'
import type { Customer, ProductDto, StoreSettings } from '../../lib/posTypes'
import { DOC_TYPES } from '../../lib/quoteTypes'
import type { Remission } from '../../lib/remissionTypes'

interface EditLine {
  key: string
  presentationId: number | null
  productName?: string
  presentations?: { id: number; name: string; price: number; factor: number }[]
  description: string
  quantity: string
  unitPrice: string
  discountPct: string
  taxRate: string
  /** Del producto (no se edita en líneas del catálogo). */
  productTaxRate: number
  fixedTax: number
}

interface CustomerForm {
  customerId: number | null
  customerName: string
  customerDocType: string
  customerDoc: string
  customerPhone: string
  deliveryAddress: string
}

const EMPTY_CUSTOMER: CustomerForm = { customerId: null, customerName: '', customerDocType: 'CC', customerDoc: '', customerPhone: '', deliveryAddress: '' }

let seq = 0
const newKey = () => `r${++seq}`

/** Crear o editar una remisión (solo en borrador: entregada ya movió el inventario). */
export default function RemissionEditorPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const currency = me?.tenant.currency ?? 'COP'
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [loaded, setLoaded] = useState(!id)
  const [c, setC] = useState<CustomerForm>(EMPTY_CUSTOMER)
  const [lines, setLines] = useState<EditLine[]>([])
  const [notes, setNotes] = useState('')
  const [showPrices, setShowPrices] = useState(true)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [matches, setMatches] = useState<Customer[]>([])

  useEffect(() => {
    api<StoreSettings>('/settings/pos').then(setSettings).catch((e) => setError(e.message))
  }, [])

  useEffect(() => {
    if (!id) return
    api<Remission>(`/remissions/${id}`)
      .then((r) => {
        setC({
          customerId: r.customerId ?? null,
          customerName: r.customerName,
          customerDocType: r.customerDocType ?? 'CC',
          customerDoc: r.customerDoc ?? '',
          customerPhone: r.customerPhone ?? '',
          deliveryAddress: r.deliveryAddress ?? '',
        })
        setLines(
          r.lines.map((l) => ({
            key: newKey(),
            presentationId: l.presentationId ?? null,
            productName: l.free ? undefined : l.description,
            presentations: l.free ? undefined : [{ id: l.presentationId!, name: l.presentationName ?? 'Unidad', price: Number(l.unitPrice), factor: Number(l.factor) }],
            description: l.description,
            quantity: String(Number(l.quantity)),
            unitPrice: String(Number(l.unitPrice)),
            discountPct: String(Number(l.discountPct)),
            taxRate: String(Number(l.taxRate)),
            productTaxRate: Number(l.taxRate),
            fixedTax: Number(l.fixedTax),
          })),
        )
        setNotes(r.notes ?? '')
        setShowPrices(r.showPrices)
        setLoaded(true)
      })
      .catch((e) => setError(e.message))
  }, [id])

  // Buscar clientes por nombre o documento mientras se escribe el nombre.
  useEffect(() => {
    const term = c.customerName.trim()
    if (c.customerId || term.length < 3) return setMatches([])
    const t = setTimeout(() => api<Customer[]>(`/pos/customers?q=${encodeURIComponent(term)}`).then(setMatches).catch(() => setMatches([])), 300)
    return () => clearTimeout(t)
  }, [c.customerName, c.customerId])

  const scale = scaleFor(currency)
  const cart: CartLine[] = lines.map((l) => ({
    key: l.key,
    productId: 0,
    presentationId: l.presentationId ?? 0,
    name: l.description,
    presentationName: '',
    factor: l.presentations?.find((p) => p.id === l.presentationId)?.factor ?? 1,
    quantity: Number(l.quantity.replace(',', '.')) || 0,
    listPrice: Number(l.unitPrice) || 0,
    unitPrice: Number(l.unitPrice) || 0,
    minPrice: 0,
    discountPct: Number(l.discountPct.replace(',', '.')) || 0,
    taxRate: l.presentationId ? l.productTaxRate : Number(l.taxRate) || 0,
    fixedTax: l.presentationId ? l.fixedTax : 0,
    unit: 'UNIDAD',
  }))
  const totals = useMemo(() => {
    if (!settings || !cart.length) return null
    try {
      return calculate(cart, settings, [], new Map(), 0, 0, scale)
    } catch {
      return null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(cart), settings, scale])

  function setLine(key: string, patch: Partial<EditLine>) {
    setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }

  function addProduct(p: ProductDto) {
    const pres = p.presentations.filter((x) => x.active !== false && x.id).map((x) => ({ id: x.id!, name: x.name, price: Number(x.price), factor: Number(x.factor) }))
    const baseId = (p.presentations.find((x) => x.isBase && x.id) ?? p.presentations.find((x) => x.id))?.id
    const base = pres.find((x) => x.id === baseId) ?? pres[0]
    if (!base) return
    const name = p.name + (p.variantLabel ? ` ${p.variantLabel}` : '')
    setLines((ls) => [
      ...ls,
      {
        key: newKey(),
        presentationId: base.id,
        productName: name,
        presentations: pres,
        description: name,
        quantity: '1',
        unitPrice: String(base.price),
        discountPct: '0',
        taxRate: String(Number(p.taxRate)),
        productTaxRate: Number(p.taxRate),
        fixedTax: Number(p.fixedTax),
      },
    ])
  }

  function addFree() {
    setLines((ls) => [
      ...ls,
      { key: newKey(), presentationId: null, description: '', quantity: '1', unitPrice: '', discountPct: '0', taxRate: String(Number(settings?.defaultTaxRate ?? 0)), productTaxRate: 0, fixedTax: 0 },
    ])
  }

  function pickCustomer(x: Customer) {
    setC({
      customerId: x.id,
      customerName: x.name,
      customerDocType: x.docType ?? 'CC',
      customerDoc: x.docNumber ?? '',
      customerPhone: x.phone ?? '',
      deliveryAddress: x.address ?? '',
    })
    setMatches([])
  }

  async function save() {
    setSaving(true)
    setError('')
    setFields({})
    const body = {
      ...c,
      notes,
      showPrices,
      lines: lines.map((l) => ({
        presentationId: l.presentationId,
        description: l.description,
        quantity: Number(l.quantity.replace(',', '.')),
        unitPrice: l.unitPrice === '' ? null : Number(l.unitPrice),
        discountPct: Number(l.discountPct.replace(',', '.')) || 0,
        taxRate: l.presentationId ? null : Number(l.taxRate) || 0,
      })),
    }
    try {
      const r = id ? await api<Remission>(`/remissions/${id}`, { method: 'PUT', body }) : await api<Remission>('/remissions', { method: 'POST', body })
      navigate(`/remisiones/${r.id}`, { replace: true })
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        setError(Object.keys(e.fields).length ? 'Revisa los campos marcados.' : e.message)
      } else setError('No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  if (!loaded || !settings) return error ? <Banner>{error}</Banner> : <Spinner />

  const set = (k: keyof CustomerForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setC((x) => ({ ...x, [k]: e.target.value }))

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex items-center gap-2">
        <Link to={id ? `/remisiones/${id}` : '/remisiones'} className="flex h-10 w-10 items-center justify-center rounded-xl text-muted hover:bg-line-soft" aria-label="Volver">
          <Icon name="arrow_back" />
        </Link>
        <h1 className="text-2xl font-bold tracking-tight">{id ? 'Editar remisión' : 'Nueva remisión'}</h1>
      </div>
      {error && <Banner>{error}</Banner>}

      <Card className="space-y-4 p-5">
        <h2 className="font-semibold">Cliente</h2>
        <div className="relative">
          <Field
            label="Nombre o razón social"
            value={c.customerName}
            onChange={(e) => setC((x) => ({ ...x, customerName: e.target.value, customerId: null }))}
            error={fields.customerName}
            hint={c.customerId ? 'Cliente guardado: puedes facturarle con fiado o factura electrónica.' : 'Escribe para buscar un cliente guardado.'}
          />
          {matches.length > 0 && (
            <ul className="absolute z-10 mt-1 w-full overflow-hidden rounded-xl border border-line bg-card shadow-tier2">
              {matches.slice(0, 6).map((m) => (
                <li key={m.id}>
                  <button type="button" onClick={() => pickCustomer(m)} className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-line-soft">
                    <span className="font-semibold">{m.name}</span>
                    <span className="num text-xs text-muted">{m.docNumber}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField label="Tipo de documento" value={c.customerDocType} onChange={set('customerDocType')}>
            {DOC_TYPES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </SelectField>
          <Field
            label="Número de documento (opcional)"
            inputMode={['CC', 'NIT', 'TI'].includes(c.customerDocType) ? 'numeric' : undefined}
            value={c.customerDoc}
            onChange={(e) => setC((x) => ({ ...x, customerDoc: ['CC', 'NIT', 'TI'].includes(x.customerDocType) ? nitDigits(e.target.value) : e.target.value }))}
            error={fields.customerDoc}
          />
          <Field label="Celular" type="tel" value={c.customerPhone} onChange={set('customerPhone')} error={fields.customerPhone} />
          <Field label="Dirección de entrega" value={c.deliveryAddress} onChange={set('deliveryAddress')} error={fields.deliveryAddress} placeholder="Ej: obra Calle 10 # 5-20" />
        </div>
      </Card>

      <Card className="space-y-3 p-5">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="flex-1 font-semibold">Mercancía</h2>
          <Button variant="secondary" icon="edit_note" onClick={addFree}>
            Línea libre
          </Button>
        </div>
        <ProductPicker onPick={addProduct} level="ITEMS" placeholder="Agregar producto del catálogo…" />
        {lines.length === 0 && <p className="text-sm text-muted">Agrega los productos que salen con el cliente. Una línea libre sirve para el flete o un servicio.</p>}
        <ul className="space-y-2">
          {lines.map((l, i) => {
            const calc = totals?.lines[i]
            return (
              <li key={l.key} className="rounded-xl border border-line p-3">
                <div className="flex items-start gap-2">
                  {l.presentationId ? (
                    <div className="min-w-0 flex-1">
                      <div className="font-semibold">{l.productName}</div>
                      {l.presentations && l.presentations.length > 1 && (
                        <select
                          value={l.presentationId}
                          onChange={(e) => {
                            const p = l.presentations!.find((x) => x.id === Number(e.target.value))!
                            setLine(l.key, { presentationId: p.id, unitPrice: String(p.price) })
                          }}
                          className="mt-1 h-9 rounded-lg border border-line bg-card px-2 text-sm"
                        >
                          {l.presentations.map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.name} · {money(p.price, currency)}
                            </option>
                          ))}
                        </select>
                      )}
                      <div className="text-xs text-muted">IVA {l.productTaxRate} %</div>
                    </div>
                  ) : (
                    <input
                      value={l.description}
                      onChange={(e) => setLine(l.key, { description: e.target.value })}
                      placeholder="Descripción (ej: Flete)"
                      className="h-10 min-w-0 flex-1 rounded-lg border border-line bg-card px-3 text-sm"
                    />
                  )}
                  <button type="button" onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))} aria-label="Quitar" className="flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-danger-soft hover:text-danger-ink">
                    <Icon name="delete" className="text-[20px]" />
                  </button>
                </div>
                <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <NumBox label="Cantidad" value={l.quantity} onChange={(v) => setLine(l.key, { quantity: v })} />
                  <NumBox label="Precio unitario" value={l.unitPrice} onChange={(v) => setLine(l.key, { unitPrice: v.replace(/[^\d.]/g, '') })} />
                  <NumBox label="Descuento %" value={l.discountPct} onChange={(v) => setLine(l.key, { discountPct: v })} />
                  {l.presentationId ? (
                    <div className="text-right">
                      <div className="text-[11px] font-semibold text-muted uppercase">Total</div>
                      <div className="num h-10 leading-10 font-bold">{calc ? money(calc.lineTotal, currency) : '—'}</div>
                    </div>
                  ) : (
                    <NumBox label="IVA %" value={l.taxRate} onChange={(v) => setLine(l.key, { taxRate: v })} />
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      </Card>

      <Card className="space-y-4 p-5">
        <TextArea label="Observaciones (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Ej: entregar en portería, recibe el maestro de obra" maxLength={1000} />
        <label className="flex cursor-pointer items-start gap-2 text-sm">
          <input type="checkbox" checked={showPrices} onChange={(e) => setShowPrices(e.target.checked)} className="mt-0.5 h-5 w-5 accent-[var(--color-primary)]" />
          <span>
            Mostrar precios en el documento impreso
            <span className="block text-xs text-muted">Quítalo si la remisión debe viajar solo con las cantidades. Los precios quedan guardados para facturar.</span>
          </span>
        </label>
      </Card>

      <div className="sticky bottom-24 z-20 rounded-2xl border border-line bg-card/95 px-4 py-3 shadow-tier2 backdrop-blur lg:bottom-4">
        <div className="flex items-center gap-3">
          <div className="min-w-0 flex-1 text-sm">
            <div className="text-xs text-muted">
              {settings.pricesIncludeTax ? 'Total con IVA incluido' : 'Total más IVA'}
              {totals && totals.taxTotal > 0 ? ` · IVA ${money(totals.taxTotal, currency)}` : ''}
            </div>
            <div className="num text-xl font-bold">{money(totals?.total ?? 0, currency)}</div>
          </div>
          <Button icon="save" onClick={save} loading={saving} disabled={!lines.length}>
            Guardar
          </Button>
        </div>
      </div>
    </div>
  )
}

function NumBox({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <label className="block">
      <span className="text-[11px] font-semibold text-muted uppercase">{label}</span>
      <input inputMode="decimal" value={value} onChange={(e) => onChange(e.target.value)} className="num h-10 w-full rounded-lg border border-line bg-card px-2 text-right text-sm" />
    </label>
  )
}
