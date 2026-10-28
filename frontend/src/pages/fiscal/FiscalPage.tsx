import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, Tabs, TextArea, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { DOC_STATUS, MUNICIPALITIES, type ActivationPreview, type NoteConcepts, type NoteType, type DocumentDetail, type BulkRetryResult, type DocumentStatus, type EInvoiceSummary, type ElectronicDocumentDto, type FiscalCatalogs, type FiscalProfile, type ProductTaxRow, type Readiness, type Resolution } from '../../lib/fiscalTypes'
import { dateTime, money } from '../../lib/format'
import { useAuth } from '../../lib/auth'
import { nitDigits, nitDv } from '../../lib/nit'
import { EInvoiceRequestCard } from './EInvoiceRequestCard'
import { INVOICE_PREFERENCES, METHOD_LABELS, type Customer, type InvoicePreference, type StoreSettings } from '../../lib/posTypes'

type Tab = 'asistente' | 'documentos' | 'datos' | 'resoluciones' | 'productos' | 'clientes'

/**
 * Facturación electrónica — Fase 13A (datos fiscales, resoluciones, clasificación tributaria y clientes) y 13B
 * (documento de prueba, activación y documentos emitidos). Mientras no haya proveedor real, emite con el simulador.
 */
export default function FiscalPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) || 'asistente'
  const setTab = (t: Tab) => setParams(t === 'asistente' ? {} : { tab: t }, { replace: true })
  const [catalogs, setCatalogs] = useState<FiscalCatalogs | null>(null)
  const [readiness, setReadiness] = useState<Readiness | null>(null)
  const [summary, setSummary] = useState<EInvoiceSummary | null>(null)
  const [error, setError] = useState('')
  const { me } = useAuth()
  // Facturación vendida aparte: hasta que Ecomerxio la activa, el asistente queda abierto pero sin prueba ni activación.
  const addon = !!me?.tenant.einvoiceAddon

  const reloadReadiness = useCallback(() => {
    api<Readiness>('/fiscal/readiness').then(setReadiness).catch((e) => setError(e.message))
    api<EInvoiceSummary>('/einvoice/summary').then(setSummary).catch(() => {})
  }, [])

  useEffect(() => {
    api<FiscalCatalogs>('/fiscal/catalogs').then(setCatalogs).catch((e) => setError(e.message))
    reloadReadiness()
  }, [reloadReadiness])

  if (error) return <Banner>{error}</Banner>
  if (!catalogs || !readiness) return <Spinner />
  const problems = summary ? ['RECHAZADO', 'BLOQUEADO', 'DESCONOCIDO'].reduce((n, k) => n + (summary.byStatus[k] ?? 0), 0) : 0

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold">Facturación electrónica</h1>
          <p className="text-sm text-muted">Deja todo listo para emitir documentos electrónicos ante la DIAN.</p>
        </div>
        <Chip tone={readiness.environment === 'PRODUCCION' ? 'success' : 'warning'} icon="science">
          Ambiente de {readiness.environment === 'PRODUCCION' ? 'producción' : 'pruebas'}
        </Chip>
      </div>
      <div className="overflow-x-auto">
        <Tabs<Tab>
          value={tab}
          onChange={setTab}
          items={[
            { value: 'asistente', label: 'Asistente', count: readiness.ready ? undefined : readiness.total - readiness.done },
            { value: 'documentos', label: 'Documentos', count: problems || undefined },
            { value: 'datos', label: 'Datos fiscales' },
            { value: 'resoluciones', label: 'Resoluciones' },
            { value: 'productos', label: 'Productos' },
            { value: 'clientes', label: 'Clientes' },
          ]}
        />
      </div>
      {!addon && (
        <Banner tone="primary">
          La facturación electrónica se vende por paquetes y se activa cuando verifiquemos tus documentos y el pago. Mientras tanto puedes completar
          el asistente y enviar tu solicitud; la caja sigue funcionando con recibos normales.
        </Banner>
      )}
      {addon && summary?.simulated && (
        <Banner tone="warning">
          Todavía no hay un proveedor tecnológico real conectado: los documentos se emiten con un <b>simulador</b>, solo en el ambiente de
          pruebas y <b>sin validez ante la DIAN</b>.
        </Banner>
      )}
      {tab === 'asistente' && <Assistant readiness={readiness} summary={summary} addon={addon} storeName={me?.tenant.name ?? ''} onGo={(t) => setTab(t as Tab)} onChanged={reloadReadiness} />}
      {tab === 'documentos' && <Documents onChanged={reloadReadiness} onGo={(t) => setTab(t as Tab)} />}
      {tab === 'datos' && <ProfileForm catalogs={catalogs} onSaved={reloadReadiness} />}
      {tab === 'resoluciones' && <Resolutions catalogs={catalogs} onChanged={reloadReadiness} />}
      {tab === 'productos' && <ProductTaxes onChanged={reloadReadiness} />}
      {tab === 'clientes' && <Customers catalogs={catalogs} />}
    </div>
  )
}

// ---------------------------------------------------------------- Asistente

