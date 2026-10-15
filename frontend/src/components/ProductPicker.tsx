import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { qty } from '../lib/format'
import type { Page, ProductDto } from '../lib/posTypes'
import { Icon } from './ui'

/** Buscador de productos (nombre, SKU o código de barras) que devuelve el elegido. */
export default function ProductPicker({ onPick, placeholder = 'Buscar producto o escanear código…', exclude = [] }: {
  onPick: (p: ProductDto) => void
  placeholder?: string
  exclude?: number[]
}) {
  const [q, setQ] = useState('')
  const [items, setItems] = useState<ProductDto[]>([])
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (!q.trim()) return setItems([])
    const t = setTimeout(
      () =>
        api<Page<ProductDto>>(`/products?size=8&status=ACTIVE&q=${encodeURIComponent(q.trim())}`)
          .then((r) => setItems(r.items.filter((p) => !exclude.includes(p.id))))
          .catch(() => setItems([])),
      200,
    )
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, exclude.join(',')])

  function pick(p: ProductDto) {
    onPick(p)
    setQ('')
    setItems([])
  }

  return (
    <div className="relative">
      <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[20px] text-muted" />
      <input
        value={q}
        onChange={(e) => (setQ(e.target.value), setOpen(true))}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && items.length >= 1) {
            e.preventDefault()
            const exact = items.find((p) => p.presentations.some((x) => x.barcode === q.trim()) || p.sku.toLowerCase() === q.trim().toLowerCase())
            pick(exact ?? items[0]!)
          }
        }}
        placeholder={placeholder}
        className="h-12 w-full rounded-xl border border-line bg-card pr-3 pl-10 text-base outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
        aria-label="Buscar producto"
      />
      {open && items.length > 0 && (
        <div className="absolute inset-x-0 top-full z-20 mt-1 max-h-72 overflow-y-auto rounded-xl border border-line bg-card shadow-tier3">
          {items.map((p) => (
            <button key={p.id} type="button" onClick={() => pick(p)} className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-canvas">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold">{p.name}</span>
                <span className="text-xs text-muted">
                  {p.sku} · stock {qty(p.stock)}
                </span>
              </span>
              <Icon name="add" className="text-primary" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
