import { useCallback, useEffect, useMemo, useState } from 'react'
import { useApproval } from '../../components/Approval'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Tabs, TextArea, useToast, InfoTip } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { http } from '../../lib/http'
import { useAuth } from '../../lib/auth'
import { dateTime, money, relative, shortDate } from '../../lib/format'
import { METHOD_ICONS, METHOD_LABELS, type CashMovementDto, type RegisterDto, type ShiftSummary, type StoreSettings } from '../../lib/posTypes'
import { ageLabel, cachedApi, setCached } from '../../lib/offline/cache'
import { closedLocal, getLocalShift, openLocal, saveLocalShift, updateLocalShift, withMovement } from '../../lib/offline/localShift'
import { newTempId, OPS_SYNCED_EVENT, runOp, useOutbox } from '../../lib/offline/queue'

const MOVE_TYPES = {
  GASTO: { label: 'Gasto de caja', icon: 'shopping_bag', tone: 'bg-warning-soft text-warning-ink', sign: -1 },
  RETIRO: { label: 'Retiro / alivio', icon: 'shield', tone: 'bg-danger-soft text-danger-ink', sign: -1 },
  INGRESO: { label: 'Ingreso adicional', icon: 'add_card', tone: 'bg-success-soft text-success-ink', sign: 1 },
  AJUSTE_ENTRADA: { label: 'Ajuste (+)', icon: 'tune', tone: 'bg-primary-tint text-primary', sign: 1 },
  AJUSTE_SALIDA: { label: 'Ajuste (−)', icon: 'tune', tone: 'bg-primary-tint text-primary', sign: -1 },
  ABONO_FIADO: { label: 'Abono de fiado', icon: 'menu_book', tone: 'bg-wallet-soft text-wallet-ink', sign: 1 },
  ANTICIPO: { label: 'Anticipo de orden de servicio', icon: 'build', tone: 'bg-success-soft text-success-ink', sign: 1 },
  PAGO_PROVEEDOR: { label: 'Pago a proveedor', icon: 'local_shipping', tone: 'bg-warning-soft text-warning-ink', sign: -1 },
} as const
type MoveType = keyof typeof MOVE_TYPES

