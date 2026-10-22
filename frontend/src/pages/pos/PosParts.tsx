import { useState } from 'react'
import { Banner, Button, Card, EmptyState, Field, Icon, Modal } from '../../components/ui'
import type { CartLine, CartTotals } from '../../lib/cart'
import { money, qty, relative } from '../../lib/format'
import type { AccountSummary, PosProduct, SaleDto } from '../../lib/posTypes'

/** Elegir presentación (unidad, six pack, caja) al tocar un producto que tiene varias. */
export function PresentationPicker({ p, currency, onPick, onClose }: { p: PosProduct; currency: string; onPick: (presentationId: number) => void; onClose: () => void }) {
  return (
    <Modal open title={p.name} onClose={onClose}>
      <div className="grid gap-2">
        {p.presentations.map((pr) => (
          <button
            key={pr.id}
            type="button"
            onClick={() => onPick(pr.id)}
            className="flex items-center justify-between rounded-xl border border-line px-4 py-4 text-left hover:border-primary hover:bg-primary-tint"
          >
            <span>
              <span className="block font-semibold">{pr.name}</span>
              <span className="text-xs text-muted">{pr.isBase ? 'Unidad base' : `Contiene ${qty(pr.factor)} unidades`}</span>
            </span>
            <span className="num text-lg font-bold">{money(pr.price, currency)}</span>
          </button>
        ))}
      </div>
    </Modal>
  )
}

