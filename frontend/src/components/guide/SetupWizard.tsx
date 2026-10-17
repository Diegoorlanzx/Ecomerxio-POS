import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Icon, Modal, Spinner } from '../ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { loadGuide, saveGuide } from '../../lib/guide'

interface WizardStep {
  id: string
  label: string
  description: string
  icon: string
  to: string
  check: () => Promise<boolean>
}

const STEPS: WizardStep[] = [
  {
    id: 'business',
    label: 'Datos del negocio',
    description: 'Nombre, dirección, logo y zona horaria',
    icon: 'storefront',
    to: '/configuracion',
    check: async () => {
      const s = await api<{ name: string; address?: string }>('/settings/pos').catch(() => null)
      return !!(s?.address)
    },
  },
  {
    id: 'payments',
    label: 'Métodos de pago',
    description: 'Efectivo, Nequi, tarjetas y Bre-B',
    icon: 'payments',
    to: '/ajustes-pos',
    check: async () => {
      const s = await api<{ payMethods: string[] }>('/settings/pos').catch(() => null)
      return (s?.payMethods?.length ?? 0) > 1
    },
  },
  {
    id: 'categories',
    label: 'Categorías',
    description: 'Organiza tus productos por grupos',
    icon: 'category',
    to: '/inventario',
    check: async () => {
      const c = await api<unknown[]>('/catalog/categories').catch(() => null)
      return (c?.length ?? 0) > 0
    },
  },
  {
    id: 'products',
    label: 'Primeros productos',
    description: 'Agrega al menos un artículo al catálogo',
    icon: 'inventory_2',
    to: '/inventario',
    check: async () => {
      const p = await api<unknown[]>('/catalog/products?level=ITEMS&size=1').catch(() => null)
      return (p?.length ?? 0) > 0
    },
  },
  {
    id: 'costs',
    label: 'Costos y precios',
    description: 'Define el costo de compra y el precio de venta',
    icon: 'sell',
    to: '/inventario',
    check: async () => {
      /* Se marca manual: no hay endpoint directo para contar productos con costo */
      return false
    },
  },
  {
    id: 'suppliers',
    label: 'Proveedores',
    description: 'Registra de quién compras tus productos',
    icon: 'local_shipping',
    to: '/proveedores',
    check: async () => {
      const s = await api<unknown[]>('/purchasing/suppliers?size=1').catch(() => null)
      return (s?.length ?? 0) > 0
    },
  },
  {
    id: 'users',
    label: 'Usuarios y permisos',
    description: 'Agrega a tu equipo y configura qué puede hacer cada uno',
    icon: 'group',
    to: '/usuarios',
    check: async () => {
      const u = await api<unknown[]>('/users').catch(() => null)
      return (u?.length ?? 0) > 1
    },
  },
  {
    id: 'shift',
    label: 'Primera caja',
    description: 'Abre el primer turno de caja',
    icon: 'point_of_sale',
    to: '/caja',
    check: async () => {
      const h = await api<unknown[]>('/shifts?size=1').catch(() => null)
      return (h?.length ?? 0) > 0
    },
  },
  {
    id: 'sale',
    label: 'Venta de prueba',
    description: 'Realiza tu primera venta en el POS',
    icon: 'receipt',
    to: '/pos',
    check: async () => {
      const s = await api<unknown[]>('/pos/sales?size=1').catch(() => null)
      return (s?.length ?? 0) > 0
    },
  },
  {
    id: 'dashboard',
    label: 'Revisa el dashboard',
    description: 'Visita el panel de control y conoce tus números',
    icon: 'space_dashboard',
    to: '/dashboard',
    check: async () => false, // marcado via localStorage al visitar
  },
]

interface Props {
  onClose: () => void
}