export default function CashPage() {
  const { me, can } = useAuth()
  const toast = useToast()
  const approval = useApproval()
  const [shift, setShift] = useState<ShiftSummary | null | undefined>(undefined)
  const [registers, setRegisters] = useState<RegisterDto[]>([])
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [history, setHistory] = useState<ShiftSummary[]>([])
  const [openShifts, setOpenShifts] = useState<ShiftSummary[]>([])
  const [tab, setTab] = useState<'mine' | 'all'>('mine')
  const [move, setMove] = useState<MoveType | null>(null)
  const [closing, setClosing] = useState<ShiftSummary | null>(null)
  const [detail, setDetail] = useState<ShiftSummary | null>(null)
  const [error, setError] = useState('')
  const supervisor = can('CAJA_SUPERVISAR')

  const outbox = useOutbox(me?.tenant.id)
  // Mientras haya cosas sin enviar, el turno que vale es el de este equipo (el del servidor está atrasado).
  const waiting = outbox.items.some((i) => i.status !== 'ERROR')
  const [savedAt, setSavedAt] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!me) return
    const t = me.tenant.id
    try {
      const [regs, st, hist] = await Promise.all([
        cachedApi<RegisterDto[]>(t, '/registers'),
        cachedApi<StoreSettings>(t, '/settings/pos'),
        cachedApi<ShiftSummary[]>(t, '/shifts?size=15'),
      ])
      setRegisters(regs.data)
      setSettings(st.data)
      setHistory(hist.data)
      setSavedAt(regs.fromCache ? regs.savedAt : null)
      let cur: ShiftSummary | null
      if (waiting) cur = await getLocalShift(t, me.id)
      else {
        try {
          cur = (await http<ShiftSummary | undefined>('/shifts/current')) ?? null
          await saveLocalShift(t, me.id, cur)
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 0)) throw e
          cur = await getLocalShift(t, me.id)
        }
      }
      setShift(cur && cur.status === 'OPEN' ? cur : null)
      if (supervisor && !regs.fromCache) setOpenShifts(await api<ShiftSummary[]>('/shifts/open').catch(() => []))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cargar la caja')
    }
  }, [supervisor, me, waiting])
  useEffect(() => {
    load()
  }, [load])
  // Al terminar de enviar lo hecho sin internet, se recarga con los datos reales del servidor.
  useEffect(() => {
    const on = () => void load()
    window.addEventListener(OPS_SYNCED_EVENT, on)
    return () => window.removeEventListener(OPS_SYNCED_EVENT, on)
  }, [load])

  /** La caja queda libre u ocupada en la copia local (para poder abrir o cerrar sin internet). */
  function markRegister(registerId: number, s: ShiftSummary | null) {
    setRegisters((rs) => {
      const next = rs.map((r) => (r.id === registerId ? { ...r, openShiftId: s?.id, openShiftUser: s?.userName } : r))
      if (me) void setCached(me.tenant.id, '/registers', next)
      return next
    })
  }

  if (error) return <Banner>{error}</Banner>
  if (shift === undefined || !settings) return <Spinner />

  return (
    <div className="space-y-4">
      {savedAt && (
        <Banner tone="warning">
          Sin internet: mostrando la caja guardada {ageLabel(savedAt)}. Puedes abrir, registrar y cerrar el turno; se envía al volver.
        </Banner>
      )}
      {supervisor && (
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'mine', label: 'Mi turno' },
            { value: 'all', label: 'Cajas abiertas', count: openShifts.length },
          ]}
        />
      )}
      {tab === 'all' ? (
        <OpenShiftsBoard shifts={openShifts} onOpen={setDetail} onClose={setClosing} />
      ) : shift ? (
        <ShiftView shift={shift} onMove={setMove} onClose={() => setClosing(shift)} />
      ) : (
        <div data-guide="caja-abrir">
          <OpenShiftForm registers={registers} onOpened={(s) => (setShift(s), markRegister(s.registerId, s), toast(s.number ? `Turno #${s.number} abierto` : 'Turno abierto (se envía al volver internet)'), load())} />
        </div>
      )}

      <section data-guide="caja-historial">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-lg font-semibold">Historial reciente de turnos</h2>
          {(supervisor || can('REPORTES_VER')) && <CashierReportButton />}
        </div>
        {history.length === 0 ? (
          <Card>
            <EmptyState icon="history" title="Aún no hay turnos" />
          </Card>
        ) : (
          <div className="space-y-2">
            {history.map((h) => (
              <button key={h.id} type="button" onClick={() => setDetail(h)} className="flex w-full items-center gap-3 rounded-2xl bg-card px-4 py-3 text-left shadow-tier1 ring-1 ring-line">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="font-semibold">Turno #{h.number}</span>
                    {h.status === 'OPEN' ? (
                      <Chip tone="success">Abierto</Chip>
                    ) : !h.difference ? (
                      <Chip tone="success">Cuadre exacto</Chip>
                    ) : h.difference > 0 ? (
                      <Chip tone="warning">Sobrante {money(h.difference)}</Chip>
                    ) : (
                      <Chip tone="danger">Faltante {money(-h.difference)}</Chip>
                    )}
                  </div>
                  <div className="text-xs text-muted">
                    {h.registerName} · {h.userName} · {shortDate(h.openedAt)}
                  </div>
                </div>
                <div className="text-right">
                  <div className="num font-bold">{money(h.salesTotal)}</div>
                  <div className="num text-xs text-muted">{h.salesCount} ventas</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </section>

      {move && shift && (
        <MovementModal
          type={move}
          shift={shift}
          onClose={() => setMove(null)}
          onSaved={() => {
            setMove(null)
            toast('Movimiento registrado')
            load()
          }}
          approval={approval}
        />
      )}
      {closing && settings && (
        <CloseShiftModal
          shift={closing}
          denominations={settings.cashDenominations.split(',').map(Number).filter(Boolean)}
          onClose={() => setClosing(null)}
          onClosed={(s) => {
            markRegister(s.registerId, null)
            setClosing(null)
            setDetail(s)
            toast(s.number ? `Turno #${s.number} cerrado` : 'Turno cerrado (se envía al volver internet)')
            load()
          }}
        />
      )}
      {detail && <ShiftDetail shift={detail} onClose={() => setDetail(null)} isMine={detail.userId === me?.id} />}
      {approval.modal}
    </div>
  )
}

function OpenShiftForm({ registers, onOpened }: { registers: RegisterDto[]; onOpened: (s: ShiftSummary) => void }) {
  const { me } = useAuth()
  const active = registers.filter((r) => r.active)
  const free = active.filter((r) => !r.openShiftId)
  const [registerId, setRegisterId] = useState<number | undefined>(free[0]?.id)
  const [cash, setCash] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function open() {
    if (!registerId || !me) return
    setLoading(true)
    setError('')
    const openingCash = Number(cash.replace(/\D/g, '') || 0)
    const tempId = newTempId()
    try {
      const r = await runOp<ShiftSummary>({
        tenantId: me.tenant.id,
        method: 'POST',
        path: '/shifts/open',
        body: { registerId, openingCash, offlineAt: new Date().toISOString() },
        label: `Abrir turno con base ${money(openingCash)}`,
        tempId,
      })
      const s = r.queued
        ? openLocal({ tempId, registerId, registerName: active.find((x) => x.id === registerId)?.name ?? 'Caja', openingCash, userId: me.id, userName: me.fullName })
        : r.data
      await saveLocalShift(me.tenant.id, me.id, s)
      onOpened(s)
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo abrir el turno')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card className="overflow-hidden">
      <div className="bg-primary px-5 py-6 text-white">
        <div className="text-[11px] font-bold tracking-[0.08em] text-blue-100 uppercase">Control operativo</div>
        <h2 className="mt-1 text-[22px] font-semibold">Abrir turno de caja</h2>
        <p className="mt-1 text-sm text-blue-100">Cuenta el efectivo con el que empiezas (la base para dar cambio).</p>
      </div>
      <div className="space-y-4 p-5">
        {error && <Banner>{error}</Banner>}
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {active.map((r) => (
            <button
              key={r.id}
              type="button"
              disabled={!!r.openShiftId}
              onClick={() => setRegisterId(r.id)}
              className={`rounded-xl border p-3 text-left disabled:opacity-50 ${registerId === r.id ? 'border-primary bg-primary-tint' : 'border-line'}`}
            >
              <Icon name="point_of_sale" className="text-[22px] text-primary" />
              <div className="mt-1 text-sm font-semibold">{r.name}</div>
              <div className="text-xs text-muted">{r.openShiftId ? `Abierta por ${r.openShiftUser}` : 'Disponible'}</div>
            </button>
          ))}
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">Base inicial de cambio</label>
          <div className="relative">
            <span className="num pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-2xl font-bold text-muted">$</span>
            <input
              inputMode="numeric"
              value={cash ? Number(cash.replace(/\D/g, '')).toLocaleString('es-CO') : ''}
              onChange={(e) => setCash(e.target.value.replace(/\D/g, ''))}
              placeholder="0"
              className="num h-16 w-full rounded-xl border border-line pr-4 pl-10 text-[30px] font-bold outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
            />
          </div>
        </div>
        <Button size="lg" className="w-full" icon="lock_open" onClick={open} loading={loading} disabled={!registerId}>
          Abrir turno
        </Button>
      </div>
    </Card>
  )
}

function ShiftView({ shift, onMove, onClose }: { shift: ShiftSummary; onMove: (t: MoveType) => void; onClose: () => void }) {
  const { can } = useAuth()
  const [counted, setCounted] = useState('')
  const c = Number(counted.replace(/\D/g, '') || 0)
  const diff = counted ? c - shift.expectedCash : null
  return (
    <div className="space-y-4">
      <Card className="p-4" data-guide="caja-turno">
        <div className="flex items-start justify-between">
          <div>
            <div className="text-[11px] font-bold tracking-[0.06em] text-muted uppercase">Control operativo</div>
            <div className="flex items-center gap-2">
              <h2 className="text-[22px] font-semibold tracking-tight">Turno de caja</h2>
              <Chip tone="success" icon="radio_button_checked">
                Turno activo #{shift.number}
              </Chip>
            </div>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-2 gap-2">
          <div className="flex items-center gap-2 rounded-xl bg-primary-tint/70 px-3 py-2">
            <Icon name="badge" className="text-[20px] text-primary" />
            <div className="min-w-0">
              <div className="text-[10px] text-muted">Cajero responsable</div>
              <div className="truncate text-sm font-semibold">{shift.userName}</div>
            </div>
          </div>
          <div className="flex items-center gap-2 rounded-xl bg-primary-tint/70 px-3 py-2">
            <Icon name="schedule" className="text-[20px] text-primary" />
            <div className="min-w-0">
              <div className="text-[10px] text-muted">Apertura ({relative(shift.openedAt).replace('Hace ', '')})</div>
              <div className="truncate text-sm font-semibold">{shortDate(shift.openedAt)}</div>
            </div>
          </div>
        </div>
        <div className="num mt-2 flex items-center justify-between rounded-xl bg-primary-tint/70 px-3 py-2 text-sm">
          <span className="flex items-center gap-2 text-muted">
            <Icon name="account_balance_wallet" className="text-[18px]" /> {shift.registerName} · base inicial
          </span>
          <span className="font-semibold">{money(shift.openingCash)}</span>
        </div>
      </Card>

      <div className="rounded-2xl bg-primary p-5 text-white shadow-tier2">
        <div className="flex items-center justify-between">
          <span className="flex items-center gap-2 text-[11px] font-bold tracking-[0.08em] uppercase">
            <Icon name="point_of_sale" className="text-[18px]" /> Efectivo físico esperado
            <InfoTip
              className="text-blue-100 hover:text-white"
              title="Efectivo físico esperado"
              text="Lo que debería haber ahora en la gaveta según lo registrado. Solo cuenta efectivo: lo digital va al banco y lo fiado queda por cobrar. Al cerrar el turno se compara con lo que cuentas."
            />
          </span>
          <span className="rounded-full bg-white/15 px-2.5 py-0.5 text-[11px] font-bold">En gaveta</span>
        </div>
        <div className="num mt-1 text-[40px] leading-tight font-extrabold tracking-tight">{money(shift.expectedCash)}</div>
        <div className="num mt-1 text-xs text-blue-100">
          Base ({money(shift.openingCash)}) + ventas efectivo ({money(shift.cashSales)})
          {shift.cashIn > 0 && ` + ingresos (${money(shift.cashIn)})`}
          {shift.expenses > 0 && ` − gastos (${money(shift.expenses)})`}
          {shift.cashOut > 0 && ` − retiros (${money(shift.cashOut)})`}
          {shift.adjustments !== 0 && ` ± ajustes (${money(shift.adjustments)})`}
          {shift.cashRefunds > 0 && ` − devoluciones (${money(shift.cashRefunds)})`}
        </div>
      </div>

      <Card className="p-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 font-semibold">
            <Icon name="calculate" className="text-primary" /> Arqueo rápido de gaveta
          </h3>
          <Chip tone="primary">En vivo</Chip>
        </div>
        <div className="relative mt-3">
          <span className="num pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-2xl font-bold text-muted">$</span>
          <input
            inputMode="numeric"
            value={counted ? Number(counted).toLocaleString('es-CO') : ''}
            onChange={(e) => setCounted(e.target.value.replace(/\D/g, ''))}
            placeholder="Total físico contado"
            className="num h-14 w-full rounded-xl border border-line bg-primary-tint/40 pr-4 pl-10 text-2xl font-bold outline-none focus:border-primary"
          />
        </div>
        <div className="no-scrollbar mt-2 flex gap-2 overflow-x-auto">
          {[50000, 20000, 10000, 5000, 2000, 1000].map((v) => (
            <button key={v} type="button" onClick={() => setCounted(String(c + v))} className="num h-9 shrink-0 rounded-lg border border-line px-3 text-xs font-semibold">
              + {money(v)}
            </button>
          ))}
          <button type="button" onClick={() => setCounted('')} className="h-9 shrink-0 rounded-lg px-3 text-xs font-semibold text-danger-ink">
            Limpiar
          </button>
        </div>
        {diff !== null && (
          <div className={`mt-3 flex items-center justify-between rounded-xl px-4 py-3 ${diff === 0 ? 'bg-success-soft text-success-ink' : diff > 0 ? 'bg-warning-soft text-warning-ink' : 'bg-danger-soft text-danger-ink'}`}>
            <div>
              <div className="text-[11px] font-bold tracking-wide uppercase">Diferencia de cuadre</div>
              <div className="text-sm font-semibold">{diff === 0 ? 'Cuadre perfecto' : diff > 0 ? 'Sobrante' : 'Faltante'}</div>
            </div>
            <div className="num text-2xl font-extrabold">{money(diff)}</div>
          </div>
        )}
      </Card>

      <Card className="p-4">
        <div className="flex items-end justify-between">
          <div>
            <div className="text-[11px] font-bold tracking-[0.06em] text-muted uppercase">Turno actual</div>
            <h3 className="flex items-center gap-1 font-semibold">
              Ventas por método de pago
              <InfoTip
                title="Ventas por método de pago"
                text="Todo lo vendido en el turno, separado por cómo pagó el cliente. Solo el efectivo entra a la gaveta; los digitales llegan al banco y el fiado queda por cobrar."
              />
            </h3>
          </div>
          <div className="text-right">
            <div className="text-[11px] text-muted">Total vendido</div>
            <div className="num text-xl font-bold text-primary">{money(shift.salesTotal)}</div>
          </div>
        </div>
        <div className="mt-3 space-y-2">
          {shift.byMethod.length === 0 && <p className="text-sm text-muted">Aún no hay ventas en este turno.</p>}
          {shift.byMethod.map((m) => (
            <div key={m.method} className="flex items-center gap-3 rounded-xl bg-canvas px-3 py-2.5">
              <span
                className={`flex h-9 w-9 items-center justify-center rounded-lg ${m.method === 'EFECTIVO' ? 'bg-success-soft text-success-ink' : ['NEQUI', 'DAVIPLATA', 'BREB'].includes(m.method) ? 'bg-wallet-soft text-wallet-ink' : 'bg-primary-tint text-primary'}`}
              >
                <Icon name={METHOD_ICONS[m.method] ?? 'payments'} className="text-[20px]" />
              </span>
              <div className="flex-1">
                <div className="text-sm font-semibold">{METHOD_LABELS[m.method] ?? m.method}</div>
                <div className="text-xs text-muted">{m.count} pagos</div>
              </div>
              <div className="text-right">
                <div className="num font-bold">{money(m.amount)}</div>
                <div className="num text-xs text-muted">{shift.salesTotal ? ((m.amount / shift.salesTotal) * 100).toFixed(1) : 0}%</div>
              </div>
            </div>
          ))}
        </div>
        {(!!shift.creditSales || !!shift.creditPayments) && (
          <div className="mt-3 space-y-1 rounded-xl border border-line px-3 py-2.5 text-sm">
            {!!shift.creditSales && (
              <div className="flex justify-between">
                <span className="text-warning-ink">Fiado del turno (no entró dinero)</span>
                <span className="num font-semibold">{money(shift.creditSales)}</span>
              </div>
            )}
            {!!shift.creditPayments && (
              <div className="flex justify-between">
                <span className="text-success-ink">Abonos de fiado recibidos</span>
                <span className="num font-semibold">{money(shift.creditPayments)}</span>
              </div>
            )}
            <div className="flex justify-between border-t border-line-soft pt-1">
              <span className="font-semibold">Dinero que entró</span>
              <span className="num font-bold">{money(Number(shift.salesTotal) - Number(shift.creditSales ?? 0) + Number(shift.creditPayments ?? 0))}</span>
            </div>
          </div>
        )}
      </Card>

      <Card className="p-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 font-semibold">
            <Icon name="swap_vert" className="text-muted" /> Movimientos de caja menor
          </h3>
          <span className="num text-sm font-bold text-danger-ink">{money(shift.cashIn + shift.adjustments - shift.cashOut - shift.expenses)}</span>
        </div>
        <div className="mt-3 space-y-2">
          {shift.movements.length === 0 && <p className="text-sm text-muted">Sin movimientos registrados.</p>}
          {shift.movements.map((m) => {
            const t = MOVE_TYPES[m.type as MoveType]
            return (
              <div key={m.id} className="flex items-center gap-3 rounded-xl bg-canvas px-3 py-2.5">
                <span className={`flex h-9 w-9 items-center justify-center rounded-lg ${t?.tone}`}>
                  <Icon name={t?.icon ?? 'payments'} className="text-[20px]" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{m.reason}</div>
                  <div className="truncate text-xs text-muted">
                    {t?.label} · {m.userName} · {shortDate(m.createdAt)}
                    {m.authorizedBy ? ` · autorizó ${m.authorizedBy}` : ''}
                  </div>
                </div>
                <span className={`num font-bold ${t && t.sign > 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
                  {t && t.sign > 0 ? '+' : '−'}
                  {money(m.amount)}
                </span>
              </div>
            )
          })}
        </div>
        <div className="mt-3 grid grid-cols-2 gap-2" data-guide="caja-movimiento">
          {can('CAJA_MOVIMIENTOS') && (
            <>
              <Button variant="soft" icon="shopping_bag" onClick={() => onMove('GASTO')}>
                Registrar gasto
              </Button>
              <Button variant="soft" icon="shield" onClick={() => onMove('RETIRO')}>
                Retiro / alivio
              </Button>
              <Button variant="soft" icon="add_card" onClick={() => onMove('INGRESO')}>
                Ingreso
              </Button>
            </>
          )}
          <Button variant="soft" icon="tune" onClick={() => onMove('AJUSTE_SALIDA')}>
            Ajuste de caja
          </Button>
        </div>
      </Card>

      <Button size="lg" variant="danger" className="w-full" icon="lock" onClick={onClose} data-guide="caja-cerrar">
        Cerrar turno y hacer arqueo definitivo
      </Button>
    </div>
  )
}

function MovementModal({
  type: initial,
  shift,
  onClose,
  onSaved,
  approval,
}: {
  type: MoveType
  shift: ShiftSummary
  onClose: () => void
  onSaved: () => void
  approval: ReturnType<typeof useApproval>
}) {
  const { me } = useAuth()
  const [type, setType] = useState<MoveType>(initial)
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [category, setCategory] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const isAdjust = type.startsWith('AJUSTE')

  async function save() {
    setLoading(true)
    setFields({})
    setError('')
    const body = { type, amount: Number(amount.replace(/\D/g, '') || 0), reason, category: category || undefined }
    try {
      // Un ajuste de caja requiere autorización: se pide el PIN si quien registra no tiene el permiso.
      if (!me) return
      if (isAdjust && !navigator.onLine) {
        setError('Los ajustes de caja necesitan internet: el PIN del supervisor lo valida el servidor.')
        return
      }
      const done = await approval.run(async (tokens) => {
        const r = await runOp<CashMovementDto>({
          tenantId: me.tenant.id,
          method: 'POST',
          path: `/shifts/${shift.id}/movements`,
          body: { ...body, approvalToken: tokens[tokens.length - 1], offline: true, offlineAt: new Date().toISOString() },
          label: `${MOVE_TYPES[type].label} ${money(body.amount)}`,
        })
        const m: CashMovementDto = r.queued
          ? { id: -Date.now(), type, amount: body.amount, reason, category: category || undefined, userName: me.fullName, createdAt: new Date().toISOString() }
          : r.data
        await updateLocalShift(me.tenant.id, me.id, (s) => withMovement(s, m))
        return true
      })
      if (done) onSaved()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title={MOVE_TYPES[type].label}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={loading} disabled={!amount}>
            Registrar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        {isAdjust && (
          <div className="grid grid-cols-2 gap-2">
            {(['AJUSTE_ENTRADA', 'AJUSTE_SALIDA'] as const).map((t) => (
              <button key={t} type="button" onClick={() => setType(t)} className={`h-11 rounded-xl border text-sm font-semibold ${type === t ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}>
                {t === 'AJUSTE_ENTRADA' ? 'Sumar a la caja' : 'Restar de la caja'}
              </button>
            ))}
          </div>
        )}
        <Field label="Valor" inputMode="numeric" value={amount ? Number(amount).toLocaleString('es-CO') : ''} onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} error={fields.amount} autoFocus />
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} error={fields.reason} placeholder={type === 'GASTO' ? 'Ej: bolsas, hielo, aseo' : type === 'RETIRO' ? 'Ej: alivio de caja entregado al dueño' : ''} />
        {type === 'GASTO' && (
          <SelectField label="Categoría" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">Sin categoría</option>
            {['Insumos', 'Transporte', 'Servicios públicos', 'Mantenimiento', 'Nómina', 'Otros gastos'].map((c) => (
              <option key={c}>{c}</option>
            ))}
          </SelectField>
        )}
        {isAdjust && <Banner tone="warning">Los ajustes de caja necesitan autorización de un supervisor.</Banner>}
      </div>
    </Modal>
  )
}

function CloseShiftModal({ shift, denominations, onClose, onClosed }: { shift: ShiftSummary; denominations: number[]; onClose: () => void; onClosed: (s: ShiftSummary) => void }) {
  const { me } = useAuth()
  const [counts, setCounts] = useState<Record<number, string>>({})
  const [notes, setNotes] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const total = useMemo(() => denominations.reduce((s, d) => s + d * Number(counts[d] || 0), 0), [counts, denominations])
  const diff = total - shift.expectedCash

  async function close() {
    setLoading(true)
    setFields({})
    setError('')
    const detail = denominations.filter((d) => Number(counts[d] || 0) > 0).map((d) => `${d}x${Number(counts[d])}`).join(',')
    if (!me) return
    // Se valida aquí también: sin internet el servidor no puede pedir la nota hasta que se envíe.
    if (total !== Number(shift.expectedCash) && !notes.trim()) {
      setFields({ closeNotes: `hay una diferencia de ${money(total - Number(shift.expectedCash))}: explica qué pasó` })
      setLoading(false)
      return
    }
    try {
      const r = await runOp<ShiftSummary>({
        tenantId: me.tenant.id,
        method: 'POST',
        path: `/shifts/${shift.id}/close`,
        body: { countDetail: detail || undefined, countedCash: detail ? undefined : 0, closeNotes: notes || undefined, offlineAt: new Date().toISOString() },
        label: `Cerrar turno (contado ${money(total)})`,
      })
      const closed = r.queued ? closedLocal(shift, total, detail || undefined, notes || undefined, me.fullName) : r.data
      await saveLocalShift(me.tenant.id, me.id, null)
      onClosed(closed)
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      wide
      title={`Cerrar turno #${shift.number} · ${shift.userName}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="danger" className="flex-1" icon="lock" onClick={close} loading={loading}>
            Cerrar turno
          </Button>
        </>
      }
    >
      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-semibold">Cuenta el efectivo por denominación</h3>
          <div className="space-y-1.5">
            {denominations.map((d) => (
              <div key={d} className="flex items-center gap-2">
                <span className="num w-24 text-sm font-semibold">{money(d)}</span>
                <span className="text-muted">×</span>
                <input
                  inputMode="numeric"
                  value={counts[d] ?? ''}
                  onChange={(e) => setCounts({ ...counts, [d]: e.target.value.replace(/\D/g, '') })}
                  className="num h-10 w-20 rounded-lg border border-line text-center font-semibold outline-none focus:border-primary"
                  aria-label={`Cantidad de ${money(d)}`}
                />
                <span className="num flex-1 text-right text-sm text-muted">{money(d * Number(counts[d] || 0))}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="space-y-3">
          <ShiftBreakdown shift={shift} />
          <div className="num space-y-1 rounded-xl bg-canvas p-4 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">Esperado en gaveta</span>
              <span className="font-semibold">{money(shift.expectedCash)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">Contado</span>
              <span className="font-semibold">{money(total)}</span>
            </div>
          </div>
          <div className={`flex items-center justify-between rounded-xl px-4 py-3 ${diff === 0 ? 'bg-success-soft text-success-ink' : diff > 0 ? 'bg-warning-soft text-warning-ink' : 'bg-danger-soft text-danger-ink'}`}>
            <span className="font-semibold">{diff === 0 ? 'Cuadre exacto' : diff > 0 ? 'Sobrante' : 'Faltante'}</span>
            <span className="num text-2xl font-extrabold">{money(diff)}</span>
          </div>
          {error && <Banner>{error}</Banner>}
          <TextArea
            label={diff !== 0 ? 'Explica la diferencia (obligatorio)' : 'Notas del cierre (opcional)'}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            error={fields.closeNotes}
          />
          <p className="text-xs text-muted">Antes de cerrar, asegúrate de que no queden ventas sin internet por enviar en este equipo.</p>
        </div>
      </div>
    </Modal>
  )
}

/**
 * Qué se vendió y por qué el esperado en la gaveta es menor: solo el efectivo se cuenta; lo digital va al banco y lo
 * fiado queda por cobrar.
 */
function ShiftBreakdown({ shift: s }: { shift: ShiftSummary }) {
  const n = (v: unknown) => Number(v ?? 0)
  const cash = n(s.cashSales)
  const credit = n(s.creditSales)
  const digital = s.byMethod.filter((m) => m.method !== 'EFECTIVO' && m.method !== 'FIADO').reduce((a, m) => a + n(m.amount), 0)
  const row = (k: string, v: number, tone = '') => (
    <div className={`flex justify-between ${tone}`}>
      <span>{k}</span>
      <span className="num font-semibold">{money(v)}</span>
    </div>
  )
  return (
    <div className="space-y-1 rounded-xl border border-line p-4 text-sm">
      <div className="mb-1 flex justify-between font-bold">
        <span>Vendido en el turno</span>
        <span className="num">{money(s.salesTotal)}</span>
      </div>
      {row('Efectivo (va a la gaveta)', cash, 'text-success-ink')}
      {digital > 0 && row('Pagos digitales (van al banco)', digital, 'text-wallet-ink')}
      {credit > 0 && row('Fiado (no entró dinero: queda por cobrar)', credit, 'text-warning-ink')}
      <div className="mt-2 border-t border-line-soft pt-2 text-xs text-muted">
        Esperado = base {money(s.openingCash)} + efectivo {money(cash)}
        {n(s.cashIn) > 0 && ` + ingresos y abonos en efectivo ${money(s.cashIn)}`}
        {n(s.cashOut) > 0 && ` − retiros ${money(s.cashOut)}`}
        {n(s.expenses) > 0 && ` − gastos ${money(s.expenses)}`}
        {n(s.adjustments) !== 0 && ` ± ajustes ${money(s.adjustments)}`}
        {n(s.cashRefunds) > 0 && ` − devoluciones en efectivo ${money(s.cashRefunds)}`}
        {' '}= <b className="num">{money(s.expectedCash)}</b>. El fiado y lo digital no se cuentan en la gaveta.
      </div>
    </div>
  )
}

function ShiftDetail({ shift: s, onClose, isMine }: { shift: ShiftSummary; onClose: () => void; isMine: boolean }) {
  return (
    <Modal open wide title={`Turno #${s.number} · ${s.registerName}`} onClose={onClose}>
      <div className="space-y-3 text-sm">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Mini label="Ventas" value={money(s.salesTotal)} sub={`${s.salesCount} ventas`} />
          <Mini label="Efectivo" value={money(s.cashSales)} />
          <Mini label="Esperado" value={money(s.expectedCash)} />
          <Mini label="Contado" value={s.countedCash !== undefined && s.countedCash !== null ? money(s.countedCash) : '—'} />
        </div>
        {s.difference !== undefined && s.difference !== null && s.difference !== 0 && (
          <Banner tone={s.difference > 0 ? 'warning' : 'danger'}>
            {s.difference > 0 ? 'Sobrante' : 'Faltante'} de {money(Math.abs(s.difference))}. {s.closeNotes}
          </Banner>
        )}
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          <Row k="Cajero" v={`${s.userName}${isMine ? ' (tú)' : ''}`} />
          <Row k="Apertura" v={`${dateTime(s.openedAt)} · base ${money(s.openingCash)}`} />
          {s.closedAt && <Row k="Cierre" v={`${dateTime(s.closedAt)} · por ${s.closedByName}`} />}
          {s.byMethod.map((m) => (
            <Row key={m.method} k={METHOD_LABELS[m.method] ?? m.method} v={`${money(m.amount)} (${m.count})`} />
          ))}
          {!!s.creditSales && <Row k="Fiado del turno (no entró dinero)" v={money(s.creditSales)} />}
          {!!s.creditPayments && <Row k="Abonos de fiado recibidos" v={money(s.creditPayments)} />}
          <Row k="Ingresos / retiros / gastos" v={`${money(s.cashIn)} / ${money(s.cashOut)} / ${money(s.expenses)}`} />
          <Row k="Devoluciones" v={money(s.refundsTotal)} />
          <Row k="Descuentos otorgados" v={money(s.discountTotal)} />
          <Row k="Anulaciones" v={`${s.voidedCount} · ${money(s.voidedTotal)}`} />
          {s.countDetail && <Row k="Conteo" v={s.countDetail.split(',').map((x) => x.replace('x', ' × ')).join(' · ')} />}
        </div>
      </div>
    </Modal>
  )
}

function Mini({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-xl bg-canvas p-3">
      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className="num font-bold">{value}</div>
      {sub && <div className="text-xs text-muted">{sub}</div>}
    </div>
  )
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-3 px-3 py-2">
      <span className="text-muted">{k}</span>
      <span className="num text-right font-medium">{v}</span>
    </div>
  )
}

function OpenShiftsBoard({ shifts, onOpen, onClose }: { shifts: ShiftSummary[]; onOpen: (s: ShiftSummary) => void; onClose: (s: ShiftSummary) => void }) {
  if (shifts.length === 0) {
    return (
      <Card>
        <EmptyState icon="point_of_sale" title="No hay cajas abiertas" />
      </Card>
    )
  }
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {shifts.map((s) => (
        <Card key={s.id} className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <div className="font-semibold">
                {s.registerName} · {s.userName}
              </div>
              <div className="text-xs text-muted">
                Turno #{s.number} · desde {shortDate(s.openedAt)}
              </div>
            </div>
            <Chip tone="success">Abierta</Chip>
          </div>
          <div className="num mt-3 grid grid-cols-2 gap-2 text-sm">
            <Mini label="Vendido" value={money(s.salesTotal)} sub={`${s.salesCount} ventas`} />
            <Mini label="Esperado en gaveta" value={money(s.expectedCash)} />
          </div>
          <div className="mt-3 flex gap-2">
            <Button size="sm" variant="soft" onClick={() => onOpen(s)}>
              Ver detalle
            </Button>
            <Button size="sm" variant="ghost" icon="lock" onClick={() => onClose(s)}>
              Cerrar turno
            </Button>
          </div>
        </Card>
      ))}
    </div>
  )
}

function CashierReportButton() {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<
    { userId: number; userName: string; shifts: number; salesCount: number; salesTotal: number; cashSales: number; digitalSales: number; voidedCount: number; refunds: number; overages: number; shortages: number }[] | null
  >(null)
  const [from, setFrom] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1).toLocaleDateString('en-CA'))
  const [to, setTo] = useState(() => new Date().toLocaleDateString('en-CA'))
  useEffect(() => {
    if (open) api<typeof rows>(`/shifts/report?from=${from}&to=${to}`).then(setRows).catch(() => setRows([]))
  }, [open, from, to])
  return (
    <>
      <Button size="sm" variant="ghost" icon="leaderboard" onClick={() => setOpen(true)}>
        Por cajero
      </Button>
      {open && (
        <Modal open wide title="Reporte por cajero" onClose={() => setOpen(false)}>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
            {!rows ? (
              <Spinner />
            ) : rows.length === 0 ? (
              <EmptyState icon="leaderboard" title="Sin turnos en ese rango" />
            ) : (
              <div className="overflow-x-auto">
                <table className="num w-full text-sm">
                  <thead className="text-left text-[11px] tracking-wide text-muted uppercase">
                    <tr>
                      <th className="py-2">Cajero</th>
                      <th>Turnos</th>
                      <th>Ventas</th>
                      <th className="text-right">Total</th>
                      <th className="text-right">Efectivo</th>
                      <th className="text-right">Digital</th>
                      <th className="text-right">Anul.</th>
                      <th className="text-right">Sobrante</th>
                      <th className="text-right">Faltante</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line-soft">
                    {rows.map((r) => (
                      <tr key={r.userId}>
                        <td className="py-2 font-semibold">{r.userName}</td>
                        <td>{r.shifts}</td>
                        <td>{r.salesCount}</td>
                        <td className="text-right font-bold">{money(r.salesTotal)}</td>
                        <td className="text-right">{money(r.cashSales)}</td>
                        <td className="text-right">{money(r.digitalSales)}</td>
                        <td className="text-right">{r.voidedCount}</td>
                        <td className="text-right text-warning-ink">{money(r.overages)}</td>
                        <td className="text-right text-danger-ink">{money(r.shortages)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </Modal>
      )}
    </>
  )
}
