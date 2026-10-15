import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, Confirm, EmptyState, Field, Icon, Modal, SelectField, Spinner, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty, shortDate } from '../../lib/format'
import type { Category } from '../../lib/posTypes'
import type { CountLine, InventoryCount, Lot } from '../../lib/purchasingTypes'
import InventoryNav from './InventoryNav'

const STATUS = { ABIERTO: { label: 'En curso', tone: 'primary' as const }, CERRADO: { label: 'Cerrado', tone: 'success' as const }, ANULADO: { label: 'Anulado', tone: 'neutral' as const } }

export function CountsPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const [list, setList] = useState<InventoryCount[] | null>(null)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    api<InventoryCount[]>('/counts').then(setList).catch(() => setList([]))
  }, [])

  return (
    <div className="space-y-4">
      <InventoryNav />
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Conteos físicos</h2>
          <p className="text-sm text-muted">Cuenta lo que hay en la tienda y el sistema ajusta las diferencias, marcando las inusuales.</p>
        </div>
        {can('INVENTARIO_CONTEO') && (
          <Button icon="fact_check" onClick={() => setCreating(true)}>
            Nuevo conteo
          </Button>
        )}
      </div>
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="fact_check" title="Aún no hay conteos" text="Haz un conteo completo o solo de una categoría (por ejemplo, licores)." />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {list.map((c) => (
            <Link key={c.id} to={`/inventario/conteos/${c.id}`} className="flex items-center gap-3 px-4 py-3 hover:bg-canvas">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="num font-semibold">#{c.number}</span>
                  <span className="font-semibold">{c.name}</span>
                  <Chip tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Chip>
                  {c.unusualLines > 0 && <Chip tone="danger">{c.unusualLines} inusuales</Chip>}
                </div>
                <div className="num text-xs text-muted">
                  {c.categoryName ?? 'Todo el inventario'} · {c.countedLines}/{c.totalLines} contados · {c.createdBy} · {shortDate(c.createdAt)}
                </div>
              </div>
              {c.shrinkageValue !== undefined && c.status === 'CERRADO' && c.shrinkageValue > 0 && <span className="num font-bold text-danger-ink">−{money(c.shrinkageValue)}</span>}
            </Link>
          ))}
        </Card>
      )}
      {creating && <NewCountModal onClose={() => setCreating(false)} onCreated={(c) => navigate(`/inventario/conteos/${c.id}`)} />}
    </div>
  )
}

function NewCountModal({ onClose, onCreated }: { onClose: () => void; onCreated: (c: InventoryCount) => void }) {
  const [name, setName] = useState(`Conteo ${new Date().toLocaleDateString('es-CO')}`)
  const [categoryId, setCategoryId] = useState('')
  const [blind, setBlind] = useState(true)
  const [categories, setCategories] = useState<Category[]>([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    api<Category[]>('/categories').then(setCategories).catch(() => {})
  }, [])
  async function create() {
    setSaving(true)
    setError('')
    try {
      onCreated(await api<InventoryCount>('/counts', { body: { name, categoryId: categoryId ? Number(categoryId) : undefined, blind } }))
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo crear')
    } finally {
      setSaving(false)
    }
  }
  return (
    <Modal
      open
      title="Nuevo conteo físico"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={create} loading={saving}>
            Empezar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field label="Nombre" value={name} onChange={(e) => setName(e.target.value)} />
        <SelectField label="Qué contar" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">Todo el inventario</option>
          {categories.filter((c) => !c.parentId).map((c) => (
            <option key={c.id} value={c.id}>
              Solo {c.name}
            </option>
          ))}
        </SelectField>
        <Toggle checked={blind} onChange={setBlind} label="Conteo a ciegas" hint="Quien cuenta no ve lo que dice el sistema: así el conteo es más honesto." />
        <p className="text-xs text-muted">Puedes seguir vendiendo mientras cuentas: al cerrar se ajusta solo la diferencia.</p>
      </div>
    </Modal>
  )
}

