import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Card, Chip, EmptyState, Icon, Spinner, Tabs } from '../../components/ui'
import { api } from '../../lib/api'
import type { Dashboard, PeriodStat } from '../../lib/analyticsTypes'
import { useAuth } from '../../lib/auth'
import { money, pct, qty } from '../../lib/format'
import { DIGITAL_WALLETS, METHOD_ICONS, METHOD_LABELS } from '../../lib/posTypes'

type PeriodKey = PeriodStat['key']

export function Change({ v, suffix = 'vs. anterior' }: { v?: number | null; suffix?: string }) {
  if (v === null || v === undefined) return <span className="text-xs text-muted">sin datos para comparar</span>
  const up = v >= 0
  return (
    <span className="inline-flex items-center gap-1 text-xs">
      <span className={`inline-flex items-center rounded-full px-1.5 py-0.5 font-bold ${up ? 'bg-success-soft text-success-ink' : 'bg-danger-soft text-danger-ink'}`}>
        <Icon name={up ? 'trending_up' : 'trending_down'} className="text-[14px]" />
        {up ? '+' : ''}
        {pct(v)}
      </span>
      <span className="text-muted">{suffix}</span>
    </span>
  )
}

const PREV_LABEL: Record<PeriodKey, string> = { dia: 'vs. ayer', semana: 'vs. semana pasada', quincena: 'vs. quincena pasada', mes: 'vs. mes pasado' }

