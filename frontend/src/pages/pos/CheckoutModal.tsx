import { useEffect, useMemo, useRef, useState } from 'react'
import { Banner, Button, Chip, Field, Icon, Modal } from '../../components/ui'
import { api } from '../../lib/api'
import { calculate, cashSuggestions, type CartLine } from '../../lib/cart'
import { CREDIT_STATUS, type CustomerCredit, type PaymentAccount } from '../../lib/financeTypes'
import { money } from '../../lib/format'
import { DIGITAL_WALLETS, METHOD_ICONS, METHOD_LABELS, type Customer, type Promotion, type StoreSettings } from '../../lib/posTypes'

export interface CheckoutPayload {
  payments: { method: string; amount: number; reference?: string }[]
  customer?: { customerId?: number; name?: string; docNumber?: string; phone?: string }
  saleDiscountPct?: number
  saleDiscountAmount?: number
  notes?: string
}

const ACCOUNTS_KEY = 'tp_pay_accounts'

/** Cuentas de cobro con QR; se guardan en el equipo para mostrarlas también sin internet. */
function cachedAccounts(): PaymentAccount[] {
  try {
    return JSON.parse(localStorage.getItem(ACCOUNTS_KEY) || '[]')
  } catch {
    return []
  }
}

interface Pay {
  method: string
  amount: number
  reference: string
}

