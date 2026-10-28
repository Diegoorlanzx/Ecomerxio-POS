import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Icon, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { relative, shortDate } from '../../lib/format'

interface Member {
  customerId: number
  name: string
  docNumber?: string
  phone?: string
  startDate?: string
  endDate?: string
  planName?: string
  daysLeft: number
  active: boolean
  lastCheckin?: string
}
interface CheckinResult {
  allowed: boolean
  message: string
  member: Member
}

const wa = (phone: string | undefined, text: string) => {
  const digits = (phone ?? '').replace(/\D/g, '')
  if (digits.length < 10) return null
  return `https://wa.me/${digits.length === 10 ? '57' + digits : digits}?text=${encodeURIComponent(text)}`
}

/** Gimnasio: control de entrada (busca al socio y dice si está al día) y socios por vencer para recordarles. */
export default function GymPage() {
  const toast = useToast()
  const { me } = useAuth()
  const [q, setQ] = useState('')
  const [found, setFound] = useState<Member[] | null>(null)
  const [expiring, setExpiring] = useState<Member[]>([])
  const [result, setResult] = useState<CheckinResult | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<Member[]>('/memberships/expiring?days=7').then(setExpiring).catch((e) => setError(e.message))
  }, [])

  const search = useCallback(() => {
    if (!q.trim()) {
      setFound(null)
      return
    }
    api<Member[]>(`/memberships?q=${encodeURIComponent(q.trim())}`).then(setFound).catch((e) => setError(e.message))
  }, [q])
  useEffect(() => {
    const id = window.setTimeout(search, 250)
    return () => window.clearTimeout(id)
  }, [search])

  async function checkin(m: Member) {
    try {
      setResult(await api<CheckinResult>(`/memberships/${m.customerId}/checkin`, { method: 'POST' }))
      setQ('')
      setFound(null)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo registrar', 'danger')
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Entrada de socios</h1>
        <p className="text-sm text-muted">Busca al socio por nombre, cédula o celular y registra su entrada. Las membresías se venden en la caja.</p>
      </div>
      {error && <Banner>{error}</Banner>}
      {result && (
        <Card className={`flex items-center gap-4 p-5 ${result.allowed ? 'border-success' : 'border-danger'}`}>
          <Icon name={result.allowed ? 'check_circle' : 'block'} className={`text-[48px] ${result.allowed ? 'text-success-ink' : 'text-danger-ink'}`} />
          <div className="min-w-0 flex-1">
            <div className="text-lg font-bold">{result.allowed ? 'Puede entrar' : 'No puede entrar'}</div>
            <div className="text-sm text-muted">{result.message}</div>
          </div>
          <Button variant="ghost" onClick={() => setResult(null)}>
            Listo
          </Button>
        </Card>
      )}
      <input value={q} onChange={(e) => setQ(e.target.value)} autoFocus placeholder="Nombre, cédula o celular del socio" className="h-14 w-full rounded-xl border border-line bg-card px-4 text-lg" />
      {found && found.length === 0 && <p className="text-sm text-muted">No hay clientes con esos datos. Créalo al venderle su membresía en la caja.</p>}
      {found && found.length > 0 && (
        <div className="space-y-2">
          {found.map((m) => (
            <Card key={m.customerId} className="flex flex-wrap items-center gap-3 p-3">
              <div className="min-w-0 flex-1 text-sm">
                <div className="font-semibold">{m.name}</div>
                <div className="text-muted">
                  {m.docNumber ?? ''} {m.endDate ? `· vence ${shortDate(m.endDate)}` : '· sin membresía'}
                  {m.lastCheckin ? ` · última entrada ${relative(m.lastCheckin)}` : ''}
                </div>
              </div>
              <Chip tone={m.active ? 'success' : 'danger'}>{m.active ? `${m.daysLeft} días` : 'Vencida'}</Chip>
              <Button size="sm" icon="login" onClick={() => checkin(m)}>
                Registrar entrada
              </Button>
            </Card>
          ))}
        </div>
      )}
      <section className="space-y-2">
        <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">Por vencer o vencidas esta semana</h2>
        {!expiring ? (
          <Spinner />
        ) : expiring.length === 0 ? (
          <p className="text-sm text-muted">Nadie vence en los próximos 7 días.</p>
        ) : (
          expiring.map((m) => {
            const link = wa(
              m.phone,
              m.active
                ? `Hola ${m.name}, tu membresía en ${me?.tenant.name ?? 'el gimnasio'} vence el ${shortDate(m.endDate)}. ¡Renuévala y sigue entrenando!`
                : `Hola ${m.name}, tu membresía en ${me?.tenant.name ?? 'el gimnasio'} venció el ${shortDate(m.endDate)}. Te esperamos para renovarla.`,
            )
            return (
              <div key={m.customerId} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span>
                  <b>{m.name}</b> · {m.active ? `vence ${shortDate(m.endDate)}` : `venció ${shortDate(m.endDate)}`}
                </span>
                {link && (
                  <a href={link} target="_blank" rel="noopener noreferrer" className="font-semibold text-primary">
                    Recordar por WhatsApp
                  </a>
                )}
              </div>
            )
          })
        )}
      </section>
    </div>
  )
}
