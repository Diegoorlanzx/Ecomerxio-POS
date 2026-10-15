import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Spinner, Tabs, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { AlertItem } from '../../lib/financeTypes'
import { dateTime, relative } from '../../lib/format'
import { SEVERITY_DOT, SEVERITY_ICON, type DeliveryItem, type Inbox, type NotificationChannels, type NotificationItem, type NotificationRule } from '../../lib/notificationTypes'

type Tab = 'bandeja' | 'activas' | 'config'

/** Bandeja de notificaciones, alertas activas y configuración de umbrales y canales. */
export default function NotificationsPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) || 'bandeja'
  const items: { value: Tab; label: string }[] = [
    { value: 'bandeja', label: 'Notificaciones' },
    { value: 'activas', label: 'Alertas activas' },
  ]
  if (can('CONFIG_EDITAR') || can('ALERTAS_VER')) items.push({ value: 'config', label: 'Umbrales y canales' })
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Notificaciones y alertas</h1>
        <p className="text-sm text-muted">Avisos del negocio según tu rol. El sistema revisa todo cada pocos minutos.</p>
      </div>
      <Tabs value={tab} onChange={(t) => setParams({ tab: t }, { replace: true })} items={items} />
      {tab === 'bandeja' && <InboxTab />}
      {tab === 'activas' && <ActiveTab />}
      {tab === 'config' && <ConfigTab editable={can('CONFIG_EDITAR')} />}
    </div>
  )
}

