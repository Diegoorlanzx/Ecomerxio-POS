import { useEffect, useMemo, useState } from 'react'
import { BarChart, clampToday, HBars, PeriodPicker, periodRange, rangeLabel, type PeriodKind } from '../../components/Charts'
import { Banner, Button, Card, Chip, EmptyState, Icon, Spinner, StatCard, Tabs } from '../../components/ui'
import { api, apiBlob } from '../../lib/api'
import type { Group, ProductRow, SalesReport } from '../../lib/analyticsTypes'
import { useAuth } from '../../lib/auth'
import { download, money, pct, qty } from '../../lib/format'
import { METHOD_LABELS } from '../../lib/posTypes'
import { Change } from '../dashboard/DashboardPage'

type GroupTab = 'productos' | 'categorias' | 'marcas' | 'empleados' | 'turnos' | 'medios'
type Sort = 'sales' | 'units' | 'profit' | 'lowMargin' | 'change'

/** Informes de ventas por período con comparación, series y desgloses. */
export default function AnalyticsPage() {
  const { can } = useAuth()
  const today = new Date().toLocaleDateString('en-CA')
  const [kind, setKind] = useState<PeriodKind>('semana')
  const [[from, to], setRange] = useState<[string, string]>(() => {
    const [f, t] = periodRange('semana', today)
    return [f, clampToday(t)]
  })
  const [r, setR] = useState<SalesReport | null>(null)
  const [tab, setTab] = useState<GroupTab>('productos')
  const [sort, setSort] = useState<Sort>('sales')
  const [error, setError] = useState('')

  useEffect(() => {
    setR(null)
    setError('')
    api<SalesReport>(`/analytics/report?from=${from}&to=${to}`)
      .then(setR)
      .catch((e) => setError(e.message))
  }, [from, to])

  const products = useMemo(() => {
    const list = [...(r?.products ?? [])]
    const by: Record<Sort, (a: ProductRow, b: ProductRow) => number> = {
      sales: (a, b) => b.sales - a.sales,
      units: (a, b) => b.units - a.units,
      profit: (a, b) => (b.profit ?? 0) - (a.profit ?? 0),
      lowMargin: (a, b) => (a.marginPct ?? 0) - (b.marginPct ?? 0),
      change: (a, b) => (a.changePct ?? 999) - (b.changePct ?? 999),
    }
    return list.sort(by[sort])
  }, [r, sort])

  async function exportCsv() {
    download(`ventas_${from}_${to}.csv`, await apiBlob(`/analytics/export?type=ventas&from=${from}&to=${to}`))
  }

  const groups: Record<Exclude<GroupTab, 'productos'>, Group[]> = {
    categorias: r?.categories ?? [],
    marcas: r?.brands ?? [],
    empleados: r?.employees ?? [],
    turnos: r?.shifts ?? [],
    medios: (r?.methods ?? []).map((m) => ({ ...m, label: METHOD_LABELS[m.key] ?? m.key })),
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Analítica de ventas</h1>
          <p className="text-sm text-muted">{rangeLabel(from, to)}</p>
        </div>
        <div className="flex gap-2 print:hidden">
          <Button variant="secondary" icon="print" onClick={() => window.print()}>
            PDF
          </Button>
          {can('REPORTES_EXPORTAR') && (
            <Button variant="secondary" icon="download" onClick={exportCsv}>
              Excel
            </Button>
          )}
        </div>
      </div>
      <PeriodPicker kind={kind} from={from} to={to} onChange={(k, f, t) => (setKind(k), setRange([f, t]))} />
      {error && <Banner>{error}</Banner>}
      {!r ? (
        !error && <Spinner />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Ventas" value={money(r.current.sales)} icon="point_of_sale" sub={<Change v={r.changes.sales} />} />
            <StatCard label="Transacciones" value={r.current.transactions} icon="receipt_long" tone="navy" sub={<Change v={r.changes.transactions} />} />
            <StatCard label="Ticket promedio" value={money(r.current.avgTicket)} icon="shopping_basket" tone="wallet" sub={<Change v={r.changes.avgTicket} />} />
            {r.costs ? (
              <StatCard label="Utilidad bruta" value={money(r.current.grossProfit)} icon="trending_up" tone="success" sub={<Change v={r.changes.grossProfit} />} />
            ) : (
              <StatCard label="Unidades" value={qty(r.current.units)} icon="inventory_2" tone="success" sub={<Change v={r.changes.units} />} />
            )}
          </div>

          <Card className="p-4">
            <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-4">
              <Kv k="Ventas sin impuestos" v={money(r.current.revenueEx)} />
              <Kv k="Impuestos" v={money(r.current.taxes)} />
              <Kv k="Descuentos" v={money(r.current.discounts)} />
              <Kv k="Devoluciones" v={money(r.current.refunds)} />
              <Kv k="Unidades vendidas" v={qty(r.current.units)} />
              {r.costs && <Kv k="Costo de lo vendido" v={money(r.current.cost)} />}
              {r.costs && <Kv k="Margen bruto" v={pct(r.current.marginPct)} />}
              <Kv k="Anuladas" v={`${r.current.voidedCount} (${money(r.current.voidedTotal)})`} />
            </div>
            <p className="mt-2 text-xs text-muted">
              Comparado con {rangeLabel(r.prevFrom, r.prevTo)}: {money(r.previous.sales)} en {r.previous.transactions} ventas.
            </p>
          </Card>

          <Card className="p-4">
            <h2 className="mb-3 font-semibold">Ventas por día</h2>
            <BarChart data={r.days.map((d) => ({ key: d.key, label: d.key.slice(8), value: d.sales, hint: `${d.transactions} ventas` }))} every={r.days.length > 16 ? 3 : 1} />
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <h2 className="font-semibold">Por hora del día</h2>
              <p className="mb-3 text-xs text-muted">{r.bestHour !== undefined && r.bestHour !== null ? `Hora pico: ${String(r.bestHour).padStart(2, '0')}:00` : 'Sin ventas'}</p>
              <BarChart data={r.hours.slice(6, 24).map((h) => ({ key: h.key, label: h.label.slice(0, 2), value: h.sales, hint: `${h.transactions} ventas` }))} every={2} tone="wallet" />
            </Card>
            <Card className="p-4">
              <h2 className="font-semibold">Por día de la semana</h2>
              <p className="mb-3 text-xs text-muted">{r.bestWeekday ? `Mejor día en promedio: ${r.bestWeekday}` : 'Sin ventas'}</p>
              <BarChart data={r.weekdays.map((w) => ({ key: w.key, label: w.label.slice(0, 3), value: w.sales, hint: `${w.transactions} ventas` }))} tone="success" />
            </Card>
          </div>

          <Card className="p-4">
            <h2 className="font-semibold">Temporada: últimos 12 meses</h2>
            <p className="mb-3 text-xs text-muted">Para ver qué meses venden más y planear compras y promociones.</p>
            <BarChart data={r.months.map((m) => ({ key: m.key, label: m.label, value: m.sales, hint: `${m.transactions} ventas` }))} />
          </Card>

          {r.declining.length > 0 && (
            <Card className="border-amber-200 p-4">
              <h2 className="flex items-center gap-2 font-semibold text-warning-ink">
                <Icon name="trending_down" /> Productos cuyas ventas están bajando
              </h2>
              <p className="mb-2 text-xs text-muted">Frente al período anterior equivalente.</p>
              <div className="divide-y divide-line-soft">
                {r.declining.slice(0, 10).map((p) => (
                  <div key={p.productId} className="flex items-center gap-3 py-2 text-sm">
                    <span className="flex-1 truncate font-semibold">{p.name}</span>
                    <span className="num text-xs text-muted">
                      {money(p.prevSales)} → {money(p.sales)}
                    </span>
                    <Chip tone="danger">{pct(p.changePct)}</Chip>
                  </div>
                ))}
              </div>
            </Card>
          )}

          <div className="overflow-x-auto print:hidden">
            <Tabs
              value={tab}
              onChange={setTab}
              items={[
                { value: 'productos', label: 'Productos' },
                { value: 'categorias', label: 'Categorías' },
                { value: 'marcas', label: 'Marcas' },
                { value: 'empleados', label: 'Empleados' },
                { value: 'turnos', label: 'Turnos' },
                { value: 'medios', label: 'Medios de pago' },
              ]}
            />
          </div>

          {tab === 'productos' ? (
            <Card>
              <div className="flex flex-wrap items-center gap-2 border-b border-line-soft px-4 py-3 print:hidden">
                <span className="text-xs font-semibold text-muted uppercase">Ordenar por</span>
                {(
                  [
                    ['sales', 'Mayores ingresos'],
                    ['units', 'Más unidades'],
                    ...(r.costs
                      ? ([
                          ['profit', 'Mayor utilidad'],
                          ['lowMargin', 'Menor margen'],
                        ] as const)
                      : []),
                    ['change', 'Mayor caída'],
                  ] as [Sort, string][]
                ).map(([k, l]) => (
                  <button key={k} type="button" onClick={() => setSort(k)} className={`rounded-full px-3 py-1 text-xs font-semibold ${sort === k ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}>
                    {l}
                  </button>
                ))}
              </div>
              {products.length === 0 ? (
                <EmptyState icon="leaderboard" title="Sin ventas en este período" />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[640px] text-sm">
                    <thead>
                      <tr className="border-b border-line-soft text-left text-[11px] text-muted uppercase">
                        <th className="px-4 py-2">Producto</th>
                        <th className="px-3 py-2 text-right">Unidades</th>
                        <th className="px-3 py-2 text-right">Ventas</th>
                        {r.costs && <th className="px-3 py-2 text-right">Utilidad</th>}
                        {r.costs && <th className="px-3 py-2 text-right">Margen</th>}
                        <th className="px-4 py-2 text-right">vs. anterior</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-line-soft">
                      {products.map((p) => (
                        <tr key={p.productId}>
                          <td className="px-4 py-2">
                            <div className="font-semibold">{p.name}</div>
                            <div className="text-xs text-muted">
                              {[p.categoryName, p.brand].filter(Boolean).join(' · ') || '—'} · {p.transactions} ventas
                            </div>
                          </td>
                          <td className="num px-3 py-2 text-right">{qty(p.units)}</td>
                          <td className="num px-3 py-2 text-right font-semibold">{money(p.sales)}</td>
                          {r.costs && <td className={`num px-3 py-2 text-right ${(p.profit ?? 0) < 0 ? 'text-danger-ink' : ''}`}>{money(p.profit)}</td>}
                          {r.costs && <td className="num px-3 py-2 text-right">{pct(p.marginPct)}</td>}
                          <td className="px-4 py-2 text-right">
                            {p.changePct === undefined || p.changePct === null ? (
                              <span className="text-xs text-muted">nuevo</span>
                            ) : (
                              <span className={`num text-xs font-bold ${p.changePct >= 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
                                {p.changePct >= 0 ? '+' : ''}
                                {pct(p.changePct)}
                              </span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          ) : (
            <Card className="p-4">
              <HBars
                items={groups[tab].map((g) => ({
                  label: g.label,
                  value: g.sales,
                  pct: g.sharePct,
                  sub: [
                    `${g.transactions} ventas`,
                    g.units !== undefined && g.units !== null ? `${qty(g.units)} unidades` : null,
                    r.costs && g.profit !== undefined && g.profit !== null ? `utilidad ${money(g.profit)} (${pct(g.marginPct)})` : null,
                  ]
                    .filter(Boolean)
                    .join(' · '),
                }))}
                tone={tab === 'medios' ? 'bg-wallet' : 'bg-primary'}
              />
            </Card>
          )}
        </>
      )}
    </div>
  )
}

function Kv({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-2 py-1">
      <span className="text-muted">{k}</span>
      <span className="num font-semibold">{v}</span>
    </div>
  )
}
