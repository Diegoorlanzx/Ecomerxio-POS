import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Icon, SectionLabel, Spinner, Tabs, useToast } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { APP_NAME, whatsappLink } from '../../lib/brand'
import { dateTime, money, shortDate } from '../../lib/format'
import { openWompiWidget, type WidgetParams } from '../../lib/wompiWidget'

type State = 'ACTIVA' | 'PRUEBA' | 'VENCIDA' | 'CORTESIA'
type Period = 'MENSUAL' | 'ANUAL'

interface Subscription {
  state: State
  plan: string
  planName: string
  billingPeriod?: Period
  trialEndsAt?: string
  paidUntil?: string
  paymentsEnabled: boolean
  plans: { code: string; name: string; priceMonthly: number; priceAnnual: number }[]
  history: { type: string; metadata?: string; createdAt: string }[]
}

interface Confirm {
  status: string
  message: string
  subscription: Subscription
}

const STATE: Record<State, { label: string; tone: 'success' | 'primary' | 'danger' | 'neutral' }> = {
  ACTIVA: { label: 'Activa', tone: 'success' },
  PRUEBA: { label: 'Prueba gratis', tone: 'primary' },
  VENCIDA: { label: 'Vencida', tone: 'danger' },
  CORTESIA: { label: 'Sin cobro', tone: 'neutral' },
}

const EVENT: Record<string, string> = {
  CHECKOUT_INICIADO: 'Pago iniciado',
  PAGO_APLICADO: 'Pago aprobado',
  PAGO_RECHAZADO: 'Pago no aprobado',
}

const SUMMARY: Record<string, string> = {
  TIENDA: '1 caja y 3 usuarios',
  NEGOCIO: '3 cajas y 10 usuarios',
  PRO: 'Cajas y usuarios ilimitados',
}

