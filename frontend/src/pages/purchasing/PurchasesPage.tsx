import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Icon, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, qty, shortDate } from '../../lib/format'
import { ORDER_STATUS, type OrderStatus, type PurchaseOrder, type SuggestionLine, type Suggestions, type Supplier } from '../../lib/purchasingTypes'
import { DirectReceiptModal, OrderEditor } from './OrderDialogs'

const PRIORITY = {
  CRITICO: { label: 'Crítico', tone: 'danger' as const },
  ALTO: { label: 'Alto', tone: 'warning' as const },
  NORMAL: { label: 'Normal', tone: 'neutral' as const },
}

export default function PurchasesPage() {
  const { can } = useAuth()
  const [tab, setTab] = useState<'suggest' | 'orders'>(can('COMPRAS_CREAR') ? 'suggest' : 'orders')
  const [direct, setDirect] = useState(false)
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const toast = useToast()
  const navigate = useNavigate()

  useEffect(() => {
    api<Supplier[]>('/suppliers').then(setSuppliers).catch(() => {})
  }, [])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Compras y reposición</h2>
          <p className="text-sm text-muted">El sistema calcula qué comprar según lo que vendes, lo que tienes, lo que viene en camino y lo que tarda cada proveedor.</p>
        </div>
        {can('COMPRAS_RECIBIR') && (
          <Button variant="secondary" icon="move_to_inbox" onClick={() => setDirect(true)}>
            Recibir sin orden
          </Button>
        )}
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          ...(can('COMPRAS_CREAR') ? [{ value: 'suggest' as const, label: 'Qué comprar' }] : []),
          { value: 'orders' as const, label: 'Órdenes de compra' },
        ]}
      />
      {tab === 'suggest' ? <SuggestionsView onCreated={(id) => navigate(`/compras/${id}`)} /> : <OrdersView suppliers={suppliers} />}
      {direct && (
        <DirectReceiptModal
          suppliers={suppliers}
          onClose={() => setDirect(false)}
          onDone={() => {
            setDirect(false)
            toast('Mercancía recibida e inventario actualizado')
          }}
        />
      )}
    </div>
  )
}

