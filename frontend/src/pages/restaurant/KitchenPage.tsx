import { useCallback, useEffect, useRef, useState } from 'react'
import { Banner, Button, Chip, EmptyState, Icon, Modal, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { qty } from '../../lib/format'
import { playAlert, setSoundEnabled, soundEnabled, soundUnlocked, unlockSound } from '../../lib/kitchenAlerts'
import { autoPrintEnabled, groupComandas, printComandas, printWidth, setAutoPrint, setPrintWidth } from '../../lib/kitchenPrint'
import type { KitchenTicketDto } from '../../lib/restaurantTypes'

type StationTab = 'COCINA' | 'BAR' | 'TODAS'
const COLUMNS: { status: KitchenTicketDto['status']; title: string; next: KitchenTicketDto['status']; action: string; tone: string }[] = [
  { status: 'PENDIENTE', title: 'Pendientes', next: 'PREPARANDO', action: 'Empezar', tone: 'border-warning' },
  { status: 'PREPARANDO', title: 'Preparando', next: 'LISTO', action: 'Listo', tone: 'border-primary' },
  { status: 'LISTO', title: 'Listos para llevar', next: 'ENTREGADO', action: 'Entregado', tone: 'border-success' },
]

/**
 * Pantalla de cocina (o de bar): las comandas llegan solas con un aviso sonoro y, si se quiere, se imprimen solas en la
 * impresora de la cocina. Se tocan para pasar de pendiente a preparando y a listo; lo listo le suena al mesero en el
 * plano de mesas. Los domicilios y pedidos para llevar de la tienda en línea también llegan aquí.
 */
export default function KitchenPage() {
  const toast = useToast()
  const [station, setStation] = useState<StationTab>(() => (localStorage.getItem('tp_kitchen_station') as StationTab) || 'COCINA')
  const [tickets, setTickets] = useState<KitchenTicketDto[] | null>(null)
  const [error, setError] = useState('')
  const [sound, setSound] = useState(soundEnabled)
  const [audioReady, setAudioReady] = useState(soundUnlocked)
  const [autoPrint, setAuto] = useState(autoPrintEnabled)
  const [width, setWidth] = useState(printWidth)
  const [settings, setSettings] = useState(false)
  /** Comandas ya vistas en esta pantalla: lo nuevo suena y se imprime una sola vez. */
  const seen = useRef<Set<number> | null>(null)

  const load = useCallback(() => {
    api<KitchenTicketDto[]>(`/restaurant/kitchen${station === 'TODAS' ? '' : `?station=${station}`}`)
      .then((t) => {
        setTickets(t)
        setError('')
        const fresh = t.filter((x) => x.status === 'PENDIENTE' && !seen.current?.has(x.id))
        if (seen.current === null) {
          // Primera carga: lo que ya estaba no suena ni se imprime otra vez.
          seen.current = new Set(t.map((x) => x.id))
          return
        }
        t.forEach((x) => seen.current!.add(x.id))
        if (!fresh.length) return
        playAlert('new')
        if (autoPrintEnabled()) printComandas(groupComandas(fresh))
      })
      .catch((e) => setError(e.message))
  }, [station])
  useEffect(() => {
    seen.current = null
    load()
    const id = setInterval(load, 5000)
    return () => clearInterval(id)
  }, [load])

  async function move(t: KitchenTicketDto, status: KitchenTicketDto['status']) {
    unlockSound()
    setAudioReady(soundUnlocked())
    try {
      await api(`/restaurant/kitchen/${t.id}/status`, { body: { status } })
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
    }
  }

  function printOne(t: KitchenTicketDto) {
    const same = (tickets ?? []).filter((x) => x.comanda === t.comanda && x.station === t.station && x.status !== 'ENTREGADO')
    printComandas([same.length ? same : [t]])
  }

  if (error) return <Banner>{error}</Banner>
  if (!tickets) return <Spinner />

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Pantalla de cocina</h1>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => {
              const on = !sound
              setSound(on)
              setSoundEnabled(on)
              setAudioReady(soundUnlocked())
              if (on) setTimeout(() => playAlert('new'), 50)
            }}
            className={`flex h-10 items-center gap-1.5 rounded-xl px-3 text-sm font-semibold ${sound ? 'bg-primary-tint text-primary-active' : 'bg-line-soft text-muted'}`}
            aria-pressed={sound}
          >
            <Icon name={sound ? 'volume_up' : 'volume_off'} className="text-[20px]" /> {sound ? 'Sonido' : 'Sin sonido'}
          </button>
          <button
            type="button"
            onClick={() => setSettings(true)}
            className={`flex h-10 items-center gap-1.5 rounded-xl px-3 text-sm font-semibold ${autoPrint ? 'bg-primary-tint text-primary-active' : 'bg-line-soft text-muted'}`}
          >
            <Icon name="print" className="text-[20px]" /> {autoPrint ? `Imprime solo (${width} mm)` : 'Impresora'}
          </button>
          <div className="w-64">
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
      </div>

      {sound && !audioReady && (
        <button
          type="button"
          onClick={() => {
            unlockSound()
            setTimeout(() => (setAudioReady(soundUnlocked()), playAlert('new')), 80)
          }}
          className="flex w-full items-center justify-center gap-2 rounded-2xl border border-warning/40 bg-warning-soft px-4 py-3 text-sm font-semibold text-warning-ink"
        >
          <Icon name="touch_app" /> Toca aquí una vez para que suenen las comandas nuevas
        </button>
      )}

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
                    <div key={t.id} className="relative">
                      <button
                        type="button"
                        onClick={() => move(t, cancel ? 'ENTREGADO' : col.next)}
                        className={`w-full rounded-2xl border-l-4 bg-card p-3 pr-12 text-left shadow-tier1 ring-1 ring-line ${cancel ? 'border-danger' : col.tone}`}
                      >
                        <div className="flex items-center justify-between gap-2 text-xs">
                          <span className="font-bold">
                            {t.onlineOrderId && <Icon name="delivery_dining" className="mr-0.5 align-[-3px] text-[15px] text-primary" />}
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
                        {cancel && <Chip tone="danger">{t.onlineOrderId ? 'ANULAR: se canceló el pedido' : 'ANULAR: el mesero lo quitó'}</Chip>}
                        {t.modifiers && <div className="text-sm text-ink">{t.modifiers}</div>}
                        {t.note && <div className="mt-0.5 rounded-lg bg-warning-soft px-2 py-1 text-sm font-semibold text-warning-ink">“{t.note}”</div>}
                        <div className="mt-1 flex items-center justify-between text-xs text-muted">
                          <span>
                            {t.waiterName ?? ''} {station === 'TODAS' && <Chip>{t.station === 'BAR' ? 'Bar' : 'Cocina'}</Chip>}
                          </span>
                          <span className="font-bold text-primary">{cancel ? 'Entendido' : col.action} →</span>
                        </div>
                      </button>
                      <button
                        type="button"
                        onClick={() => printOne(t)}
                        aria-label={`Imprimir la comanda ${t.comanda}`}
                        title="Imprimir la comanda"
                        className="absolute top-8 right-2 flex h-9 w-9 items-center justify-center rounded-xl text-muted hover:bg-line-soft hover:text-ink"
                      >
                        <Icon name="print" className="text-[20px]" />
                      </button>
                    </div>
                  )
                })
              )}
            </section>
          )
        })}
      </div>

      {settings && (
        <Modal open title="Impresora de la cocina" onClose={() => setSettings(false)}>
          <div className="space-y-4 text-sm">
            <Toggle
              checked={autoPrint}
              onChange={(v) => (setAuto(v), setAutoPrint(v))}
              label="Imprimir solas las comandas nuevas"
              hint="Cada comanda que llegue a esta pantalla sale por la impresora de este equipo."
            />
            <div>
              <div className="mb-1.5 font-semibold">Ancho del papel</div>
              <Tabs
                value={String(width) as '58' | '80'}
                onChange={(v) => {
                  const w = v === '58' ? 58 : 80
                  setWidth(w)
                  setPrintWidth(w)
                }}
                items={[
                  { value: '80', label: '80 mm' },
                  { value: '58', label: '58 mm' },
                ]}
              />
            </div>
            <div className="rounded-xl bg-canvas p-3 text-xs text-muted">
              <p className="font-semibold text-ink">Para que imprima sin preguntar</p>
              <ol className="mt-1 list-decimal space-y-1 pl-4">
                <li>Pon la impresora de la cocina como predeterminada en este computador.</li>
                <li>
                  Abre Chrome o Edge con la opción <code className="rounded bg-card px-1">--kiosk-printing</code> (clic derecho en el acceso directo → Propiedades → al final de
                  “Destino”).
                </li>
                <li>Deja esta pantalla abierta. Cada equipo recuerda su propia configuración.</li>
              </ol>
            </div>
            <Button
              variant="secondary"
              icon="print"
              className="w-full"
              onClick={() =>
                printComandas([
                  [
                    {
                      id: 0,
                      saleId: null,
                      comanda: 0,
                      station: station === 'BAR' ? 'BAR' : 'COCINA',
                      tableLabel: 'Prueba',
                      waiterName: 'Impresora',
                      productName: 'Hoja de prueba',
                      quantity: 1,
                      modifiers: 'Si ves esto, la impresora quedó lista',
                      note: null,
                      kind: 'PEDIDO',
                      status: 'PENDIENTE',
                      createdAt: new Date().toISOString(),
                      startedAt: null,
                      readyAt: null,
                      deliveredAt: null,
                      minutes: 0,
                      onlineOrderId: null,
                    },
                  ],
                ])
              }
            >
              Imprimir una hoja de prueba
            </Button>
          </div>
        </Modal>
      )}
    </div>
  )
}