/** Suscripción de la tienda: estado, planes y pago con Wompi (sin cobro automático: cada pago suma un mes o un año). */
export default function SubscriptionPage() {
  const { me, refresh } = useAuth()
  const toast = useToast()
  const [data, setData] = useState<Subscription | null>(null)
  const [period, setPeriod] = useState<Period>('MENSUAL')
  const [paying, setPaying] = useState<string | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Subscription>('/subscription')
      .then((s) => {
        setData(s)
        if (s.billingPeriod) setPeriod(s.billingPeriod)
      })
      .catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  function done(r: Confirm) {
    setData(r.subscription)
    toast(r.message, r.status === 'APLICADO' || r.status === 'YA_APLICADO' || r.status === 'PENDIENTE' ? 'success' : 'danger')
    void refresh()
  }

  async function pay(plan: string) {
    setPaying(plan)
    try {
      const { widget } = await api<{ widget: WidgetParams; amount: number }>('/subscription/checkout', { method: 'POST', body: { plan, period } })
      const tx = await openWompiWidget(widget, { email: me?.email, fullName: me?.fullName })
      if (!tx) {
        toast('Cerraste la ventana sin pagar.', 'danger')
        return
      }
      done(await api<Confirm>('/subscription/confirm', { method: 'POST', body: { transactionId: tx.id } }))
    } catch (e) {
      toast(e instanceof Error ? e.message : 'No se pudo iniciar el pago', 'danger')
    } finally {
      setPaying(null)
    }
  }

  async function verify() {
    setVerifying(true)
    try {
      done(await api<Confirm>('/subscription/verify', { method: 'POST' }))
    } catch (e) {
      toast(e instanceof Error ? e.message : 'No se pudo verificar', 'danger')
    } finally {
      setVerifying(false)
    }
  }

  if (!me?.owner) return <Banner tone="warning">Solo el dueño de la tienda maneja la suscripción.</Banner>
  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner />

  const st = STATE[data.state]
  const trialDays = data.trialEndsAt ? Math.ceil((new Date(data.trialEndsAt).getTime() - Date.now()) / 86_400_000) : null
  const paidDays = data.paidUntil ? Math.ceil((new Date(data.paidUntil).getTime() - Date.now()) / 86_400_000) : null

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Suscripción</h1>
        <p className="text-sm text-muted">Tu plan de {APP_NAME}. Pagas cuando quieras con tarjeta, PSE, Nequi o Bancolombia; no guardamos tu tarjeta ni cobramos solos.</p>
      </div>

      <Card className="p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm">
            <div className="flex items-center gap-2">
              <span className="text-lg font-bold">Plan {data.planName}</span>
              <Chip tone={st.tone}>{st.label}</Chip>
            </div>
            <div className="text-muted">
              {data.state === 'ACTIVA' && `Pagada hasta el ${shortDate(data.paidUntil)} (${paidDays} ${paidDays === 1 ? 'día' : 'días'}) · ${data.billingPeriod === 'ANUAL' ? 'anual' : 'mensual'}.`}
              {data.state === 'PRUEBA' && `Prueba gratis: te quedan ${trialDays} ${trialDays === 1 ? 'día' : 'días'}. Paga antes de que termine para no perder el ritmo.`}
              {data.state === 'VENCIDA' && 'Tu suscripción venció. Paga para seguir usando la app sin interrupciones.'}
              {data.state === 'CORTESIA' && 'Esta tienda no tiene cobro activo. Puedes pagar un plan cuando quieras.'}
            </div>
          </div>
          <Button variant="secondary" icon="refresh" onClick={verify} loading={verifying} disabled={!data.paymentsEnabled}>
            Verificar mi pago
          </Button>
        </div>
      </Card>

      {!data.paymentsEnabled && (
        <Banner tone="warning">
          El pago en línea aún no está activo.{' '}
          <a className="font-semibold underline" href={whatsappLink(`Hola, quiero pagar mi plan de ${APP_NAME} para la tienda "${me.tenant.name}".`)} target="_blank" rel="noopener noreferrer">
            Escríbenos por WhatsApp
          </a>{' '}
          y te ayudamos a pagar.
        </Banner>
      )}

      <SectionLabel action={<Tabs value={period} onChange={setPeriod} items={[{ value: 'MENSUAL', label: 'Mensual' }, { value: 'ANUAL', label: 'Anual' }]} />}>
        Planes
      </SectionLabel>
      <div className="grid gap-3 md:grid-cols-3">
        {data.plans.map((p) => {
          const price = period === 'ANUAL' ? p.priceAnnual : p.priceMonthly
          const current = p.code === data.plan && data.state === 'ACTIVA'
          return (
            <Card key={p.code} className={`flex flex-col gap-3 p-4 ${current ? 'border-primary' : ''}`}>
              <div className="flex items-center justify-between">
                <span className="font-bold">{p.name}</span>
                {current && <Chip tone="success">Tu plan</Chip>}
              </div>
              <div>
                <span className="text-2xl font-bold">{money(price)}</span>
                <span className="text-sm text-muted"> / {period === 'ANUAL' ? 'año' : 'mes'}</span>
              </div>
              <div className="text-sm text-muted">{SUMMARY[p.code] ?? ''}</div>
              <Button className="mt-auto" icon="credit_card" onClick={() => pay(p.code)} loading={paying === p.code} disabled={!data.paymentsEnabled || paying !== null}>
                {current ? 'Renovar' : 'Pagar'} {money(price)}
              </Button>
            </Card>
          )
        })}
      </div>
      <p className="text-xs text-muted">Si aún te quedan días pagados, el nuevo periodo se suma al final. Al cambiar de plan, el nuevo aplica desde el pago.</p>

      <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="text-sm">
          <div className="font-semibold">Facturación electrónica</div>
          <div className="text-muted">
            {me.tenant.einvoiceAddon ? 'Activa para tu tienda.' : 'No está incluida en ningún plan: se activa aparte. Escríbenos y te contamos cómo.'}
          </div>
        </div>
        {!me.tenant.einvoiceAddon && (
          <a
            href={whatsappLink(`Hola, quiero activar la facturación electrónica en ${APP_NAME} para mi tienda "${me.tenant.name}".`)}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex h-11 items-center gap-2 rounded-xl border border-line px-4 text-sm font-semibold"
          >
            <Icon name="chat" className="text-[20px]" />
            Activar facturación
          </a>
        )}
      </Card>

      {data.history.length > 0 && (
        <>
          <SectionLabel>Historial</SectionLabel>
          <Card className="divide-y divide-line">
            {data.history.map((h, i) => (
              <div key={i} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
                <span className="font-semibold">{EVENT[h.type] ?? h.type}</span>
                <span className="min-w-0 flex-1 truncate text-muted">{h.metadata}</span>
                <span className="text-muted">{dateTime(h.createdAt)}</span>
              </div>
            ))}
          </Card>
        </>
      )}
    </div>
  )
}
