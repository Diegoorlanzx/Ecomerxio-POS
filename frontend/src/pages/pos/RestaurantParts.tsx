import { useEffect, useMemo, useState } from 'react'
import { Banner, Button, Field, Icon, Modal } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { PosPresentation, PosProduct, SaleDto } from '../../lib/posTypes'
import type { Floor, ModifierGroupDto } from '../../lib/restaurantTypes'

/**
 * Pedir un plato con sus modificadores (término, adiciones con precio, "sin…") y una nota para la cocina.
 * El precio final lo calcula el servidor; aquí solo se muestra la suma.
 */
export function DishPicker({
  product,
  presentation,
  groups,
  currency,
  onClose,
  onConfirm,
}: {
  product: PosProduct
  presentation: PosPresentation
  groups: ModifierGroupDto[]
  currency: string
  onClose: () => void
  onConfirm: (d: { modifierIds: number[]; note: string; quantity: number }) => Promise<string | null>
}) {
  const [chosen, setChosen] = useState<Set<number>>(new Set())
  const [note, setNote] = useState('')
  const [count, setCount] = useState(1)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const extra = groups.flatMap((g) => g.options).filter((o) => chosen.has(o.id)).reduce((s, o) => s + Number(o.price), 0)
  const missing = groups.find((g) => g.options.filter((o) => chosen.has(o.id)).length < g.minSelect)

  function toggle(g: ModifierGroupDto, id: number) {
    setChosen((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else {
        // Grupo de una sola opción: elegir otra reemplaza la anterior.
        if (g.maxSelect === 1) g.options.forEach((o) => next.delete(o.id))
        else if (g.maxSelect > 0 && g.options.filter((o) => next.has(o.id)).length >= g.maxSelect) return prev
        next.add(id)
      }
      return next
    })
  }

  async function confirm() {
    if (missing) return setError(`Elige ${missing.minSelect === 1 ? 'una opción' : missing.minSelect + ' opciones'} de "${missing.name}"`)
    setBusy(true)
    setError('')
    const err = await onConfirm({ modifierIds: [...chosen], note: note.trim(), quantity: count })
    setBusy(false)
    if (err) setError(err)
  }

  return (
    <Modal
      open
      title={product.name}
      onClose={onClose}
      footer={
        <div className="flex w-full items-center gap-2">
          <div className="flex items-center gap-1">
            <Button size="sm" variant="secondary" icon="remove" onClick={() => setCount(Math.max(1, count - 1))}>
              <span className="sr-only">Menos</span>
            </Button>
            <span className="num w-8 text-center font-bold">{count}</span>
            <Button size="sm" variant="secondary" icon="add" onClick={() => setCount(count + 1)}>
              <span className="sr-only">Más</span>
            </Button>
          </div>
          <Button className="flex-1" icon="add_shopping_cart" loading={busy} onClick={confirm}>
            Agregar {money((Number(presentation.price) + extra) * count, currency)}
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        {groups.map((g) => (
          <div key={g.id}>
            <div className="mb-1.5 flex items-center justify-between text-sm">
              <span className="font-semibold">{g.name}</span>
              <span className="text-xs text-muted">
                {g.minSelect > 0 ? 'Obligatorio' : 'Opcional'}
                {g.maxSelect > 1 ? ` · hasta ${g.maxSelect}` : ''}
              </span>
            </div>
            <div className="flex flex-wrap gap-2">
              {g.options.map((o) => {
                const on = chosen.has(o.id)
                return (
                  <button
                    key={o.id}
                    type="button"
                    onClick={() => toggle(g, o.id)}
                    className={`rounded-xl border px-3 py-2 text-sm font-semibold ${on ? 'border-primary bg-primary text-white' : 'border-line bg-card'}`}
                  >
                    {o.name}
                    {Number(o.price) > 0 && <span className={on ? 'text-blue-100' : 'text-muted'}> +{money(o.price, currency)}</span>}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
        <Field label="Nota para la cocina (opcional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Para niño, sin hielo, salsa aparte…" maxLength={200} />
      </div>
    </Modal>
  )
}

/** Dividir la cuenta: lo elegido pasa a otra cuenta en la misma mesa (cada quien paga lo suyo). */
export function SplitModal({ sale, currency, onClose, onDone }: { sale: SaleDto; currency: string; onClose: () => void; onDone: (part: SaleDto) => void }) {
  const [take, setTake] = useState<Record<number, number>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [people, setPeople] = useState('2')
  const moved = sale.lines.reduce((s, l) => s + (take[l.id] ?? 0) * (Number(l.lineTotal) / Number(l.quantity)), 0)
  const perPerson = Number(people) > 1 ? Number(sale.total) / Number(people) : 0

  async function split() {
    const lines = Object.entries(take)
      .filter(([, q]) => q > 0)
      .map(([lineId, quantity]) => ({ lineId: Number(lineId), quantity }))
    if (!lines.length) return setError('Elige qué pasa a la otra cuenta')
    setBusy(true)
    setError('')
    try {
      onDone(await api<SaleDto>(`/restaurant/accounts/${sale.id}/split`, { body: { lines } }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo dividir')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={`Dividir ${sale.accountLabel}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="call_split" loading={busy} onClick={split}>
          Pasar {money(moved, currency)} a otra cuenta
        </Button>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="rounded-xl bg-canvas p-3 text-sm">
          <div className="font-semibold">Partes iguales</div>
          <div className="mt-1 flex items-center gap-2">
            Entre
            <input className="num h-9 w-16 rounded-lg border border-line text-center" inputMode="numeric" value={people} onChange={(e) => setPeople(e.target.value.replace(/\D/g, ''))} />
            personas: <b className="num">{perPerson ? money(perPerson, currency) : '—'}</b> cada una.
          </div>
          <p className="mt-1 text-xs text-muted">Para cobrar en partes iguales no hace falta dividir: en el cobro registra un pago por persona.</p>
        </div>
        <div className="text-sm font-semibold">Por productos: elige qué paga la otra persona</div>
        <ul className="divide-y divide-line-soft rounded-xl border border-line">
          {sale.lines.map((l) => {
            const max = Number(l.quantity)
            const v = take[l.id] ?? 0
            return (
              <li key={l.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                <div className="min-w-0 flex-1">
                  <div className="truncate font-semibold">{l.productName}</div>
                  {l.modifiers && <div className="truncate text-xs text-muted">{l.modifiers}</div>}
                </div>
                <span className="num text-xs text-muted">de {qty(max)}</span>
                <div className="flex items-center gap-1">
                  <Button size="sm" variant="secondary" icon="remove" onClick={() => setTake({ ...take, [l.id]: Math.max(0, v - 1) })}>
                    <span className="sr-only">Menos</span>
                  </Button>
                  <span className="num w-7 text-center font-bold">{qty(v)}</span>
                  <Button size="sm" variant="secondary" icon="add" onClick={() => setTake({ ...take, [l.id]: Math.min(max, v + 1) })}>
                    <span className="sr-only">Más</span>
                  </Button>
                </div>
              </li>
            )
          })}
        </ul>
      </div>
    </Modal>
  )
}

/** Mover la cuenta a otra mesa libre, o unirla con la cuenta de una mesa ocupada. */
export function MoveModal({ sale, onClose, onDone }: { sale: SaleDto; onClose: () => void; onDone: (s: SaleDto, msg: string) => void }) {
  const [floor, setFloor] = useState<Floor | null>(null)
  const [error, setError] = useState('')
  useEffect(() => void api<Floor>('/restaurant/floor').then(setFloor).catch((e) => setError(e.message)), [])
  const others = useMemo(() => (floor?.tables ?? []).filter((t) => t.id !== sale.tableId), [floor, sale.tableId])

  async function go(tableId: number, into?: number) {
    setError('')
    try {
      if (into) onDone(await api<SaleDto>(`/restaurant/accounts/${sale.id}/merge`, { body: { intoAccountId: into } }), 'Cuentas unidas')
      else onDone(await api<SaleDto>(`/restaurant/accounts/${sale.id}/move`, { body: { tableId } }), 'Cuenta movida de mesa')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo')
    }
  }

  return (
    <Modal open wide title={`Mover o unir ${sale.accountLabel}`} onClose={onClose}>
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <p className="text-sm text-muted">Toca una mesa libre para pasar la cuenta allá, o una ocupada para juntar las dos cuentas en una.</p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {others.map((t) => {
            const busy = t.accounts.length > 0
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => go(t.id, busy ? t.accounts[0]!.id : undefined)}
                className={`rounded-xl border-2 p-3 text-left text-sm ${busy ? 'border-primary/50 bg-primary-tint' : 'border-line bg-card'}`}
              >
                <div className="font-bold">{t.name}</div>
                <div className="flex items-center gap-1 text-xs text-muted">
                  <Icon name={busy ? 'merge' : 'arrow_forward'} className="text-[14px]" /> {busy ? 'Unir aquí' : 'Pasar aquí'}
                </div>
              </button>
            )
          })}
        </div>
      </div>
    </Modal>
  )
}
