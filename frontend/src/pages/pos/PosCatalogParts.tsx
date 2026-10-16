import { useEffect, useMemo, useRef, useState } from 'react'
import { Banner, Button, Icon, Modal } from '../../components/ui'
import { api } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { PosProduct } from '../../lib/posTypes'

/**
 * Elegir la variante (talla, color…) de un producto padre: un grupo de botones por opción. Lo que no tiene
 * stock se ve tachado pero se puede elegir si la tienda permite vender en negativo.
 */
export function VariantPicker({
  parent,
  variants,
  currency,
  allowNegative,
  onPick,
  onClose,
}: {
  parent: PosProduct
  variants: PosProduct[]
  currency: string
  allowNegative: boolean
  onPick: (variant: PosProduct) => void
  onClose: () => void
}) {
  const options = useMemo(() => {
    if (parent.variantOptions?.length) return parent.variantOptions
    // Respaldo: deducir las opciones de las variantes.
    const names = new Map<string, Set<string>>()
    variants.forEach((v) => Object.entries(v.variantValues ?? {}).forEach(([k, val]) => names.set(k, (names.get(k) ?? new Set()).add(val))))
    return [...names].map(([name, vals]) => ({ name, values: [...vals] }))
  }, [parent, variants])
  const [chosen, setChosen] = useState<Record<string, string>>({})
  const matches = (v: PosProduct, sel: Record<string, string>) => Object.entries(sel).every(([k, val]) => v.variantValues?.[k] === val)
  const exact = Object.keys(chosen).length === options.length ? variants.find((v) => matches(v, chosen)) : undefined
  const inStock = (v: PosProduct) => !v.trackStock || v.stock > 0
  const price = (v: PosProduct) => (v.presentations.find((x) => x.isBase) ?? v.presentations[0])?.price ?? 0

  return (
    <Modal
      open
      title={parent.name}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            className="flex-1"
            icon="add_shopping_cart"
            disabled={!exact || (!inStock(exact) && !allowNegative)}
            onClick={() => exact && onPick(exact)}
          >
            {exact ? `Agregar · ${money(price(exact), currency)}` : 'Elige ' + options.filter((o) => !chosen[o.name]).map((o) => o.name.toLowerCase()).join(' y ')}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {options.map((o) => (
          <div key={o.name}>
            <div className="mb-1.5 text-xs font-bold tracking-wide text-muted uppercase">{o.name}</div>
            <div className="flex flex-wrap gap-2">
              {o.values.map((val) => {
                const sel = { ...chosen, [o.name]: val }
                const any = variants.filter((v) => matches(v, sel))
                const available = any.some(inStock)
                const active = chosen[o.name] === val
                return (
                  <button
                    key={val}
                    type="button"
                    disabled={any.length === 0}
                    onClick={() => setChosen(active ? Object.fromEntries(Object.entries(chosen).filter(([k]) => k !== o.name)) : sel)}
                    aria-pressed={active}
                    className={`h-11 min-w-12 rounded-xl px-3.5 text-sm font-semibold ring-1 transition ${
                      active ? 'bg-primary text-white ring-primary' : 'bg-card ring-line hover:ring-primary'
                    } ${!available ? 'line-through opacity-50' : ''} disabled:cursor-not-allowed disabled:opacity-25`}
                  >
                    {val}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
        {exact ? (
          <div className="flex items-center justify-between rounded-xl bg-primary-tint px-4 py-3">
            <span>
              <span className="block font-semibold">{exact.variantLabel}</span>
              <span className="text-xs text-muted">
                {exact.sku}
                {exact.trackStock ? ` · ${qty(exact.stock)} disponibles` : ''}
              </span>
            </span>
            <span className="num text-lg font-bold">{money(price(exact), currency)}</span>
          </div>
        ) : (
          <p className="text-sm text-muted">{variants.filter(inStock).length} de {variants.length} variantes con existencias.</p>
        )}
        {exact && !inStock(exact) && <Banner tone="warning">Esta variante está agotada.</Banner>}
      </div>
    </Modal>
  )
}

/**
 * Pedir los seriales / IMEI de lo que se vende: se escanean o se escriben; con internet se muestran
 * los disponibles para tocarlos. Sin internet se aceptan y el servidor los revisa al sincronizar.
 */
export function SerialPrompt({
  product,
  count,
  taken,
  online,
  onDone,
  onClose,
}: {
  product: { id: number; name: string }
  count: number
  taken: string[]
  online: boolean
  onDone: (serials: string[]) => void
  onClose: () => void
}) {
  const [list, setList] = useState<string[]>([])
  const [text, setText] = useState('')
  const [available, setAvailable] = useState<string[] | null>(null)
  const [error, setError] = useState('')
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    ref.current?.focus()
    if (online) api<string[]>(`/products/${product.id}/serials/available`).then(setAvailable).catch(() => setAvailable(null))
  }, [product.id, online])

  const add = (raw: string) => {
    const v = raw.trim().toUpperCase()
    if (!v) return
    if (list.includes(v) || taken.includes(v)) return setError(`El serial ${v} ya está en la venta`)
    if (available && !available.includes(v)) return setError(`El serial ${v} no está disponible en ${product.name}`)
    setError('')
    const next = [...list, v]
    setText('')
    if (next.length >= count) onDone(next)
    else setList(next)
  }
  const free = (available ?? []).filter((s) => !list.includes(s) && !taken.includes(s))

  return (
    <Modal open title={`Serial de ${product.name}`} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-sm text-muted">
          Escanea o escribe {count === 1 ? 'el serial / IMEI' : `los ${count} seriales`} de lo que se lleva el cliente.
          {count > 1 && ` Van ${list.length} de ${count}.`}
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            add(text)
          }}
          className="flex gap-2"
        >
          <input
            ref={ref}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Serial o IMEI"
            aria-label="Serial o IMEI"
            className="h-12 flex-1 rounded-xl border border-line px-3 font-mono text-base uppercase outline-none focus:border-primary"
            autoComplete="off"
          />
          <Button type="submit" icon="check" disabled={!text.trim()}>
            Agregar
          </Button>
        </form>
        {error && <Banner tone="danger">{error}</Banner>}
        {list.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {list.map((s) => (
              <span key={s} className="flex items-center gap-1 rounded-full bg-primary-soft px-2.5 py-1 font-mono text-xs font-semibold text-primary-active">
                {s}
                <button type="button" aria-label={`Quitar ${s}`} onClick={() => setList(list.filter((x) => x !== s))}>
                  <Icon name="close" className="text-[14px]" />
                </button>
              </span>
            ))}
          </div>
        )}
        {available !== null && (
          <div>
            <div className="mb-1.5 text-xs font-bold tracking-wide text-muted uppercase">Disponibles ({free.length})</div>
            {free.length === 0 ? (
              <p className="text-sm text-muted">No quedan seriales disponibles de este producto.</p>
            ) : (
              <div className="flex max-h-48 flex-wrap gap-1.5 overflow-y-auto">
                {free.map((s) => (
                  <button key={s} type="button" onClick={() => add(s)} className="rounded-lg bg-line-soft px-2.5 py-1.5 font-mono text-xs font-semibold hover:bg-primary-tint">
                    {s}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        {!online && <Banner tone="warning">Sin internet no se puede revisar el serial: quedará para revisión al sincronizar.</Banner>}
      </div>
    </Modal>
  )
}