function Assistant({ readiness, summary, addon, storeName, onGo, onChanged }: { readiness: Readiness; summary: EInvoiceSummary | null; addon: boolean; storeName: string; onGo: (tab: string) => void; onChanged: () => void }) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const required = readiness.total - readiness.steps.filter((s) => s.status === 'BLOQUEADO').length
  const pct = Math.round((readiness.done / Math.max(1, required)) * 100)

  async function test() {
    setBusy(true)
    setMessage(null)
    try {
      const d = await api<DocumentDetail>('/einvoice/test', { body: {} })
      const ok = d.document.status === 'ACEPTADO'
      setMessage({
        tone: ok ? 'success' : 'danger',
        text: ok
          ? `Documento de prueba ${d.document.fullNumber} aceptado.`
          : `El documento de prueba quedó ${DOC_STATUS[d.document.status].label.toLowerCase()}: ${d.document.lastErrorMessage ?? ''}`,
      })
      onChanged()
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'No se pudo emitir la prueba' })
    } finally {
      setBusy(false)
    }
  }

  // Fase 16B: activar muestra antes qué pasa (fecha de corte, documentos al mes); desactivar pide la contraseña del dueño.
  const [confirmOn, setConfirmOn] = useState<ActivationPreview | null>(null)
  const [askPassword, setAskPassword] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState('')

  async function enable(on: boolean) {
    setMessage(null)
    if (on) {
      try {
        setConfirmOn(await api<ActivationPreview>('/einvoice/activation-preview'))
      } catch (e) {
        setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'No se pudo consultar' })
      }
      return
    }
    setPassword('')
    setPasswordError('')
    setAskPassword(true)
  }

  async function apply(on: boolean) {
    setBusy(true)
    setMessage(null)
    try {
      await api('/einvoice/enable', { body: { enabled: on, password: on ? undefined : password } })
      toast(on ? 'Facturación electrónica activada' : 'Facturación electrónica desactivada')
      setConfirmOn(null)
      setAskPassword(false)
      onChanged()
    } catch (e) {
      if (!on && e instanceof ApiError && e.fields.password) setPasswordError(e.fields.password)
      else {
        setConfirmOn(null)
        setAskPassword(false)
        setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'No se pudo cambiar' })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <Card className="p-5">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold">{readiness.ready ? 'Todo listo para conectar el proveedor' : 'Preparación para facturar'}</div>
            <div className="text-xs text-muted">
              {readiness.done} de {required} pasos completos
            </div>
          </div>
          <div className="num text-2xl font-bold text-primary">{pct}%</div>
        </div>
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-line-soft">
          <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${pct}%` }} />
        </div>
      </Card>
      <div className="space-y-2">
        {readiness.steps.map((s, i) => (
          <Card key={s.key} className="flex items-start gap-3 p-4">
            <span
              className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${
                s.status === 'OK' ? 'bg-success-soft text-success-ink' : s.status === 'BLOQUEADO' ? 'bg-line-soft text-muted' : 'bg-warning-soft text-warning-ink'
              }`}
            >
              <Icon name={s.status === 'OK' ? 'check_circle' : s.status === 'BLOQUEADO' ? 'lock' : 'pending'} className="text-[22px]" />
            </span>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold">
                {i + 1}. {s.title}
              </div>
              <div className="text-sm text-muted">{s.detail}</div>
            </div>
            {s.action === 'prueba' && s.status !== 'OK' && !addon ? (
              <Chip tone="neutral" icon="lock">
                Al activar
              </Chip>
            ) : s.action === 'prueba' && s.status !== 'OK' ? (
              <Button size="sm" variant="soft" loading={busy} onClick={test}>
                Emitir prueba
              </Button>
            ) : (
              s.action &&
              s.status !== 'OK' && (
                <Button size="sm" variant="soft" onClick={() => onGo(s.action!)}>
                  Ir
                </Button>
              )
            )}
          </Card>
        ))}
      </div>
      {message && <Banner tone={message.tone}>{message.text}</Banner>}
      {!addon && <EInvoiceRequestCard storeName={storeName} onChanged={onChanged} />}
      {addon && (
      <Card className="p-5">
        <Toggle
          checked={readiness.invoicingEnabled}
          onChange={(v) => enable(v)}
          label="Facturación electrónica activada"
          hint={
            readiness.invoicingEnabled
              ? readiness.invoicingMode === 'A_PEDIDO'
                ? 'Se factura cuando el cliente la pide; las devoluciones y anulaciones de esas ventas generan su nota crédito.'
                : 'Cada venta genera su documento electrónico; las devoluciones y anulaciones, su nota crédito.'
              : readiness.ready
                ? 'Todo está listo: al activarla, cada venta generará su documento electrónico.'
                : 'Se puede activar cuando todos los pasos estén completos.'
          }
        />
        {summary && <p className="mt-1 text-xs text-muted">Proveedor: {summary.simulated ? 'simulador (sin validez)' : summary.provider}</p>}
      </Card>
      )}
      {addon && <ModeCard mode={readiness.invoicingMode} onChanged={onChanged} />}
      {summary?.fiscalStartAt && <CutoffCard summary={summary} onChanged={onChanged} />}
      <Modal
        open={!!confirmOn}
        title="Activar la facturación electrónica"
        onClose={() => setConfirmOn(null)}
        footer={
          <Button className="w-full" loading={busy} onClick={() => apply(true)}>
            Activar
          </Button>
        }
      >
        {confirmOn && (
          <div className="space-y-3 text-sm">
            <p>
              {confirmOn.cutoffWillMove
                ? 'Desde este momento queda la fecha de corte:'
                : `La fecha de corte sigue siendo el ${dateTime(confirmOn.cutoffAt)}:`}
            </p>
            <ul className="list-disc space-y-1 pl-5">
              <li>
                {confirmOn.mode === 'A_PEDIDO'
                  ? 'Se factura solo cuando el cliente lo pide; el resto sale con comprobante interno.'
                  : 'Cada venta genera su documento electrónico (a nombre del cliente o a consumidor final).'}
              </li>
              <li>
                Las <b>{confirmOn.salesBeforeCutoff}</b> ventas anteriores nunca se facturan y se ocultan de Ventas, Analítica y Dashboard. No se
                borran: siguen en las copias de seguridad y el dueño puede verlas.
              </li>
              <li>
                Con tus ventas de los últimos 30 días ({confirmOn.salesLast30Days}), gastarías unos <b>{confirmOn.estimatedDocumentsPerMonth}</b>{' '}
                documentos al mes.
              </li>
            </ul>
            <p className="text-xs text-muted">Ocultar las ventas anteriores no cambia lo que tu negocio debe declarar: consúltalo con tu contador.</p>
          </div>
        )}
      </Modal>
      <Modal
        open={askPassword}
        title="Desactivar la facturación electrónica"
        onClose={() => setAskPassword(false)}
        footer={
          <Button variant="danger" className="w-full" loading={busy} onClick={() => apply(false)}>
            Desactivar
          </Button>
        }
      >
        <div className="space-y-3 text-sm">
          <p>Las ventas dejarán de generar documentos electrónicos. Las facturas ya emitidas no cambian. Solo el dueño puede hacerlo.</p>
          <Field label="Tu contraseña" type="password" value={password} onChange={(e) => setPassword(e.target.value)} error={passwordError} autoFocus />
        </div>
      </Modal>
      <p className="text-xs text-muted">
        Los pasos de inscripción y autorización del proveedor se hacen en el portal de la DIAN con tu usuario; aquí solo los marcas como
        hechos. Los nombres exactos de cada trámite y los requisitos los confirma el proveedor tecnológico.
      </p>
    </div>
  )
}

// ---------------------------------------------------------------- Fecha de corte (Fase 16B)

