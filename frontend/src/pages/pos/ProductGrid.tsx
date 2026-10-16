import { memo } from 'react'
import { Icon } from '../../components/ui'
import { money, qty } from '../../lib/format'
import type { PosProduct } from '../../lib/posTypes'

/** Tarjeta de producto del diseño "Modern Retail POS": chip, stock, foto, precio y botón (+). */
export const ProductCard = memo(function ProductCard({
  p,
  categoryName,
  topSeller,
  onAdd,
  onPick,
  currency,
}: {
  p: PosProduct
  categoryName?: string
  topSeller?: boolean
  onAdd: (p: PosProduct) => void
  onPick: (p: PosProduct) => void
  currency: string
}) {
  const base = p.presentations.find((x) => x.isBase) ?? p.presentations[0]
  const out = p.trackStock && p.stock <= 0
  const low = p.stockStatus === 'BAJO'
  const many = p.presentations.length > 1
  const parent = p.productType === 'PADRE'
  if (!base) return null
  return (
    <article
      className={`group relative flex flex-col justify-between overflow-hidden rounded-2xl bg-card p-3 shadow-tier1 ring-1 ring-line transition select-none active:scale-[0.98] ${out ? 'opacity-50' : ''}`}
    >
      <button type="button" onClick={() => (many ? onPick(p) : onAdd(p))} className="text-left" aria-label={`Agregar ${p.name}`}>
        <div className="mb-1.5 flex items-center justify-between gap-1">
          {out ? (
            <span className="rounded-full bg-danger-soft px-2 py-0.5 text-[10px] font-bold text-danger-ink">AGOTADO</span>
          ) : low ? (
            <span className="flex items-center gap-0.5 rounded-full bg-warning-soft px-2 py-0.5 text-[10px] font-bold text-warning-ink">
              <Icon name="warning" className="text-[12px]" /> Stock bajo
            </span>
          ) : topSeller ? (
            <span className="flex items-center gap-0.5 rounded-full bg-primary-soft px-2 py-0.5 text-[10px] font-bold text-primary-active">
              <Icon name="local_fire_department" className="text-[12px]" /> Favorito
            </span>
          ) : (
            <span className="truncate rounded-full bg-line-soft px-2 py-0.5 text-[10px] font-bold text-muted">{categoryName ?? 'General'}</span>
          )}
          {p.trackStock && <span className={`num shrink-0 text-[10px] font-bold ${out || low ? 'text-danger-ink' : 'text-muted'}`}>{qty(p.stock)} uds</span>}
        </div>
        <div className="mb-2 flex h-20 w-full items-center justify-center overflow-hidden rounded-xl bg-primary-tint sm:h-24">
          {p.photo ? <img src={p.photo} alt="" loading="lazy" className="h-full w-full object-contain p-1" /> : <Icon name="inventory_2" className="text-[30px] text-primary/40" />}
        </div>
        <h3 className="line-clamp-2 text-[14px] leading-tight font-semibold">{p.name}</h3>
        <span className="mt-0.5 block truncate text-[11px] text-muted">
          {[
            p.brand,
            parent ? (p.variantOptions ?? []).map((o) => o.name).join(' y ') || 'Variantes' : many ? `${p.presentations.length} presentaciones` : null,
            p.productType === 'COMBO' ? 'Combo' : null,
            p.trackSerials ? 'Con serial' : null,
          ]
            .filter(Boolean)
            .join(' · ') || ' '}
        </span>
      </button>
      <div className="mt-2 flex items-end justify-between gap-1">
        <div>
          <span className="block text-[10px] leading-none font-bold text-muted uppercase">{parent ? 'Desde' : 'Precio'}</span>
          <span className="num text-lg leading-tight font-bold tracking-tight">{money(base.price, currency)}</span>
        </div>
        <button
          type="button"
          onClick={() => onAdd(p)}
          aria-label={`Agregar una unidad de ${p.name}`}
          className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary text-white shadow-tier1 transition hover:bg-primary-active active:scale-95"
        >
          <Icon name="add" className="text-[22px]" />
        </button>
      </div>
    </article>
  )
})