function SuggestionsView({ onCreated }: { onCreated: (id: number) => void }) {
  const toast = useToast()
  const [data, setData] = useState<Suggestions | null>(null)
  const [packs, setPacks] = useState<Record<string, number>>({})
  const [skip, setSkip] = useState<Set<string>>(new Set())
  const [open, setOpen] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Suggestions>('/purchases/suggestions').then(setData).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  const key = (s: number, p: number) => `${s}-${p}`
  const lineCost = (s: number, l: SuggestionLine) => (packs[key(s, l.productId)] ?? l.packs) * l.costPerPack
  const selectedTotal = useMemo(() => {
    if (!data) return 0
    return data.suppliers.reduce((sum, g) => sum + g.lines.filter((l) => !skip.has(key(g.supplierId, l.productId))).reduce((x, l) => x + lineCost(g.supplierId, l), 0), 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, packs, skip])

  /** Ajusta la selección al presupuesto: primero entra lo crítico, luego lo alto y por último lo normal. */
  function fitBudget() {
    if (!data || data.budgetLeft === undefined || data.budgetLeft === null) return
    let left = data.budgetLeft
    const all = data.suppliers.flatMap((g) => g.lines.map((l) => ({ g, l })))
    const order = { CRITICO: 0, ALTO: 1, NORMAL: 2 }
    all.sort((a, b) => order[a.l.priority] - order[b.l.priority])
    const nextSkip = new Set<string>()
    for (const { g, l } of all) {
      const c = lineCost(g.supplierId, l)
      if (c <= left) left -= c
      else nextSkip.add(key(g.supplierId, l.productId))
    }
    setSkip(nextSkip)
    toast(`Selección ajustada al presupuesto (${nextSkip.size} productos quedaron por fuera)`)
  }

  async function createOrder(supplierId: number, lines: SuggestionLine[]) {
    const chosen = lines.filter((l) => !skip.has(key(supplierId, l.productId)))
    if (!chosen.length) return
    setBusy(supplierId)
    try {
      const o = await api<PurchaseOrder>('/purchases', {
        body: {
          supplierId,
          source: 'SUGERIDA',
          lines: chosen.map((l) => ({
            productId: l.productId,
            packName: l.packName,
            packFactor: l.packFactor,
            packs: packs[key(supplierId, l.productId)] ?? l.packs,
            costPerPack: l.costPerPack,
            reason: l.reason,
          })),
        },
      })
      toast(`Pedido #${o.number} creado en borrador`)
      onCreated(o.id)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo crear', 'danger')
    } finally {
      setBusy(null)
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner label="Calculando qué comprar…" />
  const groups = data.suppliers.filter((g) => g.lines.length)
  const later = data.suppliers.filter((g) => !g.lines.length)
  const budget = data.monthlyBudget ?? 0

  return (
    <div className="space-y-4">
      {budget > 0 && (
        <Card className="p-4">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div>
              <div className="text-[11px] font-bold tracking-wide text-muted uppercase">Presupuesto de compras del mes</div>
              <div className="num text-lg font-bold">
                {money(data.budgetLeft)} disponibles <span className="text-sm font-medium text-muted">de {money(budget)}</span>
              </div>
            </div>
            <Button size="sm" variant="soft" icon="savings" onClick={fitBudget}>
              Ajustar al presupuesto
            </Button>
          </div>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-line-soft">
            <div className="h-full bg-navy" style={{ width: `${Math.min(100, ((data.spentThisMonth ?? 0) / budget) * 100)}%` }} />
          </div>
          <div className="num mt-1 flex justify-between text-xs text-muted">
            <span>Ya aprobado: {money(data.spentThisMonth)}</span>
            <span className={selectedTotal > (data.budgetLeft ?? 0) ? 'font-bold text-danger-ink' : ''}>Esta selección: {money(selectedTotal)}</span>
          </div>
        </Card>
      )}
      {groups.length === 0 && data.withoutSupplier.length === 0 ? (
        <Card>
          <EmptyState icon="task_alt" title="No hay que comprar nada por ahora" text="Todo está por encima de su punto de pedido. Abajo verás cuándo te tocará pedir a cada proveedor." />
        </Card>
      ) : (
        groups.map((g) => {
          const chosen = g.lines.filter((l) => !skip.has(key(g.supplierId, l.productId)))
          const total = chosen.reduce((s, l) => s + lineCost(g.supplierId, l), 0)
          return (
            <Card key={g.supplierId} className="overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line-soft bg-primary-tint/50 px-4 py-3">
                <div>
                  <div className="flex items-center gap-2">
                    <Link to={`/proveedores/${g.supplierId}`} className="font-semibold hover:underline">
                      {g.supplierName}
                    </Link>
                    {g.critical > 0 && <Chip tone="danger">{g.critical} críticos</Chip>}
                  </div>
                  <div className="text-xs text-muted">
                    Entrega en {qty(g.leadDays)} días{g.orderDays ? ` · pide ${g.orderDays.toLowerCase()}` : ''} · próxima fecha de pedido {g.nextOrderDate}
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className="num text-lg font-bold">{money(total)}</span>
                  <Button size="sm" icon="add_shopping_cart" loading={busy === g.supplierId} disabled={!chosen.length} onClick={() => createOrder(g.supplierId, g.lines)}>
                    Crear pedido
                  </Button>
                </div>
              </div>
              <div className="divide-y divide-line-soft">
                {g.lines.map((l) => {
                  const k = key(g.supplierId, l.productId)
                  const off = skip.has(k)
                  const n = packs[k] ?? l.packs
                  return (
                    <div key={k} className={`px-4 py-3 ${off ? 'opacity-50' : ''}`}>
                      <div className="flex flex-wrap items-center gap-3">
                        <input
                          type="checkbox"
                          checked={!off}
                          aria-label={`Incluir ${l.productName}`}
                          onChange={() => setSkip((s) => {
                            const x = new Set(s)
                            if (x.has(k)) x.delete(k)
                            else x.add(k)
                            return x
                          })}
                          className="h-4 w-4 accent-primary"
                        />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-semibold">{l.productName}</span>
                            <Chip tone={PRIORITY[l.priority].tone}>{PRIORITY[l.priority].label}</Chip>
                          </div>
                          <div className="num text-xs text-muted">
                            Hay {qty(l.stock)}
                            {l.onOrder > 0 ? ` · vienen ${qty(l.onOrder)}` : ''} · vende {qty(l.dailyDemand)}/día
                            {l.coverageDays !== undefined && l.coverageDays !== null ? ` · alcanza ${qty(l.coverageDays)} días` : ''}
                          </div>
                        </div>
                        <div className="flex items-center gap-1 rounded-full bg-line-soft">
                          <button type="button" aria-label="Menos" onClick={() => setPacks({ ...packs, [k]: Math.max(1, n - 1) })} className="flex h-9 w-9 items-center justify-center rounded-full hover:bg-line">
                            <Icon name="remove" className="text-[18px]" />
                          </button>
                          <span className="num min-w-6 text-center text-sm font-bold">{n}</span>
                          <button type="button" aria-label="Más" onClick={() => setPacks({ ...packs, [k]: n + 1 })} className="flex h-9 w-9 items-center justify-center rounded-full hover:bg-line">
                            <Icon name="add" className="text-[18px]" />
                          </button>
                        </div>
                        <div className="num w-32 text-right">
                          <div className="text-xs text-muted">
                            {l.packName} ({qty(n * l.packFactor)} u)
                          </div>
                          <div className="font-bold">{money(n * l.costPerPack)}</div>
                        </div>
                        <button type="button" onClick={() => setOpen(open === k ? null : k)} className="text-xs font-semibold text-primary">
                          {open === k ? 'Ocultar' : '¿Por qué?'}
                        </button>
                      </div>
                      {open === k && (
                        <div className="mt-2 rounded-xl bg-canvas p-3 text-sm">
                          <p>{l.reason}</p>
                          {l.alternatives.length > 0 && (
                            <p className="num mt-2 text-xs text-muted">
                              Otros proveedores:{' '}
                              {l.alternatives.map((a) => `${a.supplierName} ${money(a.unitCost)} c/u (${a.packName})`).join(' · ')}
                            </p>
                          )}
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            </Card>
          )
        })
      )}
      {data.withoutSupplier.length > 0 && (
        <Card className="p-4">
          <h3 className="flex items-center gap-2 font-semibold">
            <Icon name="help" className="text-warning" /> Hace falta comprar, pero no tienen proveedor asignado
          </h3>
          <p className="mb-2 text-sm text-muted">Asócialos a un proveedor (en Proveedores › Productos) para poder pedirlos.</p>
          <ul className="divide-y divide-line-soft">
            {data.withoutSupplier.map((l) => (
              <li key={l.productId} className="num flex items-center justify-between py-2 text-sm">
                <span>
                  {l.productName} <Chip tone={PRIORITY[l.priority].tone}>{PRIORITY[l.priority].label}</Chip>
                </span>
                <span className="text-muted">faltan {qty(l.neededUnits)} u</span>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {later.length > 0 && (
        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Próximos pedidos</h3>
          <ul className="divide-y divide-line-soft text-sm">
            {later.map((g) => (
              <li key={g.supplierId} className="flex justify-between py-2">
                <span>{g.supplierName}</span>
                <span className="text-muted">alrededor del {g.nextOrderDate}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  )
}

function OrdersView({ suppliers }: { suppliers: Supplier[] }) {
  const { can } = useAuth()
  const navigate = useNavigate()
  const [status, setStatus] = useState<OrderStatus | ''>('')
  const [list, setList] = useState<PurchaseOrder[] | null>(null)
  const [creating, setCreating] = useState(false)

  const load = useCallback(() => {
    api<PurchaseOrder[]>(`/purchases${status ? `?status=${status}` : ''}`).then(setList).catch(() => setList([]))
  }, [status])
  useEffect(load, [load])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="no-scrollbar flex flex-1 gap-2 overflow-x-auto">
          {(['', 'BORRADOR', 'POR_APROBAR', 'APROBADA', 'PARCIAL', 'RECIBIDA', 'CANCELADA'] as const).map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setStatus(s)}
              className={`h-9 shrink-0 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${status === s ? 'bg-primary text-white' : 'bg-card text-muted'}`}
            >
              {s ? ORDER_STATUS[s].label : 'Todas'}
            </button>
          ))}
        </div>
        {can('COMPRAS_CREAR') && (
          <Button icon="add" onClick={() => setCreating(true)}>
            Nueva orden
          </Button>
        )}
      </div>
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="receipt_long" title="No hay órdenes" />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {list.map((o) => (
            <Link key={o.id} to={`/compras/${o.id}`} className="flex items-center gap-3 px-4 py-3 hover:bg-canvas">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="num font-semibold">#{o.number}</span>
                  <span className="truncate font-semibold">{o.supplierName}</span>
                  <Chip tone={ORDER_STATUS[o.status].tone}>{ORDER_STATUS[o.status].label}</Chip>
                  {o.late && <Chip tone="danger" icon="schedule">Retrasado</Chip>}
                  {o.source === 'SUGERIDA' && <Chip tone="wallet">Sugerido</Chip>}
                </div>
                <div className="text-xs text-muted">
                  {shortDate(o.createdAt)} · {o.createdBy} · {o.lines.length} productos{o.expectedDate ? ` · llega ${o.expectedDate}` : ''}
                </div>
              </div>
              {o.estimatedTotal !== undefined && <span className="num font-bold">{money(o.estimatedTotal)}</span>}
            </Link>
          ))}
        </Card>
      )}
      {creating && <OrderEditor suppliers={suppliers} onClose={() => setCreating(false)} onSaved={(o) => navigate(`/compras/${o.id}`)} />}
    </div>
  )
}
