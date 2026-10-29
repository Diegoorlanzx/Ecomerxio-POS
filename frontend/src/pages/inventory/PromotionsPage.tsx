import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { Category, Page, ProductDto, Promotion } from '../../lib/posTypes'

type PromoType = Promotion['type']

const TYPES: { type: PromoType; icon: string; title: string; example: string }[] = [
  { type: 'COMPRA_LLEVA', icon: 'add_shopping_cart', title: 'Compra uno, lleva otro', example: 'Compra una gaseosa y lleva un ponqué gratis o a mitad de precio' },
  { type: 'LLEVA_PAGA', icon: 'inventory_2', title: 'Lleva N, paga M', example: '3x2 en Pony Malta' },
  { type: 'PRECIO_POR_CANTIDAD', icon: 'sell', title: 'N unidades por $X', example: '3 cervezas por $10.000' },
  { type: 'PORCENTAJE', icon: 'percent', title: 'Descuento en %', example: '20 % en galletas' },
]

/** Qué cuenta como "una vez" del cupo en cada tipo. */
const CUPO_UNIT: Record<PromoType, [string, string]> = {
  PORCENTAJE: ['unidad con descuento', 'unidades con descuento'],
  PRECIO_POR_CANTIDAD: ['paquete', 'paquetes'],
  LLEVA_PAGA: ['paquete', 'paquetes'],
  COMPRA_LLEVA: ['combo', 'combos'],
}

const units = (t: PromoType, n: number) => `${qty(n)} ${CUPO_UNIT[t][n === 1 ? 0 : 1]}`

function rewardText(percent: number | undefined) {
  if (!percent) return 'con descuento'
  if (percent >= 100) return 'gratis'
  if (percent === 50) return 'a mitad de precio'
  return `con ${qty(percent)} % de descuento`
}