export function CountDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const toast = useToast()
  const [c, setC] = useState<InventoryCount | null>(null)
  const [scan, setScan] = useState('')
  const [entry, setEntry] = useState<CountLine | null>(null)
  const [amount, setAmount] = useState('')
  const [add, setAdd] = useState(false)
  const [filter, setFilter] = useState<'all' | 'pending' | 'diff'>('pending')
  const [closing, setClosing] = useState(false)
  const [error, setError] = useState('')
  const amountRef = useRef<HTMLInputElement>(null)

  const load = useCallback(() => {
    api<InventoryCount>(`/counts/${id}`).then(setC).catch((e) => setError(e.message))
  }, [id])
  useEffect(load, [load])
  useEffect(() => {
    if (entry) setTimeout(() => amountRef.current?.focus(), 50)
  }, [entry])

  if (error) return <Banner>{error}</Banner>
  if (!c) return <Spinner />
  const open = c.status === 'ABIERTO'

  function findLine(code: string) {
    const q = code.trim().toLowerCase()
    return c!.lines.find((l) => l.barcode === code.trim() || l.sku?.toLowerCase() === q) ?? c!.lines.find((l) => l.productName.toLowerCase().includes(q))
  }

  async function save() {
    if (!entry) return
    try {
      const line = await api<CountLine>(`/counts/${id}/entries`, { body: { productId: entry.productId, counted: Number(amount.replace(',', '.')), add } })
      setC({ ...c!, lines: c!.lines.map((l) => (l.productId === line.productId ? line : l)), countedLines: c!.lines.filter((l) => l.counted !== undefined && l.counted !== null).length + (entry.counted === undefined || entry.counted === null ? 1 : 0) })
      setEntry(null)
      setAmount('')
      setScan('')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    }
  }

  const visible = c.lines.filter((l) =>
    filter === 'pending' ? l.counted === undefined || l.counted === null : filter === 'diff' ? l.difference !== undefined && l.difference !== null && l.difference !== 0 : true,
  )
  const pendingCount = c.lines.filter((l) => l.counted === undefined || l.counted === null).length

  return (
    <div className="space-y-4">
      <Link to="/inventario/conteos" className="inline-flex items-center gap-1 text-sm font-semibold text-primary">
        <Icon name="arrow_back" className="text-[18px]" /> Conteos
      </Link>
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-[22px] font-semibold tracking-tight">
                #{c.number} · {c.name}
              </h2>
              <Chip tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Chip>
              {c.blind && <Chip tone="wallet">A ciegas</Chip>}
            </div>
            <div className="num text-sm text-muted">
              {c.categoryName ?? 'Todo el inventario'} · {c.totalLines - pendingCount} de {c.totalLines} contados
              {c.closedAt ? ` · cerrado por ${c.closedBy} ${dateTime(c.closedAt)}` : ''}
            </div>
          </div>
          {c.status === 'CERRADO' && c.shrinkageValue !== undefined && (
            <div className="num text-right text-sm">
              <div className="text-danger-ink">Faltante {money(c.shrinkageValue)}</div>
              <div className="text-success-ink">Sobrante {money(c.surplusValue)}</div>
            </div>
          )}
        </div>
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-line-soft">
          <div className="h-full bg-primary" style={{ width: `${((c.totalLines - pendingCount) / Math.max(c.totalLines, 1)) * 100}%` }} />
        </div>
        {open && (
          <div className="mt-3 flex flex-wrap gap-2">
            {can('INVENTARIO_AJUSTAR') && (
              <Button icon="lock" onClick={() => setClosing(true)}>
                Cerrar y ajustar
              </Button>
            )}
            <Button
              variant="ghost"
              icon="cancel"
              onClick={async () => {
                await api(`/counts/${id}/cancel`, { method: 'POST' })
                toast('Conteo anulado')
                load()
              }}
            >
              Anular
            </Button>
          </div>
        )}
      </Card>

      {open && (
        <div className="relative">
          <Icon name="barcode_scanner" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[22px] text-muted" />
          <input
            value={scan}
            onChange={(e) => setScan(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && scan.trim()) {
                const l = findLine(scan)
                if (l) {
                  setEntry(l)
                  setAdd(l.counted !== undefined && l.counted !== null)
                } else toast('Ese producto no está en este conteo', 'danger')
              }
            }}
            placeholder="Escanea o escribe el producto y presiona Enter"
            className="h-14 w-full rounded-xl border border-line bg-card pr-3 pl-11 text-base shadow-tier1 outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
            autoFocus
          />
        </div>
      )}

      <div className="flex gap-2">
        {(['pending', 'all', 'diff'] as const).map((f) => (
          <button
            key={f}
            type="button"
            onClick={() => setFilter(f)}
            className={`h-9 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${filter === f ? 'bg-primary text-white' : 'bg-card text-muted'}`}
          >
            {f === 'pending' ? `Por contar (${pendingCount})` : f === 'all' ? 'Todos' : 'Con diferencia'}
          </button>
        ))}
      </div>

      <Card className="divide-y divide-line-soft">
        {visible.length === 0 && <p className="p-4 text-sm text-muted">Nada en este grupo.</p>}
        {visible.map((l) => (
          <button
            key={l.id}
            type="button"
            disabled={!open}
            onClick={() => (setEntry(l), setAdd(false), setAmount(''))}
            className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas disabled:hover:bg-transparent"
          >
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5">
                <span className="truncate font-semibold">{l.productName}</span>
                {l.unusual && <Chip tone="danger">Inusual</Chip>}
              </div>
              <div className="num text-xs text-muted">
                {l.sku}
                {l.theoretical !== undefined && l.theoretical !== null ? ` · sistema ${qty(l.theoretical)}` : ''}
                {l.countedBy ? ` · contó ${l.countedBy}` : ''}
              </div>
            </div>
            <div className="num text-right">
              <div className="font-bold">{l.counted === undefined || l.counted === null ? '—' : qty(l.counted)}</div>
              {l.difference !== undefined && l.difference !== null && l.difference !== 0 && (
                <div className={`text-xs font-semibold ${l.difference < 0 ? 'text-danger-ink' : 'text-success-ink'}`}>
                  {l.difference > 0 ? '+' : ''}
                  {qty(l.difference)}
                  {l.differenceValue !== undefined && l.differenceValue !== null ? ` (${money(l.differenceValue)})` : ''}
                </div>
              )}
            </div>
          </button>
        ))}
      </Card>

      {entry && (
        <Modal
          open
          title={entry.productName}
          onClose={() => setEntry(null)}
          footer={
            <>
              <Button variant="secondary" className="flex-1" onClick={() => setEntry(null)}>
                Cancelar
              </Button>
              <Button className="flex-1" onClick={save} disabled={!amount}>
                Guardar
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            {entry.counted !== undefined && entry.counted !== null && (
              <div className="grid grid-cols-2 gap-2">
                <button type="button" onClick={() => setAdd(true)} className={`h-11 rounded-xl border text-sm font-semibold ${add ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}>
                  Sumar a {qty(entry.counted)}
                </button>
                <button type="button" onClick={() => setAdd(false)} className={`h-11 rounded-xl border text-sm font-semibold ${!add ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}>
                  Reemplazar
                </button>
              </div>
            )}
            <input
              ref={amountRef}
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && amount && save()}
              placeholder="Cantidad contada"
              className="num h-16 w-full rounded-xl border border-line text-center text-3xl font-bold outline-none focus:border-primary"
            />
            <p className="text-xs text-muted">Puedes contar por partes (nevera, bodega, vitrina) y sumar cada parte.</p>
          </div>
        </Modal>
      )}
      <Confirm
        open={closing}
        title="Cerrar el conteo"
        confirmLabel="Cerrar y ajustar"
        onClose={() => setClosing(false)}
        onConfirm={async () => {
          try {
            setC(await api<InventoryCount>(`/counts/${id}/close`, { method: 'POST' }))
            toast('Conteo cerrado e inventario ajustado')
          } catch (e) {
            toast(e instanceof ApiError ? e.message : 'No se pudo cerrar', 'danger')
          }
          setClosing(false)
        }}
        text={
          <p>
            Se ajustará la diferencia de cada producto contado. {pendingCount > 0 && <b>{pendingCount} productos sin contar se dejan como están.</b>}
          </p>
        }
      />
    </div>
  )
}

