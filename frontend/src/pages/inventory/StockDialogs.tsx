import { useEffect, useState } from 'react'
import { Banner, Button, Chip, EmptyState, Field, Icon, Modal, Spinner, Tabs } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, qty } from '../../lib/format'
import type { MovementDto, Page, PriceHistoryDto, ProductDto } from '../../lib/posTypes'

const MODES = [
  { value: 'ENTRADA', label: 'Entrada', icon: 'add_circle', text: 'Llegó mercancía o se encontró' },
  { value: 'SALIDA', label: 'Salida', icon: 'remove_circle', text: 'Consumo interno, pérdida, regalo' },
  { value: 'DANADO', label: 'Dañado/vencido', icon: 'broken_image', text: 'No se puede vender' },
  { value: 'CONTEO', label: 'Conteo', icon: 'fact_check', text: 'Fijar lo que contaste' },
] as const

export function AdjustModal({ product, onClose, onDone }: { product: ProductDto; onClose: () => void; onDone: () => void }) {
  const { can } = useAuth()
  const [mode, setMode] = useState<(typeof MODES)[number]['value']>('ENTRADA')
  const [quantity, setQuantity] = useState('')
  const [unitCost, setUnitCost] = useState('')
  const [reason, setReason] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const q = Number(quantity.replace(',', '.') || 0)
  const result = mode === 'ENTRADA' ? product.stock + q : mode === 'CONTEO' ? q : product.stock - q

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      await api('/inventory/adjust', {
        body: {
          productId: product.id,
          mode,
          quantity: q,
          unitCost: mode === 'ENTRADA' && unitCost ? Number(unitCost.replace(',', '.')) : undefined,
          reason,
        },
      })
      onDone()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length || e.status === 409) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`Ajustar ${product.name}`}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving} disabled={!quantity}>
            Registrar ajuste
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid grid-cols-2 gap-2">
          {MODES.map((m) => (
            <button
              key={m.value}
              type="button"
              onClick={() => setMode(m.value)}
              className={`rounded-xl border p-3 text-left transition ${mode === m.value ? 'border-primary bg-primary-tint' : 'border-line hover:bg-canvas'}`}
            >
              <Icon name={m.icon} className={`text-[22px] ${mode === m.value ? 'text-primary' : 'text-muted'}`} />
              <div className="mt-1 text-sm font-semibold">{m.label}</div>
              <div className="text-xs text-muted">{m.text}</div>
            </button>
          ))}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field
            label={mode === 'CONTEO' ? 'Cantidad contada' : 'Cantidad'}
            inputMode="decimal"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            error={fields.quantity}
            autoFocus
          />
          {mode === 'ENTRADA' && can('COSTOS_VER') && (
            <Field label="Costo unitario (opcional)" inputMode="decimal" value={unitCost} onChange={(e) => setUnitCost(e.target.value)} error={fields.unitCost} />
          )}
        </div>
        <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} error={fields.reason} placeholder="Ej: llegó pedido de Bavaria" />
        <div className="flex items-center justify-between rounded-xl bg-canvas px-4 py-3 text-sm">
          <span className="text-muted">Stock actual → nuevo</span>
          <span className="num font-bold">
            {qty(product.stock)} → <span className={result < 0 ? 'text-danger-ink' : 'text-primary'}>{qty(result)}</span>
          </span>
        </div>
      </div>
    </Modal>
  )
}

const TYPE_LABELS: Record<string, { label: string; tone: 'success' | 'danger' | 'primary' | 'warning' | 'neutral' }> = {
  VENTA: { label: 'Venta', tone: 'primary' },
  ANULACION: { label: 'Anulación', tone: 'warning' },
  DEVOLUCION: { label: 'Devolución', tone: 'warning' },
  AJUSTE_ENTRADA: { label: 'Ajuste +', tone: 'success' },
  AJUSTE_SALIDA: { label: 'Ajuste −', tone: 'danger' },
  DANADO: { label: 'Dañado', tone: 'danger' },
  INICIAL: { label: 'Inicial', tone: 'neutral' },
  IMPORTACION: { label: 'Importación', tone: 'neutral' },
  CORRECCION: { label: 'Corrección', tone: 'warning' },
  COMPRA: { label: 'Compra', tone: 'success' },
}

export function HistoryModal({ product, onClose }: { product: ProductDto; onClose: () => void }) {
  const [tab, setTab] = useState<'mov' | 'price'>('mov')
  const [movs, setMovs] = useState<MovementDto[] | null>(null)
  const [prices, setPrices] = useState<PriceHistoryDto[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<Page<MovementDto>>(`/inventory/movements?productId=${product.id}&size=100`)
      .then((r) => setMovs(r.items))
      .catch((e) => setError(e.message))
    api<PriceHistoryDto[]>(`/products/${product.id}/price-history`)
      .then(setPrices)
      .catch(() => setPrices([]))
  }, [product.id])

  return (
    <Modal open wide onClose={onClose} title={`Historial · ${product.name}`}>
      <div className="space-y-4">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'mov', label: 'Movimientos', count: movs?.length },
            { value: 'price', label: 'Precios y costos', count: prices?.length },
          ]}
        />
        {error && <Banner>{error}</Banner>}
        {tab === 'mov' &&
          (!movs ? (
            <Spinner />
          ) : movs.length === 0 ? (
            <EmptyState icon="history" title="Sin movimientos todavía" />
          ) : (
            <div className="divide-y divide-line-soft rounded-xl border border-line">
              {movs.map((m) => {
                const t = TYPE_LABELS[m.type] ?? { label: m.type, tone: 'neutral' as const }
                return (
                  <div key={m.id} className="flex items-center gap-3 px-3 py-2.5">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Chip tone={t.tone}>{t.label}</Chip>
                        <span className="truncate text-sm">{m.reason}</span>
                      </div>
                      <div className="mt-0.5 text-xs text-muted">
                        {m.userName ?? 'Sistema'} · {dateTime(m.createdAt)}
                        {m.unitCost !== undefined && m.unitCost !== null && m.quantity > 0 ? ` · costo ${money(m.unitCost)}` : ''}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className={`num text-sm font-bold ${m.quantity > 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
                        {m.quantity > 0 ? '+' : ''}
                        {qty(m.quantity)}
                      </div>
                      <div className="num text-xs text-muted">queda {qty(m.stockAfter)}</div>
                    </div>
                  </div>
                )
              })}
            </div>
          ))}
        {tab === 'price' &&
          (!prices ? (
            <Spinner />
          ) : prices.length === 0 ? (
            <EmptyState icon="sell" title="Sin cambios de precio" />
          ) : (
            <div className="divide-y divide-line-soft rounded-xl border border-line">
              {prices.map((h) => (
                <div key={h.id} className="flex items-center gap-3 px-3 py-2.5">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-semibold">
                      {h.field === 'COSTO' ? 'Costo' : h.field === 'PRECIO_MINIMO' ? 'Precio mínimo' : 'Precio'}
                      {h.presentationName ? ` · ${h.presentationName}` : ''}
                    </div>
                    <div className="text-xs text-muted">
                      {h.reason} · {h.userName ?? 'Sistema'} · {dateTime(h.createdAt)}
                    </div>
                  </div>
                  <div className="num text-sm font-semibold">
                    {h.oldValue !== undefined && h.oldValue !== null ? money(h.oldValue) : '—'} → {money(h.newValue)}
                  </div>
                </div>
              ))}
            </div>
          ))}
      </div>
    </Modal>
  )
}
