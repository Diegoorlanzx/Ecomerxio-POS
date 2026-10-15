import { Fragment, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, Icon, Modal, Spinner, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { relative } from '../../lib/format'

interface ToolInfo {
  name: string
  title: string
  description: string
}
interface Status {
  aiConfigured: boolean
  enabled: boolean
  model?: string
  dailyLimit: number
  usedToday: number
  tools: ToolInfo[]
}
interface Msg {
  id: number | string
  role: 'USER' | 'ASSISTANT' | 'QUICK'
  text: string
  toolsUsed?: string[]
  createdAt?: string
  pending?: boolean
}
interface Conversation {
  id: number
  title: string
  updatedAt: string
  messages: Msg[]
}

const TOOL_ICONS: Record<string, string> = {
  ventas_periodo: 'point_of_sale',
  productos_rentabilidad: 'trending_up',
  sugerencias_compra: 'shopping_cart_checkout',
  productos_en_caida: 'trending_down',
  comparar_proveedores: 'compare_arrows',
  resumen_periodo: 'summarize',
  detectar_anomalias: 'policy',
  proponer_precios_promociones: 'sell',
  estado_inventario: 'inventory_2',
}

const EXAMPLES = [
  '¿Cuánto vendí esta semana comparado con la anterior?',
  '¿Qué productos me dejan más utilidad este mes?',
  '¿Qué mercancía debo comprar esta semana y cuánto dinero necesito?',
  '¿Por qué me recomiendas pedir cerveza?',
  '¿Qué productos están vendiendo menos?',
  'Compara a mis proveedores',
  'Resúmeme cómo le fue a la tienda el mes pasado',
  '¿Hay algo raro que deba revisar en la caja o las ventas?',
  'Propón precios o promociones para revisar',
]

/** Texto con **negrita** y viñetas "- " (lo único de formato que usa el asistente). */
function Rich({ text }: { text: string }) {
  const bold = (s: string) => s.split(/(\*\*[^*]+\*\*)/g).map((p, i) => (p.startsWith('**') && p.endsWith('**') ? <b key={i}>{p.slice(2, -2)}</b> : <Fragment key={i}>{p}</Fragment>))
  const blocks: ReactNode[] = []
  let list: string[] = []
  const flush = () => {
    if (list.length) blocks.push(<ul key={`l${blocks.length}`} className="my-1 list-disc space-y-0.5 pl-5">{list.map((l, i) => <li key={i}>{bold(l)}</li>)}</ul>)
    list = []
  }
  for (const line of text.split('\n')) {
    const m = line.match(/^\s*(?:[-*•]|\d+[.)])\s+(.*)$/)
    if (m) list.push(m[1])
    else {
      flush()
      if (line.trim().startsWith('#')) blocks.push(<p key={`h${blocks.length}`} className="mt-1 font-semibold">{bold(line.replace(/^#+\s*/, ''))}</p>)
      else if (line.trim()) blocks.push(<p key={`p${blocks.length}`}>{bold(line)}</p>)
    }
  }
  flush()
  return <div className="space-y-1.5">{blocks}</div>
}

/** Asistente: preguntas en lenguaje natural respondidas con los datos de la tienda (y consultas rápidas sin IA). */
export default function AssistantPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [status, setStatus] = useState<Status | null>(null)
  const [convs, setConvs] = useState<Conversation[]>([])
  const [convId, setConvId] = useState<number | null>(null)
  const [messages, setMessages] = useState<Msg[]>([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const [quick, setQuick] = useState<ToolInfo | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [error, setError] = useState('')
  const endRef = useRef<HTMLDivElement>(null)

  const loadStatus = useCallback(() => api<Status>('/assistant/status').then(setStatus).catch((e) => setError(e.message)), [])
  const loadConvs = useCallback(() => api<Conversation[]>('/assistant/conversations').then(setConvs).catch(() => {}), [])
  useEffect(() => {
    loadStatus()
    loadConvs()
  }, [loadStatus, loadConvs])
  useEffect(() => endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }), [messages])

  async function open(id: number) {
    const c = await api<Conversation>(`/assistant/conversations/${id}`)
    setConvId(id)
    setMessages(c.messages)
  }

  function newChat() {
    setConvId(null)
    setMessages([])
  }

  async function ask(text: string) {
    const q = text.trim()
    if (!q || busy) return
    setQuestion('')
    setBusy(true)
    const tempId = `t${Date.now()}`
    setMessages((m) => [...m, { id: tempId, role: 'USER', text: q }, { id: `${tempId}a`, role: 'ASSISTANT', text: '', pending: true }])
    try {
      const r = await api<{ conversationId: number; answer: Msg }>('/assistant/ask', { method: 'POST', body: { conversationId: convId, question: q } })
      setConvId(r.conversationId)
      setMessages((m) => m.map((x) => (x.id === `${tempId}a` ? r.answer : x)))
      loadConvs()
      loadStatus()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'No se pudo enviar la pregunta'
      setMessages((m) => m.map((x) => (x.id === `${tempId}a` ? { ...x, pending: false, text: `⚠️ ${msg}` } : x)))
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: number) {
    await api(`/assistant/conversations/${id}`, { method: 'DELETE' })
    if (id === convId) newChat()
    loadConvs()
    toast('Conversación eliminada')
  }

  if (error) return <Banner>{error}</Banner>
  if (!status) return <Spinner />
  const aiReady = status.aiConfigured && status.enabled
  const left = status.dailyLimit - status.usedToday

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold tracking-tight">
            <Icon name="auto_awesome" className="text-wallet" /> Asistente
          </h1>
          <p className="text-sm text-muted">Pregunta por tus ventas, inventario, compras o rentabilidad. Responde con los datos reales de la tienda que tu rol puede ver.</p>
        </div>
        <div className="flex gap-2">
          {can('COSTOS_VER') && (
            <Link to="/recomendaciones" className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-line bg-card px-3 text-sm font-semibold">
              <Icon name="sell" className="text-[18px]" /> Recomendaciones
            </Link>
          )}
          {can('CONFIG_EDITAR') && (
            <Button size="sm" variant="ghost" icon="tune" onClick={() => setSettingsOpen(true)}>
              Ajustes
            </Button>
          )}
        </div>
      </div>

      {!status.aiConfigured && (
        <Banner tone="primary">
          El asistente conversacional necesita una clave de Claude en el servidor (variable ANTHROPIC_API_KEY). Mientras tanto, las consultas rápidas de abajo responden con
          los mismos datos.
        </Banner>
      )}
      {status.aiConfigured && !status.enabled && <Banner tone="warning">El asistente está desactivado para esta tienda. Las consultas rápidas siguen disponibles.</Banner>}

      <div className="grid gap-4 lg:grid-cols-[240px_1fr]">
        <aside className="hidden space-y-2 lg:block">
          <Button variant="secondary" icon="add_comment" className="w-full" onClick={newChat}>
            Nueva conversación
          </Button>
          <div className="space-y-1">
            {convs.map((c) => (
              <div key={c.id} className={`group flex items-center gap-1 rounded-xl px-2 py-2 text-sm ${c.id === convId ? 'bg-primary-tint text-primary-active' : 'hover:bg-line-soft'}`}>
                <button type="button" onClick={() => open(c.id)} className="min-w-0 flex-1 text-left">
                  <span className="block truncate font-medium">{c.title}</span>
                  <span className="text-[11px] text-muted">{relative(c.updatedAt)}</span>
                </button>
                <button type="button" aria-label="Eliminar conversación" onClick={() => remove(c.id)} className="hidden h-7 w-7 items-center justify-center rounded-lg text-muted group-hover:flex hover:bg-card">
                  <Icon name="delete" className="text-[16px]" />
                </button>
              </div>
            ))}
          </div>
        </aside>

        <Card className="flex min-h-[60vh] flex-col">
          <div className="flex-1 space-y-4 overflow-y-auto p-4">
            {messages.length === 0 && (
              <div className="py-4">
                <div className="mb-3 text-center">
                  <span className="mx-auto mb-2 flex h-14 w-14 items-center justify-center rounded-2xl bg-wallet-soft text-wallet-ink">
                    <Icon name="auto_awesome" className="text-[30px]" />
                  </span>
                  <p className="font-semibold">¿En qué te ayudo hoy?</p>
                </div>
                {aiReady && (
                  <div className="flex flex-wrap justify-center gap-2">
                    {EXAMPLES.map((e) => (
                      <button key={e} type="button" onClick={() => ask(e)} className="rounded-full border border-line bg-card px-3 py-1.5 text-sm hover:border-primary hover:text-primary">
                        {e}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
            {messages.map((m) => (
              <div key={m.id} className={`flex ${m.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm ${
                    m.role === 'USER' ? 'bg-primary text-white' : m.role === 'QUICK' ? 'border border-line bg-canvas' : 'bg-wallet-soft text-ink'
                  }`}
                >
                  {m.pending ? (
                    <span className="flex items-center gap-2 text-muted">
                      <Icon name="progress_activity" className="animate-spin text-[18px]" /> Consultando los datos de la tienda…
                    </span>
                  ) : m.role === 'USER' ? (
                    <p className="whitespace-pre-wrap">{m.text}</p>
                  ) : (
                    <Rich text={m.text} />
                  )}
                  {m.toolsUsed && m.toolsUsed.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-1">
                      {m.toolsUsed.map((t) => (
                        <Chip key={t} icon={TOOL_ICONS[t] ?? 'database'}>
                          {status.tools.find((x) => x.name === t)?.title ?? t}
                        </Chip>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            <div ref={endRef} />
          </div>
          <form
            className="flex gap-2 border-t border-line-soft p-3"
            onSubmit={(e) => {
              e.preventDefault()
              ask(question)
            }}
          >
            <input
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              disabled={!aiReady || busy}
              maxLength={2000}
              placeholder={aiReady ? 'Escribe tu pregunta…' : 'Usa las consultas rápidas mientras se configura la IA'}
              className="h-12 flex-1 rounded-xl border border-line bg-card px-3.5 text-base outline-none focus:border-primary disabled:bg-canvas"
            />
            <Button type="submit" icon="send" loading={busy} disabled={!aiReady || !question.trim()}>
              <span className="hidden sm:inline">Enviar</span>
            </Button>
          </form>
          {aiReady && <p className="px-4 pb-3 text-[11px] text-muted">Quedan {Math.max(0, left)} de {status.dailyLimit} preguntas hoy. Verifica las cifras importantes antes de decidir.</p>}
        </Card>
      </div>

      <section>
        <h2 className="mb-2 text-xs font-semibold tracking-[0.06em] text-muted uppercase">Consultas rápidas (sin IA)</h2>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
          {status.tools.map((t) => (
            <button key={t.name} type="button" onClick={() => setQuick(t)} title={t.description} aria-label={t.title} className="flex items-center gap-2 rounded-xl border border-line bg-card p-3 text-left text-sm font-semibold hover:border-primary">
              <Icon name={TOOL_ICONS[t.name] ?? 'database'} className="text-[20px] text-primary" />
              {t.title}
            </button>
          ))}
        </div>
      </section>

      {quick && (
        <QuickModal
          tool={quick}
          onClose={() => setQuick(null)}
          onResult={(text) => {
            setMessages((m) => [...m, { id: `q${Date.now()}`, role: 'QUICK', text, toolsUsed: [quick.name] }])
            setQuick(null)
          }}
        />
      )}
      {settingsOpen && <SettingsModal status={status} onClose={() => setSettingsOpen(false)} onSaved={() => (setSettingsOpen(false), loadStatus())} />}
    </div>
  )
}

function QuickModal({ tool, onClose, onResult }: { tool: ToolInfo; onClose: () => void; onResult: (text: string) => void }) {
  const today = new Date().toLocaleDateString('en-CA')
  const [desde, setDesde] = useState(today.slice(0, 8) + '01')
  const [hasta, setHasta] = useState(today)
  const [tipo, setTipo] = useState('semana')
  const [anterior, setAnterior] = useState(false)
  const [texto, setTexto] = useState('')
  const [orden, setOrden] = useState('mayor_utilidad')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const dated = ['ventas_periodo', 'productos_rentabilidad', 'productos_en_caida'].includes(tool.name)

  async function run() {
    setLoading(true)
    setError('')
    const input: Record<string, unknown> = {}
    if (dated) Object.assign(input, { desde, hasta })
    if (tool.name === 'productos_rentabilidad') input.orden = orden
    if (tool.name === 'resumen_periodo') Object.assign(input, { tipo, anterior })
    if (tool.name === 'comparar_proveedores' && texto) input.producto = texto
    if (tool.name === 'estado_inventario' && texto) input.buscar = texto
    if (tool.name === 'sugerencias_compra' && texto) input.proveedor = texto
    try {
      const r = await api<{ resumen: string }>('/assistant/quick', { method: 'POST', body: { tool: tool.name, input } })
      onResult(r.resumen)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo consultar')
    } finally {
      setLoading(false)
    }
  }

  const textLabel = tool.name === 'comparar_proveedores' ? 'Producto (opcional)' : tool.name === 'estado_inventario' ? 'Buscar producto (opcional)' : tool.name === 'sugerencias_compra' ? 'Proveedor (opcional)' : null
  return (
    <Modal
      open
      title={tool.title}
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="search" onClick={run} loading={loading}>
          Consultar
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted">{tool.description}</p>
        {dated && (
          <div className="grid grid-cols-2 gap-2">
            <label className="text-xs font-semibold text-muted uppercase">
              Desde
              <input type="date" value={desde} max={hasta} onChange={(e) => setDesde(e.target.value)} className="mt-1 h-11 w-full rounded-xl border border-line px-2 text-base text-ink" />
            </label>
            <label className="text-xs font-semibold text-muted uppercase">
              Hasta
              <input type="date" value={hasta} max={today} onChange={(e) => setHasta(e.target.value)} className="mt-1 h-11 w-full rounded-xl border border-line px-2 text-base text-ink" />
            </label>
          </div>
        )}
        {tool.name === 'productos_rentabilidad' && (
          <select value={orden} onChange={(e) => setOrden(e.target.value)} className="h-11 w-full rounded-xl border border-line px-2 text-base">
            <option value="mayor_utilidad">Mayor utilidad</option>
            <option value="menor_margen">Menor margen</option>
            <option value="mas_vendidos">Más vendidos</option>
          </select>
        )}
        {tool.name === 'resumen_periodo' && (
          <div className="flex flex-wrap items-center gap-3">
            {['semana', 'mes'].map((t) => (
              <button key={t} type="button" onClick={() => setTipo(t)} className={`h-10 rounded-xl border px-4 font-semibold ${tipo === t ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}>
                {t === 'semana' ? 'Semana' : 'Mes'}
              </button>
            ))}
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={anterior} onChange={(e) => setAnterior(e.target.checked)} /> {tipo === 'semana' ? 'La semana pasada' : 'El mes pasado'}
            </label>
          </div>
        )}
        {textLabel && <input value={texto} onChange={(e) => setTexto(e.target.value)} placeholder={textLabel} className="h-11 w-full rounded-xl border border-line px-3 text-base" />}
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

function SettingsModal({ status, onClose, onSaved }: { status: Status; onClose: () => void; onSaved: () => void }) {
  const [enabled, setEnabled] = useState(status.enabled)
  const [limit, setLimit] = useState(String(status.dailyLimit))
  const [error, setError] = useState('')
  async function save() {
    try {
      await api('/assistant/settings', { method: 'PUT', body: { aiEnabled: enabled, aiDailyLimit: Number(limit || 0) } })
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar')
    }
  }
  return (
    <Modal
      open
      title="Ajustes del asistente"
      onClose={onClose}
      footer={
        <Button className="flex-1" icon="save" onClick={save}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <Toggle checked={enabled} onChange={setEnabled} label="Asistente con IA activo" hint="Las consultas rápidas siempre funcionan." />
        <label className="block text-xs font-semibold text-muted uppercase">
          Preguntas al día para toda la tienda
          <input inputMode="numeric" value={limit} onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} className="mt-1 h-11 w-full rounded-xl border border-line px-3 text-base text-ink" />
        </label>
        <p className="text-xs text-muted">
          {status.aiConfigured ? `Modelo: ${status.model}. Hoy se han hecho ${status.usedToday} preguntas.` : 'El servidor aún no tiene clave de Claude (ANTHROPIC_API_KEY).'} Cada pregunta consume créditos de
          la cuenta de Claude del servidor.
        </p>
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}