export function ExpiringPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [days, setDays] = useState(30)
  const [list, setList] = useState<Lot[] | null>(null)
  const [writeOff, setWriteOff] = useState<Lot | null>(null)
  const [reason, setReason] = useState('Vencido')

  const load = useCallback(() => {
    api<Lot[]>(`/inventory/expiring?days=${days}`).then(setList).catch(() => setList([]))
  }, [days])
  useEffect(load, [load])

  return (
    <div className="space-y-4">
      <InventoryNav />
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Vencimientos</h2>
          <p className="text-sm text-muted">Lotes vencidos o por vencer. La caja vende primero lo que vence antes.</p>
        </div>
        <select aria-label="Horizonte" value={days} onChange={(e) => setDays(Number(e.target.value))} className="h-11 rounded-xl border border-line bg-card px-3 text-sm">
          <option value={7}>Próximos 7 días</option>
          <option value={15}>Próximos 15 días</option>
          <option value={30}>Próximos 30 días</option>
          <option value={90}>Próximos 90 días</option>
        </select>
      </div>
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="event_available" title="Nada por vencer" text="Activa “Controlar lotes y vencimientos” en los productos perecederos para verlos aquí." />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {list.map((l) => (
            <div key={l.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="font-semibold">{l.productName}</div>
                <div className="num text-xs text-muted">
                  Lote {l.lotCode ?? 'sin código'} · vence {l.expiryDate} · {qty(l.remaining)} unidades{l.value !== undefined && l.value !== null ? ` · ${money(l.value)}` : ''}
                </div>
              </div>
              <Chip tone={l.daysLeft < 0 ? 'danger' : l.daysLeft <= 7 ? 'warning' : 'neutral'}>
                {l.daysLeft < 0 ? `Vencido hace ${-l.daysLeft} d` : l.daysLeft === 0 ? 'Vence hoy' : `Faltan ${l.daysLeft} d`}
              </Chip>
              {can('INVENTARIO_AJUSTAR') && (
                <Button size="sm" variant="ghost" icon="delete_sweep" onClick={() => (setWriteOff(l), setReason(l.daysLeft < 0 ? 'Vencido' : 'Dañado'))}>
                  Dar de baja
                </Button>
              )}
            </div>
          ))}
        </Card>
      )}
      <Confirm
        open={!!writeOff}
        danger
        title="Dar de baja el lote"
        confirmLabel="Dar de baja"
        onClose={() => setWriteOff(null)}
        onConfirm={async () => {
          if (!writeOff) return
          try {
            await api(`/lots/${writeOff.id}/write-off`, { body: { reason } })
            toast('Lote dado de baja')
            setWriteOff(null)
            load()
          } catch (e) {
            toast(e instanceof ApiError ? e.message : 'No se pudo', 'danger')
          }
        }}
        text={
          <div className="space-y-3">
            <p>
              Salen del inventario {writeOff ? qty(writeOff.remaining) : ''} unidades de {writeOff?.productName}.
            </p>
            <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
        }
      />
    </div>
  )
}