/** Panel de control: ventas del período, caja, utilidad, qué atender y qué se vende. */
export default function DashboardPage() {
  const { can, me } = useAuth()
  const [d, setD] = useState<Dashboard | null>(null)
  const [period, setPeriod] = useState<PeriodKey>('dia')
  const [error, setError] = useState('')

  useEffect(() => {
    const load = () => api<Dashboard>('/dashboard').then(setD).catch((e) => setError(e.message))
    load()
    const t = setInterval(load, 60000)
    return () => clearInterval(t)
  }, [])

  if (error) return <Banner>{error}</Banner>
  if (!d) return <Spinner />
  const p = d.periods.find((x) => x.key === period)!
  const digital = d.methodsToday.filter((m) => DIGITAL_WALLETS.has(m.key) || m.key === 'TRANSFERENCIA')
  const digitalTotal = digital.reduce((s, m) => s + m.sales, 0)
  const cashToday = d.methodsToday.find((m) => m.key === 'EFECTIVO')?.sales ?? 0
  const maxHour = Math.max(1, ...d.todayByHour.map((h) => h.sales))
  const peak = d.todayByHour.reduce<(typeof d.todayByHour)[number] | null>((a, h) => (h.sales > (a?.sales ?? 0) ? h : a), null)
  const critical = [...d.outOfStock, ...d.runningOut]
  const quick = [
    { to: '/pos', label: 'Venta POS', icon: 'point_of_sale', show: can('POS_VENDER') },
    { to: '/caja', label: 'Caja', icon: 'currency_exchange', show: can('CAJA_OPERAR') || can('CAJA_SUPERVISAR') },
    { to: '/inventario', label: 'Inventario', icon: 'inventory_2', show: can('INVENTARIO_VER') },
    { to: '/pagos', label: 'Validar pagos', icon: 'verified_user', show: can('PAGOS_VERIFICAR'), dot: d.pending.paymentsToVerify > 0 },
    { to: '/compras', label: 'Compras', icon: 'shopping_cart_checkout', show: can('COMPRAS_VER') },
    { to: '/analitica', label: 'Analítica', icon: 'monitoring', show: can('REPORTES_VER') },
    { to: '/rentabilidad', label: 'Rentabilidad', icon: 'trending_up', show: can('COSTOS_VER') },
    { to: '/finanzas', label: 'Finanzas', icon: 'account_balance_wallet', show: can('FINANZAS_VER') },
  ].filter((q) => q.show)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-[28px] leading-tight font-bold tracking-tight">Panel de control</h1>
          <p className="flex items-center gap-1 text-sm text-muted">
            <Icon name="schedule" className="text-[16px]" /> Resumen del negocio · {me?.tenant.name}
          </p>
        </div>
        {d.cash.length > 0 && <Chip tone="primary">{d.cash.length === 1 ? d.cash[0].registerName : `${d.cash.length} cajas abiertas`}</Chip>}
      </div>

      <Tabs
        value={period}
        onChange={setPeriod}
        items={[
          { value: 'dia', label: 'Hoy' },
          { value: 'semana', label: 'Semana' },
          { value: 'quincena', label: 'Quincena' },
          { value: 'mes', label: 'Mes' },
        ]}
      />

      {can('POS_VENDER') && (
        <div className="flex items-center justify-between gap-3 rounded-2xl bg-primary-active p-4 text-white shadow-tier2 sm:p-5">
          <div>
            <div className="flex items-center gap-1.5 text-[11px] font-bold tracking-[0.08em] text-blue-100 uppercase">
              <span className="h-2 w-2 rounded-full bg-white" /> Mostrador
            </div>
            <div className="text-xl font-bold">Terminal de ventas</div>
            <div className="text-sm text-blue-100">Atención rápida en mostrador</div>
          </div>
          <Link to="/pos" className="inline-flex h-12 items-center gap-2 rounded-xl bg-white px-4 text-sm font-bold text-primary-active active:scale-[0.98]">
            <Icon name="add_circle" className="text-[22px]" /> Nueva venta
          </Link>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card className="p-4">
          <Label icon="storefront">{`Ventas ${p.label.toLowerCase()}`}</Label>
          <div className="num mt-1 text-[26px] font-bold tracking-tight">{money(p.sales)}</div>
          <Change v={p.changePct} suffix={PREV_LABEL[period]} />
        </Card>
        {d.costs ? (
          <Card className="p-4">
            <Label icon="query_stats">Utilidad bruta</Label>
            <div className="num mt-1 text-[26px] font-bold tracking-tight text-primary">{money(p.grossProfit)}</div>
            <Chip tone="primary">Margen {pct(p.sales > 0 && p.grossProfit !== undefined ? (p.grossProfit / p.sales) * 100 : 0)}</Chip>
          </Card>
        ) : (
          <Card className="p-4">
            <Label icon="receipt">Ticket promedio</Label>
            <div className="num mt-1 text-[26px] font-bold tracking-tight">{money(p.avgTicket)}</div>
            <span className="text-xs text-muted">{p.transactions} ventas</span>
          </Card>
        )}
        <Card className="p-4">
          <Label icon="point_of_sale">En caja (efectivo)</Label>
          <div className="num mt-1 text-[26px] font-bold tracking-tight">{money(d.cashTotal)}</div>
          <span className="text-xs text-muted">{d.cash.length ? `${d.cash.length} turno${d.cash.length > 1 ? 's' : ''} · ventas en efectivo hoy ${money(cashToday)}` : 'Ninguna caja abierta'}</span>
        </Card>
        <Card className="p-4">
          <Label icon="qr_code_2">Pagos digitales hoy</Label>
          <div className="num mt-1 text-[26px] font-bold tracking-tight text-wallet-ink">{money(digitalTotal)}</div>
          <span className="text-xs text-muted">{digital.length ? digital.map((m) => `${METHOD_LABELS[m.key]?.split(' ')[0]} ${money(m.sales)}`).join(' · ') : 'Sin pagos digitales'}</span>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1.4fr_1fr]">
        <Card className="p-4 sm:p-5">
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-lg font-semibold">Ventas por hora de hoy</h2>
              <p className="text-sm text-muted">{peak ? `Hora pico: ${peak.label}:00 (${money(peak.sales)})` : 'Aún no hay ventas hoy'}</p>
            </div>
            {peak && <Chip tone="success">Hora pico</Chip>}
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 rounded-xl bg-primary-tint p-3">
            <div className="flex items-center gap-2">
              <Icon name="receipt" className="text-[20px] text-primary" />
              <div>
                <div className="text-[11px] font-bold text-muted uppercase">Ticket promedio</div>
                <div className="num font-bold">{money(d.periods[0].avgTicket)}</div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Icon name="swap_vert" className="text-[20px] text-primary" />
              <div>
                <div className="text-[11px] font-bold text-muted uppercase">Transacciones</div>
                <div className="num font-bold">{d.periods[0].transactions} ventas</div>
              </div>
            </div>
          </div>
          {d.todayByHour.length > 0 ? (
            <div className="mt-4 flex h-40 items-end gap-1.5">
              {d.todayByHour.map((h) => {
                const isPeak = peak?.key === h.key
                return (
                  <div key={h.key} className="flex h-full flex-1 flex-col items-center justify-end gap-1" title={`${h.label}:00 · ${money(h.sales)} · ${h.transactions} ventas`}>
                    <div
                      className={`w-full rounded-t-md ${isPeak ? 'bg-primary-active' : h.sales > maxHour * 0.6 ? 'bg-primary' : 'bg-primary-soft'}`}
                      style={{ height: `${Math.max(2, (h.sales / maxHour) * 100)}%` }}
                    />
                    <span className={`text-[10px] ${isPeak ? 'font-bold text-primary' : 'text-muted'}`}>{Number(h.label) % 2 === 0 || isPeak ? h.label : ''}</span>
                  </div>
                )
              })}
            </div>
          ) : (
            <EmptyState icon="schedule" title="Sin ventas todavía" text="La curva aparece con la primera venta del día." />
          )}
        </Card>

        <div className="space-y-4">
          {quick.length > 0 && (
            <section>
              <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">Accesos rápidos</h2>
              <div className="grid grid-cols-4 gap-2">
                {quick.map((q) => (
                  <Link key={q.to} to={q.to} className="relative flex flex-col items-center gap-1.5 rounded-2xl border border-line bg-card p-2.5 text-center shadow-tier1 active:scale-[0.98]">
                    {q.dot && <span className="absolute top-2 right-2 h-2 w-2 rounded-full bg-danger" />}
                    <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-tint text-primary">
                      <Icon name={q.icon} className="text-[22px]" />
                    </span>
                    <span className="text-[11px] leading-tight font-semibold">{q.label}</span>
                  </Link>
                ))}
              </div>
            </section>
          )}
          {d.monthFinance && (
            <Card className="p-4">
              <h2 className="mb-2 font-semibold">Este mes</h2>
              <Line k="Utilidad bruta" v={money(d.monthFinance.grossProfit)} sub={`margen ${pct(d.monthFinance.marginPct)}`} />
              <Line k="Gastos" v={`−${money(d.monthFinance.expenses)}`} />
              <Line k="Utilidad neta estimada" v={money(d.monthFinance.netProfit)} strong tone={d.monthFinance.netProfit >= 0 ? 'text-success-ink' : 'text-danger-ink'} />
            </Card>
          )}
          {d.methodsToday.length > 0 && (
            <Card className="p-4">
              <h2 className="mb-2 font-semibold">Ventas por medio de pago (hoy)</h2>
              <div className="space-y-2">
                {d.methodsToday.map((m) => (
                  <div key={m.key}>
                    <div className="flex items-center gap-2 text-sm">
                      <Icon name={METHOD_ICONS[m.key] ?? 'payments'} className="text-[18px] text-muted" />
                      <span className="flex-1">{METHOD_LABELS[m.key] ?? m.key}</span>
                      <span className="num font-semibold">{money(m.sales)}</span>
                    </div>
                    <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-line-soft">
                      <div className={`h-full rounded-full ${DIGITAL_WALLETS.has(m.key) ? 'bg-wallet' : m.key === 'EFECTIVO' ? 'bg-success' : 'bg-primary'}`} style={{ width: `${m.sharePct}%` }} />
                    </div>
                  </div>
                ))}
              </div>
            </Card>
          )}
        </div>
      </div>

      <section>
        <div className="mb-2 flex items-center gap-2">
          <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">Atención inmediata</h2>
          {d.alertsTotal > 0 && <span className="num rounded-full bg-danger-soft px-2 text-xs font-bold text-danger-ink">{d.alertsTotal}</span>}
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          {critical.length > 0 && (
            <Attention
              tone="danger"
              icon="warning"
              title={`${critical.length} producto${critical.length > 1 ? 's' : ''} en stock crítico`}
              text={critical
                .slice(0, 4)
                .map((s) => `${s.name} (${s.flag === 'AGOTADO' ? 'agotado' : `quedan ${qty(s.stock)}`})`)
                .join(', ')}
              to="/compras"
              action="Ver sugerencia de pedido"
              badge="Urgente"
            />
          )}
          {d.pending.paymentsToVerify > 0 && (
            <Attention
              tone="warning"
              icon="pending_actions"
              title={`${d.pending.paymentsToVerify} pago${d.pending.paymentsToVerify > 1 ? 's' : ''} digital${d.pending.paymentsToVerify > 1 ? 'es' : ''} por verificar`}
              text="Confirma que el dinero llegó antes de darlo por recibido."
              to="/pagos"
              action="Verificar ahora"
              badge={money(d.pending.paymentsToVerifyTotal)}
            />
          )}
          {d.pending.toOrderProducts > 0 && (
            <Attention
              tone="primary"
              icon="shopping_cart_checkout"
              title={`${d.pending.toOrderProducts} producto${d.pending.toOrderProducts > 1 ? 's' : ''} para pedir`}
              text={`${d.pending.toOrderCritical ? `${d.pending.toOrderCritical} críticos · ` : ''}${d.pending.toOrderTotal !== undefined ? `pedido sugerido ${money(d.pending.toOrderTotal)}` : 'según la reposición automática'}`}
              to="/compras"
              action="Revisar pedidos sugeridos"
            />
          )}
          {d.pending.toReceiveOrders > 0 && (
            <Attention
              tone={d.pending.lateOrders ? 'danger' : 'primary'}
              icon="local_shipping"
              title={`${d.pending.toReceiveOrders} pedido${d.pending.toReceiveOrders > 1 ? 's' : ''} por recibir`}
              text={`${d.pending.lateOrders ? `${d.pending.lateOrders} retrasado${d.pending.lateOrders > 1 ? 's' : ''} · ` : ''}${d.pending.toReceiveTotal !== undefined ? `mercancía por ${money(d.pending.toReceiveTotal)}` : 'mercancía en camino'}`}
              to="/compras"
              action="Ver pedidos"
            />
          )}
          {d.alerts
            .filter((a) => !['AGOTADO', 'AGOTAMIENTO_INMINENTE', 'STOCK_MINIMO', 'PAGOS_POR_VERIFICAR'].includes(a.type))
            .slice(0, 4)
            .map((a, i) => (
              <Attention key={i} tone={a.severity === 'CRITICA' ? 'danger' : a.severity === 'ALTA' ? 'warning' : 'primary'} icon="notifications" title={a.title} text={a.detail} to={a.link} action="Ver" />
            ))}
          {critical.length === 0 && d.pending.paymentsToVerify === 0 && d.pending.toOrderProducts === 0 && d.pending.toReceiveOrders === 0 && d.alerts.length === 0 && (
            <Card className="p-4 text-sm text-muted lg:col-span-2">
              <Icon name="task_alt" className="mr-1 align-[-5px] text-success" /> Todo en orden por ahora.
            </Card>
          )}
        </div>
      </section>

      <div className="grid gap-4 lg:grid-cols-2">
        <section>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">Más vendidos (7 días)</h2>
            {can('REPORTES_VER') && (
              <Link to="/analitica" className="text-xs font-semibold text-primary">
                Ver analítica
              </Link>
            )}
          </div>
          <Card>
            {d.topProducts.length === 0 ? (
              <EmptyState icon="leaderboard" title="Aún no hay ventas" />
            ) : (
              <ol className="divide-y divide-line-soft">
                {d.topProducts.map((t, i) => (
                  <li key={t.productId} className="flex items-center gap-3 px-4 py-2.5">
                    <span className={`num flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${i < 3 ? 'bg-warning-soft text-warning-ink' : 'bg-line-soft text-muted'}`}>{i + 1}</span>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-semibold">{t.name}</div>
                      <div className="text-xs text-muted">{qty(t.units)} unidades</div>
                    </div>
                    <div className="text-right">
                      <div className="num text-sm font-bold">{money(t.sales)}</div>
                      {t.marginPct !== undefined && <div className="text-[11px] text-muted">margen {pct(t.marginPct)}</div>}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </Card>
        </section>
        <section>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">Baja rotación ({d.lowRotationCount})</h2>
            {can('INVENTARIO_VER') && (
              <Link to="/inventario/inteligente" className="text-xs font-semibold text-primary">
                Inventario inteligente
              </Link>
            )}
          </div>
          <Card>
            {d.lowRotation.length === 0 ? (
              <EmptyState icon="autorenew" title="Todo rota" text="No hay productos quietos con existencias." />
            ) : (
              <ul className="divide-y divide-line-soft">
                {d.lowRotation.map((s) => (
                  <li key={s.productId} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                    <Icon name="hourglass_empty" className="text-[20px] text-warning-ink" />
                    <span className="flex-1 truncate">{s.name}</span>
                    <span className="num text-muted">{qty(s.stock)} en stock</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </section>
      </div>
    </div>
  )
}

function Label({ icon, children }: { icon: string; children: string }) {
  return (
    <div className="flex items-start justify-between gap-2">
      <span className="text-[11px] font-bold tracking-[0.06em] text-muted uppercase">{children}</span>
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary-tint text-primary">
        <Icon name={icon} className="text-[18px]" />
      </span>
    </div>
  )
}

function Line({ k, v, sub, strong, tone }: { k: string; v: string; sub?: string; strong?: boolean; tone?: string }) {
  return (
    <div className={`flex items-baseline justify-between py-1.5 text-sm ${strong ? 'border-t border-line-soft pt-2 font-bold' : ''}`}>
      <span className={strong ? '' : 'text-muted'}>
        {k}
        {sub && <span className="ml-1 text-xs font-normal text-muted">({sub})</span>}
      </span>
      <span className={`num ${tone ?? ''}`}>{v}</span>
    </div>
  )
}

function Attention({ tone, icon, title, text, to, action, badge }: { tone: 'danger' | 'warning' | 'primary'; icon: string; title: string; text: string; to: string; action: string; badge?: string }) {
  const tones = {
    danger: ['bg-danger-soft text-danger-ink', 'text-danger-ink'],
    warning: ['bg-warning-soft text-warning-ink', 'text-warning-ink'],
    primary: ['bg-primary-tint text-primary', 'text-primary-active'],
  }[tone]
  return (
    <Card className="flex gap-3 p-4">
      <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${tones[0]}`}>
        <Icon name={icon} className="text-[22px]" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-2">
          <div className={`text-sm font-semibold ${tones[1]}`}>{title}</div>
          {badge && <span className="num shrink-0 text-xs font-semibold text-muted">{badge}</span>}
        </div>
        <p className="mt-0.5 text-sm text-ink/80">{text}</p>
        <Link to={to} className={`mt-2 inline-flex rounded-lg px-2.5 py-1 text-xs font-bold ${tones[0]}`}>
          {action}
        </Link>
      </div>
    </Card>
  )
}