/** Cambiar cantidad, precio especial o descuento de una línea. */
export function LineEditor({
  line,
  currency,
  maxDiscount,
  onSave,
  onRemove,
  onClose,
  weigh,
}: {
  line: CartLine
  currency: string
  maxDiscount: number
  /** Lee el peso de la báscula conectada (en la unidad del producto). */
  weigh?: () => Promise<number>
  onSave: (patch: Partial<CartLine>) => void
  onRemove: () => void
  onClose: () => void
}) {
  const decimals = line.unit !== 'UNIDAD' || !Number.isInteger(line.factor)
  const [q, setQ] = useState(String(line.quantity))
  const [price, setPrice] = useState(String(line.unitPrice))
  const [disc, setDisc] = useState(line.discountPct ? String(line.discountPct) : '')
  const [weighing, setWeighing] = useState(false)
  const [weighError, setWeighError] = useState('')
  const serialLine = !!line.trackSerials
  const nq = Number(q.replace(',', '.'))
  const np = Number(price.replace(/\D/g, ''))
  const nd = Number(disc.replace(',', '.') || 0)
  const invalid = !(nq > 0) || (!decimals && !Number.isInteger(nq)) || np < 0 || nd < 0 || nd > 100 || (serialLine && nq !== line.quantity)
  const effective = np * (1 - nd / 100)
  return (
    <Modal
      open
      title={line.name}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" icon="delete" className="text-danger-ink" onClick={onRemove}>
            Quitar
          </Button>
          <div className="flex-1" />
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button onClick={() => onSave({ quantity: nq, unitPrice: np, discountPct: nd })} disabled={invalid}>
            Aplicar
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="text-sm text-muted">
          {line.presentationName} · precio de lista {money(line.listPrice, currency)}
        </div>
        {serialLine && (
          <div>
            <div className="mb-1 text-xs font-bold tracking-wide text-muted uppercase">Seriales</div>
            <div className="flex flex-wrap gap-1.5">
              {(line.serials ?? []).map((s) => (
                <span key={s} className="rounded-full bg-primary-soft px-2.5 py-1 font-mono text-xs font-semibold text-primary-active">
                  {s}
                </span>
              ))}
            </div>
            <p className="mt-1 text-xs text-muted">Para vender otra unidad usa el botón + del carrito: pedirá su serial.</p>
          </div>
        )}
        {weigh && (
          <div>
            <Button
              variant="secondary"
              icon="scale"
              className="w-full"
              disabled={weighing}
              onClick={async () => {
                setWeighing(true)
                setWeighError('')
                try {
                  const w = await weigh()
                  setQ(String(Math.round(w * 1000) / 1000))
                } catch (e) {
                  setWeighError(e instanceof Error ? e.message : 'No se pudo leer la báscula')
                } finally {
                  setWeighing(false)
                }
              }}
            >
              {weighing ? 'Leyendo la báscula…' : 'Pesar con la báscula'}
            </Button>
            {weighError && <p className="mt-1 text-xs text-danger-ink">{weighError}</p>}
          </div>
        )}
        <div className={`flex items-center gap-2 ${serialLine ? 'hidden' : ''}`}>
          <button type="button" aria-label="Menos" onClick={() => setQ(String(Math.max(decimals ? 0.1 : 1, nq - 1)))} className="flex h-14 w-14 items-center justify-center rounded-xl bg-line-soft">
            <Icon name="remove" />
          </button>
          <input
            inputMode={decimals ? 'decimal' : 'numeric'}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            className="num h-14 flex-1 rounded-xl border border-line text-center text-2xl font-bold outline-none focus:border-primary"
            aria-label="Cantidad"
          />
          <button type="button" aria-label="Más" onClick={() => setQ(String(nq + 1))} className="flex h-14 w-14 items-center justify-center rounded-xl bg-line-soft">
            <Icon name="add" />
          </button>
        </div>
        {decimals && <p className="text-xs text-muted">Se vende por {line.unit.toLowerCase()}: puedes usar decimales (ej. 0,75).</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Precio por unidad" inputMode="numeric" value={price} onChange={(e) => setPrice(e.target.value)} hint={line.minPrice ? `Mínimo ${money(line.minPrice, currency)}` : undefined} />
          <Field label="Descuento %" inputMode="decimal" value={disc} onChange={(e) => setDisc(e.target.value)} hint={`Hasta ${maxDiscount}% sin autorización`} />
        </div>
        {(np < line.listPrice || nd > 0) && (
          <Banner tone={line.minPrice && effective < line.minPrice ? 'warning' : 'primary'}>
            Precio final {money(effective, currency)} por unidad.
            {line.minPrice && effective < line.minPrice ? ' Está por debajo del mínimo: pedirá autorización.' : ''}
          </Banner>
        )}
      </div>
    </Modal>
  )
}

/** Lista del carrito (venta normal o cuenta abierta). */
export function CartList({
  lines,
  totals,
  currency,
  onQty,
  onEdit,
  busy,
}: {
  lines: CartLine[]
  totals: CartTotals
  currency: string
  onQty: (key: string, q: number) => void
  onEdit: (key: string) => void
  busy?: boolean
}) {
  if (lines.length === 0) {
    return <EmptyState icon="shopping_cart" title="Carrito vacío" text="Busca, escanea o toca un producto para agregarlo." />
  }
  return (
    <ul className={`divide-y divide-line-soft ${busy ? 'opacity-60' : ''}`}>
      {lines.map((l, i) => {
        const c = totals.lines[i]
        const decimals = l.unit !== 'UNIDAD' || !Number.isInteger(l.factor)
        return (
          <li key={l.key} className="flex items-center gap-2 py-2.5">
            <button type="button" onClick={() => onEdit(l.key)} className="min-w-0 flex-1 text-left">
              <div className="truncate text-sm font-semibold">{l.name}</div>
              <div className="num truncate text-xs text-muted">
                {l.presentationName !== 'Unidad' ? `${l.presentationName} · ` : ''}
                {money(l.unitPrice, currency)}
                {l.unitPrice !== l.listPrice && !l.modifiers && <span className="ml-1 text-warning-ink">(precio especial)</span>}
                {l.discountPct > 0 && <span className="ml-1 text-success-ink">-{l.discountPct}%</span>}
              </div>
              {l.modifiers && <div className="truncate text-xs text-ink">{l.modifiers}</div>}
              {l.kitchenNote && <div className="truncate text-xs font-semibold text-warning-ink">“{l.kitchenNote}”</div>}
              {l.pendingKitchen !== undefined && (
                <div className={`text-[11px] font-semibold ${l.pendingKitchen ? 'text-warning-ink' : 'text-success-ink'}`}>
                  {l.pendingKitchen ? '● Sin enviar a cocina' : '✓ En cocina'}
                </div>
              )}
              {l.serials && l.serials.length > 0 && (
                <div className="truncate font-mono text-[11px] text-primary-active">
                  <Icon name="qr_code_2" className="mr-0.5 align-[-2px] text-[13px]" />
                  {l.serials.join(', ')}
                </div>
              )}
              {c?.promoName && (
                <div className="flex items-center gap-1 text-[11px] font-semibold text-wallet-ink">
                  <Icon name="local_offer" className="text-[13px]" />
                  {c.promoName} · -{money(c.promoDiscount, currency)}
                </div>
              )}
            </button>
            <div className="flex items-center rounded-full bg-line-soft">
              <button
                type="button"
                aria-label={`Menos ${l.name}`}
                disabled={busy}
                onClick={() => onQty(l.key, l.quantity - (decimals ? 0.5 : 1))}
                className="flex h-9 w-9 items-center justify-center rounded-full hover:bg-line"
              >
                <Icon name={l.quantity <= (decimals ? 0.5 : 1) ? 'delete' : 'remove'} className="text-[18px]" />
              </button>
              <span className="num min-w-7 text-center text-sm font-bold">{qty(l.quantity)}</span>
              <button
                type="button"
                aria-label={`Más ${l.name}`}
                disabled={busy}
                onClick={() => onQty(l.key, l.quantity + 1)}
                className="flex h-9 w-9 items-center justify-center rounded-full hover:bg-line"
              >
                <Icon name="add" className="text-[18px]" />
              </button>
            </div>
            <div className="num w-20 text-right text-sm font-bold">{money(c?.lineTotal ?? 0, currency)}</div>
          </li>
        )
      })}
    </ul>
  )
}

/** Tablero de cuentas abiertas (hasta el límite de la tienda). */
export function AccountsBoard({
  accounts,
  limit,
  currency,
  offline,
  onOpen,
  onNew,
}: {
  accounts: AccountSummary[]
  limit: number
  currency: string
  offline: boolean
  onOpen: (id: number) => void
  onNew: () => void
}) {
  const full = accounts.length >= limit
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold">Cuentas abiertas</h2>
          <p className="num text-sm text-muted">
            {accounts.length} de {limit} · el inventario se descuenta al cobrar
          </p>
        </div>
        <Button icon="add" onClick={onNew} disabled={full || offline}>
          Nueva cuenta
        </Button>
      </div>
      {offline && <Banner tone="warning">Las cuentas abiertas necesitan internet porque las comparten todos los aparatos.</Banner>}
      {full && <Banner tone="warning">Llegaste al máximo de {limit} cuentas abiertas. Cobra o cierra alguna para abrir otra.</Banner>}
      <div className="h-1.5 overflow-hidden rounded-full bg-line-soft">
        <div className={`h-full rounded-full ${full ? 'bg-danger' : 'bg-primary'}`} style={{ width: `${(accounts.length / limit) * 100}%` }} />
      </div>
      {accounts.length === 0 ? (
        <Card>
          <EmptyState icon="receipt_long" title="No hay cuentas abiertas" text="Abre una cuenta para el cliente que sigue consumiendo y cóbrala cuando se vaya." />
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
          {accounts.map((a) => (
            <button
              key={a.id}
              type="button"
              onClick={() => onOpen(a.id)}
              className="rounded-2xl bg-card p-4 text-left shadow-tier1 ring-1 ring-line transition hover:ring-primary active:scale-[0.98]"
            >
              <div className="flex items-center justify-between">
                <Icon name="receipt_long" className="text-[22px] text-primary" />
                <span className="text-[11px] text-muted">{relative(a.createdAt)}</span>
              </div>
              <div className="mt-2 truncate font-semibold">{a.label}</div>
              <div className="truncate text-xs text-muted">{a.customerName ?? a.userName}</div>
              <div className="num mt-2 text-xl font-bold tracking-tight">{money(a.total, currency)}</div>
              <div className="num text-xs text-muted">{a.itemCount} productos</div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export function NewAccountModal({ onClose, onCreate }: { onClose: () => void; onCreate: (label: string) => Promise<string | null> }) {
  const [label, setLabel] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  async function go() {
    setLoading(true)
    const err = await onCreate(label)
    setLoading(false)
    if (err) setError(err)
  }
  return (
    <Modal
      open
      title="Nueva cuenta abierta"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={go} loading={loading} disabled={!label.trim()}>
            Abrir cuenta
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nombre de la cuenta" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Ej: Don Pedro, Mesa 3, Los del parqueadero" autoFocus onKeyDown={(e) => e.key === 'Enter' && label.trim() && go()} error={error} />
      </div>
    </Modal>
  )
}

export function HoldsModal({ holds, currency, onRecover, onClose }: { holds: SaleDto[]; currency: string; onRecover: (id: number) => void; onClose: () => void }) {
  return (
    <Modal open title="Ventas en espera" onClose={onClose}>
      {holds.length === 0 ? (
        <EmptyState icon="pause_circle" title="No hay ventas en espera" />
      ) : (
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          {holds.map((h) => (
            <button key={h.id} type="button" onClick={() => onRecover(h.id)} className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-canvas">
              <Icon name="pause_circle" className="text-[22px] text-primary" />
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{h.accountLabel}</div>
                <div className="text-xs text-muted">
                  {h.lines.length} productos · {h.userName} · {relative(h.createdAt)}
                </div>
                {/* Qué lleva, para reconocerla sin abrirla. */}
                <ul className="mt-1 space-y-0.5 text-xs">
                  {h.lines.slice(0, 4).map((l) => (
                    <li key={l.id} className="flex gap-2">
                      <span className="num w-10 shrink-0 text-right font-semibold">{qty(Number(l.quantity))}×</span>
                      <span className="min-w-0 flex-1 truncate">
                        {l.productName}
                        {l.presentationName && l.presentationName !== 'Unidad' ? ` · ${l.presentationName}` : ''}
                      </span>
                      <span className="num shrink-0 text-muted">{money(l.lineTotal, currency)}</span>
                    </li>
                  ))}
                  {h.lines.length > 4 && <li className="pl-12 text-muted">y {h.lines.length - 4} más…</li>}
                </ul>
              </div>
              <span className="num font-bold">{money(h.total, currency)}</span>
            </button>
          ))}
        </div>
      )}
    </Modal>
  )
}