export default function CheckoutModal({
  lines,
  settings,
  promotions,
  categoryParents,
  currency,
  scale,
  title,
  initialCustomer,
  offline,
  onClose,
  onSubmit,
  fixedPrices = false,
}: {
  lines: CartLine[]
  settings: StoreSettings
  promotions: Promotion[]
  categoryParents: Map<number, number | undefined>
  currency: string
  scale: number
  title: string
  initialCustomer?: { name?: string; docNumber?: string; customerId?: number }
  offline: boolean
  onClose: () => void
  onSubmit: (p: CheckoutPayload) => Promise<string | null>
  /** Precio ya pactado (pedido en línea): sin descuento ni cambio de cliente. */
  fixedPrices?: boolean
}) {
  const methods = settings.paymentMethods.split(',').filter(Boolean)
  const [discMode, setDiscMode] = useState<'pct' | 'amount'>('pct')
  const [discValue, setDiscValue] = useState('')
  const [showDiscount, setShowDiscount] = useState(false)
  const [showCustomer, setShowCustomer] = useState(!!initialCustomer?.name)
  const [customer, setCustomer] = useState({ name: initialCustomer?.name ?? '', docNumber: initialCustomer?.docNumber ?? '', phone: '', customerId: initialCustomer?.customerId })
  const [matches, setMatches] = useState<Customer[]>([])
  const [method, setMethod] = useState('EFECTIVO')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const [pays, setPays] = useState<Pay[]>([])
  const [notes, setNotes] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const amountRef = useRef<HTMLInputElement>(null)
  const [accounts, setAccounts] = useState<PaymentAccount[]>(cachedAccounts)
  const [credit, setCredit] = useState<CustomerCredit | null>(null)

  useEffect(() => {
    if (offline) return
    api<PaymentAccount[]>('/payment-accounts')
      .then((a) => {
        setAccounts(a)
        try {
          localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(a))
        } catch {
          /* sin espacio: se muestran sin caché */
        }
      })
      .catch(() => {})
  }, [offline])

  useEffect(() => {
    if (method === 'FIADO') setShowCustomer(true)
  }, [method])

  const creditOn = methods.includes('FIADO')
  useEffect(() => {
    setCredit(null)
    if (!customer.customerId || offline || !creditOn) return
    api<CustomerCredit>(`/credit/${customer.customerId}`).then(setCredit).catch(() => {})
  }, [customer.customerId, offline, creditOn])

  const dv = Number(discValue.replace(',', '.') || 0)
  const totals = useMemo(
    () => calculate(lines, settings, promotions, categoryParents, discMode === 'pct' ? dv : 0, discMode === 'amount' ? dv : 0, scale),
    [lines, settings, promotions, categoryParents, discMode, dv, scale],
  )
  const paid = pays.reduce((s, p) => s + p.amount, 0)
  const remaining = Math.max(0, totals.total - paid)
  const typed = Number(amount.replace(/\D/g, '') || 0)
  const effective = typed || remaining
  const change = method === 'EFECTIVO' && effective > remaining ? effective - remaining : 0
  const covers = remaining <= 0 || effective >= remaining
  const digitalOver = method !== 'EFECTIVO' && effective > remaining

  useEffect(() => {
    amountRef.current?.focus()
  }, [method])

  useEffect(() => {
    if (!showCustomer || customer.customerId || customer.name.trim().length < 2 || offline) return setMatches([])
    const t = setTimeout(() => api<Customer[]>(`/pos/customers?q=${encodeURIComponent(customer.name)}`).then(setMatches).catch(() => setMatches([])), 300)
    return () => clearTimeout(t)
  }, [customer.name, customer.customerId, showCustomer, offline])

  function addPayment() {
    if (effective <= 0 || remaining <= 0) return
    if (digitalOver) return setError(`Un pago por ${METHOD_LABELS[method]?.toLowerCase()} no puede pasar de lo que falta`)
    setPays([...pays, { method, amount: effective, reference }])
    setAmount('')
    setReference('')
    setError('')
    if (method !== 'EFECTIVO') setMethod('EFECTIVO')
  }

  async function confirm() {
    setError('')
    const all = [...pays]
    if (remaining > 0) {
      if (digitalOver) return setError(`Un pago por ${METHOD_LABELS[method]?.toLowerCase()} no puede pasar de lo que falta`)
      if (effective < remaining) return setError(`Faltan ${money(remaining - effective, currency)}. Agrega otro medio de pago.`)
      all.push({ method, amount: effective, reference })
    }
    if (all.some((p) => p.method === 'FIADO') && !customer.customerId && !(customer.name.trim() && (customer.docNumber.trim() || customer.phone.trim()))) {
      setShowCustomer(true)
      return setError('Para fiar elige un cliente registrado o escribe su nombre con cédula o celular.')
    }
    setLoading(true)
    const err = await onSubmit({
      payments: all.map((p) => ({ method: p.method, amount: p.amount, reference: p.reference || undefined })),
      customer:
        showCustomer && (customer.customerId || customer.name.trim() || customer.docNumber.trim())
          ? { customerId: customer.customerId, name: customer.name || undefined, docNumber: customer.docNumber || undefined, phone: customer.phone || undefined }
          : undefined,
      saleDiscountPct: discMode === 'pct' && dv > 0 ? dv : undefined,
      saleDiscountAmount: discMode === 'amount' && dv > 0 ? dv : undefined,
      notes: notes || undefined,
    })
    setLoading(false)
    if (err) setError(err)
  }

  const isWallet = DIGITAL_WALLETS.has(method)
  const account = accounts.find((a) => a.active && a.method === method)
  const fiadoTotal = [...pays.filter((p) => p.method === 'FIADO').map((p) => p.amount), method === 'FIADO' && remaining > 0 ? effective : 0].reduce((a, b) => a + b, 0)
  const available = credit ? Math.max(0, credit.limit - credit.balance) : null

  return (
    <Modal open wide title={title} onClose={onClose}>
      <div className="grid gap-5 lg:grid-cols-[1fr_1.1fr]">
        <div className="space-y-3">
          <div className="rounded-2xl bg-navy p-5 text-white">
            <div className="text-[11px] font-bold tracking-[0.08em] text-blue-200 uppercase">Total a pagar</div>
            <div className="num text-[40px] leading-tight font-extrabold tracking-tight">{money(totals.total, currency)}</div>
            <div className="num mt-2 space-y-0.5 text-xs text-slate-300">
              <div className="flex justify-between">
                <span>Subtotal</span>
                <span>{money(totals.subtotal, currency)}</span>
              </div>
              {totals.discountTotal > 0 && (
                <div className="flex justify-between text-emerald-300">
                  <span>Descuentos y promociones</span>
                  <span>-{money(totals.discountTotal, currency)}</span>
                </div>
              )}
              {totals.taxTotal > 0 && (
                <div className="flex justify-between">
                  <span>Impuestos {settings.pricesIncludeTax ? 'incluidos' : ''}</span>
                  <span>{money(totals.taxTotal, currency)}</span>
                </div>
              )}
            </div>
          </div>

          {!fixedPrices && (
          <button type="button" onClick={() => setShowDiscount((v) => !v)} className="flex w-full items-center justify-between rounded-xl border border-line px-4 py-3 text-sm font-semibold">
            <span className="flex items-center gap-2">
              <Icon name="percent" className="text-[20px] text-muted" /> Descuento a toda la venta
            </span>
            <Icon name={showDiscount ? 'expand_less' : 'expand_more'} />
          </button>
          )}
          {showDiscount && (
            <div className="flex gap-2">
              <div className="flex rounded-xl bg-primary-tint p-1">
                {(['pct', 'amount'] as const).map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setDiscMode(m)}
                    className={`h-10 w-12 rounded-lg text-sm font-bold ${discMode === m ? 'bg-card shadow-tier1' : 'text-muted'}`}
                  >
                    {m === 'pct' ? '%' : '$'}
                  </button>
                ))}
              </div>
              <input
                inputMode="decimal"
                value={discValue}
                onChange={(e) => setDiscValue(e.target.value)}
                placeholder={discMode === 'pct' ? `Hasta ${settings.maxDiscountPct}% sin autorización` : 'Valor'}
                className="num h-12 flex-1 rounded-xl border border-line px-3 text-base outline-none focus:border-primary"
              />
            </div>
          )}

          {!fixedPrices && (
          <button type="button" onClick={() => setShowCustomer((v) => !v)} className="flex w-full items-center justify-between rounded-xl border border-line px-4 py-3 text-sm font-semibold">
            <span className="flex items-center gap-2">
              <Icon name="person" className="text-[20px] text-muted" /> Datos del cliente (opcional)
            </span>
            <Icon name={showCustomer ? 'expand_less' : 'expand_more'} />
          </button>
          )}
          {showCustomer && (
            <div className="space-y-2">
              <div className="relative">
                <Field label="Nombre" value={customer.name} onChange={(e) => setCustomer({ ...customer, name: e.target.value, customerId: undefined })} />
                {matches.length > 0 && (
                  <div className="absolute inset-x-0 top-full z-10 mt-1 divide-y divide-line-soft rounded-xl border border-line bg-card shadow-tier3">
                    {matches.slice(0, 5).map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        onClick={() => (setCustomer({ name: c.name, docNumber: c.docNumber ?? '', phone: c.phone ?? '', customerId: c.id }), setMatches([]))}
                        className="block w-full px-3 py-2 text-left text-sm hover:bg-canvas"
                      >
                        {c.name} <span className="text-muted">{c.docNumber}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 gap-2">
                <Field label="Cédula / NIT" value={customer.docNumber} onChange={(e) => setCustomer({ ...customer, docNumber: e.target.value, customerId: undefined })} />
                <Field label="Celular" type="tel" value={customer.phone} onChange={(e) => setCustomer({ ...customer, phone: e.target.value })} />
              </div>
              {credit && (
                <div className="rounded-xl border border-violet-200 bg-wallet-soft px-3 py-2.5 text-sm text-wallet-ink">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-semibold">Fiado de {credit.name}</span>
                    <Chip tone={CREDIT_STATUS[credit.status].tone}>{CREDIT_STATUS[credit.status].label}</Chip>
                  </div>
                  <div className="num mt-1 grid grid-cols-3 gap-2 text-xs">
                    <span>
                      Debe
                      <b className="block text-sm">{money(credit.balance, currency)}</b>
                    </span>
                    <span>
                      Cupo
                      <b className="block text-sm">{money(credit.limit, currency)}</b>
                    </span>
                    <span>
                      Disponible
                      <b className="block text-sm">{money(available, currency)}</b>
                    </span>
                  </div>
                  {credit.overdue > 0 && <p className="mt-1 text-xs font-semibold text-danger-ink">Tiene {money(credit.overdue, currency)} vencido: fiarle necesita autorización.</p>}
                  {available !== null && fiadoTotal > available && credit.overdue <= 0 && (
                    <p className="mt-1 text-xs font-semibold text-danger-ink">Este fiado supera su cupo: necesita autorización de un supervisor.</p>
                  )}
                </div>
              )}
            </div>
          )}
          <Field label="Nota (opcional)" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </div>

        <div className="space-y-3">
          <div className="grid grid-cols-3 gap-2">
            {methods.map((m) => {
              const active = method === m
              const wallet = DIGITAL_WALLETS.has(m)
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => setMethod(m)}
                  className={`flex h-16 flex-col items-center justify-center gap-0.5 rounded-xl border-2 text-xs font-semibold transition ${
                    active ? (m === 'EFECTIVO' ? 'border-success bg-success-soft text-success-ink' : wallet ? 'border-wallet bg-wallet-soft text-wallet-ink' : 'border-primary bg-primary-tint text-primary') : 'border-line text-muted hover:bg-canvas'
                  }`}
                >
                  <Icon name={METHOD_ICONS[m] ?? 'payments'} className="text-[22px]" />
                  {METHOD_LABELS[m] ?? m}
                </button>
              )
            })}
          </div>

          {remaining > 0 && (
            <>
              <div>
                <label className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">
                  {method === 'EFECTIVO' ? 'Efectivo recibido' : method === 'FIADO' ? 'Valor a fiar' : `Valor por ${METHOD_LABELS[method]?.toLowerCase()}`}
                </label>
                <div className="relative">
                  <span className="num pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-2xl font-bold text-muted">$</span>
                  <input
                    ref={amountRef}
                    inputMode="numeric"
                    value={amount ? Number(amount.replace(/\D/g, '')).toLocaleString('es-CO') : ''}
                    onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))}
                    onKeyDown={(e) => e.key === 'Enter' && confirm()}
                    placeholder={remaining.toLocaleString('es-CO')}
                    className="num h-16 w-full rounded-xl border border-line bg-card pr-4 pl-10 text-[30px] font-bold tracking-tight outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
                  />
                </div>
              </div>
              {method === 'EFECTIVO' && (
                <div className="grid grid-cols-4 gap-2">
                  <button type="button" onClick={() => setAmount(String(remaining))} className="h-11 rounded-xl bg-primary-tint text-xs font-bold text-primary">
                    Exacto
                  </button>
                  {cashSuggestions(remaining).slice(0, 3).map((v) => (
                    <button key={v} type="button" onClick={() => setAmount(String(v))} className="num h-11 rounded-xl bg-line-soft text-xs font-bold">
                      {money(v, currency)}
                    </button>
                  ))}
                </div>
              )}
              {account && (
                <div className="flex items-center gap-3 rounded-2xl border border-violet-200 bg-wallet-soft p-3">
                  {account.qrImage ? (
                    <img src={account.qrImage} alt={`QR de ${account.label}`} className="h-32 w-32 shrink-0 rounded-xl bg-white object-contain p-1" />
                  ) : (
                    <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-white text-wallet-ink">
                      <Icon name="qr_code_2" className="text-[32px]" />
                    </span>
                  )}
                  <div className="min-w-0 text-sm text-wallet-ink">
                    <div className="text-[11px] font-bold tracking-wide uppercase">Pagar a</div>
                    <div className="font-semibold">{account.label}</div>
                    {account.holder && <div className="text-xs">{account.holder}</div>}
                    {account.accountRef && <div className="num text-base font-bold">{account.accountRef}</div>}
                  </div>
                </div>
              )}
              {method === 'FIADO' && !customer.customerId && (
                <Banner tone="primary">Elige al cliente en “Datos del cliente”. Si es nuevo, escribe su nombre con cédula o celular.</Banner>
              )}
              {method !== 'EFECTIVO' && method !== 'FIADO' && (
                <Field
                  label={isWallet ? 'Referencia o número de la transacción' : 'Referencia (opcional)'}
                  value={reference}
                  onChange={(e) => setReference(e.target.value)}
                  hint={isWallet ? 'Verifica que el dinero llegó a la cuenta: un pantallazo no es prueba de pago.' : undefined}
                />
              )}
            </>
          )}

          {pays.length > 0 && (
            <div className="divide-y divide-line-soft rounded-xl border border-line">
              {pays.map((p, i) => (
                <div key={i} className="flex items-center gap-3 px-3 py-2 text-sm">
                  <Icon name={METHOD_ICONS[p.method] ?? 'payments'} className="text-[20px] text-muted" />
                  <span className="flex-1">
                    {METHOD_LABELS[p.method]}
                    {p.reference ? ` · ${p.reference}` : ''}
                  </span>
                  <span className="num font-semibold">{money(p.amount, currency)}</span>
                  <button type="button" aria-label="Quitar pago" onClick={() => setPays(pays.filter((_, j) => j !== i))} className="text-muted hover:text-danger-ink">
                    <Icon name="close" className="text-[18px]" />
                  </button>
                </div>
              ))}
              <div className="num flex justify-between px-3 py-2 text-sm font-semibold">
                <span>Falta</span>
                <span>{money(remaining, currency)}</span>
              </div>
            </div>
          )}

          {change > 0 && (
            <div className="flex items-center justify-between rounded-2xl border border-emerald-200 bg-success-soft px-4 py-3">
              <span className="text-sm font-semibold text-success-ink">Cambio para el cliente</span>
              <span className="num text-[28px] font-extrabold text-success-ink">{money(change, currency)}</span>
            </div>
          )}

          {error && <Banner>{error}</Banner>}
          {offline && <Banner tone="warning">Sin internet: la venta se guarda en este equipo y se envía al reconectar.</Banner>}

          <div className="flex gap-2">
            {remaining > 0 && !covers && (
              <Button variant="secondary" icon="add" className="flex-1" onClick={addPayment} disabled={effective <= 0}>
                Pago mixto
              </Button>
            )}
            <Button size="lg" className="flex-[2]" icon="check" onClick={confirm} loading={loading} disabled={!covers && remaining > 0}>
              Cobrar {money(totals.total, currency)}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  )
}
