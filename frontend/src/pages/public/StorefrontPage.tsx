import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, Field, Icon, Modal, Spinner, TextArea } from '../../components/ui'
import { money, qty } from '../../lib/format'
import { publicApi, PublicError, type PublicOrder, type PublicProduct, type PublicStore } from '../../lib/onlineTypes'
import { APP_NAME } from '../../lib/brand'

interface CartItem {
  presentationId: number
  productId: number
  name: string
  presentationName: string
  price: number
  quantity: number
  unit: string
}

const fold = (s: string) => s.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '')
const cartKey = (slug: string) => `tp_shop_${slug}`
const customerKey = 'tp_shop_customer'

function load<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) ?? 'null') ?? fallback
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* sin almacenamiento */
  }
}

/** Vitrina pública de la tienda (se abre con el QR o el enlace): catálogo, carrito y pedido. */
export default function StorefrontPage() {
  const { slug = '' } = useParams()
  const navigate = useNavigate()
  const [store, setStore] = useState<PublicStore | null>(null)
  const [error, setError] = useState('')
  const [q, setQ] = useState('')
  const [category, setCategory] = useState<number | null>(null)
  const [cart, setCart] = useState<CartItem[]>(() => load(cartKey(slug), []))
  const [picker, setPicker] = useState<PublicProduct | null>(null)
  const [checkout, setCheckout] = useState(false)

  useEffect(() => {
    publicApi<PublicStore>(`/stores/${encodeURIComponent(slug)}`)
      .then((s) => {
        setStore(s)
        document.title = `${s.name} · pedidos en línea`
      })
      .catch((e) => setError(e instanceof PublicError && e.status === 404 ? 'Esta tienda no tiene catálogo en línea.' : e.message))
  }, [slug])
  useEffect(() => save(cartKey(slug), cart), [cart, slug])

  const variantsOf = useMemo(() => {
    const m = new Map<number, PublicProduct[]>()
    store?.products.forEach((p) => p.parentId && m.set(p.parentId, [...(m.get(p.parentId) ?? []), p]))
    return m
  }, [store])
  const cards = useMemo(() => (store?.products ?? []).filter((p) => p.productType !== 'VARIANTE'), [store])
  const parents = useMemo(() => new Map((store?.categories ?? []).map((c) => [c.id, c.parentId])), [store])
  const visible = useMemo(() => {
    const f = fold(q.trim())
    return cards.filter((p) => {
      if (category && p.categoryId !== category && parents.get(p.categoryId ?? -1) !== category) return false
      if (!f) return true
      return fold(p.name).includes(f) || fold(p.brand ?? '').includes(f) || (variantsOf.get(p.id) ?? []).some((v) => fold(v.variantLabel ?? '').includes(f))
    })
  }, [cards, q, category, parents, variantsOf])

  if (error) {
    return (
      <div className="mx-auto max-w-md p-6 pt-20 text-center">
        <Icon name="storefront" className="text-[48px] text-primary" />
        <p className="mt-3 text-lg font-semibold">{error}</p>
      </div>
    )
  }
  if (!store) return <Spinner label="Cargando la tienda…" />

  const ordering = store.mode === 'PEDIDOS'
  const cur = store.currency
  const count = cart.reduce((s, i) => s + i.quantity, 0)
  const subtotal = cart.reduce((s, i) => s + i.price * i.quantity, 0)
  const topCats = store.categories.filter((c) => !c.parentId)
  const priceOf = (p: PublicProduct) => {
    if (p.productType === 'PADRE') {
      const prices = (variantsOf.get(p.id) ?? []).flatMap((v) => v.presentations.map((x) => x.price))
      return prices.length ? Math.min(...prices) : 0
    }
    return p.presentations[0]?.price ?? 0
  }
  const available = (p: PublicProduct) => (p.productType === 'PADRE' ? (variantsOf.get(p.id) ?? []).some((v) => v.available) : p.available)

  function add(p: PublicProduct, presentationId?: number) {
    const pr = p.presentations.find((x) => x.id === presentationId) ?? p.presentations[0]
    if (!pr) return
    navigator.vibrate?.(15)
    setCart((c) => {
      const i = c.findIndex((x) => x.presentationId === pr.id)
      if (i >= 0) return c.map((x, j) => (j === i ? { ...x, quantity: x.quantity + 1 } : x))
      return [...c, { presentationId: pr.id, productId: p.id, name: p.name, presentationName: pr.name, price: pr.price, quantity: 1, unit: p.unit }]
    })
  }
  const setQty = (presentationId: number, n: number) =>
    setCart((c) => (n <= 0 ? c.filter((x) => x.presentationId !== presentationId) : c.map((x) => (x.presentationId === presentationId ? { ...x, quantity: n } : x))))
  const inCart = (presentationId?: number) => cart.find((x) => x.presentationId === presentationId)?.quantity ?? 0

  return (
    <div className="min-h-dvh bg-canvas pb-28">
      <header className="bg-card shadow-tier1">
        <div className="mx-auto flex max-w-5xl items-center gap-3 px-4 pt-4 pb-3">
          {store.logoUrl ? (
            <img src={store.logoUrl} alt="" className="h-12 w-12 rounded-xl object-contain" />
          ) : (
            <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary text-white">
              <Icon name="storefront" />
            </span>
          )}
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-lg leading-tight font-bold">{store.name}</h1>
            <div className={`flex items-center gap-1 text-xs font-semibold ${store.open ? 'text-success-ink' : 'text-danger-ink'}`}>
              <span className={`h-2 w-2 rounded-full ${store.open ? 'bg-success' : 'bg-danger'}`} />
              {store.statusText}
            </div>
          </div>
          {store.whatsapp && (
            <a
              href={`https://wa.me/${waDigits(store.whatsapp)}`}
              target="_blank"
              rel="noreferrer"
              aria-label="Escribir por WhatsApp"
              className="flex h-11 w-11 items-center justify-center rounded-xl bg-success-soft text-success-ink"
            >
              <Icon name="chat" />
            </a>
          )}
        </div>
        <div className="mx-auto max-w-5xl px-4 pb-3">
          {store.welcome && <p className="mb-2 text-sm text-muted">{store.welcome}</p>}
          <div className="mb-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted">
            {ordering && store.deliveryEnabled && (
              <span className="flex items-center gap-1">
                <Icon name="two_wheeler" className="text-[16px]" /> Domicilio
              </span>
            )}
            {ordering && store.pickupEnabled && (
              <span className="flex items-center gap-1">
                <Icon name="storefront" className="text-[16px]" /> Recoger en tienda
              </span>
            )}
            {ordering && (
              <span className="flex items-center gap-1">
                <Icon name="schedule" className="text-[16px]" /> Listo en ~{store.prepMinutes} min
              </span>
            )}
            {store.address && (
              <span className="flex items-center gap-1">
                <Icon name="location_on" className="text-[16px]" /> {store.address}
                {store.city ? `, ${store.city}` : ''}
              </span>
            )}
          </div>
          <div className="relative">
            <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[20px] text-muted" />
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="¿Qué buscas?"
              aria-label="Buscar productos"
              className="h-11 w-full rounded-xl bg-canvas pr-3 pl-10 text-base outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
        </div>
        {topCats.length > 1 && (
          <div className="no-scrollbar mx-auto flex max-w-5xl gap-2 overflow-x-auto px-4 pb-3">
            <Chip active={!category} onClick={() => setCategory(null)}>
              Todo
            </Chip>
            {topCats.map((c) => (
              <Chip key={c.id} active={category === c.id} onClick={() => setCategory(category === c.id ? null : c.id)}>
                {c.name}
              </Chip>
            ))}
          </div>
        )}
      </header>

      <main className="mx-auto max-w-5xl px-4 pt-4">
        {!store.open && ordering && <Banner tone="warning">{store.statusText}. Puedes ver el catálogo y hacer tu pedido cuando abramos.</Banner>}
        {!ordering && <Banner tone="primary">Catálogo y precios. Para comprar, visítanos o escríbenos por WhatsApp.</Banner>}
        {visible.length === 0 ? (
          <p className="py-16 text-center text-muted">No encontramos productos con esa búsqueda.</p>
        ) : (
          <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {visible.map((p) => {
              const ok = available(p)
              const parent = p.productType === 'PADRE'
              const n = parent ? 0 : inCart(p.presentations[0]?.id)
              return (
                <article key={p.id} className={`flex flex-col overflow-hidden rounded-2xl bg-card shadow-tier1 ring-1 ring-line ${ok ? '' : 'opacity-60'}`}>
                  <button type="button" className="text-left" onClick={() => (parent || p.presentations.length > 1 ? setPicker(p) : undefined)} disabled={!parent && p.presentations.length <= 1}>
                    <div className="relative flex aspect-square items-center justify-center bg-primary-tint">
                      {p.photoUrl ? (
                        <img src={p.photoUrl} alt="" loading="lazy" decoding="async" className="h-full w-full object-contain p-2" />
                      ) : (
                        <Icon name="inventory_2" className="text-[36px] text-primary/40" />
                      )}
                      {!ok && <span className="absolute top-2 left-2 rounded-full bg-danger-soft px-2 py-0.5 text-[10px] font-bold text-danger-ink">AGOTADO</span>}
                    </div>
                    <div className="p-3 pb-1">
                      <h2 className="line-clamp-2 text-sm leading-tight font-semibold">{p.name}</h2>
                      <div className="mt-0.5 truncate text-[11px] text-muted">
                        {parent ? (p.variantOptions ?? []).map((o) => o.name).join(' y ') || 'Opciones' : p.brand ?? ' '}
                        {p.stock !== undefined && p.stock !== null && !parent ? ` · ${qty(p.stock)} disponibles` : ''}
                      </div>
                    </div>
                  </button>
                  <div className="mt-auto flex items-end justify-between gap-1 p-3 pt-1">
                    <div>
                      {parent && <span className="block text-[10px] leading-none font-bold text-muted uppercase">Desde</span>}
                      <span className="num text-base font-bold">{money(priceOf(p), cur)}</span>
                    </div>
                    {ordering && ok && (
                      parent || p.presentations.length > 1 ? (
                        <button type="button" onClick={() => setPicker(p)} aria-label={`Elegir ${p.name}`} className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary text-white">
                          <Icon name="add" />
                        </button>
                      ) : n > 0 ? (
                        <div className="flex items-center rounded-xl bg-primary-tint">
                          <button type="button" aria-label={`Menos ${p.name}`} onClick={() => setQty(p.presentations[0]!.id, n - 1)} className="flex h-10 w-9 items-center justify-center text-primary">
                            <Icon name="remove" />
                          </button>
                          <span className="num min-w-5 text-center text-sm font-bold">{n}</span>
                          <button type="button" aria-label={`Más ${p.name}`} onClick={() => add(p)} className="flex h-10 w-9 items-center justify-center text-primary">
                            <Icon name="add" />
                          </button>
                        </div>
                      ) : (
                        <button type="button" onClick={() => add(p)} aria-label={`Agregar ${p.name}`} className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary text-white">
                          <Icon name="add" />
                        </button>
                      )
                    )}
                  </div>
                </article>
              )
            })}
          </div>
        )}
        {store.showBadge && (
          <p className="mt-10 text-center text-xs text-muted">
            Hecho con <span className="font-semibold text-primary">{APP_NAME}</span>
          </p>
        )}
      </main>

      {ordering && count > 0 && (
        <div className="fixed inset-x-0 bottom-0 z-30 p-3">
          <button type="button" onClick={() => setCheckout(true)} className="mx-auto flex w-full max-w-xl items-center gap-3 rounded-2xl bg-primary px-4 py-3.5 text-white shadow-tier3">
            <span className="num flex h-8 w-8 items-center justify-center rounded-full bg-white/20 text-sm font-bold">{Math.round(count)}</span>
            <span className="flex-1 text-left font-semibold">Ver mi pedido</span>
            <span className="num text-lg font-extrabold">{money(subtotal, cur)}</span>
          </button>
        </div>
      )}

      {picker && (
        <OptionPicker
          product={picker}
          variants={variantsOf.get(picker.id) ?? []}
          currency={cur}
          ordering={ordering}
          onClose={() => setPicker(null)}
          onPick={(p, presentationId) => {
            add(p, presentationId)
            setPicker(null)
          }}
        />
      )}
      {checkout && (
        <CheckoutSheet
          store={store}
          cart={cart}
          onQty={setQty}
          onClose={() => setCheckout(false)}
          onPlaced={(o) => {
            setCart([])
            navigate(`/t/${store.slug}/pedido/${o.token}`)
          }}
        />
      )}
    </div>
  )
}

