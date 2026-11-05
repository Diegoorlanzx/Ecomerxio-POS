import { useCallback, useMemo, useState } from 'react'
import { Banner, Button, EmptyState, Field, Icon, Modal } from '../../components/ui'
import { money } from '../../lib/format'
import { UNIT_LABELS, UNIT_SHORT, type PosProduct } from '../../lib/posTypes'
import { readScaleWeight, scaleSupported } from '../../lib/scale'

/** Unidades que se venden por peso. */
export const WEIGHT_UNITS = ['KILOGRAMO', 'GRAMO', 'LIBRA']

const fold = (s: string) => s.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '')

/** Kilos a la unidad del producto (gramos, libras o kilos), con 3 decimales. */
export function kgToUnit(kg: number, unit: string): number {
  const v = unit === 'GRAMO' ? kg * 1000 : unit === 'LIBRA' ? kg / 0.453592 : kg
  return Math.round(v * 1000) / 1000
}

const show = (n: number) => n.toLocaleString('es-CO', { maximumFractionDigits: 3, useGrouping: false })
const basePrice = (p: PosProduct) => Number((p.presentations.find((x) => x.isBase) ?? p.presentations[0])?.price ?? 0)

/**
 * Botón de báscula de la caja: 1) muestra solo los productos que se venden por peso, 2) al elegir uno pesa,
 * muestra peso, precio por kilo y total, 3) "Agregar" lo pasa a la cuenta con su peso y valor, y la caja sigue
 * lista para cobrar o agregar más. Sin cable (o en un navegador sin puerto serial) el peso se escribe a mano.
 */
