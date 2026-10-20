import { useEffect, useMemo, useState } from 'react'
import { clampToday, HBars, PeriodPicker, periodRange, rangeLabel, TwoLines, type PeriodKind } from '../../components/Charts'
import { Banner, Button, Card, Chip, EmptyState, Icon, Modal, Spinner, StatCard } from '../../components/ui'
import { api, apiBlob } from '../../lib/api'
import { QUADRANTS, type Evolution, type Profitability, type ProfitRow, type Quadrant } from '../../lib/analyticsTypes'
import { useAuth } from '../../lib/auth'
import { dateTime, download, money, pct, qty } from '../../lib/format'

type Sort = 'profit' | 'margin' | 'lowMargin' | 'perUnit' | 'units' | 'afterCosts'

/** Cuánto deja cada producto: margen, utilidad por unidad, impacto de descuentos y gastos asignados. */
export default function ProfitabilityPage() {
  const { can } = useAuth()
  const today = new Date().toLocaleDateString('en-CA')
  const [kind, setKind] = useState<PeriodKind>('mes')
  const [[from, to], setRange] = useState<[string, string]>(() => {
    const [f, t] = periodRange('mes', today)
    return [f, clampToday(t)]
  })
  const [p, setP] = useState<Profitability | null>(null)
  const [quadrant, setQuadrant] = useState<Quadrant | null>(null)
  const [sort, setSort] = useState<Sort>('profit')
  const [evolution, setEvolution] = useState<ProfitRow | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setP(null)
    setError('')
    api<Profitability>(`/analytics/profitability?from=${from}&to=${to}`)
      .then(setP)
      .catch((e) => setError(e.message))
  }, [from, to])

  const rows = useMemo(() => {
    const list = (p?.products ?? []).filter((r) => !quadrant || r.quadrant === quadrant)
    const by: Record<Sort, (a: ProfitRow, b: ProfitRow) => number> = {
      profit: (a, b) => b.grossProfit - a.grossProfit,
      margin: (a, b) => b.marginPct - a.marginPct,
      lowMargin: (a, b) => a.marginPct - b.marginPct,
      perUnit: (a, b) => (b.profitPerUnit ?? 0) - (a.profitPerUnit ?? 0),
      units: (a, b) => b.units - a.units,
      afterCosts: (a, b) => (b.profitAfterCosts ?? 0) - (a.profitAfterCosts ?? 0),
    }
    return [...list].sort(by[sort])
  }, [p, quadrant, sort])

  async function exportCsv() {
    download(`rentabilidad_${from}_${to}.csv`, await apiBlob(`/analytics/export?type=rentabilidad&from=${from}&to=${to}`))
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Rentabilidad por producto</h1>
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
      {!p ? (
        !error && <Spinner />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Ingreso neto" help="Lo vendido sin IVA ni otros impuestos: el impuesto no es tuyo, se le paga a la DIAN." value={money(p.revenueEx)} icon="payments" sub="Ventas sin impuestos" />
            <StatCard label="Costo de lo vendido" help="Lo que te costó la mercancía que vendiste, según el costo de cada producto al momento de la venta." value={money(p.cost)} icon="inventory" tone="navy" />
            <StatCard label="Utilidad bruta" help="Ingreso neto − costo de lo vendido. El margen es la utilidad ÷ el ingreso neto." value={money(p.grossProfit)} icon="trending_up" tone="success" sub={`Margen ${pct(p.marginPct)}`} />
            <StatCard label="Utilidad neta estimada" help="Utilidad bruta − gastos del período (arriendo, servicios, nómina…). Es estimada porque depende de que los gastos estén registrados." value={money(p.netProfit)} icon="savings" tone={p.netProfit >= 0 ? 'wallet' : 'danger'} sub={`Después de ${money(p.expenses)} en gastos`} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <h2 className="font-semibold">Impacto de los descuentos</h2>
              <p className="mb-3 text-sm text-muted">Descuentos y promociones del período: {money(p.discounts)}.</p>
              <div className="grid grid-cols-2 gap-3 text-center">
                <div className="rounded-xl bg-canvas p-3">
                  <div className="text-[11px] font-bold text-muted uppercase">Margen real</div>
                  <div className="num text-xl font-bold">{pct(p.marginPct)}</div>
                </div>
                <div className="rounded-xl bg-canvas p-3">
                  <div className="text-[11px] font-bold text-muted uppercase">Sin descuentos sería</div>
                  <div className="num text-xl font-bold text-primary">{pct(p.marginWithoutDiscountsPct)}</div>
                </div>
              </div>
            </Card>
            <Card className="p-4">
              <h2 className="mb-3 font-semibold">Margen por categoría</h2>
              <HBars
                items={p.categories.map((c) => ({ label: c.label, value: c.profit ?? 0, sub: `margen ${pct(c.marginPct)} · ventas ${money(c.sales)}` }))}
                tone="bg-success"
              />
            </Card>
          </div>

          <section>
            <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">
              Clasificación (margen bajo &lt; {pct(p.lowMarginPct)} · alto ≥ {pct(p.highMarginPct)})
            </h2>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              {(['ESTRELLA', 'VOLUMEN_BAJO_MARGEN', 'NICHO_RENTABLE', 'REVISAR'] as Quadrant[]).map((q) => {
                const info = QUADRANTS[q]
                const active = quadrant === q
                return (
                  <button
                    key={q}
                    type="button"
                    onClick={() => setQuadrant(active ? null : q)}
                    className={`rounded-2xl border bg-card p-3 text-left shadow-tier1 transition ${active ? 'border-primary ring-2 ring-primary/20' : 'border-line'}`}
                  >
                    <div className="flex items-center justify-between">
                      <Chip tone={info.tone} icon={info.icon}>
                        {info.label}
                      </Chip>
                      <span className="num text-xl font-bold">{p.quadrantCounts[q] ?? 0}</span>
                    </div>
                    <p className="mt-1.5 text-xs text-muted">{info.text}</p>
                  </button>
                )
              })}
            </div>
          </section>

          <Card>
            <div className="flex flex-wrap items-center gap-2 border-b border-line-soft px-4 py-3 print:hidden">
              <span className="text-xs font-semibold text-muted uppercase">Ordenar por</span>
              {(
                [
                  ['profit', 'Mayor utilidad'],
                  ['margin', 'Mayor margen'],
                  ['lowMargin', 'Menor margen'],
                  ['perUnit', 'Utilidad por unidad'],
                  ['units', 'Más vendidos'],
                  ...(p.allocateExpenses ? ([['afterCosts', 'Después de gastos']] as const) : []),
                ] as [Sort, string][]
              ).map(([k, l]) => (
                <button key={k} type="button" onClick={() => setSort(k)} className={`rounded-full px-3 py-1 text-xs font-semibold ${sort === k ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}>
                  {l}
                </button>
              ))}
              {quadrant && (
                <button type="button" onClick={() => setQuadrant(null)} className="ml-auto flex items-center gap-1 text-xs font-semibold text-primary">
                  <Icon name="close" className="text-[14px]" /> Quitar filtro
                </button>
              )}
            </div>
            {rows.length === 0 ? (
              <EmptyState icon="trending_up" title="Sin ventas en este período" />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[820px] text-sm">
                  <thead>
                    <tr className="border-b border-line-soft text-left text-[11px] text-muted uppercase">
                      <th className="px-4 py-2">Producto</th>
                      <th className="px-3 py-2 text-right">Unid.</th>
                      <th className="px-3 py-2 text-right">Ingreso neto</th>
                      <th className="px-3 py-2 text-right">Costo</th>
                      <th className="px-3 py-2 text-right">Utilidad</th>
                      <th className="px-3 py-2">Margen</th>
                      <th className="px-3 py-2 text-right">Por unidad</th>
                      <th className="px-3 py-2 text-right">Descuentos</th>
                      {p.allocateExpenses && <th className="px-4 py-2 text-right">Tras gastos</th>}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line-soft">
                    {rows.map((r) => (
                      <tr key={r.productId} className="cursor-pointer hover:bg-canvas" onClick={() => setEvolution(r)}>
                        <td className="px-4 py-2">
                          <div className="font-semibold">{r.name}</div>
                          <div className="flex items-center gap-1.5 text-xs text-muted">
                            <Chip tone={QUADRANTS[r.quadrant].tone}>{QUADRANTS[r.quadrant].label}</Chip>
                            {r.categoryName}
                          </div>
                        </td>
                        <td className="num px-3 py-2 text-right">{qty(r.units)}</td>
                        <td className="num px-3 py-2 text-right">{money(r.revenueEx)}</td>
                        <td className="num px-3 py-2 text-right text-muted">{money(r.cost)}</td>
                        <td className={`num px-3 py-2 text-right font-semibold ${r.grossProfit < 0 ? 'text-danger-ink' : ''}`}>{money(r.grossProfit)}</td>
                        <td className="px-3 py-2">
                          <div className="flex items-center gap-2">
                            <div className="h-1.5 w-16 overflow-hidden rounded-full bg-line-soft">
                              <div
                                className={`h-full rounded-full ${r.marginPct < p.lowMarginPct ? 'bg-danger' : r.marginPct >= p.highMarginPct ? 'bg-success' : 'bg-primary'}`}
                                style={{ width: `${Math.min(100, Math.max(0, r.marginPct))}%` }}
                              />
                            </div>
                            <span className="num text-xs">{pct(r.marginPct)}</span>
                          </div>
                        </td>
                        <td className="num px-3 py-2 text-right">{money(r.profitPerUnit)}</td>
                        <td className="num px-3 py-2 text-right text-muted">{r.discount > 0 ? money(r.discount) : '—'}</td>
                        {p.allocateExpenses && (
                          <td className={`num px-4 py-2 text-right ${(r.profitAfterCosts ?? 0) < 0 ? 'text-danger-ink' : ''}`}>{money(r.profitAfterCosts)}</td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          {p.allocateExpenses && (
            <p className="text-xs text-muted">“Tras gastos” reparte los gastos operativos del período ({money(p.expenses)}) entre los productos según cuánto vendió cada uno.</p>
          )}
        </>
      )}
      {evolution && <EvolutionModal row={evolution} onClose={() => setEvolution(null)} />}
    </div>
  )
}

const MONTHS = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic']

function EvolutionModal({ row, onClose }: { row: ProfitRow; onClose: () => void }) {
  const [e, setE] = useState<Evolution | null>(null)
  useEffect(() => {
    api<Evolution>(`/analytics/products/${row.productId}/evolution`)
      .then(setE)
      .catch(() => {})
  }, [row.productId])
  return (
    <Modal open wide title={row.name} onClose={onClose}>
      {!e ? (
        <Spinner />
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-2 text-center">
            <div className="rounded-xl bg-canvas p-2">
              <div className="text-[11px] font-bold text-muted uppercase">Precio actual</div>
              <div className="num font-bold">{money(e.currentPrice)}</div>
            </div>
            <div className="rounded-xl bg-canvas p-2">
              <div className="text-[11px] font-bold text-muted uppercase">Costo actual</div>
              <div className="num font-bold">{money(e.currentCost)}</div>
            </div>
            <div className="rounded-xl bg-canvas p-2">
              <div className="text-[11px] font-bold text-muted uppercase">Margen actual</div>
              <div className="num font-bold">{pct(row.currentMarginPct)}</div>
            </div>
          </div>
          <div>
            <h3 className="mb-1 text-sm font-semibold">Precio de venta y costo promedio por mes (sin impuestos)</h3>
            <TwoLines
              labels={e.months.map((m) => MONTHS[Number(m.month.slice(5)) - 1])}
              a={e.months.map((m) => m.avgPrice ?? null)}
              b={e.months.map((m) => m.avgCost ?? null)}
              aLabel="Precio promedio"
              bLabel="Costo promedio"
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <h3 className="mb-1 text-sm font-semibold">Cambios de precio y costo</h3>
              {e.priceChanges.length === 0 ? (
                <p className="text-sm text-muted">Sin cambios registrados.</p>
              ) : (
                <ul className="divide-y divide-line-soft text-sm">
                  {[...e.priceChanges].reverse().slice(0, 12).map((c, i) => (
                    <li key={i} className="py-1.5">
                      <div className="flex justify-between">
                        <span>
                          {c.field === 'COSTO' ? 'Costo' : c.field === 'PRECIO' ? 'Precio' : 'Precio mínimo'}
                          {c.presentation ? ` · ${c.presentation}` : ''}
                        </span>
                        <span className="num">
                          {money(c.oldValue)} → <b>{money(c.newValue)}</b>
                        </span>
                      </div>
                      <div className="text-xs text-muted">
                        {dateTime(c.at)} {c.reason ? `· ${c.reason}` : ''}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <h3 className="mb-1 text-sm font-semibold">Costo de compra (entradas)</h3>
              {e.purchaseCosts.length === 0 ? (
                <p className="text-sm text-muted">Sin entradas con costo.</p>
              ) : (
                <ul className="divide-y divide-line-soft text-sm">
                  {[...e.purchaseCosts].reverse().slice(0, 12).map((c, i) => (
                    <li key={i} className="flex justify-between py-1.5">
                      <span>
                        {dateTime(c.at)}
                        <span className="block text-xs text-muted">
                          {c.type === 'COMPRA' ? 'Compra' : c.type === 'INICIAL' ? 'Inventario inicial' : 'Entrada'} · {qty(c.quantity)} unid.
                        </span>
                      </span>
                      <span className="num font-semibold">{money(c.unitCost)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      )}
    </Modal>
  )
}