export function waDigits(n: string) {
  const d = n.replace(/\D/g, '')
  return d.length === 10 ? `57${d}` : d
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} className={`h-9 shrink-0 rounded-full px-3.5 text-xs font-semibold ${active ? 'bg-primary text-white' : 'bg-canvas text-muted'}`}>
      {children}
    </button>
  )
}

/** Elegir talla/color o presentación. */
function OptionPicker({
  product,
  variants,
  currency,
  ordering,
  onPick,
  onClose,
}: {
  product: PublicProduct
  variants: PublicProduct[]
  currency: string
  ordering: boolean
  onPick: (p: PublicProduct, presentationId?: number) => void
  onClose: () => void
}) {
  const [chosen, setChosen] = useState<Record<string, string>>({})
  if (product.productType !== 'PADRE') {
    return (
      <Modal open title={product.name} onClose={onClose}>
        <div className="grid gap-2">
          {product.presentations.map((pr) => (
            <button
              key={pr.id}
              type="button"
              disabled={!ordering}
              onClick={() => onPick(product, pr.id)}
              className="flex items-center justify-between rounded-xl border border-line px-4 py-4 text-left hover:border-primary"
            >
              <span className="font-semibold">{pr.name}</span>
              <span className="num font-bold">{money(pr.price, currency)}</span>
            </button>
          ))}
        </div>
      </Modal>
    )
  }
  const options = product.variantOptions?.length
    ? product.variantOptions
    : [...new Set(variants.flatMap((v) => Object.keys(v.variantValues ?? {})))].map((name) => ({
        name,
        values: [...new Set(variants.map((v) => v.variantValues?.[name]).filter(Boolean) as string[])],
      }))
  const matches = (v: PublicProduct, sel: Record<string, string>) => Object.entries(sel).every(([k, val]) => v.variantValues?.[k] === val)
  const exact = Object.keys(chosen).length === options.length ? variants.find((v) => matches(v, chosen)) : undefined
  return (
    <Modal
      open
      title={product.name}
      onClose={onClose}
      footer={
        ordering ? (
          <Button className="w-full" icon="add_shopping_cart" disabled={!exact || !exact.available} onClick={() => exact && onPick(exact)}>
            {exact ? (exact.available ? `Agregar · ${money(exact.presentations[0]?.price, currency)}` : 'Agotado') : 'Elige ' + options.filter((o) => !chosen[o.name]).map((o) => o.name.toLowerCase()).join(' y ')}
          </Button>
        ) : undefined
      }
    >
      <div className="space-y-4">
        {product.photoUrl && <img src={product.photoUrl} alt="" className="mx-auto h-40 object-contain" />}
        {product.description && <p className="text-sm text-muted">{product.description}</p>}
        {options.map((o) => (
          <div key={o.name}>
            <div className="mb-1.5 text-xs font-bold tracking-wide text-muted uppercase">{o.name}</div>
            <div className="flex flex-wrap gap-2">
              {o.values.map((val) => {
                const sel = { ...chosen, [o.name]: val }
                const any = variants.filter((v) => matches(v, sel))
                const ok = any.some((v) => v.available)
                const active = chosen[o.name] === val
                return (
                  <button
                    key={val}
                    type="button"
                    disabled={any.length === 0}
                    aria-pressed={active}
                    onClick={() => setChosen(active ? Object.fromEntries(Object.entries(chosen).filter(([k]) => k !== o.name)) : sel)}
                    className={`h-11 min-w-12 rounded-xl px-3.5 text-sm font-semibold ring-1 ${active ? 'bg-primary text-white ring-primary' : 'bg-card ring-line'} ${ok ? '' : 'line-through opacity-50'} disabled:opacity-25`}
                  >
                    {val}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
        {exact && (
          <div className="num flex justify-between rounded-xl bg-primary-tint px-4 py-3 font-semibold">
            <span>{exact.variantLabel}</span>
            <span>{money(exact.presentations[0]?.price, currency)}</span>
          </div>
        )}
      </div>
    </Modal>
  )
}

/** Datos del cliente, entrega y pago; hace el pedido. */
function CheckoutSheet({
  store,
  cart,
  onQty,
  onClose,
  onPlaced,
}: {
  store: PublicStore
  cart: CartItem[]
  onQty: (presentationId: number, n: number) => void
  onClose: () => void
  onPlaced: (o: PublicOrder) => void
}) {
  const saved = load<{ name?: string; phone?: string; email?: string; address?: string; zoneId?: number }>(customerKey, {})
  const [f, setF] = useState({
    name: saved.name ?? '',
    phone: saved.phone ?? '',
    email: saved.email ?? '',
    address: saved.address ?? '',
    notes: '',
    fulfillment: store.deliveryEnabled ? 'DOMICILIO' : 'RECOGER',
    zoneId: saved.zoneId && store.zones.some((z) => z.id === saved.zoneId) ? saved.zoneId : undefined,
    payMethod: store.payMethods[0]?.code ?? 'CONTRA_ENTREGA',
  })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const cur = store.currency
  const subtotal = cart.reduce((s, i) => s + i.price * i.quantity, 0)
  const zone = f.fulfillment === 'DOMICILIO' ? store.zones.find((z) => z.id === f.zoneId) : undefined
  const fee = zone?.fee ?? 0
  const min = Math.max(store.minOrder, zone?.minOrder ?? 0)
  const short = subtotal < min
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value })

  async function send() {
    setSending(true)
    setError('')
    setFields({})
    try {
      save(customerKey, { name: f.name, phone: f.phone, email: f.email, address: f.address, zoneId: f.zoneId })
      const o = await publicApi<PublicOrder>(`/stores/${store.slug}/orders`, {
        ...f,
        email: f.email || undefined,
        zoneId: f.fulfillment === 'DOMICILIO' ? f.zoneId : undefined,
        items: cart.map((i) => ({ presentationId: i.presentationId, quantity: i.quantity })),
      })
      onPlaced(o)
    } catch (e) {
      if (e instanceof PublicError) {
        setFields(e.fields)
        setError(Object.keys(e.fields).length ? 'Revisa los datos marcados.' : e.message)
      }
    } finally {
      setSending(false)
    }
  }

  return (
    <Modal
      open
      title="Tu pedido"
      onClose={onClose}
      footer={
        <Button className="w-full" size="lg" icon="send" onClick={send} loading={sending} disabled={!cart.length || short || !store.open}>
          {store.open ? `Hacer pedido · ${money(subtotal + fee, cur)}` : 'La tienda está cerrada'}
        </Button>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <ul className="divide-y divide-line-soft rounded-xl border border-line">
          {cart.map((i) => (
            <li key={i.presentationId} className="flex items-center gap-2 px-3 py-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold">{i.name}</span>
                <span className="num text-xs text-muted">
                  {i.presentationName !== 'Unidad' ? `${i.presentationName} · ` : ''}
                  {money(i.price, cur)}
                </span>
              </span>
              <div className="flex items-center rounded-full bg-line-soft">
                <button type="button" aria-label={`Menos ${i.name}`} onClick={() => onQty(i.presentationId, i.quantity - 1)} className="flex h-9 w-9 items-center justify-center">
                  <Icon name={i.quantity <= 1 ? 'delete' : 'remove'} className="text-[18px]" />
                </button>
                <span className="num min-w-5 text-center text-sm font-bold">{i.quantity}</span>
                <button type="button" aria-label={`Más ${i.name}`} onClick={() => onQty(i.presentationId, i.quantity + 1)} className="flex h-9 w-9 items-center justify-center">
                  <Icon name="add" className="text-[18px]" />
                </button>
              </div>
              <span className="num w-20 text-right text-sm font-bold">{money(i.price * i.quantity, cur)}</span>
            </li>
          ))}
        </ul>

        {store.deliveryEnabled && store.pickupEnabled && (
          <div className="grid grid-cols-2 gap-2">
            {(['DOMICILIO', 'RECOGER'] as const).map((m) => (
              <button
                key={m}
                type="button"
                aria-pressed={f.fulfillment === m}
                onClick={() => setF({ ...f, fulfillment: m })}
                className={`flex h-12 items-center justify-center gap-2 rounded-xl text-sm font-semibold ring-1 ${f.fulfillment === m ? 'bg-primary text-white ring-primary' : 'bg-card ring-line'}`}
              >
                <Icon name={m === 'DOMICILIO' ? 'two_wheeler' : 'storefront'} /> {m === 'DOMICILIO' ? 'Domicilio' : 'Recojo en tienda'}
              </button>
            ))}
          </div>
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Tu nombre" autoComplete="name" value={f.name} onChange={set('name')} error={fields.name} />
          <Field label="Celular" type="tel" autoComplete="tel" value={f.phone} onChange={set('phone')} error={fields.phone} />
        </div>
        {f.fulfillment === 'DOMICILIO' && (
          <>
            {store.zones.length > 0 && (
              <label className="block">
                <span className="mb-1 block text-xs font-semibold text-muted">Barrio o zona</span>
                <select
                  value={f.zoneId ?? ''}
                  onChange={(e) => setF({ ...f, zoneId: e.target.value ? Number(e.target.value) : undefined })}
                  className="h-12 w-full rounded-xl border border-line bg-card px-3 text-base outline-none focus:border-primary"
                >
                  <option value="">Elige tu zona…</option>
                  {store.zones.map((z) => (
                    <option key={z.id} value={z.id}>
                      {z.name} · domicilio {money(z.fee, cur)}
                    </option>
                  ))}
                </select>
                {fields.zoneId && <span className="mt-1 block text-xs font-medium text-danger-ink">{fields.zoneId}</span>}
              </label>
            )}
            <Field label="Dirección de entrega" autoComplete="street-address" value={f.address} onChange={set('address')} error={fields.address} />
          </>
        )}
        <TextArea label="Notas (opcional)" rows={2} value={f.notes} onChange={set('notes')} placeholder="Ej: sin cebolla, timbre dañado, llamar al llegar" />
        <div>
          <span className="mb-1 block text-xs font-semibold text-muted">¿Cómo vas a pagar?</span>
          <div className="grid gap-2">
            {store.payMethods.map((m) => (
              <label key={m.code} className={`flex items-center gap-3 rounded-xl px-3 py-3 ring-1 ${f.payMethod === m.code ? 'bg-primary-tint ring-primary' : 'ring-line'}`}>
                <input type="radio" name="pay" className="accent-primary" checked={f.payMethod === m.code} onChange={() => setF({ ...f, payMethod: m.code })} />
                <span className="text-sm font-semibold">{m.label}</span>
              </label>
            ))}
          </div>
        </div>
        <Field label="Correo (opcional)" type="email" autoComplete="email" value={f.email} onChange={set('email')} error={fields.email} />
        <div className="num space-y-1 rounded-xl bg-canvas px-4 py-3 text-sm">
          <div className="flex justify-between">
            <span>Productos</span>
            <span>{money(subtotal, cur)}</span>
          </div>
          {f.fulfillment === 'DOMICILIO' && (
            <div className="flex justify-between">
              <span>Domicilio{zone ? ` (${zone.name})` : ''}</span>
              <span>{zone ? money(fee, cur) : store.zones.length ? 'elige la zona' : money(0, cur)}</span>
            </div>
          )}
          <div className="flex justify-between text-base font-bold">
            <span>Total</span>
            <span>{money(subtotal + fee, cur)}</span>
          </div>
        </div>
        {short && <Banner tone="warning">El pedido mínimo es {money(min, cur)}.</Banner>}
        <p className="text-center text-xs text-muted">
          Al hacer el pedido, la tienda recibe tus datos para entregarlo.{' '}
          <Link to={`/t/${store.slug}`} onClick={onClose} className="underline">
            Seguir comprando
          </Link>
        </p>
      </div>
    </Modal>
  )
}