export default function SetupWizard({ onClose }: Props) {
  const { me } = useAuth()
  const [statuses, setStatuses] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(true)

  const tenantId = me?.tenant.id ?? 0
  const userId = me?.id ?? 0
  const state = loadGuide(tenantId, userId)
  const manual = new Set(state.wizardManual)

  useEffect(() => {
    let alive = true
    const run = async () => {
      const results: Record<string, boolean> = {}
      for (const step of STEPS) {
        results[step.id] = manual.has(step.id) || (await step.check().catch(() => false))
      }
      if (alive) {
        setStatuses(results)
        setLoading(false)
      }
    }
    run()
    return () => {
      alive = false
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  function toggleManual(id: string) {
    const newManual = manual.has(id) ? [...manual].filter((x) => x !== id) : [...manual, id]
    const newState = { ...state, wizardManual: newManual }
    saveGuide(tenantId, userId, newState)
    setStatuses((prev) => ({ ...prev, [id]: newManual.includes(id) }))
  }

  const done = STEPS.filter((s) => statuses[s.id]).length
  const pct = Math.round((done / STEPS.length) * 100)

  return (
    <Modal
      open
      wide
      title="Configuración inicial"
      onClose={onClose}
      footer={
        <Button onClick={onClose} className="ml-auto">
          Cerrar
        </Button>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Completa estos pasos para dejar tu tienda lista para vender. Puedes hacerlos en cualquier orden y el sistema detecta automáticamente cuáles ya están hechos.
        </p>

        {/* Barra de progreso */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs font-semibold text-muted">
            <span>{done} de {STEPS.length} completados</span>
            <span>{pct}%</span>
          </div>
          <div className="h-2 w-full rounded-full bg-line-soft overflow-hidden">
            <div
              className="h-full rounded-full bg-primary transition-all duration-500"
              style={{ width: `${pct}%` }}
            />
          </div>
        </div>

        {loading ? (
          <Spinner />
        ) : (
          <div className="space-y-2">
            {STEPS.map((step, i) => {
              const isDone = statuses[step.id] ?? false
              return (
                <div
                  key={step.id}
                  className={`flex items-center gap-3 rounded-xl border p-3 transition-colors ${
                    isDone ? 'border-success/30 bg-success-soft/30' : 'border-line bg-card'
                  }`}
                >
                  {/* Número / check */}
                  <div
                    className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm font-bold ${
                      isDone ? 'bg-success text-white' : 'bg-line-soft text-muted'
                    }`}
                  >
                    {isDone ? <Icon name="check" className="text-[16px]" /> : i + 1}
                  </div>

                  {/* Texto */}
                  <div className="min-w-0 flex-1">
                    <div className={`font-semibold text-sm ${isDone ? 'text-success-ink line-through opacity-70' : ''}`}>
                      {step.label}
                    </div>
                    <div className="text-xs text-muted truncate">{step.description}</div>
                  </div>

                  {/* Acciones */}
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      type="button"
                      title={isDone ? 'Marcar como pendiente' : 'Marcar como hecho'}
                      onClick={() => toggleManual(step.id)}
                      className="rounded-lg p-1 text-muted hover:bg-canvas hover:text-foreground transition-colors"
                    >
                      <Icon name={isDone ? 'undo' : 'check_circle'} className="text-[18px]" />
                    </button>
                    <Link
                      to={step.to}
                      onClick={onClose}
                      className="flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-semibold text-primary hover:bg-primary/10 transition-colors"
                    >
                      <Icon name={step.icon} className="text-[14px]" />
                      Ir
                    </Link>
                  </div>
                </div>
              )
            })}
          </div>
        )}

        {done === STEPS.length && (
          <div className="rounded-xl border border-success/30 bg-success-soft/30 p-4 text-center">
            <Icon name="celebration" className="text-[32px] text-success-ink" />
            <div className="mt-1 font-bold text-success-ink">¡Configuración completa!</div>
            <div className="text-sm text-muted mt-0.5">Tu tienda está lista para vender.</div>
          </div>
        )}
      </div>
    </Modal>
  )
}