function InboxTab() {
  const navigate = useNavigate()
  const [onlyUnread, setOnlyUnread] = useState(false)
  const [inbox, setInbox] = useState<Inbox | null>(null)
  const [error, setError] = useState('')
  const load = useCallback(() => {
    api<Inbox>(`/notifications?limit=200&onlyUnread=${onlyUnread}`)
      .then(setInbox)
      .catch((e) => setError(e.message))
  }, [onlyUnread])
  useEffect(load, [load])

  async function open(n: NotificationItem) {
    if (!n.read) await api(`/notifications/${n.id}/read`, { method: 'POST' }).catch(() => {})
    if (n.link) navigate(n.link)
    else load()
  }

  if (error) return <Banner>{error}</Banner>
  if (!inbox) return <Spinner />
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="w-72">
          <Tabs
            value={onlyUnread ? 'u' : 't'}
            onChange={(v) => setOnlyUnread(v === 'u')}
            items={[
              { value: 't', label: 'Todas' },
              { value: 'u', label: 'Sin leer', count: inbox.unread },
            ]}
          />
        </div>
        {inbox.unread > 0 && (
          <Button size="sm" variant="secondary" icon="done_all" onClick={() => api('/notifications/read-all', { method: 'POST' }).then(load)}>
            Marcar todo como leído
          </Button>
        )}
      </div>
      {inbox.items.length === 0 ? (
        <Card>
          <EmptyState icon="notifications_off" title={onlyUnread ? 'Nada sin leer' : 'Sin notificaciones'} text="Aquí llegan los avisos de inventario, compras, pagos, caja y los reportes." />
        </Card>
      ) : (
        <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
          {inbox.items.map((n) => {
            const s = SEVERITY_ICON[n.severity] ?? SEVERITY_ICON.MEDIA
            return (
              <button key={n.id} type="button" onClick={() => open(n)} className="flex w-full gap-3 px-4 py-3 text-left hover:bg-canvas">
                <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${s.tone}`}>
                  <Icon name={s.icon} className="text-[20px]" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2">
                    {!n.read && <span className={`h-2 w-2 shrink-0 rounded-full ${SEVERITY_DOT[n.severity]}`} />}
                    <span className={`text-sm ${n.read ? '' : 'font-semibold'}`}>{n.title}</span>
                  </span>
                  {n.body && <span className="mt-0.5 block text-sm whitespace-pre-line text-ink/75">{n.body}</span>}
                  <span className="mt-0.5 block text-xs text-muted">
                    {n.typeLabel} · {dateTime(n.createdAt)}
                  </span>
                </span>
                {n.link && <Icon name="chevron_right" className="self-center text-muted" />}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

function ActiveTab() {
  const [alerts, setAlerts] = useState<AlertItem[] | null>(null)
  useEffect(() => {
    api<AlertItem[]>('/alerts')
      .then(setAlerts)
      .catch(() => setAlerts([]))
  }, [])
  if (!alerts) return <Spinner />
  if (!alerts.length)
    return (
      <Card>
        <EmptyState icon="task_alt" title="Todo en orden" text="No hay situaciones abiertas que atender en este momento." />
      </Card>
    )
  return (
    <div className="space-y-2">
      <p className="text-sm text-muted">Lo que está pasando ahora mismo (se actualiza al abrir esta pestaña).</p>
      <div className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
        {alerts.map((a, i) => (
          <Link key={i} to={a.link} className="flex gap-3 px-4 py-3 hover:bg-canvas">
            <span className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${a.severity === 'CRITICA' ? 'bg-danger' : a.severity === 'ALTA' ? 'bg-warning' : 'bg-primary'}`} />
            <span className="min-w-0 flex-1 text-sm">
              <span className="block font-semibold">{a.title}</span>
              <span className="text-muted">{a.detail}</span>
            </span>
            <Icon name="chevron_right" className="self-center text-muted" />
          </Link>
        ))}
      </div>
    </div>
  )
}

const GROUPS: [string, string[]][] = [
  ['Inventario', ['STOCK_CRITICO', 'PROXIMO_AGOTAMIENTO', 'SIN_ROTACION', 'EXCESO_INVENTARIO']],
  ['Compras', ['PEDIDO_RECOMENDADO', 'PEDIDO_POR_APROBAR', 'PEDIDO_RETRASADO', 'MERCANCIA_RECIBIDA']],
  ['Pagos y caja', ['PAGO_POR_VERIFICAR', 'PAGO_INCONSISTENTE', 'DIFERENCIA_CAJA', 'DEVOLUCIONES_INUSUALES']],
  ['Finanzas', ['PAGO_PROVEEDOR', 'FIADO_VENCIDO']],
  ['Reportes', ['REPORTE_DIARIO', 'RESUMEN_SEMANAL', 'RESUMEN_MENSUAL']],
]

function ConfigTab({ editable }: { editable: boolean }) {
  const toast = useToast()
  const [rules, setRules] = useState<NotificationRule[] | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    api<NotificationRule[]>('/notifications/rules')
      .then(setRules)
      .catch((e) => setError(e.message))
  }, [])

  function set(type: string, patch: Partial<NotificationRule>) {
    setRules((rs) => rs?.map((r) => (r.type === type ? { ...r, ...patch } : r)) ?? null)
  }

  async function save() {
    if (!rules) return
    setSaving(true)
    setError('')
    try {
      setRules(
        await api<NotificationRule[]>('/notifications/rules', {
          method: 'PUT',
          body: rules.map((r) => ({ type: r.type, enabled: r.enabled, threshold: r.threshold ?? null, email: r.email, webhook: r.webhook, whatsapp: r.whatsapp })),
        }),
      )
      toast('Alertas guardadas')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  if (error && !rules) return <Banner>{error}</Banner>
  if (!rules) return <Spinner />
  const byType = new Map(rules.map((r) => [r.type, r]))
  return (
    <div className="space-y-4">
      {editable && <ChannelsCard />}
      {GROUPS.map(([title, types]) => (
        <Card key={title}>
          <div className="border-b border-line-soft px-4 py-3 text-sm font-semibold">{title}</div>
          <div className="divide-y divide-line-soft">
            {types
              .map((t) => byType.get(t))
              .filter((r): r is NotificationRule => !!r)
              .map((r) => (
                <fieldset key={r.type} disabled={!editable} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                  <div className="min-w-[14rem] flex-1">
                    <Toggle checked={r.enabled} onChange={(v) => set(r.type, { enabled: v })} label={r.label} hint={r.description} />
                  </div>
                  {r.thresholdLabel && (
                    <label className="flex items-center gap-2 text-xs text-muted">
                      <input
                        inputMode="numeric"
                        value={r.threshold ?? ''}
                        onChange={(e) => set(r.type, { threshold: e.target.value === '' ? null : Number(e.target.value.replace(/\D/g, '')) })}
                        className="num h-10 w-24 rounded-lg border border-line px-2 text-right text-base text-ink outline-none focus:border-primary"
                        aria-label={`Umbral de ${r.label}`}
                      />
                      {r.thresholdLabel}
                    </label>
                  )}
                  <div className="flex gap-1.5">
                    {(
                      [
                        ['email', 'mail', 'Correo'],
                        ['webhook', 'webhook', 'Webhook'],
                        ['whatsapp', 'chat', 'WhatsApp'],
                      ] as const
                    ).map(([k, icon, label]) => (
                      <button
                        key={k}
                        type="button"
                        title={`Enviar también por ${label}`}
                        onClick={() => set(r.type, { [k]: !r[k] })}
                        className={`flex h-9 items-center gap-1 rounded-lg border px-2 text-xs font-semibold ${r[k] ? 'border-primary bg-primary-tint text-primary' : 'border-line text-muted'}`}
                      >
                        <Icon name={icon} className="text-[16px]" /> {label}
                      </button>
                    ))}
                  </div>
                </fieldset>
              ))}
          </div>
        </Card>
      ))}
      {error && <Banner>{error}</Banner>}
      {editable && (
        <div className="sticky bottom-24 flex justify-end lg:bottom-4">
          <Button icon="save" onClick={save} loading={saving} className="shadow-tier3">
            Guardar alertas
          </Button>
        </div>
      )}
      {editable && <DeliveriesCard />}
    </div>
  )
}

function ChannelsCard() {
  const toast = useToast()
  const [c, setC] = useState<NotificationChannels | null>(null)
  const [form, setForm] = useState({ emailTo: '', webhookUrl: '', webhookSecret: '', whatsappPhoneId: '', whatsappToken: '', whatsappTo: '', dailyReportHour: '21' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState('')

  useEffect(() => {
    api<NotificationChannels>('/notifications/channels').then((x) => {
      setC(x)
      setForm({
        emailTo: x.emailTo ?? '',
        webhookUrl: x.webhookUrl ?? '',
        webhookSecret: '',
        whatsappPhoneId: x.whatsappPhoneId ?? '',
        whatsappToken: '',
        whatsappTo: x.whatsappTo ?? '',
        dailyReportHour: String(x.dailyReportHour),
      })
    })
  }, [])

  async function save() {
    setSaving(true)
    setFields({})
    try {
      setC(await api<NotificationChannels>('/notifications/channels', { method: 'PUT', body: { ...form, dailyReportHour: Number(form.dailyReportHour || 0) } }))
      setForm((f) => ({ ...f, webhookSecret: '', whatsappToken: '' }))
      toast('Canales guardados')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { emailTo: e.message })
    } finally {
      setSaving(false)
    }
  }

  async function test(channel: string) {
    setTesting(channel)
    try {
      const r = await api<{ status: string; detail: string }>('/notifications/channels/test', { method: 'POST', body: { channel } })
      toast(r.status === 'ENVIADO' ? `Prueba enviada por ${channel.toLowerCase()}` : `${r.status === 'SIN_CONFIGURAR' ? 'Falta configurar' : 'Falló'}: ${r.detail}`, r.status === 'ENVIADO' ? 'success' : 'danger')
    } finally {
      setTesting('')
    }
  }

  if (!c) return <Spinner />
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })
  return (
    <Card className="p-4">
      <h3 className="font-semibold">Canales externos</h3>
      <p className="mb-3 text-sm text-muted">
        Dentro del sistema las notificaciones llegan siempre. Además puedes recibirlas por correo, por un webhook (para conectar otras herramientas) o por WhatsApp
        Cloud API. Última revisión: {relative(c.lastScanAt).toLowerCase()}.
      </p>
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
          <Field label="Correos de destino" value={form.emailTo} onChange={set('emailTo')} error={fields.emailTo} placeholder="dueno@mitienda.com, socio@mitienda.com"
            hint={c.smtpConfigured ? undefined : 'El servidor aún no tiene correo saliente (SMTP): los correos quedan en el registro hasta configurarlo.'} />
          <Button variant="secondary" size="sm" icon="send" className="mb-6" onClick={() => test('EMAIL')} loading={testing === 'EMAIL'}>
            Probar
          </Button>
        </div>
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr_auto] sm:items-end">
          <Field label="Webhook (https)" value={form.webhookUrl} onChange={set('webhookUrl')} error={fields.webhookUrl} placeholder="https://..." />
          <Field label="Secreto de firma" value={form.webhookSecret} onChange={set('webhookSecret')} placeholder={c.webhookSecretSet ? '•••••• (guardado)' : 'opcional'} hint="Firma HMAC-SHA256 en X-Signature" />
          <Button variant="secondary" size="sm" icon="send" className="mb-6" onClick={() => test('WEBHOOK')} loading={testing === 'WEBHOOK'}>
            Probar
          </Button>
        </div>
        <div className="grid gap-3 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end">
          <Field label="WhatsApp: ID del número" value={form.whatsappPhoneId} onChange={set('whatsappPhoneId')} error={fields.whatsappPhoneId} />
          <Field label="Token de acceso" type="password" value={form.whatsappToken} onChange={set('whatsappToken')} placeholder={c.whatsappTokenSet ? '•••••• (guardado)' : ''} />
          <Field label="Enviar a (celular)" value={form.whatsappTo} onChange={set('whatsappTo')} error={fields.whatsappTo} placeholder="300 123 4567" />
          <Button variant="secondary" size="sm" icon="send" className="mb-1.5" onClick={() => test('WHATSAPP')} loading={testing === 'WHATSAPP'}>
            Probar
          </Button>
        </div>
        <p className="text-xs text-muted">WhatsApp Cloud API (Meta) requiere una cuenta de empresa verificada; fuera de la ventana de 24 horas Meta puede exigir plantillas aprobadas.</p>
        <Field label="Hora del reporte diario (0 a 23)" inputMode="numeric" value={form.dailyReportHour} onChange={set('dailyReportHour')} error={fields.dailyReportHour} className="w-56" />
      </div>
      <div className="mt-3 flex justify-end">
        <Button icon="save" onClick={save} loading={saving}>
          Guardar canales
        </Button>
      </div>
    </Card>
  )
}

