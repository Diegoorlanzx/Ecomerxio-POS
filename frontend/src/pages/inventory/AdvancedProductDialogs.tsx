import { useEffect, useMemo, useState } from 'react'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Chip, EmptyState, Field, Icon, Modal, Spinner, TextArea } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty } from '../../lib/format'
import type { ProductDto, SerialDto, VariantOption } from '../../lib/posTypes'

// =====================================================================
// Variantes: opciones (talla, color…) y la matriz de combinaciones
// =====================================================================

const PRESETS: VariantOption[] = [
  { name: 'Talla', values: ['XS', 'S', 'M', 'L', 'XL'] },
  { name: 'Color', values: ['Negro', 'Blanco', 'Azul'] },
  { name: 'Número', values: ['35', '36', '37', '38', '39', '40', '41', '42'] },
  { name: 'Capacidad', values: ['64 GB', '128 GB', '256 GB'] },
]

interface Row {
  id?: number
  values: Record<string, string>
  label: string
  sku: string
  barcode: string
  price: string
  cost: string
  initialStock: string
  active: boolean
  stock?: number
}

function combos(options: VariantOption[]): Record<string, string>[] {
  let out: Record<string, string>[] = [{}]
  for (const o of options) {
    const vals = o.values.filter((v) => v.trim())
    if (!o.name.trim() || vals.length === 0) continue
    out = out.flatMap((c) => vals.map((v) => ({ ...c, [o.name.trim()]: v.trim() })))
  }
  return out.filter((c) => Object.keys(c).length > 0)
}

