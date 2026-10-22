import { useCallback, useEffect, useState } from 'react'
import { useApproval } from '../../components/Approval'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, TextArea, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty, shortDate } from '../../lib/format'
import { discardSale, retrySale, usePendingSales } from '../../lib/offline/queue'
import { METHOD_LABELS, type Customer, type Page, type Receipt, type SaleDto, type SaleSummary, type StoreSettings } from '../../lib/posTypes'
import type { UserDto } from '../../lib/types'
import { PrintArea, ReceiptView } from '../pos/Receipt'

const FLAG_LABELS: Record<string, string> = {
  STOCK_NEGATIVO: 'Se vendió sin stock suficiente',
  PRECIO_DIFERENTE: 'Precio distinto al actual',
  DESCUENTO_SIN_APROBAR: 'Descuento sin autorización (sin internet)',
  TURNO_CERRADO: 'Llegó con el turno ya cerrado',
  SIN_TURNO: 'Sin turno abierto',
}

export default function SalesPage() {
  const { me, can } = useAuth()
  const pendingQueue = usePendingSales(me?.tenant.id)
  const [from, setFrom] = useState(() => new Date().toLocaleDateString('en-CA'))
  const [to, setTo] = useState(() => new Date().toLocaleDateString('en-CA'))
  const [number, setNumber] = useState(() => new URLSearchParams(window.location.search).get('numero') ?? '')
  const [status, setStatus] = useState('')
  const [userId, setUserId] = useState('')
  const [onlyReview, setOnlyReview] = useState(false)
  // Fase 16B: el dueño puede ver las ventas anteriores a la fecha de corte de la facturación.
  const [includePrevious, setIncludePrevious] = useState(false)
  const showPreviousFilter = !!me?.fiscalStartAt && !!me?.hidePreCutoff && can('VENTAS_HISTORIAL_PREVIO')
  const [users, setUsers] = useState<UserDto[]>([])
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<SaleSummary> | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [error, setError] = useState('')
  const seeAll = can('POS_HISTORIAL') || can('REPORTES_VER')

  useEffect(() => {
    if (seeAll) api<UserDto[]>('/users').then(setUsers).catch(() => {})
  }, [seeAll])

  const load = useCallback(() => {
    const p = new URLSearchParams({ page: String(page), size: '30', onlyReview: String(onlyReview) })
    if (number) p.set('number', number)
    else {
      if (from) p.set('from', from)
      if (to) p.set('to', to)
    }
    if (status) p.set('status', status)
    if (userId) p.set('userId', userId)
    if (includePrevious) p.set('includePrevious', 'true')
    api<Page<SaleSummary>>(`/pos/sales?${p}`)
      .then(setData)
      .catch((e) => setError(e.message))
  }, [page, onlyReview, number, from, to, status, userId, includePrevious])
  useEffect(load, [load])

  const total = data?.items.filter((s) => s.status === 'PAGADA').reduce((s, x) => s + x.total - x.refundedTotal, 0) ?? 0
  const sel = 'h-11 rounded-xl border border-line bg-card px-3 text-sm outline-none focus:border-primary'

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Historial de ventas</h2>
        <p className="text-sm text-muted">{seeAll ? 'Todas las ventas de la tienda.' : 'Tus ventas.'} Toca una para ver el comprobante, anularla o registrar una devolución.</p>
      </div>

      {pendingQueue.items.length > 0 && (
        <Card className="p-4">
          <h3 className="mb-2 flex items-center gap-2 font-semibold">
            <Icon name="cloud_off" className="text-warning" /> Ventas sin internet en este equipo
          </h3>
          <div className="divide-y divide-line-soft">
            {pendingQueue.items.map((i) => (
              <div key={i.clientRef} className="flex items-center gap-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="num text-sm font-semibold">
                    {money(i.receipt.total)} · {shortDate(i.createdAt)}
                  </div>
                  <div className={`text-xs ${i.status === 'ERROR' ? 'text-danger-ink' : 'text-muted'}`}>{i.status === 'ERROR' ? i.error : 'Esperando conexión'}</div>
                </div>
                {i.status === 'ERROR' && (
                  <>
                    <Button size="sm" variant="soft" onClick={() => retrySale(i.clientRef)}>
                      Reintentar
                    </Button>
                    <Button size="sm" variant="ghost" className="text-danger-ink" onClick={() => discardSale(i.clientRef)}>
                      Descartar
                    </Button>
                  </>
                )}
              </div>
            ))}
          </div>
        </Card>
      )}

      <Card className="grid grid-cols-2 gap-2 p-3 lg:grid-cols-6">
        <input aria-label="Desde" type="date" className={sel} value={from} onChange={(e) => (setPage(0), setFrom(e.target.value))} />
        <input aria-label="Hasta" type="date" className={sel} value={to} onChange={(e) => (setPage(0), setTo(e.target.value))} />
        <input aria-label="Número de venta" inputMode="numeric" placeholder="N.º de venta" className={sel} value={number} onChange={(e) => (setPage(0), setNumber(e.target.value.replace(/\D/g, '')))} />
        <select aria-label="Estado" className={sel} value={status} onChange={(e) => (setPage(0), setStatus(e.target.value))}>
          <option value="">Pagadas y anuladas</option>
          <option value="PAGADA">Pagadas</option>
          <option value="ANULADA">Anuladas</option>
        </select>
        {seeAll && (
          <select aria-label="Cajero" className={sel} value={userId} onChange={(e) => (setPage(0), setUserId(e.target.value))}>
            <option value="">Todos los cajeros</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.fullName}
              </option>
            ))}
          </select>
        )}
        {seeAll && (
          <label className="flex h-11 items-center gap-2 rounded-xl border border-line px-3 text-sm font-medium">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={onlyReview} onChange={(e) => (setPage(0), setOnlyReview(e.target.checked))} />
            Por revisar
          </label>
        )}
        {showPreviousFilter && (
          <label className="col-span-2 flex h-11 items-center gap-2 rounded-xl border border-line px-3 text-sm font-medium lg:col-span-2">
            <input type="checkbox" className="h-4 w-4 accent-primary" checked={includePrevious} onChange={(e) => (setPage(0), setIncludePrevious(e.target.checked))} />
            Incluir ventas antes de facturación
          </label>
        )}
      </Card>
      {me?.fiscalStartAt && me.hidePreCutoff && !includePrevious && (
        <p className="text-xs text-muted">Se muestran las ventas desde la fecha de corte de la facturación ({shortDate(me.fiscalStartAt)}).</p>
      )}

      {error && <Banner>{error}</Banner>}
      {data && (
        <div className="num flex justify-between text-sm text-muted">
          <span>{data.total} ventas</span>
          <span>
            Neto en esta página: <b className="text-ink">{money(total)}</b>
          </span>
        </div>
      )}
      {!data ? (
        <Spinner />
      ) : data.items.length === 0 ? (
        <Card>
          <EmptyState icon="receipt_long" title="No hay ventas con estos filtros" />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {data.items.map((s) => (
            <button key={s.id} type="button" onClick={() => setOpen(s.id)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="num font-semibold">#{s.number}</span>
                  {s.status === 'ANULADA' && <Chip tone="danger">Anulada</Chip>}
                  {s.refundedTotal > 0 && <Chip tone="warning">Devolución {money(s.refundedTotal)}</Chip>}
                  {s.offline && <Chip tone="neutral" icon="cloud_off">Sin internet</Chip>}
                  {s.reviewFlags.length > 0 && !s.reviewed && <Chip tone="warning" icon="flag">Revisar</Chip>}
                </div>
                <div className="truncate text-xs text-muted">
                  {shortDate(s.completedAt)} · {s.userName}
                  {s.customerName ? ` · ${s.customerName}` : ''} · {s.methods.map((m) => METHOD_LABELS[m] ?? m).join(' + ')} · {s.itemCount} productos
                </div>
              </div>
              <span className={`num font-bold ${s.status === 'ANULADA' ? 'text-muted line-through' : ''}`}>{money(s.total)}</span>
            </button>
          ))}
        </Card>
      )}
      {data && data.pages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage((x) => x - 1)}>
            Anterior
          </Button>
          <span className="num text-sm text-muted">
            {page + 1} / {data.pages}
          </span>
          <Button size="sm" variant="secondary" disabled={page + 1 >= data.pages} onClick={() => setPage((x) => x + 1)}>
            Siguiente
          </Button>
        </div>
      )}
      {open && <SaleDetail id={open} onClose={() => setOpen(null)} onChanged={load} />}
    </div>
  )
}