function DeliveriesCard() {
  const [list, setList] = useState<DeliveryItem[] | null>(null)
  useEffect(() => {
    api<DeliveryItem[]>('/notifications/deliveries')
      .then(setList)
      .catch(() => setList([]))
  }, [])
  if (!list || list.length === 0) return null
  return (
    <Card>
      <div className="border-b border-line-soft px-4 py-3 text-sm font-semibold">Últimos envíos por canales externos</div>
      <div className="divide-y divide-line-soft">
        {list.map((d) => (
          <div key={d.id} className="flex items-center gap-3 px-4 py-2 text-sm">
            <Icon name={d.channel === 'EMAIL' ? 'mail' : d.channel === 'WEBHOOK' ? 'webhook' : 'chat'} className="text-[18px] text-muted" />
            <span className="min-w-0 flex-1 truncate text-xs text-muted">
              {dateTime(d.createdAt)} · {d.detail}
            </span>
            <Chip tone={d.status === 'ENVIADO' ? 'success' : d.status === 'FALLIDO' ? 'danger' : 'neutral'}>{d.status === 'SIN_CONFIGURAR' ? 'Sin configurar' : d.status === 'ENVIADO' ? 'Enviado' : 'Falló'}</Chip>
          </div>
        ))}
      </div>
    </Card>
  )
}
