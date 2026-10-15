import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, Spinner, Tabs, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, money, pct } from '../../lib/format'

interface Reco {
  id: number
  type: 'PRECIO' | 'PROMOCION'
  productId: number
  productName: string
  currentPrice?: number
  suggestedPrice?: number
  discountPct?: number
  currentMargin?: number
  expectedMargin?: number
  reason: string
  source: 'REGLAS' | 'ASISTENTE'
  status: 'PENDIENTE' | 'APLICADA' | 'DESCARTADA'
  createdBy?: string
  decidedBy?: string
  decidedAt?: string
  decisionNote?: string
  createdAt: string
}

/** Recomendaciones de precios y promociones: el sistema propone, una persona decide. */
export default function RecommendationsPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [tab, setTab] = useState<'PENDIENTE' | 'historial'>('PENDIENTE')
  const [list, setList] = useState<Reco[] | null>(null)
  const [generating, setGenerating] = useState(false)
  const [decide, setDecide] = useState<{ r: Reco; approve: boolean } | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    setList(null)
    api<Reco[]>(`/recommendations${tab === 'PENDIENTE' ? '?status=PENDIENTE' : ''}`)
      .then((l) => setList(tab === 'PENDIENTE' ? l : l.filter((x) => x.status !== 'PENDIENTE')))
      .catch((e) => setError(e.message))
  }, [tab])
  useEffect(load, [load])

  async function generate() {
    setGenerating(true)
    try {
      const r = await api<Reco[]>('/recommendations/generate', { method: 'POST' })
      toast(r.length ? `${r.length} recomendaciones nuevas` : 'No hay recomendaciones nuevas')
      setTab('PENDIENTE')
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'No se pudo calcular', 'danger')
    } finally {
      setGenerating(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Recomendaciones de precios y promociones</h1>
          <p className="text-sm text-muted">Se calculan con el margen, la rotación y el exceso de cada producto. Nada cambia hasta que alguien con permiso de precios lo aprueba.</p>
        </div>
        {can('COSTOS_VER') && (
          <Button icon="auto_awesome" onClick={generate} loading={generating}>
            Buscar recomendaciones
          </Button>
        )}
      </div>
      <div className="w-72">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'PENDIENTE', label: 'Por revisar' },
            { value: 'historial', label: 'Historial' },
          ]}
        />
      </div>
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState icon="sell" title={tab === 'PENDIENTE' ? 'Nada por revisar' : 'Sin decisiones todavía'} text="Pulsa “Buscar recomendaciones” o pídeselas al asistente." />
        </Card>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {list.map((r) => (
            <Card key={r.id} className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold">{r.productName}</div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    <Chip tone={r.type === 'PRECIO' ? 'primary' : 'wallet'} icon={r.type === 'PRECIO' ? 'price_change' : 'local_offer'}>
                      {r.type === 'PRECIO' ? 'Ajuste de precio' : 'Promoción'}
                    </Chip>
                    {r.source === 'ASISTENTE' && <Chip icon="auto_awesome">Del asistente</Chip>}
                    {r.status !== 'PENDIENTE' && <Chip tone={r.status === 'APLICADA' ? 'success' : 'neutral'}>{r.status === 'APLICADA' ? 'Aplicada' : 'Descartada'}</Chip>}
                  </div>
                </div>
                <div className="text-right text-sm">
                  {r.type === 'PRECIO' ? (
                    <div className="num">
                      <span className="text-muted line-through">{money(r.currentPrice)}</span> <b className="text-lg">{money(r.suggestedPrice)}</b>
                    </div>
                  ) : (
                    <div className="num">
                      <b className="text-lg">-{pct(r.discountPct)}</b>
                      <div className="text-xs text-muted">{money(r.suggestedPrice)} por 14 días</div>
                    </div>
                  )}
                  <div className="text-xs text-muted">
                    Margen {pct(r.currentMargin)} → {pct(r.expectedMargin)}
                  </div>
                </div>
              </div>
              <p className="mt-2 text-sm text-ink/80">{r.reason}</p>
              <div className="mt-2 text-xs text-muted">
                {dateTime(r.createdAt)}
                {r.decidedBy && ` · ${r.status === 'APLICADA' ? 'aprobó' : 'descartó'} ${r.decidedBy}${r.decisionNote ? `: ${r.decisionNote}` : ''}`}
              </div>
              {r.status === 'PENDIENTE' && can('PRODUCTOS_PRECIOS') && (
                <div className="mt-3 flex justify-end gap-2">
                  <Button size="sm" variant="ghost" icon="close" onClick={() => setDecide({ r, approve: false })}>
                    Descartar
                  </Button>
                  <Button size="sm" icon="check" onClick={() => setDecide({ r, approve: true })}>
                    Aprobar y aplicar
                  </Button>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
      {decide && (
        <DecideModal
          {...decide}
          onClose={() => setDecide(null)}
          onDone={() => {
            toast(decide.approve ? 'Aplicado' : 'Descartada')
            setDecide(null)
            load()
          }}
        />
      )}
    </div>
  )
}

function DecideModal({ r, approve, onClose, onDone }: { r: Reco; approve: boolean; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  async function submit() {
    setLoading(true)
    try {
      await api(`/recommendations/${r.id}/${approve ? 'approve' : 'dismiss'}`, { method: 'POST', body: { note: note || undefined } })
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setLoading(false)
    }
  }
  return (
    <Modal
      open
      title={approve ? 'Aprobar recomendación' : 'Descartar recomendación'}
      onClose={onClose}
      footer={
        <Button className="flex-1" variant={approve ? 'primary' : 'secondary'} onClick={submit} loading={loading}>
          {approve ? 'Aprobar y aplicar' : 'Descartar'}
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p>
          {approve ? (
            r.type === 'PRECIO' ? (
              <>
                El precio de <b>{r.productName}</b> pasará de {money(r.currentPrice)} a <b>{money(r.suggestedPrice)}</b> desde ya (queda en el historial de precios).
              </>
            ) : (
              <>
                Se crea la promoción de <b>-{pct(r.discountPct)}</b> en <b>{r.productName}</b> por 14 días.
              </>
            )
          ) : (
            <>No se aplicará ningún cambio.</>
          )}
        </p>
        <Field label="Nota (opcional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder={approve ? 'Ej: revisé el precio de la competencia' : 'Ej: el precio lo fija el proveedor'} />
        {error && <Banner>{error}</Banner>}
        {approve && (
          <p className="flex items-center gap-1 text-xs text-muted">
            <Icon name="info" className="text-[14px]" /> Revisa siempre antes de aprobar: es una sugerencia calculada, no una orden.
          </p>
        )}
      </div>
    </Modal>
  )
}
