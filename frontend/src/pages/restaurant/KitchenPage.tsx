import { useCallback, useEffect, useState } from 'react'
import { Banner, Chip, EmptyState, Icon, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { qty } from '../../lib/format'
import type { KitchenTicketDto } from '../../lib/restaurantTypes'

type StationTab = 'COCINA' | 'BAR' | 'TODAS'
const COLUMNS: { status: KitchenTicketDto['status']; title: string; next: KitchenTicketDto['status']; action: string; tone: string }[] = [
  { status: 'PENDIENTE', title: 'Pendientes', next: 'PREPARANDO', action: 'Empezar', tone: 'border-warning' },
  { status: 'PREPARANDO', title: 'Preparando', next: 'LISTO', action: 'Listo', tone: 'border-primary' },
  { status: 'LISTO', title: 'Listos para llevar', next: 'ENTREGADO', action: 'Entregado', tone: 'border-success' },
]

/**
 * Pantalla de cocina (o de bar): las comandas llegan solas, se tocan para pasar de pendiente a preparando y a listo.
 * Lo listo le aparece al mesero en el plano de mesas. Se actualiza cada 5 segundos.
 */
export default function KitchenPage() {
  const toast = useToast()
  const [station, setStation] = useState<StationTab>(() => (localStorage.getItem('tp_kitchen_station') as StationTab) || 'COCINA')
  const [tickets, setTickets] = useState<KitchenTicketDto[] | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<KitchenTicketDto[]>(`/restaurant/kitchen${station === 'TODAS' ? '' : `?station=${station}`}`)
      .then((t) => (setTickets(t), setError('')))
      .catch((e) => setError(e.message))
  }, [station])
  useEffect(() => {
    load()
    const id = setInterval(load, 5000)
    return () => clearInterval(id)
  }, [load])

  async function move(t: KitchenTicketDto, status: KitchenTicketDto['status']) {
    try {
      await api(`/restaurant/kitchen/${t.id}/status`, { body: { status } })
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
    }
  }

  if (error) return <Banner>{error}</Banner>
  if (!tickets) return <Spinner />

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Pantalla de cocina</h1>
        <div className="w-72">
          <Tabs
            value={station}
            onChange={(v) => (setStation(v), localStorage.setItem('tp_kitchen_station', v))}
            items={[
              { value: 'COCINA', label: 'Cocina' },
              { value: 'BAR', label: 'Bar' },
              { value: 'TODAS', label: 'Todas' },
            ]}
          />
        </div>
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        {COLUMNS.map((col) => {
          const list = tickets.filter((t) => t.status === col.status)
          return (
            <section key={col.status} className="space-y-2" data-guide={`cocina-${col.status.toLowerCase()}`}>
              <h2 className="flex items-center justify-between text-xs font-bold tracking-wider text-muted uppercase">
                {col.title}
                <span className="num rounded-full bg-line-soft px-2 py-0.5 text-ink">{list.length}</span>
              </h2>
              {list.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-line p-6">
                  <EmptyState icon="done_all" title="Nada aquí" />
                </div>
              ) : (
                list.map((t) => {
                  const late = t.status !== 'LISTO' && t.minutes >= 15
                  const cancel = t.kind === 'ANULAR'
                  return (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => move(t, cancel ? 'ENTREGADO' : col.next)}
                      className={`w-full rounded-2xl border-l-4 bg-card p-3 text-left shadow-tier1 ring-1 ring-line ${cancel ? 'border-danger' : col.tone}`}
                    >
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <span className="font-bold">
                          {t.tableLabel} · comanda {t.comanda}
                        </span>
                        <span className={`num font-semibold ${late ? 'text-danger-ink' : 'text-muted'}`}>
                          <Icon name="schedule" className="mr-0.5 align-[-3px] text-[14px]" />
                          {t.minutes} min
                        </span>
                      </div>
                      <div className={`mt-1 text-base font-bold ${cancel ? 'text-danger-ink line-through' : ''}`}>
                        {qty(Number(t.quantity))} × {t.productName}
                      </div>
                      {cancel && <Chip tone="danger">ANULAR: el mesero lo quitó</Chip>}
                      {t.modifiers && <div className="text-sm text-ink">{t.modifiers}</div>}
                      {t.note && <div className="mt-0.5 rounded-lg bg-warning-soft px-2 py-1 text-sm font-semibold text-warning-ink">“{t.note}”</div>}
                      <div className="mt-1 flex items-center justify-between text-xs text-muted">
                        <span>
                          {t.waiterName ?? ''} {station === 'TODAS' && <Chip>{t.station === 'BAR' ? 'Bar' : 'Cocina'}</Chip>}
                        </span>
                        <span className="font-bold text-primary">{cancel ? 'Entendido' : col.action} →</span>
                      </div>
                    </button>
                  )
                })
              )}
            </section>
          )
        })}
      </div>
    </div>
  )
}