export function VariantsModal({ product, onClose, onSaved }: { product: ProductDto; onClose: () => void; onSaved: (p: ProductDto) => void }) {
  const { can } = useAuth()
  const seeCosts = can('COSTOS_VER')
  const basePrice = String(product.presentations.find((p) => p.isBase)?.price ?? 0)
  const [options, setOptions] = useState<VariantOption[]>(
    product.variantOptions?.length ? product.variantOptions : [{ name: 'Talla', values: ['S', 'M', 'L'] }],
  )
  const [texts, setTexts] = useState<string[]>(options.map((o) => o.values.join(', ')))
  const existing = useMemo(() => new Map((product.variants ?? []).map((v) => [v.label.toLowerCase(), v])), [product.variants])
  const [edits, setEdits] = useState<Record<string, Partial<Row>>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [allPrice, setAllPrice] = useState('')

  const rows: Row[] = combos(options).map((values) => {
    const label = Object.values(values).join(' / ')
    const ex = existing.get(label.toLowerCase())
    const base: Row = {
      id: ex?.id,
      values,
      label,
      sku: ex?.sku ?? '',
      barcode: ex?.barcode ?? '',
      price: String(ex?.price ?? basePrice),
      cost: ex?.cost !== undefined && ex.cost !== null ? String(ex.cost) : seeCosts && product.cost !== undefined ? String(product.cost) : '',
      initialStock: '',
      active: ex ? ex.status === 'ACTIVE' : true,
      stock: ex?.stock,
    }
    return { ...base, ...edits[label] }
  })
  const removed = (product.variants ?? []).filter((v) => v.status === 'ACTIVE' && !rows.some((r) => r.id === v.id))

  const setOption = (i: number, patch: Partial<VariantOption>) => setOptions((os) => os.map((o, j) => (j === i ? { ...o, ...patch } : o)))
  const setText = (i: number, t: string) => {
    setTexts((ts) => ts.map((x, j) => (j === i ? t : x)))
    setOption(i, { values: [...new Set(t.split(',').map((v) => v.trim()).filter(Boolean))] })
  }
  const edit = (label: string, patch: Partial<Row>) => setEdits((e) => ({ ...e, [label]: { ...e[label], ...patch } }))

  async function save() {
    setSaving(true)
    setError('')
    try {
      const num = (v: string) => (v.trim() === '' ? undefined : Number(v.replace(/[^\d.,]/g, '').replace(',', '.')))
      const saved = await api<ProductDto>(`/products/${product.id}/variants`, {
        method: 'PUT',
        body: {
          options: options.filter((o) => o.name.trim() && o.values.length).map((o) => ({ name: o.name.trim(), values: o.values })),
          variants: rows.map((r) => ({
            id: r.id,
            values: r.values,
            sku: r.sku || undefined,
            barcode: r.barcode || undefined,
            price: num(r.price) ?? 0,
            cost: seeCosts ? num(r.cost) : undefined,
            initialStock: r.id ? undefined : num(r.initialStock),
            active: r.active,
          })),
        },
      })
      onSaved(saved)
    } catch (e) {
      if (e instanceof ApiError) setError(Object.values(e.fields)[0] ?? e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={`Variantes de ${product.name}`}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <div className="flex-1" />
          <Button icon="save" onClick={save} loading={saving} disabled={rows.length === 0}>
            Guardar {rows.length} variantes
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {product.productType === 'SIMPLE' && (
          <Banner tone="primary">
            Al guardar, “{product.name}” se vuelve el producto principal: deja de venderse solo y cada combinación (por ejemplo M / Negro) queda
            con su propio código, precio y stock. En la caja se elige la talla y el color.
          </Banner>
        )}
        {error && <Banner>{error}</Banner>}
        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-semibold">Opciones</h3>
            {options.length < 3 && (
              <div className="flex flex-wrap gap-1">
                {PRESETS.filter((p) => !options.some((o) => o.name.toLowerCase() === p.name.toLowerCase())).map((p) => (
                  <Button
                    key={p.name}
                    size="sm"
                    variant="soft"
                    icon="add"
                    onClick={() => {
                      setOptions([...options, p])
                      setTexts([...texts, p.values.join(', ')])
                    }}
                  >
                    {p.name}
                  </Button>
                ))}
              </div>
            )}
          </div>
          {options.map((o, i) => (
            <div key={i} className="grid gap-2 rounded-xl border border-line p-3 sm:grid-cols-[180px_1fr_auto]">
              <Field label="Opción" value={o.name} onChange={(e) => setOption(i, { name: e.target.value })} />
              <Field label="Valores (separados por coma)" value={texts[i] ?? ''} onChange={(e) => setText(i, e.target.value)} />
              <div className="flex items-end">
                <Button
                  variant="ghost"
                  icon="delete"
                  aria-label={`Quitar ${o.name}`}
                  onClick={() => {
                    setOptions(options.filter((_, j) => j !== i))
                    setTexts(texts.filter((_, j) => j !== i))
                  }}
                />
              </div>
            </div>
          ))}
        </section>

        <section>
          <div className="mb-2 flex flex-wrap items-end justify-between gap-2">
            <h3 className="font-semibold">
              {rows.length} combinaciones{removed.length ? ` · ${removed.length} se descontinúan` : ''}
            </h3>
            <div className="flex items-end gap-2">
              <Field label="Mismo precio para todas" inputMode="numeric" value={allPrice} onChange={(e) => setAllPrice(e.target.value)} className="w-40" />
              <Button
                variant="soft"
                disabled={!allPrice}
                onClick={() => {
                  const next: Record<string, Partial<Row>> = { ...edits }
                  rows.forEach((r) => (next[r.label] = { ...next[r.label], price: allPrice }))
                  setEdits(next)
                }}
              >
                Aplicar
              </Button>
            </div>
          </div>
          <div className="overflow-x-auto rounded-xl border border-line">
            <table className="w-full min-w-[680px] text-sm">
              <thead className="bg-canvas text-left text-[11px] tracking-wide text-muted uppercase">
                <tr>
                  <th className="px-3 py-2">Variante</th>
                  <th className="px-2 py-2">SKU</th>
                  <th className="px-2 py-2">Código de barras</th>
                  <th className="px-2 py-2">Precio</th>
                  {seeCosts && <th className="px-2 py-2">Costo</th>}
                  <th className="px-2 py-2">Stock</th>
                  <th className="px-2 py-2">Activa</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-soft">
                {rows.map((r) => {
                  const cell = 'h-9 w-full rounded-lg border border-line px-2 outline-none focus:border-primary'
                  return (
                    <tr key={r.label} className={r.active ? '' : 'opacity-50'}>
                      <td className="px-3 py-1.5 font-semibold whitespace-nowrap">
                        {r.label}
                        {!r.id && <span className="ml-1 rounded bg-success-soft px-1 text-[10px] font-bold text-success-ink">NUEVA</span>}
                      </td>
                      <td className="px-2 py-1.5">
                        <input aria-label={`SKU ${r.label}`} className={cell} value={r.sku} placeholder="automático" onChange={(e) => edit(r.label, { sku: e.target.value })} />
                      </td>
                      <td className="px-2 py-1.5">
                        <input aria-label={`Código ${r.label}`} className={cell} value={r.barcode} onChange={(e) => edit(r.label, { barcode: e.target.value })} />
                      </td>
                      <td className="px-2 py-1.5">
                        <input aria-label={`Precio ${r.label}`} inputMode="numeric" className={`${cell} num w-28`} value={r.price} onChange={(e) => edit(r.label, { price: e.target.value })} />
                      </td>
                      {seeCosts && (
                        <td className="px-2 py-1.5">
                          <input aria-label={`Costo ${r.label}`} inputMode="numeric" className={`${cell} num w-24`} value={r.cost} onChange={(e) => edit(r.label, { cost: e.target.value })} />
                        </td>
                      )}
                      <td className="px-2 py-1.5">
                        {r.id ? (
                          <span className="num text-muted">{qty(r.stock ?? 0)}</span>
                        ) : product.trackSerials ? (
                          <span className="text-xs text-muted">por serial</span>
                        ) : (
                          <input
                            aria-label={`Stock inicial ${r.label}`}
                            inputMode="decimal"
                            className={`${cell} num w-20`}
                            value={r.initialStock}
                            placeholder="0"
                            onChange={(e) => edit(r.label, { initialStock: e.target.value })}
                          />
                        )}
                      </td>
                      <td className="px-2 py-1.5 text-center">
                        <input type="checkbox" aria-label={`Activa ${r.label}`} className="h-4 w-4 accent-primary" checked={r.active} onChange={(e) => edit(r.label, { active: e.target.checked })} />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-xs text-muted">El stock de una variante que ya existe se cambia con “Ajustar” o recibiendo una compra.</p>
        </section>
      </div>
    </Modal>
  )
}

// =====================================================================
// Combos y kits
// =====================================================================

export function ComboModal({ product, onClose, onSaved }: { product: ProductDto; onClose: () => void; onSaved: (p: ProductDto) => void }) {
  const { can } = useAuth()
  const seeCosts = can('COSTOS_VER')
  const [items, setItems] = useState<{ productId: number; name: string; unit: string; quantity: string; cost?: number }[]>(
    (product.components ?? []).map((c) => ({ productId: c.productId, name: c.name, unit: c.unit, quantity: String(c.quantity), cost: c.unitCost })),
  )
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const price = product.presentations.find((p) => p.isBase)?.price ?? 0
  const cost = items.reduce((s, i) => s + (i.cost ?? 0) * Number(i.quantity.replace(',', '.') || 0), 0)

  async function save() {
    setSaving(true)
    setError('')
    try {
      onSaved(
        await api<ProductDto>(`/products/${product.id}/components`, {
          method: 'PUT',
          body: { components: items.map((i) => ({ productId: i.productId, quantity: Number(i.quantity.replace(',', '.')) })) },
        }),
      )
    } catch (e) {
      if (e instanceof ApiError) setError(Object.values(e.fields)[0] ?? e.message)
    } finally {
      setSaving(false)
    }
  }

  async function unmake() {
    setSaving(true)
    try {
      onSaved(await api<ProductDto>(`/products/${product.id}/components`, { method: 'DELETE' }))
    } catch (e) {
      if (e instanceof ApiError) setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`Combo: ${product.name}`}
      footer={
        <>
          {product.productType === 'COMBO' && (
            <Button variant="ghost" icon="undo" onClick={unmake} disabled={saving}>
              Dejar de ser combo
            </Button>
          )}
          <div className="flex-1" />
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button icon="save" onClick={save} loading={saving} disabled={items.length === 0}>
            Guardar combo
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Al vender el combo se descuenta del inventario cada producto que lo forma. El combo se vende con su propio precio ({money(price)}).
        </p>
        {error && <Banner>{error}</Banner>}
        <ProductPicker onPick={(p) => setItems([...items, { productId: p.id, name: p.name, unit: p.unit, quantity: '1', cost: p.cost }])} exclude={[product.id, ...items.map((i) => i.productId)]} placeholder="Agregar un producto al combo…" />
        {items.length === 0 ? (
          <EmptyState icon="lunch_dining" title="El combo está vacío" text="Busca y agrega los productos que lleva." />
        ) : (
          <ul className="divide-y divide-line-soft rounded-xl border border-line">
            {items.map((i, k) => (
              <li key={i.productId} className="flex items-center gap-3 px-3 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-semibold">{i.name}</span>
                  {seeCosts && i.cost !== undefined && <span className="num text-xs text-muted">{money(i.cost)} c/u</span>}
                </span>
                <input
                  aria-label={`Cantidad de ${i.name}`}
                  inputMode="decimal"
                  value={i.quantity}
                  onChange={(e) => setItems(items.map((x, j) => (j === k ? { ...x, quantity: e.target.value } : x)))}
                  className="num h-10 w-20 rounded-lg border border-line text-center outline-none focus:border-primary"
                />
                <Button variant="ghost" icon="delete" aria-label={`Quitar ${i.name}`} onClick={() => setItems(items.filter((_, j) => j !== k))} />
              </li>
            ))}
          </ul>
        )}
        {seeCosts && items.length > 0 && (
          <div className="num flex justify-between rounded-xl bg-primary-tint px-4 py-3 text-sm">
            <span>Costo del combo {money(cost)}</span>
            <span className={`font-bold ${price > 0 && (price - cost) / price < 0.15 ? 'text-danger-ink' : 'text-success-ink'}`}>
              Margen {price > 0 ? (((price - cost) / price) * 100).toFixed(1) : '0'}%
            </span>
          </div>
        )}
      </div>
    </Modal>
  )
}

// =====================================================================
// Seriales / IMEI de un producto
// =====================================================================

const SERIAL_STATUS: Record<string, { label: string; tone: 'success' | 'primary' | 'danger' }> = {
  EN_STOCK: { label: 'En stock', tone: 'success' },
  VENDIDO: { label: 'Vendido', tone: 'primary' },
  DEFECTUOSO: { label: 'Defectuoso', tone: 'danger' },
}

export function SerialsModal({ product, onClose, onChanged }: { product: ProductDto; onClose: () => void; onChanged: () => void }) {
  const { can } = useAuth()
  const canAdjust = can('INVENTARIO_AJUSTAR')
  const [list, setList] = useState<SerialDto[] | null>(null)
  const [adding, setAdding] = useState(false)
  const [text, setText] = useState('')
  const [cost, setCost] = useState('')
  const [reason, setReason] = useState('Inventario inicial')
  const [writeOff, setWriteOff] = useState<SerialDto | null>(null)
  const [woReason, setWoReason] = useState('')
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('')
  const load = () => api<SerialDto[]>(`/serials?productId=${product.id}`).then(setList).catch((e) => setError(e.message))
  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [product.id])
  const parsed = [...new Set(text.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()).filter(Boolean))]

  async function add() {
    setError('')
    try {
      await api(`/products/${product.id}/serials`, { body: { serials: parsed, unitCost: cost ? Number(cost.replace(/\D/g, '')) : undefined, reason } })
      setText('')
      setAdding(false)
      load()
      onChanged()
    } catch (e) {
      if (e instanceof ApiError) setError(Object.values(e.fields)[0] ?? e.message)
    }
  }

  async function doWriteOff() {
    if (!writeOff) return
    try {
      await api(`/serials/${writeOff.id}/write-off`, { body: { reason: woReason } })
      setWriteOff(null)
      setWoReason('')
      load()
      onChanged()
    } catch (e) {
      if (e instanceof ApiError) setError(Object.values(e.fields)[0] ?? e.message)
    }
  }

  const shown = (list ?? []).filter((s) => !filter || s.status === filter)
  const count = (st: string) => (list ?? []).filter((s) => s.status === st).length

  return (
    <Modal open wide onClose={onClose} title={`Seriales de ${product.name}`}>
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="flex flex-wrap items-center gap-2">
          {[
            ['', `Todos (${list?.length ?? 0})`],
            ['EN_STOCK', `En stock (${count('EN_STOCK')})`],
            ['VENDIDO', `Vendidos (${count('VENDIDO')})`],
            ['DEFECTUOSO', `Defectuosos (${count('DEFECTUOSO')})`],
          ].map(([v, l]) => (
            <button
              key={v}
              type="button"
              onClick={() => setFilter(v!)}
              className={`h-9 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${filter === v ? 'bg-primary text-white' : 'bg-card text-muted'}`}
            >
              {l}
            </button>
          ))}
          <div className="flex-1" />
          {canAdjust && !adding && (
            <Button icon="add" onClick={() => setAdding(true)}>
              Registrar seriales
            </Button>
          )}
        </div>
        {adding && (
          <div className="space-y-3 rounded-xl border border-primary/40 bg-primary-tint/40 p-3">
            <TextArea label="Seriales o IMEI (uno por línea, o escanéalos seguidos)" rows={4} value={text} onChange={(e) => setText(e.target.value)} />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Costo por unidad" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} hint="Vacío = costo actual" />
              <Field label="De dónde vienen" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setAdding(false)}>
                Cancelar
              </Button>
              <Button icon="save" disabled={!parsed.length || !reason.trim()} onClick={add}>
                Registrar {parsed.length || ''}
              </Button>
            </div>
          </div>
        )}
        {!list ? (
          <Spinner />
        ) : shown.length === 0 ? (
          <EmptyState icon="qr_code_2" title="Sin seriales" text="Registra los seriales o IMEI de las unidades que tienes, o recíbelos con una compra." />
        ) : (
          <ul className="divide-y divide-line-soft rounded-xl border border-line text-sm">
            {shown.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center gap-2 px-3 py-2">
                <span className="font-mono font-semibold">{s.serial}</span>
                <Chip tone={SERIAL_STATUS[s.status]!.tone}>{SERIAL_STATUS[s.status]!.label}</Chip>
                <span className="min-w-0 flex-1 truncate text-xs text-muted">
                  {s.status === 'VENDIDO'
                    ? `Venta #${s.saleNumber ?? '—'}${s.customerName ? ` · ${s.customerName}` : ''}${s.warrantyUntil ? ` · garantía hasta ${s.warrantyUntil}` : ''}`
                    : s.notes ?? `Entró ${dateTime(s.createdAt)}`}
                </span>
                {s.status === 'EN_STOCK' && canAdjust && (
                  <Button size="sm" variant="ghost" icon="broken_image" onClick={() => setWriteOff(s)}>
                    Dar de baja
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
        {writeOff && (
          <div className="space-y-3 rounded-xl border border-danger/40 bg-danger-soft/40 p-3">
            <p className="text-sm font-semibold">Dar de baja el serial {writeOff.serial}: sale del stock y queda como defectuoso.</p>
            <Field label="Motivo" value={woReason} onChange={(e) => setWoReason(e.target.value)} placeholder="Ej: pantalla rota, robo" />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setWriteOff(null)}>
                Cancelar
              </Button>
              <Button variant="danger" disabled={!woReason.trim()} onClick={doWriteOff}>
                Dar de baja
              </Button>
            </div>
          </div>
        )}
        <p className="flex items-center gap-1 text-xs text-muted">
          <Icon name="info" className="text-[16px]" /> El stock de este producto es igual a sus seriales en stock.
        </p>
      </div>
    </Modal>
  )
}
