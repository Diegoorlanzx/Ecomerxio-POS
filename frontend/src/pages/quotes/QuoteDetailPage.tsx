import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, Field, Icon, Modal, Spinner, TextArea, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { scaleFor, type CartLine } from '../../lib/cart'
import { dateTime, money } from '../../lib/format'
import { appUrl } from '../../lib/host'
import { publicApi } from '../../lib/onlineTypes'
import type { Receipt, SaleDto, StoreSettings } from '../../lib/posTypes'
import { dayLabel, QUOTE_STATUS, type Issue, type PublicQuote, type Quote, type Validation } from '../../lib/quoteTypes'
import CheckoutModal, { type CheckoutPayload } from '../pos/CheckoutModal'
import { PrintArea, ReceiptView } from '../pos/Receipt'
import QuoteDocument from './QuoteDocument'

const SEVERITY: Record<Issue['severity'], { icon: string; cls: string; label: string }> = {
  ERROR: { icon: 'error', cls: 'border-danger/40 bg-danger-soft text-danger-ink', label: 'Error' },
  WARNING: { icon: 'warning', cls: 'border-warning/40 bg-warning-soft text-warning-ink', label: 'Advertencia' },
  INFO: { icon: 'info', cls: 'border-line bg-line-soft text-muted', label: 'Info' },
}

const FIX_LABEL: Record<string, string> = { CLIENTE: 'Corregir cliente', LINEAS: 'Corregir líneas', FACTURACION: 'Ir a facturación', PRODUCTOS: 'Clasificar productos' }

const wa = (n?: string | null) => {
  const d = (n ?? '').replace(/\D/g, '')
  return d.length === 10 ? `57${d}` : d
}

