import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Modal, SelectField, Spinner, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { Category, Page, ProductDto, Promotion } from '../../lib/posTypes'

const TYPES = {
  PORCENTAJE: 'Descuento en %',
  PRECIO_POR_CANTIDAD: 'N unidades por $X',
  LLEVA_PAGA: 'Lleva N, paga M',
} as const

function describe(p: Promotion) {
  if (p.type === 'PORCENTAJE') return `${qty(p.percent)}% de descuento${p.minQty ? ` desde ${qty(p.minQty)} unidades` : ''}`
  if (p.type === 'PRECIO_POR_CANTIDAD') return `${qty(p.bundleQty)} por ${money(p.bundlePrice)}`
  return `Lleva ${qty(p.bundleQty)}, paga ${qty(p.payQty)}`
}

export default function PromotionsPage() {
  const toast = useToast()
  const [list, setList] = useState<Promotion[] | null>(null)
  const [editing, setEditing] = useState<Promotion | 'new' | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Promotion[]>('/pos/promotions').then(setList).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Promociones</h2>
          <p className="text-sm text-muted">Se aplican solas en la caja. Si a un producto le sirven varias, se usa la que más ahorra.</p>
        </div>
        <Button icon="add" onClick={() => setEditing('new')}>
          Nueva promoción
        </Button>
      </div>
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="local_offer" title="Sin promociones" text="Crea un 3x2, un combo de 3 cervezas por $10.000 o un descuento por categoría." />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {list.map((p) => (
            <Card key={p.id} className={`p-4 ${p.active ? '' : 'opacity-60'}`}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold">{p.name}</div>
                  <div className="text-sm text-muted">{describe(p)}</div>
                  <div className="mt-1 text-xs text-muted">
                    {p.productName ? `Producto: ${p.productName}` : `Categoría: ${p.categoryName}`}
                    {p.startsOn || p.endsOn ? ` · ${p.startsOn ?? '…'} a ${p.endsOn ?? '…'}` : ''}
                  </div>
                </div>
                <Chip tone={p.active ? 'success' : 'neutral'}>{p.active ? 'Activa' : 'Inactiva'}</Chip>
              </div>
              <div className="mt-3 flex gap-1.5">
                <Button size="sm" variant="soft" icon="edit" onClick={() => setEditing(p)}>
                  Editar
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  icon="delete"
                  onClick={async () => {
                    await api(`/pos/promotions/${p.id}`, { method: 'DELETE' })
                    toast('Promoción eliminada')
                    load()
                  }}
                >
                  Eliminar
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
      {editing && (
        <PromotionForm
          promo={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            toast('Promoción guardada')
            load()
          }}
        />
      )}
    </div>
  )
}

function PromotionForm({ promo, onClose, onSaved }: { promo: Promotion | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    name: promo?.name ?? '',
    type: promo?.type ?? 'LLEVA_PAGA',
    target: promo?.categoryId ? 'category' : 'product',
    productId: promo?.productId ? String(promo.productId) : '',
    productName: promo?.productName ?? '',
    categoryId: promo?.categoryId ? String(promo.categoryId) : '',
    percent: promo?.percent ? String(promo.percent) : '',
    minQty: promo?.minQty ? String(promo.minQty) : '',
    bundleQty: promo?.bundleQty ? String(promo.bundleQty) : '3',
    bundlePrice: promo?.bundlePrice ? String(promo.bundlePrice) : '',
    payQty: promo?.payQty ? String(promo.payQty) : '2',
    startsOn: promo?.startsOn ?? '',
    endsOn: promo?.endsOn ?? '',
    active: promo?.active ?? true,
  })
  const [categories, setCategories] = useState<Category[]>([])
  const [search, setSearch] = useState('')
  const [results, setResults] = useState<ProductDto[]>([])
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value })

  useEffect(() => {
    api<Category[]>('/categories').then(setCategories).catch(() => {})
  }, [])
  useEffect(() => {
    if (!search.trim()) return setResults([])
    const t = setTimeout(() => api<Page<ProductDto>>(`/products?size=8&status=ACTIVE&level=ITEMS&q=${encodeURIComponent(search)}`).then((r) => setResults(r.items)), 250)
    return () => clearTimeout(t)
  }, [search])

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    const n = (v: string) => (v ? Number(v.replace(',', '.')) : undefined)
    const body = {
      name: f.name,
      type: f.type,
      productId: f.target === 'product' && f.productId ? Number(f.productId) : undefined,
      categoryId: f.target === 'category' && f.categoryId ? Number(f.categoryId) : undefined,
      percent: f.type === 'PORCENTAJE' ? n(f.percent) : undefined,
      minQty: f.type === 'PORCENTAJE' ? n(f.minQty) : undefined,
      bundleQty: f.type !== 'PORCENTAJE' ? n(f.bundleQty) : undefined,
      bundlePrice: f.type === 'PRECIO_POR_CANTIDAD' ? n(f.bundlePrice) : undefined,
      payQty: f.type === 'LLEVA_PAGA' ? n(f.payQty) : undefined,
      startsOn: f.startsOn || undefined,
      endsOn: f.endsOn || undefined,
      active: f.active,
    }
    try {
      if (promo) await api(`/pos/promotions/${promo.id}`, { method: 'PUT', body })
      else await api('/pos/promotions', { body })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={promo ? 'Editar promoción' : 'Nueva promoción'}
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
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre" value={f.name} onChange={set('name')} error={fields.name} placeholder="Ej: 3x2 en Pony Malta" />
        <SelectField label="Tipo" value={f.type} onChange={set('type')}>
          {Object.entries(TYPES).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </SelectField>
        <div className="grid grid-cols-2 gap-2">
          {(['product', 'category'] as const).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setF({ ...f, target: t })}
              className={`h-11 rounded-xl border text-sm font-semibold ${f.target === t ? 'border-primary bg-primary-tint text-primary' : 'border-line text-muted'}`}
            >
              {t === 'product' ? 'Un producto' : 'Una categoría'}
            </button>
          ))}
        </div>
        {f.target === 'product' ? (
          <div>
            {f.productId && <div className="mb-2 text-sm">Producto: <b>{f.productName}</b></div>}
            <Field label="Buscar producto" value={search} onChange={(e) => setSearch(e.target.value)} error={fields.productId} />
            {results.length > 0 && (
              <div className="mt-1 divide-y divide-line-soft rounded-xl border border-line">
                {results.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    onClick={() => (setF({ ...f, productId: String(p.id), productName: p.name }), setSearch(''))}
                    className="block w-full px-3 py-2 text-left text-sm hover:bg-canvas"
                  >
                    {p.name}
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <SelectField label="Categoría" value={f.categoryId} onChange={set('categoryId')} error={fields.categoryId}>
            <option value="">Elige…</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.parentId ? `  › ${c.name}` : c.name}
              </option>
            ))}
          </SelectField>
        )}
        {f.type === 'PORCENTAJE' && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Descuento %" inputMode="decimal" value={f.percent} onChange={set('percent')} error={fields.percent} />
            <Field label="Desde cuántas unidades" inputMode="decimal" value={f.minQty} onChange={set('minQty')} hint="Opcional" />
          </div>
        )}
        {f.type === 'PRECIO_POR_CANTIDAD' && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Unidades" inputMode="numeric" value={f.bundleQty} onChange={set('bundleQty')} error={fields.bundleQty} />
            <Field label="Por precio de" inputMode="numeric" value={f.bundlePrice} onChange={set('bundlePrice')} error={fields.bundlePrice} />
          </div>
        )}
        {f.type === 'LLEVA_PAGA' && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Lleva" inputMode="numeric" value={f.bundleQty} onChange={set('bundleQty')} error={fields.bundleQty} />
            <Field label="Paga" inputMode="numeric" value={f.payQty} onChange={set('payQty')} error={fields.payQty} />
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Desde" type="date" value={f.startsOn} onChange={set('startsOn')} />
          <Field label="Hasta" type="date" value={f.endsOn} onChange={set('endsOn')} error={fields.endsOn} />
        </div>
        <Toggle checked={f.active} onChange={(v) => setF({ ...f, active: v })} label="Activa" />
      </div>
    </Modal>
  )
}
