import { useState } from 'react'
import { Button, Field, Modal } from '../../components/ui'
import { UNIT_LABELS, UNIT_SHORT, type PosProduct } from '../../lib/posTypes'
import { money } from '../../lib/format'

/**
 * Venta por medida (ferretería, fruver sin báscula, agro): al tocar un producto por metro, kilo, litro o libra la caja pide
 * la cantidad de una vez (2,5 m de cable, 0,75 kg de puntilla) en vez de agregar 1 y obligar a editar la línea.
 */
export default function MeasureModal({
  product,
  currency,
  onClose,
  onAdd,
  weigh,
}: {
  product: PosProduct
  currency: string
  onClose: () => void
  onAdd: (quantity: number) => void
  weigh?: () => Promise<number>
}) {
  const base = product.presentations.find((x) => x.isBase) ?? product.presentations[0]
  const [q, setQ] = useState('')
  const [weighing, setWeighing] = useState(false)
  const n = Number(q.replace(',', '.'))
  const ok = n > 0 && n < 100000
  const unit = UNIT_LABELS[product.unit]?.toLowerCase() ?? product.unit.toLowerCase()
  const short = UNIT_SHORT[product.unit] ?? ''
  const quick = product.unit === 'METRO' ? [0.5, 1, 2, 5, 10] : product.unit === 'GRAMO' || product.unit === 'MILILITRO' ? [100, 250, 500, 1000] : [0.25, 0.5, 1, 2]

  return (
    <Modal
      open
      title={product.name}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="add_shopping_cart" disabled={!ok} onClick={() => onAdd(n)}>
          Agregar {ok ? `${String(n).replace('.', ',')} ${short} · ${money(n * Number(base?.price ?? 0), currency)}` : ''}
        </Button>
      }
    >
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault()
          if (ok) onAdd(n)
        }}
      >
        <Field label={`¿Cuántos ${unit}s?`} inputMode="decimal" autoFocus value={q} onChange={(e) => setQ(e.target.value)} hint={`Precio por ${unit}: ${money(Number(base?.price ?? 0), currency)}`} />
        <div className="flex flex-wrap gap-2">
          {quick.map((v) => (
            <button key={v} type="button" onClick={() => setQ(String(v).replace('.', ','))} className="h-10 rounded-lg border border-line px-3 text-sm font-semibold">
              {String(v).replace('.', ',')} {short}
            </button>
          ))}
          {weigh && (
            <button
              type="button"
              disabled={weighing}
              onClick={async () => {
                setWeighing(true)
                try {
                  setQ(String(Math.round((await weigh()) * 1000) / 1000).replace('.', ','))
                } finally {
                  setWeighing(false)
                }
              }}
              className="h-10 rounded-lg bg-primary-soft px-3 text-sm font-semibold text-primary-active"
            >
              Pesar
            </button>
          )}
        </div>
      </form>
    </Modal>
  )
}