export default function QuoteDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { me, can } = useAuth()
  const toast = useToast()
  const [q, setQ] = useState<Quote | null>(null)
  const [doc, setDoc] = useState<PublicQuote | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<'decision' | 'cancel' | 'approve' | null>(null)
  const [charge, setCharge] = useState<StoreSettings | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const currency = me?.tenant.currency ?? 'COP'

  const load = useCallback(() => {
    api<Quote>(`/quotes/${id}`)
      .then((x) => {
        setQ(x)
        setError('')
        publicApi<PublicQuote>(`/quotes/${x.publicToken}`).then(setDoc).catch(() => setDoc(null))
      })
      .catch((e) => setError(e.message))
  }, [id])
  useEffect(load, [load])

  async function act<T>(path: string, body: unknown, msg?: string): Promise<T | null> {
    setBusy(true)
    setError('')
    try {
      const r = await api<T>(`/quotes/${id}/${path}`, { method: 'POST', body: body ?? {} })
      if (msg) toast(msg)
      load()
      return r
    } catch (e) {
      setError(e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo completar')
      return null
    } finally {
      setBusy(false)
    }
  }

  if (!q) return error ? <Banner>{error}</Banner> : <Spinner />

  const isQuote = q.kind === 'COTIZACION'
  const st = QUOTE_STATUS[q.status] ?? { label: q.status, tone: 'neutral' as const }
  const link = appUrl(`/c/${q.publicToken}`)
  const editable = isQuote ? ['BORRADOR', 'ENVIADA'].includes(q.status) : ['BORRADOR', 'APROBADA'].includes(q.status)
  const canCreate = can('COTIZACIONES_CREAR')
  const v = q.validation

  function share() {
    const text = isQuote
      ? `Hola ${q!.customerName ?? ''}, te comparto la cotización ${q!.code} de ${me?.tenant.name} por ${money(q!.total, currency)}${
          q!.validUntil ? `, válida hasta el ${dayLabel(q!.validUntil)}` : ''
        }. Puedes verla y aceptarla aquí: ${link}`
      : `Hola ${q!.customerName ?? ''}, antes de expedir tu factura por ${money(q!.total, currency)}, por favor revisa que tus datos estén correctos y confírmalo aquí: ${link}`
    window.open(`https://wa.me/${wa(q!.customerPhone)}?text=${encodeURIComponent(text)}`, '_blank', 'noopener')
    if (canCreate && (isQuote ? q!.status === 'BORRADOR' : !q!.sentAt)) void act('sent', {})
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(link)
      toast('Enlace copiado')
    } catch {
      window.prompt('Copia el enlace', link)
    }
  }

  async function toPrefactura() {
    const pf = await act<Quote>('prefactura', {}, 'Prefactura creada: revisa los datos')
    if (pf) navigate(`/cotizaciones/${pf.id}`)
  }

  async function duplicate() {
    const c = await act<Quote>('duplicate', {}, 'Copia creada')
    if (c) navigate(`/cotizaciones/${c.id}/editar`)
  }

  async function startCharge() {
    try {
      setCharge(await api<StoreSettings>('/settings/pos'))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  async function submitCharge(p: CheckoutPayload): Promise<string | null> {
    try {
      const sale = await api<SaleDto>(`/quotes/${id}/charge`, { body: { payments: p.payments, electronicInvoice: !!p.electronicInvoice } })
      setCharge(null)
      try {
        setReceipt(await api<Receipt>(`/pos/sales/${sale.id}/receipt`))
      } catch {
        /* sin comprobante: igual quedó cobrado */
      }
      toast(`${q!.code} cobrada: venta #${sale.number}`)
      load()
      return null
    } catch (e) {
      return e instanceof ApiError ? (Object.values(e.fields)[0] ?? e.message) : 'No se pudo cobrar'
    }
  }

  const cartLines: CartLine[] = q.lines.map((l, i) => ({
    key: String(l.id ?? i),
    productId: l.productId ?? 0,
    presentationId: l.presentationId ?? 0,
    name: l.description,
    presentationName: l.presentationName ?? '',
    factor: Number(l.factor),
    quantity: Number(l.quantity),
    listPrice: Number(l.unitPrice),
    unitPrice: Number(l.unitPrice),
    minPrice: 0,
    discountPct: Number(l.discountPct),
    taxRate: Number(l.taxRate),
    fixedTax: Number(l.fixedTax),
    unit: 'UNIDAD',
  }))

  if (receipt) {
    return (
      <Modal
        open
        title={`${q.code} cobrada`}
        onClose={() => setReceipt(null)}
        footer={
          <>
            <Button variant="secondary" icon="print" className="flex-1" onClick={() => window.print()}>
              Imprimir
            </Button>
            <Button className="flex-1" onClick={() => setReceipt(null)}>
              Listo
            </Button>
          </>
        }
      >
        <div className="rounded-xl bg-line-soft p-3">
          <ReceiptView r={receipt} />
        </div>
        <PrintArea r={receipt} />
      </Modal>
    )
  }

  if (charge) {
    return (
      <CheckoutModal
        lines={cartLines}
        settings={charge}
        promotions={[]}
        categoryParents={new Map()}
        currency={currency}
        scale={scaleFor(currency)}
        title={`Cobrar ${q.code}`}
        offline={false}
        fixedPrices
        fixedSaleDiscount={Number(q.saleDiscountAmount)}
        initialInvoice={!isQuote && !!me?.invoicingEnabled}
        initialCustomer={q.customerId ? { customerId: q.customerId, name: q.customerName ?? undefined, docNumber: q.customerDoc ?? undefined } : undefined}
        onClose={() => setCharge(null)}
        onSubmit={submitCharge}
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <Link to={`/cotizaciones${isQuote ? '' : '?tipo=prefacturas'}`} className="flex h-10 w-10 items-center justify-center rounded-xl text-muted hover:bg-line-soft" aria-label="Volver">
          <Icon name="arrow_back" />
        </Link>
        <h1 className="num text-2xl font-bold tracking-tight">{q.code}</h1>
        <Chip tone={st.tone}>{st.label}</Chip>
        <span className="flex-1" />
        <Button variant="secondary" icon="print" onClick={() => window.print()}>
          Imprimir / PDF
        </Button>
      </div>
      {error && <Banner>{error}</Banner>}

      <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
        <div className="min-w-0">{doc ? <QuoteDocument q={doc} /> : <Spinner />}</div>

        <aside className="space-y-3 print:hidden">
          {/* ------------------------------ Validación (prefactura) */}
          {!isQuote && v && <ValidationCard v={v} quoteId={q.id} approved={q.status === 'APROBADA'} />}

          {/* ------------------------------ Acciones */}
          <Card className="space-y-2 p-4">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Acciones</div>
            {editable && canCreate && (
              <Button variant="secondary" icon="edit" className="w-full" onClick={() => navigate(`/cotizaciones/${q.id}/editar`)}>
                {isQuote ? 'Editar' : 'Corregir datos'}
              </Button>
            )}
            {editable && (
              <>
                <Button variant="soft" icon="chat" className="w-full" onClick={share}>
                  {isQuote ? 'Enviar por WhatsApp' : 'Pedir al cliente que confirme'}
                </Button>
                <Button variant="ghost" icon="link" className="w-full" onClick={copyLink}>
                  Copiar enlace para el cliente
                </Button>
              </>
            )}
            {isQuote && ['BORRADOR', 'ENVIADA'].includes(q.status) && canCreate && (
              <Button variant="secondary" icon="how_to_reg" className="w-full" onClick={() => setDialog('decision')}>
                Registrar respuesta del cliente
              </Button>
            )}
            {isQuote && ['ACEPTADA', 'ENVIADA', 'BORRADOR'].includes(q.status) && canCreate && (
              <Button icon="fact_check" className="w-full" onClick={toPrefactura} loading={busy}>
                Pasar a prefactura
              </Button>
            )}
            {isQuote && q.status === 'ACEPTADA' && can('POS_VENDER') && (
              <Button variant="secondary" icon="point_of_sale" className="w-full" onClick={startCharge}>
                Cobrar sin revisar datos
              </Button>
            )}
            {!isQuote && q.status === 'BORRADOR' && can('PREFACTURAS_APROBAR') && (
              <Button icon="task_alt" className="w-full" disabled={!v || v.errors > 0} onClick={() => setDialog('approve')}>
                Aprobar prefactura
              </Button>
            )}
            {!isQuote && q.status === 'BORRADOR' && !can('PREFACTURAS_APROBAR') && (
              <p className="text-xs text-muted">Pide a alguien con permiso de aprobar prefacturas que la revise.</p>
            )}
            {!isQuote && q.status === 'APROBADA' && can('POS_VENDER') && (
              <Button icon="point_of_sale" className="w-full" onClick={startCharge}>
                Cobrar y facturar
              </Button>
            )}
            {canCreate && ['VENCIDA', 'RECHAZADA', 'ANULADA', 'COBRADA', 'FACTURADA', 'CONVERTIDA'].includes(q.status) && isQuote && (
              <Button variant="secondary" icon="content_copy" className="w-full" onClick={duplicate} loading={busy}>
                Copiar como nueva cotización
              </Button>
            )}
            {canCreate && !['ANULADA', 'COBRADA', 'FACTURADA', 'CONVERTIDA'].includes(q.status) && (
              <Button variant="ghost" icon="cancel" className="w-full text-danger-ink" onClick={() => setDialog('cancel')}>
                Anular
              </Button>
            )}
          </Card>

          {/* ------------------------------ Historia */}
          <Card className="space-y-2 p-4 text-sm">
            <div className="text-xs font-bold tracking-wide text-muted uppercase">Historia</div>
            <Event icon="add_circle" text={`Creada por ${q.createdBy ?? '—'}`} at={q.createdAt} />
            {q.sourceCode && (
              <Link to={`/cotizaciones/${q.sourceId}`} className="flex items-center gap-2 text-primary">
                <Icon name="request_quote" className="text-[18px]" /> Viene de la cotización {q.sourceCode}
              </Link>
            )}
            {q.sentAt && <Event icon="send" text="Enviada al cliente" at={q.sentAt} />}
            {q.decidedAt && (
              <Event
                icon={q.status === 'RECHAZADA' ? 'thumb_down' : 'thumb_up'}
                text={`${q.status === 'RECHAZADA' ? 'Rechazada' : 'Aceptada'} por ${q.decidedBy}${q.decisionNote ? `: "${q.decisionNote}"` : ''}`}
                at={q.decidedAt}
              />
            )}
            {q.clientCheckAt && (
              <Event
                icon={q.clientCheck === 'CONFIRMADO' ? 'verified' : 'report'}
                text={q.clientCheck === 'CONFIRMADO' ? 'El cliente confirmó sus datos' : `El cliente reportó: "${q.clientCheckNote}"`}
                at={q.clientCheckAt}
              />
            )}
            {q.prefacturaCode && (
              <Link to={`/cotizaciones/${q.prefacturaId}`} className="flex items-center gap-2 text-primary">
                <Icon name="fact_check" className="text-[18px]" /> Prefactura {q.prefacturaCode}
              </Link>
            )}
            {q.approvedAt && <Event icon="task_alt" text={`Aprobada por ${q.approvedBy}`} at={q.approvedAt} />}
            {q.approvedWarnings.length > 0 && (
              <ul className="ml-6 list-disc text-xs text-muted">
                {q.approvedWarnings.map((w) => (
                  <li key={w}>Aceptó: {w}</li>
                ))}
              </ul>
            )}
            {q.saleNumber && <Event icon="point_of_sale" text={`Cobrada en la venta #${q.saleNumber}`} at={q.updatedAt} />}
            {q.cancelledAt && <Event icon="cancel" text={`Anulada: ${q.cancelReason}`} at={q.cancelledAt} />}
          </Card>
        </aside>
      </div>

      {dialog === 'decision' && <DecisionDialog onClose={() => setDialog(null)} onSave={(b) => act('decision', b, 'Respuesta registrada').then((r) => r && setDialog(null))} busy={busy} />}
      {dialog === 'cancel' && <CancelDialog onClose={() => setDialog(null)} onSave={(reason) => act('cancel', { reason }, 'Documento anulado').then((r) => r && setDialog(null))} busy={busy} />}
      {dialog === 'approve' && v && (
        <ApproveDialog v={v} onClose={() => setDialog(null)} busy={busy} onSave={() => act('approve', { acceptWarnings: true }, 'Prefactura aprobada: lista para cobrar').then((r) => r && setDialog(null))} />
      )}
    </div>
  )
}

function Event({ icon, text, at }: { icon: string; text: string; at?: string | null }) {
  return (
    <div className="flex items-start gap-2">
      <Icon name={icon} className="mt-0.5 text-[18px] text-muted" />
      <div className="min-w-0 flex-1">
        <div>{text}</div>
        {at && <div className="text-xs text-muted">{dateTime(at)}</div>}
      </div>
    </div>
  )
}

function ValidationCard({ v, quoteId, approved }: { v: Validation; quoteId: number; approved: boolean }) {
  const navigate = useNavigate()
  const go = (fix?: string | null) => {
    if (fix === 'CLIENTE' || fix === 'LINEAS') navigate(`/cotizaciones/${quoteId}/editar`)
    else if (fix === 'FACTURACION') navigate('/facturacion')
    else if (fix === 'PRODUCTOS') navigate('/facturacion?tab=productos')
  }
  return (
    <Card className="space-y-2 p-4">
      <div className="flex items-center gap-2">
        <Icon name={v.errors ? 'gpp_bad' : 'verified_user'} className={`text-[22px] ${v.errors ? 'text-danger' : 'text-success'}`} fill />
        <div className="flex-1">
          <div className="font-semibold">
            {v.errors ? `${v.errors} ${v.errors === 1 ? 'error' : 'errores'} por corregir` : approved ? 'Aprobada: lista para cobrar' : 'Lista para aprobar'}
          </div>
          <div className="text-xs text-muted">
            {v.warnings} advertencia{v.warnings === 1 ? '' : 's'} para revisar
          </div>
        </div>
      </div>
      <ul className="space-y-1.5">
        {v.issues.map((i) => {
          const s = SEVERITY[i.severity]
          return (
            <li key={i.code + i.message} className={`rounded-xl border px-3 py-2 text-xs ${s.cls}`}>
              <div className="flex items-start gap-1.5">
                <Icon name={s.icon} className="mt-px text-[16px]" />
                <span className="flex-1">{i.message}</span>
              </div>
              {i.fix && i.severity !== 'INFO' && (
                <button type="button" onClick={() => go(i.fix)} className="mt-1 ml-5 font-semibold underline">
                  {FIX_LABEL[i.fix]}
                </button>
              )}
            </li>
          )
        })}
      </ul>
    </Card>
  )
}

function ApproveDialog({ v, onClose, onSave, busy }: { v: Validation; onClose: () => void; onSave: () => void; busy: boolean }) {
  const warnings = v.issues.filter((i) => i.severity === 'WARNING')
  const [ok, setOk] = useState(warnings.length === 0)
  return (
    <Modal
      open
      title="Aprobar prefactura"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Volver
          </Button>
          <Button className="flex-1" icon="task_alt" disabled={!ok} loading={busy} onClick={onSave}>
            Aprobar
          </Button>
        </>
      }
    >
      <div className="space-y-3 text-sm">
        <p>Al aprobar das por buenos los datos. Los datos del cliente se guardan en su ficha para que la factura salga con ellos.</p>
        {warnings.length > 0 && (
          <>
            <ul className="space-y-1.5">
              {warnings.map((w) => (
                <li key={w.message} className="rounded-xl border border-warning/40 bg-warning-soft px-3 py-2 text-xs text-warning-ink">
                  {w.message}
                </li>
              ))}
            </ul>
            <label className="flex cursor-pointer items-start gap-2">
              <input type="checkbox" checked={ok} onChange={(e) => setOk(e.target.checked)} className="mt-0.5 h-5 w-5 accent-[var(--color-primary)]" />
              <span>Revisé estas advertencias y las acepto (queda registrado con mi nombre).</span>
            </label>
          </>
        )}
      </div>
    </Modal>
  )
}

function DecisionDialog({ onClose, onSave, busy }: { onClose: () => void; onSave: (b: { accepted: boolean; by: string; note: string }) => void; busy: boolean }) {
  const [by, setBy] = useState('')
  const [note, setNote] = useState('')
  return (
    <Modal
      open
      title="Respuesta del cliente"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" icon="thumb_down" className="flex-1" loading={busy} onClick={() => onSave({ accepted: false, by, note })}>
            Rechazó
          </Button>
          <Button icon="thumb_up" className="flex-1" loading={busy} onClick={() => onSave({ accepted: true, by, note })}>
            Aceptó
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted">Úsalo cuando el cliente te respondió por teléfono, WhatsApp o en persona.</p>
        <Field label="Quién respondió (opcional)" value={by} onChange={(e) => setBy(e.target.value)} placeholder="Nombre de quien aprobó" />
        <TextArea label="Nota (opcional)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
      </div>
    </Modal>
  )
}

function CancelDialog({ onClose, onSave, busy }: { onClose: () => void; onSave: (reason: string) => void; busy: boolean }) {
  const [reason, setReason] = useState('')
  return (
    <Modal
      open
      title="Anular documento"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Volver
          </Button>
          <Button variant="danger" className="flex-1" disabled={!reason.trim()} loading={busy} onClick={() => onSave(reason)}>
            Anular
          </Button>
        </>
      }
    >
      <Field label="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ej: el cliente desistió" />
      <p className="mt-2 text-xs text-muted">El enlace que tiene el cliente deja de funcionar.</p>
    </Modal>
  )
}
