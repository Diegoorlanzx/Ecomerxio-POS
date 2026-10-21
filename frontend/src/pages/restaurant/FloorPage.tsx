import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Banner, Button, EmptyState, Field, Icon, Modal, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import { TABLE_STATE, type DiningTableDto, type Floor } from '../../lib/restaurantTypes'
import type { SaleDto } from '../../lib/posTypes'

/**
 * Plano de mesas: cada mesa con su estado (libre, ocupada, por cobrar o con platos listos), el mesero, el total y el
 * tiempo. Tocar una mesa libre la abre; una ocupada abre su cuenta en la caja.
 */
export default function FloorPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [floor, setFloor] = useState<Floor | null>(null)
  const [area, setArea] = useState<string>('todas')
  const [error, setError] = useState('')
  const [opening, setOpening] = useState<DiningTableDto | null>(null)
  const [choose, setChoose] = useState<DiningTableDto | null>(null)

  const load = useCallback(() => {
    api<Floor>('/restaurant/floor')
      .then((f) => (setFloor(f), setError('')))
      .catch((e) => setError(e.message))
  }, [])
  useEffect(() => {
    load()
    const id = setInterval(load, 10_000) // la cocina va marcando platos listos
    return () => clearInterval(id)
  }, [load])

  const goAccount = (id: number) => navigate(`/pos?cuenta=${id}`)

  function tap(t: DiningTableDto) {
    if (t.accounts.length === 0) return setOpening(t)
    if (t.accounts.length === 1) return goAccount(t.accounts[0]!.id)
    setChoose(t)
  }

  if (error) return <Banner>{error}</Banner>
  if (!floor) return <Spinner />
  const shown = floor.tables.filter((t) => area === 'todas' || String(t.areaId ?? 'sin') === area)
  const counts = floor.tables.reduce((acc, t) => ((acc[t.state] = (acc[t.state] ?? 0) + 1), acc), {} as Record<string, number>)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Mesas</h1>
          <p className="text-sm text-muted">
            {counts.LIBRE ?? 0} libres · {(counts.OCUPADA ?? 0) + (counts.LISTO ?? 0)} ocupadas · {counts.POR_COBRAR ?? 0} por cobrar
          </p>
        </div>
        <div className="flex gap-2">
          <Link to="/cocina" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
            <Icon name="soup_kitchen" className="text-[20px] text-primary" /> Cocina
          </Link>
          {can('CONFIG_EDITAR') && (
            <Link to="/mesas/configurar" className="flex h-11 items-center gap-2 rounded-xl border border-line bg-card px-4 text-sm font-semibold">
              <Icon name="settings" className="text-[20px] text-muted" /> Configurar
            </Link>
          )}
        </div>
      </div>

      {floor.areas.length > 0 && (
        <div className="overflow-x-auto">
          <Tabs
            value={area}
            onChange={setArea}
            items={[{ value: 'todas', label: 'Todas' }, ...floor.areas.map((a) => ({ value: String(a.id), label: a.name }))]}
          />
        </div>
      )}

      <div className="flex flex-wrap gap-3 text-xs text-muted">
        {Object.entries(TABLE_STATE).map(([k, v]) => (
          <span key={k} className="flex items-center gap-1.5">
            <span className={`h-2.5 w-2.5 rounded-full ${v.dot}`} /> {v.label}
          </span>
        ))}
      </div>

      {floor.tables.length === 0 ? (
        <EmptyState
          icon="table_restaurant"
          title="Todavía no hay mesas"
          text="Crea tus mesas y salones para empezar a atender."
          action={can('CONFIG_EDITAR') ? <Button onClick={() => navigate('/mesas/configurar')}>Crear mesas</Button> : undefined}
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5" data-guide="mesas-plano">
          {shown.map((t) => {
            const st = TABLE_STATE[t.state]
            const total = t.accounts.reduce((s, a) => s + Number(a.total), 0)
            const minutes = Math.max(0, ...t.accounts.map((a) => a.minutes))
            const ready = t.accounts.reduce((s, a) => s + a.ready, 0)
            const pending = t.accounts.reduce((s, a) => s + a.pendingToSend, 0)
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => tap(t)}
                className={`relative flex min-h-[112px] flex-col rounded-2xl border-2 p-3 text-left shadow-tier1 transition active:scale-[0.98] ${st.card}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-base font-bold">{t.name}</span>
                  <span className="flex items-center gap-1 text-[11px] text-muted">
                    <Icon name="chair" className="text-[14px]" /> {t.seats}
                  </span>
                </div>
                {t.accounts.length === 0 ? (
                  <span className="mt-auto text-xs font-semibold text-muted">Libre · toca para abrir</span>
                ) : (
                  <div className="mt-1 space-y-0.5 text-xs">
                    <div className="num text-lg font-bold">{money(total)}</div>
                    <div className="text-muted">
                      {t.accounts[0]!.waiterName.split(' ')[0]} · {minutes} min{t.accounts.length > 1 ? ` · ${t.accounts.length} cuentas` : ''}
                    </div>
                    {ready > 0 && <div className="font-bold text-success-ink">● {ready} listo(s) para llevar</div>}
                    {pending > 0 && <div className="font-semibold text-warning-ink">{pending} sin enviar a cocina</div>}
                    {t.state === 'POR_COBRAR' && <div className="font-semibold text-warning-ink">Precuenta entregada</div>}
                  </div>
                )}
              </button>
            )
          })}
        </div>
      )}

      {opening && (
        <OpenTableModal
          table={opening}
          onClose={() => setOpening(null)}
          onOpened={(sale) => {
            setOpening(null)
            toast(`${opening.name} abierta`)
            goAccount(sale.id)
          }}
        />
      )}
      {choose && (
        <Modal open title={`${choose.name}: ¿qué cuenta?`} onClose={() => setChoose(null)}>
          <div className="space-y-2">
            {choose.accounts.map((a) => (
              <button key={a.id} type="button" onClick={() => goAccount(a.id)} className="flex w-full items-center justify-between rounded-xl border border-line p-3 text-left">
                <span className="font-semibold">{a.label}</span>
                <span className="num font-bold">{money(a.total)}</span>
              </button>
            ))}
          </div>
        </Modal>
      )}
    </div>
  )
}

function OpenTableModal({ table, onClose, onOpened }: { table: DiningTableDto; onClose: () => void; onOpened: (s: SaleDto) => void }) {
  const [guests, setGuests] = useState(String(Math.min(2, table.seats)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function open() {
    setBusy(true)
    setError('')
    try {
      onOpened(await api<SaleDto>(`/restaurant/tables/${table.id}/open`, { body: { guests: Number(guests) || undefined } }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo abrir la mesa')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={`Abrir ${table.name}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="restaurant" loading={busy} onClick={open}>
          Abrir mesa y tomar el pedido
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="¿Cuántas personas?" inputMode="numeric" value={guests} onChange={(e) => setGuests(e.target.value.replace(/\D/g, ''))} />
        <p className="text-xs text-muted">La cuenta queda a tu nombre como mesero.</p>
      </div>
    </Modal>
  )
}