/** La promoción en una frase, como se la contarías a un cliente. */
function describe(p: Pick<Promotion, 'type' | 'percent' | 'minQty' | 'bundleQty' | 'bundlePrice' | 'payQty' | 'rewardQty'> & { buyName?: string; rewardName?: string }) {
  const what = p.buyName ?? 'el producto'
  if (p.type === 'PORCENTAJE') return `${qty(p.percent)} % de descuento en ${what}${p.minQty ? ` desde ${qty(p.minQty)} unidades` : ''}`
  if (p.type === 'PRECIO_POR_CANTIDAD') return `${qty(p.bundleQty)} de ${what} por ${money(p.bundlePrice)}`
  if (p.type === 'LLEVA_PAGA') return `Lleva ${qty(p.bundleQty)} de ${what} y paga ${qty(p.payQty)}`
  return `Compra ${qty(p.bundleQty ?? 1)} de ${what} y lleva ${qty(p.rewardQty ?? 1)} de ${p.rewardName ?? 'otro producto'} ${rewardText(p.percent)}`
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
          <EmptyState
            icon="local_offer"
            title="Sin promociones"
            text="Crea un 3x2, un combo de 3 cervezas por $10.000 o un &quot;compra una gaseosa y lleva un ponqué gratis&quot;, y si quieres limítalo a las primeras 100."
          />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {list.map((p) => (
            <PromoCard
              key={p.id}
              p={p}
              onEdit={() => setEditing(p)}
              onDelete={async () => {
                await api(`/pos/promotions/${p.id}`, { method: 'DELETE' })
                toast('Promoción eliminada')
                load()
              }}
            />
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

function PromoCard({ p, onEdit, onDelete }: { p: Promotion; onEdit: () => void; onDelete: () => void }) {
  const limit = p.usageLimit == null ? null : Number(p.usageLimit)
  const used = Number(p.usedCount ?? 0)
  const soldOut = limit !== null && used >= limit
  const pct = limit ? Math.min(100, (used / limit) * 100) : 0
  return (
    <Card className={`p-4 ${p.active ? '' : 'opacity-60'}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="font-semibold">{p.name}</div>
          <div className="text-sm text-muted">
            {describe({ ...p, buyName: p.productName ?? (p.categoryName ? `la categoría ${p.categoryName}` : undefined), rewardName: p.rewardProductName })}
          </div>
          {(p.startsOn || p.endsOn) && (
            <div className="mt-1 text-xs text-muted">
              {p.startsOn ?? '…'} a {p.endsOn ?? '…'}
            </div>
          )}
        </div>
        <Chip tone={!p.active ? 'neutral' : soldOut ? 'warning' : 'success'}>{!p.active ? 'Inactiva' : soldOut ? 'Agotada' : 'Activa'}</Chip>
      </div>
      {limit !== null && (
        <div className="mt-3">
          <div className="flex justify-between text-xs">
            <span className="font-semibold">
              {qty(used)} de {units(p.type, limit)} usados
            </span>
            <span className="text-muted">{soldOut ? 'ya va a precio normal' : `quedan ${qty(limit - used)}`}</span>
          </div>
          <div className="mt-1 h-2 overflow-hidden rounded-full bg-line-soft">
            <div className={`h-full rounded-full ${soldOut ? 'bg-warning' : 'bg-primary'}`} style={{ width: `${pct}%` }} />
          </div>
        </div>
      )}
      <div className="mt-3 flex gap-1.5">
        <Button size="sm" variant="soft" icon="edit" onClick={onEdit}>
          Editar
        </Button>
        <Button size="sm" variant="ghost" icon="delete" onClick={onDelete}>
          Eliminar
        </Button>
      </div>
    </Card>
  )
}

/** Buscador de un producto: muestra el elegido y deja cambiarlo. */
function ProductPick({ label, value, onPick, error }: { label: string; value: { id: string; name: string }; onPick: (id: string, name: string) => void; error?: string }) {
  const [search, setSearch] = useState('')
  const [results, setResults] = useState<ProductDto[]>([])
  useEffect(() => {
    if (!search.trim()) return setResults([])
    const t = setTimeout(() => api<Page<ProductDto>>(`/products?size=8&status=ACTIVE&level=ITEMS&q=${encodeURIComponent(search)}`).then((r) => setResults(r.items)), 250)
    return () => clearTimeout(t)
  }, [search])
  return (
    <div>
      {value.id && (
        <div className="mb-2 flex items-center gap-2 rounded-xl bg-primary-tint px-3 py-2 text-sm">
          <Icon name="check_circle" className="text-[18px] text-primary" />
          <b className="truncate">{value.name}</b>
        </div>
      )}
      <Field label={value.id ? 'Buscar otro producto' : label} value={search} onChange={(e) => setSearch(e.target.value)} error={error} placeholder="Escribe el nombre" />
      {results.length > 0 && (
        <div className="mt-1 divide-y divide-line-soft rounded-xl border border-line">
          {results.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => (onPick(String(p.id), p.name), setSearch(''))}
              className="block w-full px-3 py-2 text-left text-sm hover:bg-canvas"
            >
              {p.name}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function PromotionForm({ promo, onClose, onSaved }: { promo: Promotion | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    name: promo?.name ?? '',
    type: (promo?.type ?? 'COMPRA_LLEVA') as PromoType,
    target: promo?.categoryId ? 'category' : 'product',
    productId: promo?.productId ? String(promo.productId) : '',
    productName: promo?.productName ?? '',
    categoryId: promo?.categoryId ? String(promo.categoryId) : '',
    percent: promo?.percent ? String(promo.percent) : promo ? '' : '100',
    minQty: promo?.minQty ? String(promo.minQty) : '',
    bundleQty: promo?.bundleQty ? String(promo.bundleQty) : promo ? '3' : '1',
    bundlePrice: promo?.bundlePrice ? String(promo.bundlePrice) : '',
    payQty: promo?.payQty ? String(promo.payQty) : '2',
    rewardProductId: promo?.rewardProductId ? String(promo.rewardProductId) : '',
    rewardProductName: promo?.rewardProductName ?? '',
    rewardQty: promo?.rewardQty ? String(promo.rewardQty) : '1',
    limited: promo?.usageLimit != null,
    usageLimit: promo?.usageLimit != null ? String(promo.usageLimit) : '100',
    startsOn: promo?.startsOn ?? '',
    endsOn: promo?.endsOn ?? '',
    active: promo?.active ?? true,
  })
  const [categories, setCategories] = useState<Category[]>([])
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value })
  const n = (v: string) => (v ? Number(v.replace(',', '.')) : undefined)
  const isReward = f.type === 'COMPRA_LLEVA'

  useEffect(() => {
    api<Category[]>('/categories').then(setCategories).catch(() => {})
  }, [])

  function chooseType(type: PromoType) {
    // Valores de partida que tienen sentido para cada tipo.
    setF({ ...f, type, bundleQty: type === 'COMPRA_LLEVA' ? '1' : f.bundleQty === '1' ? '3' : f.bundleQty, percent: type === 'COMPRA_LLEVA' && !f.percent ? '100' : f.percent })
  }

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    const t = f.type
    const body = {
      name: f.name,
      type: t,
      productId: f.target === 'product' && f.productId ? Number(f.productId) : undefined,
      categoryId: f.target === 'category' && f.categoryId ? Number(f.categoryId) : undefined,
      percent: t === 'PORCENTAJE' || t === 'COMPRA_LLEVA' ? n(f.percent) : undefined,
      minQty: t === 'PORCENTAJE' ? n(f.minQty) : undefined,
      bundleQty: t !== 'PORCENTAJE' ? n(f.bundleQty) : undefined,
      bundlePrice: t === 'PRECIO_POR_CANTIDAD' ? n(f.bundlePrice) : undefined,
      payQty: t === 'LLEVA_PAGA' ? n(f.payQty) : undefined,
      rewardProductId: isReward && f.rewardProductId ? Number(f.rewardProductId) : undefined,
      rewardQty: isReward ? n(f.rewardQty) : undefined,
      usageLimit: f.limited ? n(f.usageLimit) : undefined,
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

  const buyName = f.target === 'product' ? f.productName || undefined : categories.find((c) => String(c.id) === f.categoryId)?.name
  const limitNum = n(f.usageLimit)
  const summary =
    describe({
      type: f.type,
      percent: n(f.percent),
      minQty: n(f.minQty),
      bundleQty: n(f.bundleQty),
      bundlePrice: n(f.bundlePrice),
      payQty: n(f.payQty),
      rewardQty: n(f.rewardQty),
      buyName: f.target === 'category' && buyName ? `la categoría ${buyName}` : buyName,
      rewardName: f.rewardProductName || undefined,
    }) + (f.limited && limitNum ? `. Solo ${units(f.type, limitNum)}; después, precio normal.` : '.')
  const used = Number(promo?.usedCount ?? 0)

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
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}

        <section className="space-y-2">
          <div className="text-sm font-semibold">1. ¿Qué tipo de promoción?</div>
          <div className="grid gap-2 sm:grid-cols-2">
            {TYPES.map((t) => (
              <button
                key={t.type}
                type="button"
                onClick={() => chooseType(t.type)}
                className={`flex items-start gap-2.5 rounded-xl border p-3 text-left transition ${f.type === t.type ? 'border-primary bg-primary-tint ring-2 ring-primary/15' : 'border-line hover:border-primary/40'}`}
              >
                <Icon name={t.icon} className={`mt-0.5 text-[22px] ${f.type === t.type ? 'text-primary' : 'text-muted'}`} />
                <span>
                  <span className="block text-sm font-semibold">{t.title}</span>
                  <span className="block text-xs text-muted">Ej: {t.example}</span>
                </span>
              </button>
            ))}
          </div>
        </section>

        <section className="space-y-2">
          <div className="text-sm font-semibold">2. {isReward ? '¿Qué tiene que comprar?' : '¿A qué productos aplica?'}</div>
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
            <ProductPick
              label="Buscar producto"
              value={{ id: f.productId, name: f.productName }}
              onPick={(id, name) => setF({ ...f, productId: id, productName: name })}
              error={fields.productId}
            />
          ) : (
            <SelectField label="Categoría" value={f.categoryId} onChange={set('categoryId')} error={fields.categoryId ?? fields.productId}>
              <option value="">Elige…</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.parentId ? `  › ${c.name}` : c.name}
                </option>
              ))}
            </SelectField>
          )}
          {isReward && (
            <Field label="¿Cuántos tiene que comprar?" inputMode="numeric" value={f.bundleQty} onChange={set('bundleQty')} error={fields.bundleQty} />
          )}
        </section>

        <section className="space-y-2">
          <div className="text-sm font-semibold">3. {isReward ? '¿Qué se lleva con descuento?' : '¿Cuál es el descuento?'}</div>
          {isReward && (
            <>
              <ProductPick
                label="Buscar el producto que se lleva"
                value={{ id: f.rewardProductId, name: f.rewardProductName }}
                onPick={(id, name) => setF({ ...f, rewardProductId: id, rewardProductName: name })}
                error={fields.rewardProductId}
              />
              <Field label="¿Cuántos se lleva?" inputMode="numeric" value={f.rewardQty} onChange={set('rewardQty')} error={fields.rewardQty} />
              <div className="grid grid-cols-3 gap-2">
                {[
                  ['100', 'Gratis'],
                  ['50', 'Mitad de precio'],
                  ['', 'Otro %'],
                ].map(([v, l]) => {
                  const on = v ? f.percent === v : f.percent !== '100' && f.percent !== '50'
                  return (
                    <button
                      key={l}
                      type="button"
                      onClick={() => setF({ ...f, percent: v || (on ? f.percent : '') })}
                      className={`h-11 rounded-xl border text-sm font-semibold ${on ? 'border-primary bg-primary-tint text-primary' : 'border-line text-muted'}`}
                    >
                      {l}
                    </button>
                  )
                })}
              </div>
              {f.percent !== '100' && f.percent !== '50' && (
                <Field label="Descuento %" inputMode="decimal" value={f.percent} onChange={set('percent')} error={fields.percent} placeholder="Ej: 30" />
              )}
              {(f.percent === '100' || f.percent === '50') && fields.percent && <p className="text-xs text-danger">{fields.percent}</p>}
            </>
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
        </section>

        <section className="space-y-2">
          <div className="text-sm font-semibold">4. ¿Cuántas veces y hasta cuándo?</div>
          <Toggle
            checked={f.limited}
            onChange={(v) => setF({ ...f, limited: v })}
            label="Solo una cantidad limitada"
            hint={`Ej: solo los primeros 100 ${CUPO_UNIT[f.type][1]}. El resto del inventario se vende a precio normal.`}
          />
          {f.limited && (
            <Field
              label={`¿Cuántos ${CUPO_UNIT[f.type][1]}?`}
              inputMode={f.type === 'PORCENTAJE' ? 'decimal' : 'numeric'}
              value={f.usageLimit}
              onChange={set('usageLimit')}
              error={fields.usageLimit}
              hint={promo && used > 0 ? `Ya se han usado ${units(f.type, used)}.` : undefined}
            />
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="Desde" type="date" value={f.startsOn} onChange={set('startsOn')} hint="Opcional" />
            <Field label="Hasta" type="date" value={f.endsOn} onChange={set('endsOn')} error={fields.endsOn} hint="Opcional" />
          </div>
        </section>

        <Field label="Nombre (sale en el tiquete)" value={f.name} onChange={set('name')} error={fields.name} placeholder={isReward ? 'Ej: Gaseosa + ponqué gratis' : 'Ej: 3x2 en Pony Malta'} />

        <div className="flex gap-2 rounded-xl border border-line-soft bg-canvas p-3 text-sm">
          <Icon name="info" className="shrink-0 text-[20px] text-primary" />
          <span>
            <b>Así queda:</b> {summary}
          </span>
        </div>

        <Toggle checked={f.active} onChange={(v) => setF({ ...f, active: v })} label="Activa" />
      </div>
    </Modal>
  )
}