function SaleDetail({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const { can, me } = useAuth()
  const toast = useToast()
  const approval = useApproval()
  const [r, setR] = useState<Receipt | null>(null)
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [action, setAction] = useState<'void' | 'return' | 'reinvoice' | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Receipt>(`/pos/sales/${id}/receipt`).then(setR).catch((e) => setError(e.message))
  }, [id])
  useEffect(() => {
    load()
    api<StoreSettings>('/settings/pos').then(setSettings).catch(() => {})
  }, [load])

  const s = r?.sale
  const canReturn = s && s.status === 'PAGADA' && s.lines.some((l) => l.quantity > l.returnedQty)
  // Fase 16A: facturar a nombre de un cliente una venta ya cobrada (nota crédito + factura nueva).
  const preCutoff = !!(s && me?.fiscalStartAt && (s.completedAt ?? s.createdAt) < me.fiscalStartAt)
  const canReinvoice = s && !preCutoff && me?.invoicingEnabled && can('FACTURACION_CONFIGURAR') && s.status === 'PAGADA' && s.refundedTotal === 0
  return (
    <Modal
      open
      wide
      title={s ? `Venta #${s.number}` : 'Venta'}
      onClose={onClose}
      footer={
        s && (
          <>
            <Button variant="secondary" icon="print" onClick={() => window.print()}>
              Reimprimir
            </Button>
            <div className="flex-1" />
            {s.reviewFlags.length > 0 && !s.reviewedAt && can('POS_HISTORIAL') && (
              <Button
                variant="soft"
                icon="task_alt"
                onClick={async () => {
                  await api(`/pos/sales/${s.id}/reviewed`, { method: 'POST' })
                  toast('Marcada como revisada')
                  load()
                  onChanged()
                }}
              >
                Revisada
              </Button>
            )}
            {canReinvoice && (
              <Button variant="soft" icon="receipt_long" onClick={() => setAction('reinvoice')}>
                Factura a nombre de…
              </Button>
            )}
            {canReturn && (
              <Button variant="soft" icon="assignment_return" onClick={() => setAction('return')}>
                Devolución
              </Button>
            )}
            {s.status === 'PAGADA' && s.refundedTotal === 0 && (
              <Button variant="danger" icon="block" onClick={() => setAction('void')}>
                Anular
              </Button>
            )}
          </>
        )
      }
    >
      {error && <Banner>{error}</Banner>}
      {!r || !s ? (
        <Spinner />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[auto_1fr]">
          <div className="rounded-xl bg-line-soft p-3">
            <ReceiptView r={r} />
          </div>
          <div className="space-y-3 text-sm">
            {s.status === 'ANULADA' && (
              <Banner>
                Anulada por {s.voidedBy} el {dateTime(s.voidedAt)}. Motivo: {s.voidReason}
                {s.voidAuthorizedBy ? ` · autorizó ${s.voidAuthorizedBy}` : ''}
              </Banner>
            )}
            {s.reviewFlags.length > 0 && (
              <Banner tone={s.reviewedAt ? 'success' : 'warning'}>
                {s.reviewedAt ? 'Revisada. ' : 'Para revisar: '}
                {s.reviewFlags.map((f) => FLAG_LABELS[f] ?? f).join(' · ')}
              </Banner>
            )}
            {s.discountAuthorizedBy && <Banner tone="primary">Descuento o precio especial autorizado por {s.discountAuthorizedBy}.</Banner>}
            {s.electronicDocument ? (
              <div className="rounded-xl border border-line p-3">
                <div className="font-semibold">
                  {s.electronicDocument.documentLabel} {s.electronicDocument.fullNumber}
                </div>
                <div className="text-xs text-muted">
                  A nombre de {s.electronicDocument.customerName ?? 'consumidor final'}
                  {s.electronicDocument.paymentForm === 'CREDITO' &&
                    ` · a crédito${s.electronicDocument.dueDate ? `, vence el ${shortDate(s.electronicDocument.dueDate)}` : ''}`}
                </div>
              </div>
            ) : (
              me?.invoicingEnabled &&
              s.status === 'PAGADA' && (
                <div className="rounded-xl bg-canvas p-3 text-xs text-muted">
                  {preCutoff
                    ? 'Venta anterior a la facturación electrónica: no se factura.'
                    : 'Sin documento electrónico: salió con comprobante interno.'}
                </div>
              )
            )}
            {s.costTotal !== undefined && s.costTotal !== null && (
              <div className="num grid grid-cols-3 gap-2">
                <Info label="Vendido" value={money(s.total)} />
                <Info label="Costo" value={money(s.costTotal)} />
                <Info label="Utilidad bruta" value={money(s.total - s.taxTotal - s.costTotal)} />
              </div>
            )}
            {s.returns.map((ret) => (
              <div key={ret.id} className="rounded-xl border border-line p-3">
                <div className="font-semibold">
                  Devolución #{ret.number} · {money(ret.refundAmount)} en {METHOD_LABELS[ret.refundMethod]?.toLowerCase()}
                </div>
                <div className="text-xs text-muted">
                  {ret.userName} · {dateTime(ret.createdAt)}
                  {ret.authorizedBy ? ` · autorizó ${ret.authorizedBy}` : ''} · {ret.reason}
                </div>
                <ul className="mt-1 text-xs">
                  {ret.lines.map((l) => (
                    <li key={l.saleLineId}>
                      {qty(l.quantity)} × {l.productName} {l.restock ? '(vuelve al inventario)' : `(${l.condition === 'DANADO' ? 'dañado' : 'no reingresa'})`}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      )}
      <PrintArea r={r} />
      {action === 'void' && s && (
        <VoidModal
          sale={s}
          onClose={() => setAction(null)}
          onDone={async (reason) => {
            try {
              const done = await approval.run((approvals) => api(`/pos/sales/${s.id}/void`, { body: { reason, approvals } }))
              if (done === null) return null
              setAction(null)
              toast(`Venta #${s.number} anulada`)
              load()
              onChanged()
              return null
            } catch (e) {
              return e instanceof ApiError ? e.fields.reason ?? e.message : 'No se pudo anular'
            }
          }}
        />
      )}
      {action === 'return' && s && settings && (
        <ReturnModal
          sale={s}
          methods={settings.paymentMethods.split(',')}
          onClose={() => setAction(null)}
          onDone={async (body) => {
            try {
              const done = await approval.run((approvals) => api(`/pos/sales/${s.id}/returns`, { body: { ...body, approvals } }))
              if (done === null) return null
              setAction(null)
              toast('Devolución registrada')
              load()
              onChanged()
              return null
            } catch (e) {
              return e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo registrar'
            }
          }}
        />
      )}
      {action === 'reinvoice' && s && (
        <ReinvoiceModal
          sale={s}
          onClose={() => setAction(null)}
          onDone={(number) => {
            setAction(null)
            toast(`Factura ${number} emitida`)
            load()
            onChanged()
          }}
        />
      )}
      {approval.modal}
    </Modal>
  )
}

/** Fase 16A: la factura nunca cambia de dueño; se anula con nota crédito y se emite una nueva a nombre del cliente. */
function ReinvoiceModal({ sale, onClose, onDone }: { sale: SaleDto; onClose: () => void; onDone: (number: string) => void }) {
  const [q, setQ] = useState('')
  const [matches, setMatches] = useState<Customer[]>([])
  const [picked, setPicked] = useState<Customer | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (picked || q.trim().length < 2) return setMatches([])
    const t = setTimeout(() => api<Customer[]>(`/pos/customers?q=${encodeURIComponent(q)}`).then(setMatches).catch(() => setMatches([])), 250)
    return () => clearTimeout(t)
  }, [q, picked])

  async function confirm() {
    if (!picked) return setError('Elige el cliente')
    setLoading(true)
    setError('')
    try {
      const d = await api<{ fullNumber: string }>(`/einvoice/sales/${sale.id}/reinvoice`, { body: { customerId: picked.id } })
      onDone(d.fullNumber)
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo facturar')
    } finally {
      setLoading(false)
    }
  }

  const missing = picked?.fiscalMissing ?? []
  return (
    <Modal
      open
      title={`Factura a nombre de un cliente · venta #${sale.number}`}
      onClose={onClose}
      footer={
        <Button className="w-full" loading={loading} disabled={!picked || missing.length > 0} onClick={confirm}>
          Emitir factura
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted">
          {sale.electronicDocument
            ? `El documento ${sale.electronicDocument.fullNumber} se anula con una nota crédito y se emite una factura nueva por ${money(sale.total)}. Solo se puede dentro del mismo mes.`
            : `Se emite una factura por ${money(sale.total)} a nombre del cliente que elijas.`}
        </p>
        <div className="relative">
          <Field
            label="Cliente"
            value={picked ? picked.name : q}
            onChange={(e) => {
              setPicked(null)
              setQ(e.target.value)
            }}
            placeholder="Nombre, cédula o NIT"
          />
          {matches.length > 0 && (
            <div className="absolute inset-x-0 top-full z-10 mt-1 divide-y divide-line-soft rounded-xl border border-line bg-card shadow-tier3">
              {matches.slice(0, 6).map((c) => (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => (setPicked(c), setMatches([]))}
                  className="block w-full px-3 py-2 text-left text-sm hover:bg-canvas"
                >
                  {c.name} <span className="text-muted">{c.docNumber}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        {picked && missing.length > 0 && (
          <Banner tone="warning">A {picked.name} le falta: {missing.join(', ')}. Complétalo en Facturación → Clientes.</Banner>
        )}
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-canvas p-3">
      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className="font-bold">{value}</div>
    </div>
  )
}

function VoidModal({ sale, onClose, onDone }: { sale: SaleDto; onClose: () => void; onDone: (reason: string) => Promise<string | null> }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  return (
    <Modal
      open
      title={`Anular venta #${sale.number}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            variant="danger"
            className="flex-1"
            loading={loading}
            disabled={!reason.trim()}
            onClick={async () => {
              setLoading(true)
              const err = await onDone(reason)
              setLoading(false)
              if (err) setError(err)
            }}
          >
            Anular venta
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm">
          Se devuelven al inventario los productos y el dinero ({money(sale.total)}) deja de contar en la caja. Solo se puede anular mientras el turno de la venta siga abierto.
        </p>
        {error && <Banner>{error}</Banner>}
        <TextArea label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: se registró dos veces" />
      </div>
    </Modal>
  )
}

function ReturnModal({
  sale,
  methods,
  onClose,
  onDone,
}: {
  sale: SaleDto
  methods: string[]
  onClose: () => void
  onDone: (body: {
    lines: { saleLineId: number; quantity: number; restock: boolean; condition: string; serials?: string[] }[]
    reason: string
    refundMethod: string
  }) => Promise<string | null>
}) {
  const [picked, setPicked] = useState<Record<number, string[]>>({})
  const [qtysRaw, setQtys] = useState<Record<number, string>>({})
  // En productos con serial la cantidad es la de seriales marcados.
  const qtys: Record<number, string> = { ...qtysRaw }
  Object.entries(picked).forEach(([k, v]) => {
    const l = sale.lines.find((x) => x.id === Number(k))
    if (l) qtys[l.id] = String(v.length / (Number(l.factor) || 1))
  })
  const [damaged, setDamaged] = useState<Record<number, boolean>>({})
  const [reason, setReason] = useState('')
  const [method, setMethod] = useState('EFECTIVO')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const selected = sale.lines.filter((l) => Number(qtys[l.id] || 0) > 0)
  const refund = selected.reduce((s, l) => s + (l.lineTotal / l.quantity) * Number(qtys[l.id]), 0)

  return (
    <Modal
      open
      wide
      title={`Devolución de la venta #${sale.number}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            className="flex-1"
            loading={loading}
            disabled={!selected.length || !reason.trim()}
            onClick={async () => {
              setLoading(true)
              const err = await onDone({
                lines: selected.map((l) => ({
                  saleLineId: l.id,
                  quantity: Number(qtys[l.id]),
                  restock: !damaged[l.id],
                  condition: damaged[l.id] ? 'DANADO' : 'BUENO',
                  serials: l.serials?.length ? picked[l.id] : undefined,
                })),
                reason,
                refundMethod: method,
              })
              setLoading(false)
              if (err) setError(err)
            }}
          >
            Devolver {money(refund)}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          {sale.lines.map((l) => {
            const left = l.quantity - l.returnedQty
            return (
              <div key={l.id} className="flex flex-wrap items-center gap-3 px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-semibold">{l.productName}</div>
                  <div className="num text-xs text-muted">
                    Compró {qty(l.quantity)} · pagó {money(l.lineTotal)}
                    {l.returnedQty > 0 ? ` · ya devolvió ${qty(l.returnedQty)}` : ''}
                  </div>
                </div>
                {l.serials?.length ? (
                  <div className="flex w-full flex-wrap gap-1.5 sm:order-last">
                    {l.serials.map((sn) => {
                      const on = (picked[l.id] ?? []).includes(sn)
                      return (
                        <button
                          key={sn}
                          type="button"
                          aria-pressed={on}
                          onClick={() => setPicked({ ...picked, [l.id]: on ? (picked[l.id] ?? []).filter((x) => x !== sn) : [...(picked[l.id] ?? []), sn] })}
                          className={`rounded-lg px-2.5 py-1.5 font-mono text-xs font-semibold ring-1 ${on ? 'bg-primary text-white ring-primary' : 'bg-card ring-line'}`}
                        >
                          {sn}
                        </button>
                      )
                    })}
                  </div>
                ) : null}
                <input
                  inputMode="decimal"
                  placeholder="0"
                  disabled={left <= 0 || !!l.serials?.length}
                  value={qtys[l.id] ?? ''}
                  onChange={(e) => setQtys({ ...qtys, [l.id]: e.target.value.replace(',', '.') })}
                  className="num h-10 w-20 rounded-lg border border-line text-center outline-none focus:border-primary"
                  aria-label={`Cantidad a devolver de ${l.productName}`}
                />
                <label className="flex items-center gap-1.5 text-xs">
                  <input type="checkbox" checked={!!damaged[l.id]} onChange={(e) => setDamaged({ ...damaged, [l.id]: e.target.checked })} className="h-4 w-4 accent-primary" />
                  Dañado
                </label>
              </div>
            )
          })}
        </div>
        <p className="text-xs text-muted">Lo que no está marcado como dañado vuelve al inventario.</p>
        <SelectField label="Devolver el dinero en" value={method} onChange={(e) => setMethod(e.target.value)}>
          {methods.map((m) => (
            <option key={m} value={m}>
              {METHOD_LABELS[m] ?? m}
            </option>
          ))}
        </SelectField>
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: producto vencido" />
      </div>
    </Modal>
  )
}