function CutoffCard({ summary, onChanged }: { summary: EInvoiceSummary; onChanged: () => void }) {
  const { me } = useAuth()
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  async function toggle(hide: boolean) {
    setBusy(true)
    try {
      await api('/einvoice/cutoff-view', { body: { hidePreCutoff: hide } })
      toast(hide ? 'Las ventas anteriores quedan ocultas' : 'Las ventas anteriores se muestran en los informes')
      onChanged()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="space-y-2 p-5">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <Icon name="event" className="text-[20px] text-primary" /> Fecha de corte: {dateTime(summary.fiscalStartAt)}
      </div>
      <p className="text-xs text-muted">
        Las ventas anteriores nunca se facturan. No se borran: siguen en las copias de seguridad, sus devoluciones y la cartera de fiados.
      </p>
      <Toggle
        checked={summary.hidePreCutoff}
        onChange={(v) => !busy && me?.owner && toggle(v)}
        label="Ocultar en Ventas, Analítica y Dashboard las ventas anteriores"
        hint={me?.owner ? 'El dueño puede verlas en Ventas con el filtro «Incluir ventas antes de facturación».' : 'Solo el dueño puede cambiarlo.'}
      />
    </Card>
  )
}

// ---------------------------------------------------------------- Modo fiscal (Fase 16A)

const MODES: { value: 'OBLIGADO' | 'A_PEDIDO'; title: string; text: string }[] = [
  {
    value: 'OBLIGADO',
    title: 'Toda venta lleva documento',
    text: 'Para negocios obligados a facturar: si el cliente no pide factura a su nombre, sale a consumidor final.',
  },
  {
    value: 'A_PEDIDO',
    title: 'Factura solo cuando el cliente la pide',
    text: 'Para negocios no obligados que facturan por voluntad: el resto de ventas sale con comprobante interno, que no es factura.',
  },
]

function ModeCard({ mode, onChanged }: { mode: 'OBLIGADO' | 'A_PEDIDO'; onChanged: () => void }) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function choose(value: 'OBLIGADO' | 'A_PEDIDO') {
    if (value === mode) return
    setBusy(true)
    setError('')
    try {
      await api('/einvoice/mode', { body: { mode: value } })
      toast('Modo de facturación actualizado')
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo cambiar')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="space-y-3 p-5">
      <div>
        <div className="text-sm font-semibold">¿Cuándo se factura?</div>
        <p className="text-xs text-muted">
          Si estás obligado a facturar, ninguna venta puede quedar sin documento. Si no sabes cuál es tu caso, pregúntale a tu contador.
        </p>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        {MODES.map((m) => (
          <button
            key={m.value}
            type="button"
            disabled={busy}
            onClick={() => choose(m.value)}
            className={`rounded-xl border p-3 text-left ${mode === m.value ? 'border-primary bg-primary-tint' : 'border-line hover:bg-canvas'}`}
          >
            <span className="flex items-center gap-2 text-sm font-semibold">
              <Icon name={mode === m.value ? 'radio_button_checked' : 'radio_button_unchecked'} className="text-[20px] text-primary" />
              {m.title}
            </span>
            <span className="mt-1 block text-xs text-muted">{m.text}</span>
          </button>
        ))}
      </div>
      {error && <Banner tone="danger">{error}</Banner>}
    </Card>
  )
}

// ---------------------------------------------------------------- Documentos

const FILTERS: { value: '' | DocumentStatus; label: string }[] = [
  { value: '', label: 'Todos' },
  { value: 'RECHAZADO', label: 'Rechazados' },
  { value: 'BLOQUEADO', label: 'Bloqueados' },
  { value: 'DESCONOCIDO', label: 'Por verificar' },
  { value: 'EN_PROCESO', label: 'En proceso' },
  { value: 'ACEPTADO', label: 'Aceptados' },
]

const PROBLEM: DocumentStatus[] = ['RECHAZADO', 'BLOQUEADO', 'DESCONOCIDO']

function Documents({ onChanged, onGo }: { onChanged: () => void; onGo: (tab: string) => void }) {
  const toast = useToast()
  const navigate = useNavigate()
  const [status, setStatus] = useState<'' | DocumentStatus>('')
  const [list, setList] = useState<ElectronicDocumentDto[] | null>(null)
  const [detail, setDetail] = useState<DocumentDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [noteFor, setNoteFor] = useState<{ doc: ElectronicDocumentDto; type: NoteType } | null>(null)

  const load = useCallback(() => {
    setList(null)
    setSelected(new Set())
    api<ElectronicDocumentDto[]>(`/einvoice/documents?status=${status}`).then(setList).catch((e) => setError(e.message))
  }, [status])
  useEffect(load, [load])

  async function open(id: number) {
    setDetail(await api<DocumentDetail>(`/einvoice/documents/${id}`))
  }

  async function retry(id: number) {
    setBusy(true)
    try {
      const d = await api<ElectronicDocumentDto>(`/einvoice/documents/${id}/retry`, { body: {} })
      toast(d.status === 'ACEPTADO' ? `${d.fullNumber} aceptado` : `${d.fullNumber}: ${DOC_STATUS[d.status].label.toLowerCase()}`, d.status === 'ACEPTADO' ? 'success' : 'danger')
      await open(id)
      load()
      onChanged()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo reintentar', 'danger')
    } finally {
      setBusy(false)
    }
  }

  async function retrySelected() {
    setBusy(true)
    try {
      const r = await api<BulkRetryResult>('/einvoice/documents/retry', { body: { ids: [...selected] } })
      toast(
        r.stillWithProblems === 0 ? `${r.accepted} documento(s) aceptados` : `${r.accepted} aceptados, ${r.stillWithProblems} siguen con problemas`,
        r.stillWithProblems === 0 ? 'success' : 'danger',
      )
      load()
      onChanged()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo reintentar', 'danger')
    } finally {
      setBusy(false)
    }
  }

  const canRetry = (d: ElectronicDocumentDto) => [...PROBLEM, 'EN_PROCESO'].includes(d.status)
  const d = detail?.document

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            type="button"
            onClick={() => setStatus(f.value)}
            className={`h-9 rounded-full px-3.5 text-sm font-semibold ${status === f.value ? 'bg-primary text-white' : 'bg-card text-muted ring-1 ring-line'}`}
          >
            {f.label}
          </button>
        ))}
      </div>
      {error && <Banner>{error}</Banner>}
      {selected.size > 0 && (
        <Card className="flex flex-wrap items-center gap-2 p-3">
          <span className="text-sm font-semibold">{selected.size} seleccionado(s)</span>
          <span className="flex-1" />
          <Button size="sm" loading={busy} onClick={retrySelected}>
            Reintentar seleccionados
          </Button>
        </Card>
      )}
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="receipt_long" title="Sin documentos" text={status ? 'No hay documentos en este estado.' : 'Aquí aparecen los documentos de cada venta, devolución y anulación.'} />
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line-soft">
            {list.map((x) => (
              <li key={x.id} className="flex items-center">
                {canRetry(x) && (
                  <input
                    type="checkbox"
                    aria-label={`Seleccionar ${x.fullNumber}`}
                    checked={selected.has(x.id)}
                    onChange={(e) => {
                      const next = new Set(selected)
                      if (e.target.checked) next.add(x.id)
                      else next.delete(x.id)
                      setSelected(next)
                    }}
                    className="ml-4 accent-[var(--color-primary)]"
                  />
                )}
                <button type="button" onClick={() => open(x.id)} className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left hover:bg-canvas">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">
                      {x.documentLabel} {x.fullNumber}
                      {x.test && ' · prueba'}
                    </div>
                    <div className="truncate text-xs text-muted">
                      {x.saleNumber ? `Venta #${x.saleNumber} · ` : x.receiptNumber ? `Recepción #${x.receiptNumber} · ` : ''}
                      {x.customerName} · {money(x.total)}
                      {x.help ? ` · ${x.help.title}` : x.lastErrorMessage && PROBLEM.includes(x.status) ? ` · ${x.lastErrorMessage}` : ''}
                    </div>
                  </div>
                  <Chip tone={DOC_STATUS[x.status].tone}>{DOC_STATUS[x.status].label}</Chip>
                </button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Modal
        open={!!d}
        wide
        title={d ? `${d.documentLabel} ${d.fullNumber}` : ''}
        onClose={() => setDetail(null)}
        footer={
          d && canRetry(d) ? (
            <Button className="w-full" loading={busy} onClick={() => retry(d.id)}>
              {d.status === 'DESCONOCIDO' || d.status === 'EN_PROCESO' ? 'Consultar estado' : 'Reintentar con los datos corregidos'}
            </Button>
          ) : d && d.status === 'ACEPTADO' && !d.test && !d.superseded && ['FACTURA', 'POS_ELECTRONICO', 'DOCUMENTO_SOPORTE'].includes(d.documentType) ? (
            <div className="flex w-full flex-wrap gap-2">
              {d.documentType === 'DOCUMENTO_SOPORTE' ? (
                <Button variant="secondary" className="flex-1" icon="edit_note" onClick={() => setNoteFor({ doc: d, type: 'NOTA_AJUSTE_SOPORTE' })}>
                  Nota de ajuste
                </Button>
              ) : (
                <>
                  <Button variant="secondary" className="flex-1" icon="remove_circle" onClick={() => setNoteFor({ doc: d, type: 'NOTA_CREDITO' })}>
                    Nota crédito
                  </Button>
                  <Button variant="secondary" className="flex-1" icon="add_circle" onClick={() => setNoteFor({ doc: d, type: 'NOTA_DEBITO' })}>
                    Nota débito
                  </Button>
                </>
              )}
            </div>
          ) : undefined
        }
      >
        {d && detail && (
          <div className="space-y-4 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Chip tone={DOC_STATUS[d.status].tone}>{DOC_STATUS[d.status].label}</Chip>
              {d.simulated && <Chip tone="warning">Simulado · sin validez</Chip>}
              <Chip>{d.environment === 'PRUEBAS' ? 'Pruebas' : 'Producción'}</Chip>
            </div>
            {d.help && (
              <div className={`space-y-2 rounded-xl p-4 ${d.status === 'DESCONOCIDO' ? 'bg-warning-soft text-warning-ink' : 'bg-danger-soft text-danger-ink'}`}>
                <div className="font-semibold">{d.help.title}</div>
                <p>{d.help.explanation}</p>
                <p>
                  <b>Causa probable:</b> {d.help.cause}
                </p>
                <p>
                  <b>Qué hacer:</b> {d.help.recommendation}
                </p>
                {d.help.fixTab && (
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => {
                      setDetail(null)
                      if (d.help!.fixTab === 'proveedores') navigate(d.supplierId ? `/proveedores/${d.supplierId}` : '/proveedores')
                      else onGo(d.help!.fixTab!)
                    }}
                  >
                    Ir a corregir
                  </Button>
                )}
              </div>
            )}
            {d.lastErrorMessage && PROBLEM.includes(d.status) && (
              <div className="rounded-xl border border-line px-3 py-2 text-xs text-muted">
                Mensaje original: {d.lastErrorCode && <b>{d.lastErrorCode}: </b>}
                {d.lastErrorMessage}
              </div>
            )}
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2">
              <dt className="text-muted">{d.documentType === 'DOCUMENTO_SOPORTE' ? 'Compra' : 'Venta'}</dt>
              <dd>
                {d.saleNumber
                  ? `#${d.saleNumber}`
                  : d.receiptNumber
                    ? `Recepción #${d.receiptNumber}`
                    : d.test
                      ? 'Documento de prueba'
                      : '—'}
              </dd>
              {d.originalNumber && (
                <>
                  <dt className="text-muted">Corrige a</dt>
                  <dd>{d.originalNumber}</dd>
                </>
              )}
              <dt className="text-muted">{d.documentType === 'DOCUMENTO_SOPORTE' ? 'Proveedor (vendedor)' : 'Comprador'}</dt>
              <dd>
                {d.customerName} {d.customerIdNumber ? `(${d.customerIdNumber})` : ''}
              </dd>
              <dt className="text-muted">Total</dt>
              <dd className="num">{money(d.total)}</dd>
              <dt className="text-muted">Fecha</dt>
              <dd>{new Date(d.issueDate).toLocaleString('es-CO')}</dd>
              {d.cufe && (
                <>
                  <dt className="text-muted">CUFE</dt>
                  <dd className="text-xs break-all">{d.cufe}</dd>
                </>
              )}
              {d.reason && (
                <>
                  <dt className="text-muted">Motivo</dt>
                  <dd>{d.reason}</dd>
                </>
              )}
            </dl>
            {d.remaining != null && detail.notes.length > 0 && (
              <div className="rounded-xl bg-canvas px-3 py-2 text-xs">
                Valor vigente después de las notas: <b className="num">{money(d.remaining)}</b>
              </div>
            )}
            {detail.notes.length > 0 && (
              <div>
                <div className="mb-1 font-semibold">Notas</div>
                {detail.notes.map((n) => (
                  <button key={n.id} type="button" onClick={() => open(n.id)} className="flex w-full items-center justify-between rounded-lg px-2 py-1.5 hover:bg-canvas">
                    <span>
                      {n.documentLabel} {n.fullNumber} · {money(n.total)}
                    </span>
                    <Chip tone={DOC_STATUS[n.status].tone}>{DOC_STATUS[n.status].label}</Chip>
                  </button>
                ))}
              </div>
            )}
            <div>
              <div className="mb-1 font-semibold">Historial</div>
              <ol className="space-y-2 border-l border-line pl-4">
                {detail.events.map((e, i) => (
                  <li key={i}>
                    <div className="text-xs text-muted">
                      {new Date(e.createdAt).toLocaleString('es-CO')} · {e.userName}
                    </div>
                    <div>
                      <b>{e.type.replace(/_/g, ' ').toLowerCase()}</b>
                      {e.message ? ` — ${e.message}` : ''}
                    </div>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        )}
      </Modal>
      {noteFor && (
        <NoteModal
          doc={noteFor.doc}
          type={noteFor.type}
          onClose={() => setNoteFor(null)}
          onDone={async (n) => {
            setNoteFor(null)
            toast(`${n.documentLabel} ${n.fullNumber}: ${DOC_STATUS[n.status].label.toLowerCase()}`, n.status === 'ACEPTADO' ? 'success' : 'danger')
            await open(noteFor.doc.id)
            load()
            onChanged()
          }}
        />
      )}
    </div>
  )
}

const NOTE_TITLES: Record<NoteType, string> = {
  NOTA_CREDITO: 'Nota crédito',
  NOTA_DEBITO: 'Nota débito',
  NOTA_AJUSTE_SOPORTE: 'Nota de ajuste al documento soporte',
}

/** Fase 16E: nota crédito o débito manual, o nota de ajuste al documento soporte, con concepto DIAN. */
function NoteModal({ doc, type, onClose, onDone }: { doc: ElectronicDocumentDto; type: NoteType; onClose: () => void; onDone: (n: ElectronicDocumentDto) => void }) {
  const [concepts, setConcepts] = useState<NoteConcepts | null>(null)
  const [methods, setMethods] = useState<string[]>([])
  const [f, setF] = useState({ concept: '', description: '', amount: '', taxRate: type === 'NOTA_AJUSTE_SOPORTE' ? '0' : '19', reason: '', settlement: doc.paymentForm === 'CREDITO' ? 'FIADO' : '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const support = type === 'NOTA_AJUSTE_SOPORTE'

  useEffect(() => {
    api<NoteConcepts>('/einvoice/note-concepts').then(setConcepts).catch(() => {})
    api<StoreSettings>('/settings/pos').then((s) => setMethods(s.paymentMethods.split(',').filter(Boolean))).catch(() => {})
  }, [])

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const n = await api<ElectronicDocumentDto>(`/einvoice/documents/${doc.id}/notes`, {
        body: {
          type,
          concept: f.concept,
          description: f.description,
          amount: Number(f.amount.replace(/\D/g, '')),
          taxRate: Number(f.taxRate),
          reason: f.reason,
          settlement: support ? undefined : f.settlement,
        },
      })
      onDone(n)
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      } else setError('No se pudo crear la nota')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      title={`${NOTE_TITLES[type]} sobre ${doc.fullNumber}`}
      onClose={onClose}
      footer={
        <Button className="w-full" loading={saving} onClick={save}>
          Emitir {NOTE_TITLES[type].toLowerCase()}
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted">
          {type === 'NOTA_DEBITO'
            ? 'Suma al valor del documento: intereses de mora, gastos por cobrar o un mayor valor.'
            : `Rebaja el valor del documento. Queda: ${money(doc.remaining ?? doc.total)}.`}{' '}
          Para devoluciones de productos o anular la venta, hazlo desde Ventas.
        </p>
        <SelectField label="Concepto" value={f.concept} onChange={(e) => setF({ ...f, concept: e.target.value })} error={fields.concept}>
          <option value="">Elige…</option>
          {(concepts?.[type] ?? []).map((c) => (
            <option key={c.code} value={c.code}>
              {c.label}
            </option>
          ))}
        </SelectField>
        <Field label="Descripción (sale en la nota)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} error={fields.description} />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Valor (con IVA)" inputMode="numeric" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} error={fields.amount} />
          <SelectField label="IVA" value={f.taxRate} disabled={support} onChange={(e) => setF({ ...f, taxRate: e.target.value })} error={fields.taxRate}>
            <option value="0">0 %</option>
            <option value="5">5 %</option>
            <option value="19">19 %</option>
          </SelectField>
        </div>
        {!support && (
          <SelectField
            label="¿Cómo se arregla?"
            value={f.settlement}
            onChange={(e) => setF({ ...f, settlement: e.target.value })}
            error={fields.settlement}
            hint={
              f.settlement === 'FIADO'
                ? type === 'NOTA_DEBITO'
                  ? 'Suma a la deuda del cliente.'
                  : 'Rebaja la deuda del cliente.'
                : 'Si entra o sale efectivo de la caja, regístralo también como movimiento de caja.'
            }
          >
            <option value="">Elige…</option>
            {doc.customerIdNumber && doc.customerIdNumber !== '222222222222' && <option value="FIADO">Cuenta del cliente (fiado)</option>}
            {methods
              .filter((m) => m !== 'FIADO')
              .map((m) => (
                <option key={m} value={m}>
                  {METHOD_LABELS[m] ?? m}
                </option>
              ))}
          </SelectField>
        )}
        <TextArea label="Motivo" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} error={fields.reason} />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------- Datos fiscales

function ProfileForm({ catalogs, onSaved }: { catalogs: FiscalCatalogs; onSaved: () => void }) {
  const toast = useToast()
  const [p, setP] = useState<FiscalProfile | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api<FiscalProfile>('/fiscal/profile').then(setP).catch((e) => setError(e.message))
  }, [])

  if (!p) return error ? <Banner>{error}</Banner> : <Spinner />
  const set = <K extends keyof FiscalProfile>(k: K, v: FiscalProfile[K]) => setP({ ...p, [k]: v })
  const expected = p.idType === 'NIT' ? nitDv(p.idNumber ?? '') : ''

  // Se escribe el nombre o el código; al elegir de la lista queda el código DANE y se llenan ciudad y departamento.
  function pickMunicipality(value: string) {
    const m = MUNICIPALITIES.find(([c]) => c === value)
    setP({ ...p!, municipalityCode: value, ...(m ? { municipalityName: m[1], departmentName: m[2] } : {}) })
  }
  const municipality = MUNICIPALITIES.find(([c]) => c === p.municipalityCode)
  const natural = p.personType === 'NATURAL'

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const saved = await api<FiscalProfile>('/fiscal/profile', { method: 'PUT', body: { ...p, idNumber: nitDigits(p!.idNumber ?? '') } })
      setP(saved)
      toast('Datos fiscales guardados')
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-4">
      {error && <Banner>{error}</Banner>}
      <Card className="space-y-4 p-5">
        <h2 className="font-semibold">Identificación del negocio</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField label="Tipo de persona" value={p.personType ?? ''} onChange={(e) => set('personType', (e.target.value || null) as FiscalProfile['personType'])} error={fields.personType}>
            <option value="">Elige…</option>
            <option value="NATURAL">Persona natural</option>
            <option value="JURIDICA">Persona jurídica (empresa)</option>
          </SelectField>
          <SelectField label="Tipo de documento" value={p.idType} onChange={(e) => set('idType', e.target.value)} error={fields.idType}>
            {catalogs.idTypes.filter((o) => ['NIT', 'CC', 'CE', 'PP', 'NIT_EXT'].includes(o.code)).map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </SelectField>
          <Field label="Número" inputMode="numeric" value={p.idNumber ?? ''} onChange={(e) => set('idNumber', e.target.value)} error={fields.idNumber} />
          {p.idType === 'NIT' && (
            <Field
              label="Dígito de verificación"
              inputMode="numeric"
              maxLength={1}
              value={p.dv ?? ''}
              onChange={(e) => set('dv', e.target.value.replace(/\D/g, ''))}
              error={fields.dv}
              hint={expected ? `Para este NIT debería ser ${expected}` : undefined}
            />
          )}
          {natural ? (
            <>
              <Field label="Nombres" value={p.firstNames ?? ''} onChange={(e) => set('firstNames', e.target.value)} error={fields.firstNames} hint="Como aparecen en el RUT." />
              <Field label="Apellidos" value={p.lastNames ?? ''} onChange={(e) => set('lastNames', e.target.value)} error={fields.lastNames} />
            </>
          ) : (
            <Field label="Razón social" value={p.legalName ?? ''} onChange={(e) => set('legalName', e.target.value)} error={fields.legalName} hint="Como aparece en el RUT." />
          )}
          <Field label="Nombre comercial" value={p.tradeName ?? ''} onChange={(e) => set('tradeName', e.target.value)} error={fields.tradeName} />
          <Field
            label="Matrícula mercantil (opcional)"
            value={p.registrationCode ?? ''}
            onChange={(e) => set('registrationCode', e.target.value.trim())}
            error={fields.registrationCode}
            hint="Número de registro en la Cámara de Comercio, si tienes."
          />
        </div>
      </Card>

      <Card className="space-y-4 p-5">
        <h2 className="font-semibold">Información tributaria</h2>
        <SelectField
          label="¿Es responsable de IVA?"
          value={p.ivaResponsible === null ? '' : p.ivaResponsible ? 'SI' : 'NO'}
          onChange={(e) => set('ivaResponsible', e.target.value === '' ? null : e.target.value === 'SI')}
          error={fields.ivaResponsible}
          hint="Está en tu RUT. Si tienes dudas, pregúntale a tu contador."
        >
          <option value="">Elige…</option>
          <option value="SI">Sí, responsable de IVA</option>
          <option value="NO">No responsable de IVA</option>
        </SelectField>
        <div>
          <div className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">Responsabilidades fiscales (casilla 53 del RUT)</div>
          <div className="grid gap-2 sm:grid-cols-2">
            {catalogs.responsibilities.map((o) => {
              const on = p.responsibilities.includes(o.code)
              return (
                <label key={o.code} className={`flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2.5 text-sm ${on ? 'border-primary bg-primary-tint' : 'border-line'}`}>
                  <input
                    type="checkbox"
                    checked={on}
                    onChange={() => set('responsibilities', on ? p.responsibilities.filter((x) => x !== o.code) : [...p.responsibilities, o.code])}
                    className="accent-[var(--color-primary)]"
                  />
                  {o.label}
                </label>
              )
            })}
          </div>
          {fields.responsibilities && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.responsibilities}</p>}
        </div>
        <Field label="Actividad económica principal (CIIU)" inputMode="numeric" maxLength={4} placeholder="Ej: 4711" value={p.ciiu ?? ''} onChange={(e) => set('ciiu', e.target.value)} error={fields.ciiu} hint="Casilla 46 del RUT." />
      </Card>

      <Card className="space-y-4 p-5">
        <h2 className="font-semibold">Ubicación y contacto</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Dirección" value={p.address ?? ''} onChange={(e) => set('address', e.target.value)} error={fields.address} className="sm:col-span-2" />
          <Field
            label="Municipio"
            list="municipios-dane"
            placeholder="Escribe el nombre o el código DANE"
            value={p.municipalityCode ?? ''}
            onChange={(e) => pickMunicipality(e.target.value.trim())}
            error={fields.municipalityCode || (p.municipalityCode && !municipality ? 'Elige un municipio de la lista' : undefined)}
            hint={municipality ? `Código DANE ${municipality[0]}: ${municipality[1]} (${municipality[2]})` : 'Elige de la lista para que quede el código DANE.'}
          />
          <datalist id="municipios-dane">
            {MUNICIPALITIES.map(([c, n, d]) => (
              <option key={c} value={c}>{`${n} (${d})`}</option>
            ))}
          </datalist>
          <Field label="Ciudad" value={p.municipalityName ?? ''} readOnly={!!municipality} onChange={(e) => set('municipalityName', e.target.value)} error={fields.municipalityName} />
          <Field label="Departamento" value={p.departmentName ?? ''} readOnly={!!municipality} onChange={(e) => set('departmentName', e.target.value)} error={fields.departmentName} />
          <Field label="Código postal (opcional)" inputMode="numeric" maxLength={6} value={p.postalCode ?? ''} onChange={(e) => set('postalCode', e.target.value)} error={fields.postalCode} />
          <Field label="Correo de facturación" type="email" value={p.invoicingEmail ?? ''} onChange={(e) => set('invoicingEmail', e.target.value)} error={fields.invoicingEmail} />
          <Field label="Teléfono" type="tel" value={p.phone ?? ''} onChange={(e) => set('phone', e.target.value)} error={fields.phone} />
        </div>
      </Card>

      <Card className="space-y-2 p-5">
        <h2 className="font-semibold">Habilitación ante la DIAN</h2>
        <div className="rounded-xl border border-line bg-canvas p-3 text-sm">
          <div className="font-semibold">Documentos para activar la facturación</div>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-muted">
            <li>RUT actualizado (los datos de arriba deben coincidir con él).</li>
            <li>Certificado de Cámara de Comercio de máximo 30 días, si eres empresa o tienes matrícula mercantil.</li>
            <li>Cédula del representante legal (o la tuya, si eres persona natural).</li>
          </ul>
          <p className="mt-1 text-muted">Con ellos se tramita el certificado de firma digital, que es uno por cada NIT.</p>
        </div>
        <Toggle
          checked={p.registeredAsIssuer}
          onChange={(v) => set('registeredAsIssuer', v)}
          label="Ya me inscribí como facturador electrónico"
          hint="Se hace en el portal de la DIAN con tu usuario."
        />
        {p.registeredAsIssuer && (
          <Field label="Fecha de inscripción" type="date" value={p.issuerRegisteredOn ?? ''} onChange={(e) => set('issuerRegisteredOn', e.target.value || null)} />
        )}
        <Toggle
          checked={p.providerAuthorized}
          onChange={(v) => set('providerAuthorized', v)}
          label="Ya autoricé al proveedor tecnológico"
          hint="En el portal de la DIAN eliges el proveedor que transmitirá tus documentos."
        />
        <Field label="Proveedor tecnológico" placeholder="El que use Ecomerxio POS" value={p.providerName ?? ''} onChange={(e) => set('providerName', e.target.value)} error={fields.providerName} />
        <SelectField label="Ambiente" value={p.environment} onChange={(e) => set('environment', e.target.value as FiscalProfile['environment'])} error={fields.environment} hint="Empieza en pruebas. Producción emite documentos reales.">
          <option value="PRUEBAS">Pruebas (habilitación)</option>
          <option value="PRODUCCION">Producción</option>
        </SelectField>
      </Card>

      <div className="flex justify-end">
        <Button icon="save" loading={saving} onClick={save}>
          Guardar datos fiscales
        </Button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- Resoluciones

const STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  VIGENTE: { label: 'Vigente', tone: 'success' },
  POR_VENCER: { label: 'Por vencer', tone: 'warning' },
  POR_AGOTAR: { label: 'Por agotarse', tone: 'warning' },
  VENCIDA: { label: 'Vencida', tone: 'danger' },
  AGOTADA: { label: 'Agotada', tone: 'danger' },
  NO_INICIA: { label: 'Aún no empieza', tone: 'neutral' },
  INACTIVA: { label: 'Inactiva', tone: 'neutral' },
}

interface ResolutionForm {
  documentType: string
  resolutionNumber: string
  resolutionDate: string
  prefix: string
  rangeFrom: string
  rangeTo: string
  nextNumber: string
  validFrom: string
  validTo: string
  environment: string
  technicalKey: string
  active: boolean
}

const EMPTY_FORM: ResolutionForm = {
  documentType: 'POS_ELECTRONICO', resolutionNumber: '', resolutionDate: '', prefix: '', rangeFrom: '1', rangeTo: '', nextNumber: '',
  validFrom: '', validTo: '', environment: 'PRUEBAS', technicalKey: '', active: true,
}

function Resolutions({ catalogs, onChanged }: { catalogs: FiscalCatalogs; onChanged: () => void }) {
  const toast = useToast()
  const [list, setList] = useState<Resolution[] | null>(null)
  const [editing, setEditing] = useState<{ id: number | null; form: ResolutionForm } | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(() => {
    api<Resolution[]>('/fiscal/resolutions').then(setList).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  function open(r?: Resolution) {
    setFields({})
    setError('')
    setEditing(
      r
        ? {
            id: r.id,
            form: {
              documentType: r.documentType, resolutionNumber: r.resolutionNumber, resolutionDate: r.resolutionDate ?? '', prefix: r.prefix ?? '',
              rangeFrom: String(r.rangeFrom), rangeTo: String(r.rangeTo), nextNumber: String(r.nextNumber), validFrom: r.validFrom,
              validTo: r.validTo, environment: r.environment, technicalKey: '', active: r.active,
            },
          }
        : { id: null, form: EMPTY_FORM },
    )
  }

  async function save() {
    if (!editing) return
    const f = editing.form
    setSaving(true)
    setFields({})
    try {
      const body = {
        documentType: f.documentType, resolutionNumber: f.resolutionNumber, resolutionDate: f.resolutionDate || null,
        prefix: f.prefix.toUpperCase(), rangeFrom: Number(f.rangeFrom) || null, rangeTo: Number(f.rangeTo) || null,
        nextNumber: f.nextNumber ? Number(f.nextNumber) : null, validFrom: f.validFrom || null, validTo: f.validTo || null,
        environment: f.environment, technicalKey: f.technicalKey || null, active: f.active,
      }
      await api(editing.id ? `/fiscal/resolutions/${editing.id}` : '/fiscal/resolutions', { method: editing.id ? 'PUT' : 'POST', body })
      toast(editing.id ? 'Resolución actualizada' : 'Resolución registrada')
      setEditing(null)
      load()
      onChanged()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  if (!list) return error ? <Banner>{error}</Banner> : <Spinner />
  const f = editing?.form
  const setF = (k: keyof ResolutionForm, v: string | boolean) => setEditing(editing && { ...editing, form: { ...editing.form, [k]: v } })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">La resolución de numeración autoriza el prefijo, el rango de números y la fecha de vigencia.</p>
        <Button icon="add" onClick={() => open()}>
          Nueva resolución
        </Button>
      </div>
      {list.length === 0 ? (
        <EmptyState icon="description" title="Sin resoluciones" text="Registra la que te dé la DIAN para el documento POS electrónico o la factura." />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {list.map((r) => (
            <Card key={r.id} className="space-y-3 p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="text-sm font-semibold">{r.documentLabel}</div>
                  <div className="text-xs text-muted">
                    Resolución {r.resolutionNumber} · {r.environment === 'PRUEBAS' ? 'pruebas' : 'producción'}
                  </div>
                </div>
                <Chip tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Chip>
              </div>
              <div className="num text-sm">
                {r.prefix ?? ''}
                {r.rangeFrom} – {r.prefix ?? ''}
                {r.rangeTo} · siguiente <b>{r.prefix ?? ''}{r.nextNumber}</b>
              </div>
              <div>
                <div className="h-1.5 overflow-hidden rounded-full bg-line-soft">
                  <div className={`h-full rounded-full ${r.usedPct >= 80 ? 'bg-warning' : 'bg-primary'}`} style={{ width: `${Math.min(100, r.usedPct)}%` }} />
                </div>
                <div className="mt-1 flex justify-between text-xs text-muted">
                  <span>
                    {r.used} usados · quedan {r.remaining}
                  </span>
                  <span>{r.daysLeft >= 0 ? `vence en ${r.daysLeft} días` : 'vencida'} ({r.validTo})</span>
                </div>
              </div>
              <Button size="sm" variant="secondary" icon="edit" onClick={() => open(r)}>
                Editar
              </Button>
            </Card>
          ))}
        </div>
      )}

      <Modal
        open={!!editing}
        title={editing?.id ? 'Editar resolución' : 'Nueva resolución'}
        onClose={() => setEditing(null)}
        footer={
          <Button className="w-full" loading={saving} onClick={save}>
            Guardar
          </Button>
        }
      >
        {f && (
          <div className="space-y-4">
            {error && <Banner>{error}</Banner>}
            <SelectField label="Tipo de documento" value={f.documentType} onChange={(e) => setF('documentType', e.target.value)} error={fields.documentType}>
              {catalogs.documentTypes.map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </SelectField>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Número de resolución" value={f.resolutionNumber} onChange={(e) => setF('resolutionNumber', e.target.value)} error={fields.resolutionNumber} />
              <Field label="Fecha de la resolución" type="date" value={f.resolutionDate} onChange={(e) => setF('resolutionDate', e.target.value)} />
              <Field label="Prefijo" maxLength={4} placeholder="Ej: POS" value={f.prefix} onChange={(e) => setF('prefix', e.target.value.toUpperCase())} error={fields.prefix} />
              <SelectField label="Ambiente" value={f.environment} onChange={(e) => setF('environment', e.target.value)}>
                <option value="PRUEBAS">Pruebas</option>
                <option value="PRODUCCION">Producción</option>
              </SelectField>
              <Field label="Desde el número" inputMode="numeric" value={f.rangeFrom} onChange={(e) => setF('rangeFrom', e.target.value.replace(/\D/g, ''))} error={fields.rangeFrom} />
              <Field label="Hasta el número" inputMode="numeric" value={f.rangeTo} onChange={(e) => setF('rangeTo', e.target.value.replace(/\D/g, ''))} error={fields.rangeTo} />
              <Field label="Vigente desde" type="date" value={f.validFrom} onChange={(e) => setF('validFrom', e.target.value)} error={fields.validFrom} />
              <Field label="Vigente hasta" type="date" value={f.validTo} onChange={(e) => setF('validTo', e.target.value)} error={fields.validTo} />
            </div>
            <Field
              label="Siguiente número a usar"
              inputMode="numeric"
              placeholder={f.rangeFrom || '1'}
              value={f.nextNumber}
              onChange={(e) => setF('nextNumber', e.target.value.replace(/\D/g, ''))}
              error={fields.nextNumber}
              hint="Déjalo vacío si nunca has usado esta resolución. Si ya facturaste con otro sistema, pon el que sigue."
            />
            <Field
              label="Clave técnica (si el proveedor la pide)"
              placeholder={editing?.id ? 'Déjala vacía para no cambiarla' : ''}
              value={f.technicalKey}
              onChange={(e) => setF('technicalKey', e.target.value)}
            />
            <Toggle checked={f.active} onChange={(v) => setF('active', v)} label="Activa" hint="Solo las activas se usan para numerar." />
          </div>
        )}
      </Modal>
    </div>
  )
}

// ---------------------------------------------------------------- Productos

function ProductTaxes({ onChanged }: { onChanged: () => void }) {
  const toast = useToast()
  const [onlyPending, setOnlyPending] = useState(true)
  const [rows, setRows] = useState<ProductTaxRow[] | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    setRows(null)
    api<ProductTaxRow[]>(`/fiscal/products?pending=${onlyPending}`).then(setRows).catch((e) => setError(e.message))
  }, [onlyPending])
  useEffect(load, [load])

  const visible = useMemo(() => {
    const t = q.trim().toLowerCase()
    return (rows ?? []).filter((r) => !t || r.name.toLowerCase().includes(t) || (r.category ?? '').toLowerCase().includes(t) || (r.sku ?? '').toLowerCase().includes(t))
  }, [rows, q])
  const selectable = visible.filter((r) => !(r.taxRate > 0))

  async function classify(taxClass: 'EXCLUIDO' | 'EXENTO') {
    setBusy(true)
    try {
      const res = await api<{ changed: number; skippedTaxed: number }>('/fiscal/products/classify', { body: { productIds: [...selected], taxClass } })
      toast(`${res.changed} producto(s) marcados como ${taxClass === 'EXCLUIDO' ? 'excluidos' : 'exentos'}`)
      setSelected(new Set())
      load()
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setBusy(false)
    }
  }

  const label = (r: ProductTaxRow) =>
    r.effectiveClass === 'GRAVADO' ? `Gravado ${r.taxName} ${r.taxRate}%` : r.effectiveClass === 'EXENTO' ? 'Exento' : r.effectiveClass === 'EXCLUIDO' ? 'Excluido' : 'Sin clasificar'

  return (
    <div className="space-y-4">
      <Banner tone="primary">
        Los productos con IVA mayor a 0 ya quedan como <b>gravados</b>. Solo hay que decidir los que tienen IVA 0 %: <b>excluido</b> (no causa IVA) o{' '}
        <b>exento</b> (IVA 0 % con derecho a devolución). El sistema no lo adivina: si tienes dudas, pregúntale a tu contador.
      </Banner>
      {error && <Banner>{error}</Banner>}
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Buscar" placeholder="Producto, categoría o código" value={q} onChange={(e) => setQ(e.target.value)} className="min-w-56 flex-1" />
        <Toggle checked={onlyPending} onChange={(v) => (setOnlyPending(v), setSelected(new Set()))} label="Solo sin clasificar" />
      </div>
      {selected.size > 0 && (
        <Card className="flex flex-wrap items-center gap-2 p-3">
          <span className="text-sm font-semibold">{selected.size} seleccionado(s)</span>
          <span className="flex-1" />
          <Button size="sm" variant="secondary" loading={busy} onClick={() => classify('EXCLUIDO')}>
            Marcar excluidos
          </Button>
          <Button size="sm" variant="secondary" loading={busy} onClick={() => classify('EXENTO')}>
            Marcar exentos
          </Button>
        </Card>
      )}
      {!rows ? (
        <Spinner />
      ) : visible.length === 0 ? (
        <EmptyState icon="check_circle" title={onlyPending ? 'Todo clasificado' : 'Sin productos'} text={onlyPending ? 'No hay productos con IVA 0 % sin clasificar.' : undefined} />
      ) : (
        <Card className="overflow-hidden">
          <label className="flex items-center gap-3 border-b border-line-soft px-4 py-2.5 text-xs font-semibold text-muted uppercase">
            <input
              type="checkbox"
              checked={selectable.length > 0 && selectable.every((r) => selected.has(r.id))}
              onChange={(e) => setSelected(e.target.checked ? new Set(selectable.map((r) => r.id)) : new Set())}
              className="accent-[var(--color-primary)]"
            />
            Seleccionar los que se pueden clasificar
          </label>
          <ul className="divide-y divide-line-soft">
            {visible.map((r) => {
              const taxed = r.taxRate > 0
              return (
                <li key={r.id} className="flex items-center gap-3 px-4 py-3">
                  <input
                    type="checkbox"
                    disabled={taxed}
                    checked={selected.has(r.id)}
                    onChange={(e) => {
                      const next = new Set(selected)
                      if (e.target.checked) next.add(r.id)
                      else next.delete(r.id)
                      setSelected(next)
                    }}
                    className="accent-[var(--color-primary)]"
                  />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">{r.name}</div>
                    <div className="text-xs text-muted">
                      {r.category ?? 'Sin categoría'} · {r.unit.toLowerCase()} (código {r.unitCode ?? '—'})
                    </div>
                  </div>
                  <Chip tone={r.pending ? 'warning' : taxed ? 'primary' : 'success'}>{label(r)}</Chip>
                </li>
              )
            })}
          </ul>
        </Card>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- Clientes

function Customers({ catalogs }: { catalogs: FiscalCatalogs }) {
  const toast = useToast()
  const [q, setQ] = useState('')
  const [list, setList] = useState<Customer[] | null>(null)
  const [editing, setEditing] = useState<Customer | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Customer[]>(`/pos/customers?q=${encodeURIComponent(q)}`).then(setList).catch((e) => setError(e.message))
  }, [q])
  useEffect(() => {
    const t = setTimeout(load, 250)
    return () => clearTimeout(t)
  }, [load])

  async function save() {
    if (!editing) return
    setSaving(true)
    setFields({})
    try {
      await api(`/pos/customers/${editing.id}`, {
        method: 'PUT',
        body: {
          name: editing.name, docType: editing.docType || null, docNumber: editing.docNumber || null, phone: editing.phone || null,
          email: editing.email || null, address: editing.address || null, notes: editing.notes || null, dv: editing.dv || null,
          personType: editing.personType || null, invoiceEmail: editing.invoiceEmail || null, fiscalResponsibilities: editing.fiscalResponsibilities ?? [],
          municipalityCode: editing.municipalityCode || null, ivaResponsible: editing.ivaResponsible ?? null,
          invoicePreference: editing.invoicePreference ?? 'PREGUNTAR',
        },
      })
      toast('Cliente actualizado')
      setEditing(null)
      load()
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  const c = editing
  const setC = (patch: Partial<Customer>) => setEditing(c && { ...c, ...patch })
  const expected = c?.docType === 'NIT' ? nitDv(c.docNumber ?? '') : ''
  const cMunicipality = MUNICIPALITIES.find(([code]) => code === c?.municipalityCode)

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">
        A quien no se identifica se le emite como <b>consumidor final</b>. Para facturarle a nombre de un cliente, completa aquí sus datos.
      </p>
      {error && <Banner>{error}</Banner>}
      <Field label="Buscar cliente" placeholder="Nombre, documento o teléfono" value={q} onChange={(e) => setQ(e.target.value)} />
      {!list ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="group" title="Sin clientes" text="Los clientes se crean desde el cobro en la caja." />
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line-soft">
            {list.map((x) => (
              <li key={x.id} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{x.name}</div>
                  <div className="text-xs text-muted">
                    {x.docType ?? 'Sin tipo'} {x.docNumber ?? ''}
                    {x.dv ? `-${x.dv}` : ''}
                    {x.fiscalMissing && x.fiscalMissing.length > 0 && ` · falta: ${x.fiscalMissing.join(', ')}`}
                  </div>
                </div>
                <Chip tone={x.fiscalMissing?.length ? 'warning' : 'success'}>{x.fiscalMissing?.length ? 'Incompleto' : 'Listo'}</Chip>
                <Button size="sm" variant="ghost" icon="edit" onClick={() => (setFields({}), setEditing({ ...x, fiscalResponsibilities: x.fiscalResponsibilities ?? [] }))}>
                  <span className="sr-only">Editar</span>
                </Button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Modal
        open={!!c}
        title="Datos para facturar"
        onClose={() => setEditing(null)}
        footer={
          <Button className="w-full" loading={saving} onClick={save}>
            Guardar
          </Button>
        }
      >
        {c && (
          <div className="space-y-4">
            <Field label="Nombre o razón social" value={c.name} onChange={(e) => setC({ name: e.target.value })} error={fields.name} />
            <SelectField
              label="Factura electrónica a su nombre"
              value={c.invoicePreference ?? 'PREGUNTAR'}
              onChange={(e) => setC({ invoicePreference: e.target.value as InvoicePreference })}
              error={fields.invoicePreference}
              hint={INVOICE_PREFERENCES[c.invoicePreference ?? 'PREGUNTAR'].hint}
            >
              {(Object.keys(INVOICE_PREFERENCES) as InvoicePreference[]).map((k) => (
                <option key={k} value={k}>
                  {INVOICE_PREFERENCES[k].label}
                </option>
              ))}
            </SelectField>
            <div className="grid gap-3 sm:grid-cols-2">
              <SelectField label="Tipo de persona" value={c.personType ?? ''} onChange={(e) => setC({ personType: (e.target.value || null) as Customer['personType'] })} error={fields.personType}>
                <option value="">Elige…</option>
                <option value="NATURAL">Persona natural</option>
                <option value="JURIDICA">Persona jurídica</option>
              </SelectField>
              <SelectField label="Tipo de documento" value={c.docType ?? ''} onChange={(e) => setC({ docType: e.target.value || undefined })} error={fields.docType}>
                <option value="">Elige…</option>
                {catalogs.idTypes.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </SelectField>
              <Field label="Número" inputMode="numeric" value={c.docNumber ?? ''} onChange={(e) => setC({ docNumber: e.target.value })} error={fields.docNumber} />
              {c.docType === 'NIT' && (
                <Field
                  label="Dígito de verificación"
                  inputMode="numeric"
                  maxLength={1}
                  value={c.dv ?? ''}
                  onChange={(e) => setC({ dv: e.target.value.replace(/\D/g, '') })}
                  error={fields.dv}
                  hint={expected ? `Debería ser ${expected}` : undefined}
                />
              )}
            </div>
            <Field label="Correo para enviarle la factura" type="email" value={c.invoiceEmail ?? ''} onChange={(e) => setC({ invoiceEmail: e.target.value })} error={fields.invoiceEmail} hint={c.email ? `Si lo dejas vacío se usa ${c.email}` : undefined} />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Dirección (opcional)" value={c.address ?? ''} onChange={(e) => setC({ address: e.target.value })} error={fields.address} />
              <Field
                label="Municipio (opcional)"
                list="municipios-dane-clientes"
                placeholder="Nombre o código DANE"
                value={c.municipalityCode ?? ''}
                onChange={(e) => setC({ municipalityCode: e.target.value.trim() })}
                error={fields.municipalityCode || (c.municipalityCode && !cMunicipality ? 'Elige un municipio de la lista' : undefined)}
                hint={cMunicipality ? `${cMunicipality[1]} (${cMunicipality[2]})` : undefined}
              />
              <datalist id="municipios-dane-clientes">
                {MUNICIPALITIES.map(([code, n, d]) => (
                  <option key={code} value={code}>{`${n} (${d})`}</option>
                ))}
              </datalist>
            </div>
            {c.personType === 'JURIDICA' && (
              <SelectField
                label="¿Es responsable de IVA?"
                value={c.ivaResponsible == null ? '' : c.ivaResponsible ? 'SI' : 'NO'}
                onChange={(e) => setC({ ivaResponsible: e.target.value === '' ? null : e.target.value === 'SI' })}
                error={fields.ivaResponsible}
                hint="Está en el RUT del cliente. Si no lo sabes, déjalo sin elegir."
              >
                <option value="">No sé</option>
                <option value="SI">Sí, responsable de IVA</option>
                <option value="NO">No responsable de IVA</option>
              </SelectField>
            )}
            {c.personType === 'JURIDICA' && (
              <div>
                <div className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">Responsabilidades fiscales</div>
                <div className="grid gap-2">
                  {catalogs.responsibilities.map((o) => {
                    const on = (c.fiscalResponsibilities ?? []).includes(o.code)
                    return (
                      <label key={o.code} className="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={on}
                          onChange={() =>
                            setC({ fiscalResponsibilities: on ? (c.fiscalResponsibilities ?? []).filter((x) => x !== o.code) : [...(c.fiscalResponsibilities ?? []), o.code] })
                          }
                          className="accent-[var(--color-primary)]"
                        />
                        {o.label}
                      </label>
                    )
                  })}
                </div>
                {fields.fiscalResponsibilities && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.fiscalResponsibilities}</p>}
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  )
}
