import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Card, Chip, EmptyState, Icon, Spinner, StatCard } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, pct, qty, relative } from '../../lib/format'
import type { AlertItem, Insights, ProductInsight } from '../../lib/purchasingTypes'
import InventoryNav from './InventoryNav'

const FLAG: Record<string, { label: string; tone: 'danger' | 'warning' | 'primary' | 'wallet' | 'neutral' }> = {
  AGOTADO: { label: 'Agotado', tone: 'danger' },
  AGOTAMIENTO_INMINENTE: { label: 'Se acaba pronto', tone: 'danger' },
  BAJO_MINIMO: { label: 'Bajo mínimo', tone: 'warning' },
  SIN_ROTACION: { label: 'Sin rotación', tone: 'neutral' },
  EXCESO: { label: 'Exceso', tone: 'primary' },
}

type Filter = 'all' | 'urgent' | 'SIN_ROTACION' | 'EXCESO' | 'A' | 'B' | 'C'

export default function SmartInventoryPage() {
  const { can } = useAuth()
  const [data, setData] = useState<Insights | null>(null)
  const [alerts, setAlerts] = useState<AlertItem[]>([])
  const [byUser, setByUser] = useState<{ userName: string; adjustments: number; unitsIn: number; unitsOut: number; valueOut?: number }[]>([])
  const [filter, setFilter] = useState<Filter>('urgent')
  const [sort, setSort] = useState<'coverage' | 'revenue' | 'value'>('coverage')
  const [error, setError] = useState('')
  const seeCosts = can('COSTOS_VER')

  useEffect(() => {
    api<Insights>('/inventory/insights').then(setData).catch((e) => setError(e.message))
    api<AlertItem[]>('/inventory/alerts').then(setAlerts).catch(() => {})
    api<typeof byUser>('/inventory/adjustments-by-user').then(setByUser).catch(() => {})
  }, [])

  const rows = useMemo(() => {
    if (!data) return []
    let r = data.products
    if (filter === 'urgent') r = r.filter((p) => p.flags.some((f) => f === 'AGOTADO' || f === 'AGOTAMIENTO_INMINENTE' || f === 'BAJO_MINIMO'))
    else if (filter === 'SIN_ROTACION' || filter === 'EXCESO') r = r.filter((p) => p.flags.includes(filter))
    else if (filter !== 'all') r = r.filter((p) => p.abc === filter)
    const cov = (p: ProductInsight) => (p.coverageDays === undefined || p.coverageDays === null ? 1e9 : p.coverageDays)
    return [...r].sort((a, b) =>
      sort === 'coverage' ? cov(a) - cov(b) : sort === 'revenue' ? b.revenueWindow - a.revenueWindow : (b.immobilizedValue ?? 0) - (a.immobilizedValue ?? 0),
    )
  }, [data, filter, sort])

  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner label="Analizando el inventario…" />
  const s = data.summary
  const urgent = s.outOfStock + s.belowMin + s.stockoutSoon

  const chips: { v: Filter; label: string; n?: number }[] = [
    { v: 'urgent', label: 'Por reponer', n: data.products.filter((p) => p.flags.some((f) => ['AGOTADO', 'AGOTAMIENTO_INMINENTE', 'BAJO_MINIMO'].includes(f))).length },
    { v: 'SIN_ROTACION', label: 'Sin rotación', n: s.noRotation },
    { v: 'EXCESO', label: 'Exceso', n: s.excess },
    { v: 'A', label: 'Clase A', n: s.abc.a },
    { v: 'B', label: 'Clase B', n: s.abc.b },
    { v: 'C', label: 'Clase C', n: s.abc.c },
    { v: 'all', label: 'Todos', n: s.activeProducts },
  ]

  return (
    <div className="space-y-4">
      <InventoryNav />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {seeCosts ? (
          <StatCard label="Valor del inventario" help="Existencias × costo de todos los productos activos." value={money(s.totalValue)} icon="account_balance_wallet" tone="navy" sub="A costo" />
        ) : (
          <StatCard label="Productos activos" help="Productos del catálogo que se están vendiendo." value={s.activeProducts} icon="inventory_2" tone="navy" />
        )}
        {seeCosts ? (
          <StatCard label="Capital inmovilizado" help="Plata en mercancía que no se mueve (sin ventas en los últimos días) o que sobra frente al máximo. Candidata a promoción." value={money(s.immobilizedValue)} icon="ac_unit" tone="wallet" sub={`${s.noRotation} sin rotación · ${s.excess} con exceso`} />
        ) : (
          <StatCard label="Sin rotación" help="Productos con existencias que no se han vendido en el período de análisis." value={s.noRotation} icon="ac_unit" tone="wallet" />
        )}
        <StatCard label="Alerta urgente" help="Agotados más los que se acaban antes de que llegue el próximo pedido, al ritmo actual de ventas." value={urgent} icon="emergency" tone="danger" sub={`${s.outOfStock} agotados · ${s.stockoutSoon} por agotarse`} />
        <StatCard label="Clasificación ABC" help="A: los pocos productos que dejan la mayor parte de las ventas (nunca deben faltar). B: importancia media. C: los que menos venden." value={`${s.abc.a} · ${s.abc.b} · ${s.abc.c}`} icon="leaderboard" sub={`La clase A deja el ${pct(s.abc.aRevenuePct)} de las ventas`} />
      </div>

      {alerts.length > 0 && (
        <Card className="p-4">
          <h3 className="mb-2 flex items-center gap-2 font-semibold">
            <Icon name="notifications_active" className="text-danger" /> Alertas
            <span className="num rounded-full bg-danger-soft px-2 text-xs text-danger-ink">{alerts.length}</span>
          </h3>
          <ul className="max-h-72 space-y-1.5 overflow-y-auto">
            {alerts.slice(0, 30).map((a, i) => (
              <li key={i} className="flex items-start gap-2 rounded-xl bg-canvas px-3 py-2 text-sm">
                <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${a.severity === 'CRITICA' ? 'bg-danger' : a.severity === 'ALTA' ? 'bg-warning' : 'bg-primary'}`} />
                <span className="flex-1">
                  <b>{a.title}</b> <span className="text-muted">· {a.detail}</span>
                </span>
                {a.type.startsWith('PEDIDO') && a.referenceId && (
                  <Link to={`/compras/${a.referenceId}`} className="text-xs font-semibold text-primary">
                    Ver
                  </Link>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <div className="no-scrollbar flex flex-1 gap-2 overflow-x-auto">
          {chips.map((c) => (
            <button
              key={c.v}
              type="button"
              onClick={() => setFilter(c.v)}
              className={`h-9 shrink-0 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${filter === c.v ? 'bg-primary text-white' : 'bg-card text-muted'}`}
            >
              {c.label} <span className="num opacity-80">({c.n})</span>
            </button>
          ))}
        </div>
        <select aria-label="Ordenar" value={sort} onChange={(e) => setSort(e.target.value as typeof sort)} className="h-9 rounded-xl border border-line bg-card px-2 text-xs">
          <option value="coverage">Menos cobertura primero</option>
          <option value="revenue">Más ventas primero</option>
          {seeCosts && <option value="value">Más capital inmovilizado</option>}
        </select>
      </div>
      <p className="text-xs text-muted">
        Análisis de los últimos {s.analysisDays} días. La velocidad pesa más la última semana. Cobertura = días que alcanza lo que hay al ritmo actual.
      </p>

      {rows.length === 0 ? (
        <Card>
          <EmptyState icon="task_alt" title="Nada en este grupo" />
        </Card>
      ) : (
        <Card className="overflow-x-auto">
          <table className="num w-full min-w-[760px] text-sm [&_td]:px-2 [&_th]:px-2">
            <thead className="bg-canvas text-left text-[11px] tracking-wide text-muted uppercase">
              <tr>
                <th className="px-4 py-2">Producto</th>
                <th className="text-right">Stock</th>
                <th className="text-right">Vende/día</th>
                <th className="text-right">Tendencia</th>
                <th className="text-right">Cobertura</th>
                <th className="text-center">ABC</th>
                <th className="text-right">Ventas</th>
                {seeCosts && <th className="text-right">Inmovilizado</th>}
                <th className="pr-4">Última venta</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-soft">
              {rows.map((p) => (
                <tr key={p.productId}>
                  <td className="px-4 py-2.5">
                    <div className="font-semibold">{p.name}</div>
                    <div className="mt-0.5 flex flex-wrap gap-1">
                      {p.flags.map((f) => (
                        <Chip key={f} tone={FLAG[f]?.tone ?? 'neutral'}>
                          {FLAG[f]?.label ?? f}
                        </Chip>
                      ))}
                      {p.onOrder > 0 && <Chip tone="wallet">{qty(p.onOrder)} en camino</Chip>}
                    </div>
                  </td>
                  <td className="text-right">{qty(p.stock)}</td>
                  <td className="text-right">{qty(p.dailyVelocity)}</td>
                  <td className={`text-right ${p.trendPct && p.trendPct < 0 ? 'text-danger-ink' : p.trendPct && p.trendPct > 0 ? 'text-success-ink' : 'text-muted'}`}>
                    {p.trendPct === undefined || p.trendPct === null ? 'nuevo' : `${p.trendPct > 0 ? '+' : ''}${pct(p.trendPct)}`}
                  </td>
                  <td className={`text-right font-semibold ${p.coverageDays !== undefined && p.coverageDays !== null && p.coverageDays < 4 ? 'text-danger-ink' : ''}`}>
                    {p.coverageDays === undefined || p.coverageDays === null ? '∞' : `${qty(p.coverageDays)} d`}
                  </td>
                  <td className="text-center">
                    <span className={`inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold ${p.abc === 'A' ? 'bg-primary text-white' : p.abc === 'B' ? 'bg-primary-soft text-primary-active' : 'bg-line-soft text-muted'}`}>
                      {p.abc}
                    </span>
                  </td>
                  <td className="text-right">{money(p.revenueWindow)}</td>
                  {seeCosts && <td className="text-right">{p.immobilizedValue ? money(p.immobilizedValue) : '—'}</td>}
                  <td className="pr-4 text-xs text-muted">{p.lastSaleAt ? relative(p.lastSaleAt) : 'Nunca'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {byUser.length > 0 && (
        <Card className="p-4">
          <h3 className="mb-1 font-semibold">Ajustes de inventario por empleado</h3>
          <p className="mb-2 text-xs text-muted">Últimos {s.analysisDays} días: ajustes manuales, dañados y conteos.</p>
          <ul className="divide-y divide-line-soft text-sm">
            {byUser.map((u) => (
              <li key={u.userName} className="num flex flex-wrap items-center justify-between gap-2 py-2">
                <span className="font-semibold">{u.userName}</span>
                <span className="text-muted">
                  {u.adjustments} ajustes · +{qty(u.unitsIn)} / −{qty(u.unitsOut)} u{u.valueOut !== undefined && u.valueOut !== null ? ` · salidas ${money(u.valueOut)}` : ''}
                </span>
              </li>
            ))}
          </ul>
          <Link to="/movimientos" className="mt-2 inline-block text-xs font-semibold text-primary">
            Ver todos los movimientos
          </Link>
        </Card>
      )}
    </div>
  )
}