export default function ScaleModal({
  products,
  currency,
  onClose,
  onAdd,
}: {
  products: PosProduct[]
  currency: string
  onClose: () => void
  onAdd: (product: PosProduct, quantity: number) => void
}) {
  const canRead = scaleSupported()
  const [chosen, setChosen] = useState<PosProduct | null>(null)
  const [kg, setKg] = useState('')
  const [reading, setReading] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')

  const read = useCallback(async () => {
    setReading(true)
    setError('')
    try {
      const w = await readScaleWeight()
      setKg(show(Math.round(w * 1000) / 1000))
      if (!(w > 0)) setError('La báscula marca 0. Pon el producto encima y vuelve a pesar.')
    } catch (e) {
      // Cerrar la lista de puertos sin elegir no es un error que haya que mostrar.
      const msg = e instanceof Error ? e.message : ''
      if (!/No port selected|cancel/i.test(msg)) setError(msg || 'No se pudo leer la báscula.')
    } finally {
      setReading(false)
    }
  }, [])

  function choose(p: PosProduct) {
    setChosen(p)
    setKg('')
    setError('')
    // Pesa apenas se elige (el clic permite pedir el puerto la primera vez).
    if (canRead) void read()
  }

  // Coma o punto como decimal ("1,25" o "1.25"): la báscula no pasa de 1000 kg, no hay separador de miles.
  const weight = Number(kg.trim().replace(',', '.'))
  const ok = weight > 0 && weight < 1000

  const list = useMemo(() => {
    const q = fold(query.trim())
    const all = [...products].sort((a, b) => Number(b.favorite) - Number(a.favorite) || a.name.localeCompare(b.name))
    if (!q) return all
    const plu = q.replace(/^0+(?=\d)/, '')
    return all.filter((p) => fold(p.name).includes(q) || fold(p.brand ?? '').includes(q) || (p.plu ?? '') === plu || p.sku.toLowerCase().startsWith(q))
  }, [products, query])

  // ---------------- paso 2: pesar el producto elegido ----------------
  if (chosen) {
    const price = basePrice(chosen)
    const unit = UNIT_SHORT[chosen.unit] ?? ''
    const unitName = UNIT_LABELS[chosen.unit]?.toLowerCase() ?? unit
    const q = ok ? kgToUnit(weight, chosen.unit) : 0
    const total = Math.round(q * price)
    return (
      <Modal
        open
        title={chosen.name}
        onClose={onClose}
        footer={
          <div className="flex gap-2">
            <Button variant="secondary" icon="arrow_back" onClick={() => setChosen(null)}>
              Otro producto
            </Button>
            <Button className="flex-1" icon="add_shopping_cart" disabled={!ok || reading} onClick={() => onAdd(chosen, q)}>
              Agregar {ok ? money(total, currency) : ''}
            </Button>
          </div>
        }
      >
        <div className="space-y-3">
          <div className="rounded-2xl bg-primary-tint/60 p-4">
            <div className="flex items-center gap-3">
              <Icon name="scale" className="text-[34px] text-primary" />
              <div className="min-w-0 flex-1">
                <div className="text-xs font-semibold tracking-wide text-muted uppercase">Peso</div>
                <div className="truncate text-3xl font-black whitespace-nowrap tabular-nums leading-tight sm:text-4xl">
                  {reading ? 'Pesando…' : ok ? `${kg} kg` : '— kg'}
                </div>
              </div>
              {canRead && (
                <Button variant="secondary" icon="refresh" disabled={reading} onClick={() => void read()} aria-label="Volver a pesar">
                  <span className="hidden sm:inline">Volver a pesar</span>
                </Button>
              )}
            </div>
            <div className="mt-3 grid grid-cols-2 gap-2 border-t border-primary/15 pt-3 text-sm">
              <div>
                <div className="text-xs text-muted">Precio por {unitName}</div>
                <div className="font-semibold tabular-nums">{money(price, currency)}</div>
              </div>
              <div className="text-right">
                <div className="text-xs text-muted">Valor a cobrar{ok && unit !== 'kg' ? ` (${show(q)} ${unit})` : ''}</div>
                <div className="text-2xl font-black tabular-nums text-primary">{ok ? money(total, currency) : '—'}</div>
              </div>
            </div>
          </div>

          {error && <Banner tone="warning">{error}</Banner>}
          {!canRead && (
            <Banner tone="primary">Este navegador no se conecta a la báscula: escribe el peso. Para leerlo solo, abre el POS en Chrome o Edge en un computador.</Banner>
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault()
              if (ok && !reading) onAdd(chosen, q)
            }}
          >
            <Field
              label="Peso en kilos"
              inputMode="decimal"
              value={kg}
              onChange={(e) => setKg(e.target.value)}
              placeholder="Ej: 1,25"
              autoFocus={!canRead}
              hint={canRead ? 'Se llena solo al pesar; también puedes escribirlo.' : 'Mira el peso en la báscula y escríbelo aquí.'}
            />
          </form>
        </div>
      </Modal>
    )
  }

  // ---------------- paso 1: elegir el producto ----------------
  return (
    <Modal open title="Báscula: ¿qué vas a pesar?" onClose={onClose} wide>
      <div className="space-y-3">
        {products.length > 0 && (
          <Field label="Buscar producto" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Nombre o código de báscula" autoFocus />
        )}
        {products.length === 0 ? (
          <EmptyState icon="scale" title="No hay productos por peso" text="Crea o edita un producto en Inventario con unidad Kilogramo, Gramo o Libra." />
        ) : list.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted">Ningún producto por peso coincide con “{query}”.</p>
        ) : (
          <div className="grid max-h-[55vh] grid-cols-1 gap-2 overflow-y-auto sm:grid-cols-2">
            {list.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => choose(p)}
                className="flex items-center gap-3 rounded-xl border border-line bg-card p-3 text-left transition hover:border-primary active:scale-[0.99]"
              >
                <Icon name="scale" className="text-[22px] text-primary" />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-semibold">{p.name}</div>
                  <div className="text-xs text-muted">
                    {money(basePrice(p), currency)} / {UNIT_SHORT[p.unit] ?? ''}
                    {p.plu ? ` · PLU ${p.plu}` : ''}
                  </div>
                </div>
                <Icon name="chevron_right" className="text-[20px] text-muted" />
              </button>
            ))}
          </div>
        )}
      </div>
    </Modal>
  )
}
