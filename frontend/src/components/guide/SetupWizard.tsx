import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Icon, Modal, Spinner } from '../ui'
import { api } from '../../lib/api'
import type { GuideState } from '../../lib/guide'
import type { StoreDto } from '../../lib/types'
import { useGuide } from './GuideProvider'

interface Page<T> {
  items: T[]
  total: number
}

type Check = (g: GuideState) => Promise<boolean>

const count = (path: string) => api<Page<unknown>>(path).then((p) => p.total)
const list = (path: string) => api<unknown[]>(path).then((l) => l.length)

interface WizardStep {
  id: string
  label: string
  description: string
  to: string
  check: Check
}

const STEPS: WizardStep[] = [
  {
    id: 'business',
    label: 'Datos del negocio',
    description: 'Nombre, dirección, teléfono y logo',
    to: '/configuracion',
    check: async () => {
      const s = await api<StoreDto>('/store')
      return !!(s.address?.trim() && (s.phone?.trim() || s.whatsapp?.trim()))
    },
  },
  {
    id: 'payments',
    label: 'Métodos de pago',
    description: 'Efectivo, Nequi, tarjeta, Bre-B…',
    to: '/ajustes-pos',
    check: async () => {
      const s = await api<{ paymentMethods: string }>('/settings/pos')
      return s.paymentMethods.split(',').filter((m) => m && m !== 'EFECTIVO').length > 0
    },
  },
  { id: 'categories', label: 'Categorías', description: 'Agrupa tus productos (bebidas, aseo…)', to: '/inventario', check: async () => (await list('/categories')) > 0 },
  { id: 'products', label: 'Primeros productos', description: 'Crea al menos un artículo para vender', to: '/inventario', check: async () => (await count('/products?level=ITEMS&size=1')) > 0 },
  {
    id: 'costs',
    label: 'Costos, precios y existencias',
    description: 'Cada producto con su costo de compra',
    to: '/inventario',
    check: async () => {
      const p = await api<Page<{ cost?: number }>>('/products?level=ITEMS&size=200')
      return p.items.length > 0 && p.items.filter((x) => Number(x.cost ?? 0) > 0).length >= p.items.length * 0.8
    },
  },
  { id: 'suppliers', label: 'Proveedores', description: 'De quién compras la mercancía', to: '/proveedores', check: async () => (await list('/suppliers')) > 0 },
  { id: 'users', label: 'Usuarios y permisos', description: 'Tu equipo, cada uno con su rol', to: '/usuarios', check: async () => (await list('/users')) > 1 },
  { id: 'shift', label: 'Primera caja', description: 'Abre el primer turno de caja', to: '/caja', check: async () => (await list('/shifts?size=1')) > 0 },
  { id: 'sale', label: 'Venta de prueba', description: 'Cobra tu primera venta en el POS', to: '/pos', check: async () => (await count('/pos/sales?size=1')) > 0 },
  { id: 'dashboard', label: 'Revisa el dashboard', description: 'Mira cómo van tus ventas y tu caja', to: '/dashboard', check: async (g) => g.seenDashboard },
]

type Status = 'done' | 'pending' | 'unknown'

/** Asistente de primera configuración: detecta lo que ya está hecho y lleva a cada pantalla. */
export default function SetupWizard({ onClose }: { onClose: () => void }) {
  const guide = useGuide()
  const navigate = useNavigate()
  const [auto, setAuto] = useState<Record<string, Status> | null>(null)

  useEffect(() => {
    if (!guide) return
    let alive = true
    Promise.all(STEPS.map((s) => s.check(guide.state).then((ok): Status => (ok ? 'done' : 'pending')).catch((): Status => 'unknown'))).then((r) => {
      if (alive) setAuto(Object.fromEntries(STEPS.map((s, i) => [s.id, r[i]])))
    })
    return () => {
      alive = false
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (!guide) return null
  const manual = new Set(guide.state.wizardManual)
  const isDone = (id: string) => auto?.[id] === 'done' || manual.has(id)
  const done = STEPS.filter((s) => isDone(s.id)).length
  const pct = Math.round((done / STEPS.length) * 100)
  const next = STEPS.find((s) => !isDone(s.id))

  function toggle(id: string) {
    guide!.update({ wizardManual: manual.has(id) ? [...manual].filter((x) => x !== id) : [...manual, id] })
  }

  function go(to: string) {
    onClose()
    navigate(to)
  }

  return (
    <Modal
      open
      wide
      title="Configuración inicial"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Lo hago después
          </Button>
          <div className="flex-1" />
          {next && (
            <Button icon="arrow_forward" onClick={() => go(next.to)}>
              Ir a: {next.label}
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-muted">Haz estos pasos en el orden que quieras: el sistema reconoce lo que ya está listo. Puedes seguir vendiendo aunque falten pasos.</p>
        <div className="space-y-1">
          <div className="flex justify-between text-xs font-semibold text-muted">
            <span>
              {done} de {STEPS.length} completados
            </span>
            <span className="num">{pct}%</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-line-soft" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Progreso de la configuración">
            <div className="h-full rounded-full bg-primary transition-all duration-500" style={{ width: `${pct}%` }} />
          </div>
        </div>
        {!auto ? (
          <Spinner label="Revisando tu configuración…" />
        ) : (
          <ol className="space-y-2">
            {STEPS.map((s, i) => {
              const ok = isDone(s.id)
              const current = s === next
              const status = ok ? 'Completado' : current ? 'En progreso' : 'Pendiente'
              return (
                <li key={s.id} className={`flex items-center gap-3 rounded-xl border p-3 ${ok ? 'border-success/30 bg-success-soft/40' : current ? 'border-primary/40 bg-primary-tint/60' : 'border-line bg-card'}`}>
                  <span className={`num flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm font-bold ${ok ? 'bg-success text-white' : current ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}>
                    {ok ? <Icon name="check" className="text-[18px]" /> : i + 1}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold">{s.label}</span>
                    <span className="block truncate text-xs text-muted">
                      {status}
                      {auto[s.id] === 'unknown' && !ok ? ' · márcalo cuando lo tengas' : ` · ${s.description}`}
                    </span>
                  </span>
                  {auto[s.id] !== 'done' && (
                    <button
                      type="button"
                      onClick={() => toggle(s.id)}
                      title={manual.has(s.id) ? 'Marcar como pendiente' : 'Marcar como hecho'}
                      aria-label={manual.has(s.id) ? `Marcar "${s.label}" como pendiente` : `Marcar "${s.label}" como hecho`}
                      className="shrink-0 rounded-lg p-1.5 text-muted hover:bg-canvas hover:text-ink"
                    >
                      <Icon name={manual.has(s.id) ? 'undo' : 'check_circle'} className="text-[20px]" />
                    </button>
                  )}
                  <button type="button" onClick={() => go(s.to)} className="shrink-0 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-primary hover:bg-primary-tint">
                    Ir
                  </button>
                </li>
              )
            })}
          </ol>
        )}
        {auto && done === STEPS.length && (
          <div className="rounded-xl border border-success/30 bg-success-soft/40 p-4 text-center">
            <Icon name="celebration" className="text-[32px] text-success-ink" />
            <div className="mt-1 font-bold text-success-ink">¡Configuración completa!</div>
            <div className="text-sm text-muted">Tu tienda está lista para vender.</div>
          </div>
        )}
      </div>
    </Modal>
  )
}
