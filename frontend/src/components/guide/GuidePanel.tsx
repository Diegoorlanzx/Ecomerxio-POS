import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Icon, Modal } from '../ui'
import { useAuth } from '../../lib/auth'
import { loadGuide, saveGuide } from '../../lib/guide'
import { TOURS } from '../../lib/tours'
import TourOverlay from './TourOverlay'
import SetupWizard from './SetupWizard'

interface Props {
  onClose: () => void
}

/** Panel principal de la guía interactiva: lista de tours, wizard de configuración y opciones. */
export default function GuidePanel({ onClose }: Props) {
  const { me } = useAuth()
  const navigate = useNavigate()
  const tenantId = me?.tenant.id ?? 0
  const userId = me?.id ?? 0
  const state = loadGuide(tenantId, userId)
  const [wizard, setWizard] = useState(false)
  const [activeTour, setActiveTour] = useState<{ id: string; step: number } | null>(null)
  const [showContextual, setShowContextual] = useState(state.showContextualHelp)

  function toggleContextual() {
    const next = !showContextual
    setShowContextual(next)
    saveGuide(tenantId, userId, { ...loadGuide(tenantId, userId), showContextualHelp: next })
  }

  function startTour(id: string) {
    const tour = TOURS.find((t) => t.id === id)
    if (!tour) return
    onClose()
    navigate(tour.route)
    setTimeout(() => setActiveTour({ id, step: 0 }), 300)
  }

  function resumeTour() {
    if (!state.activeTour) return
    onClose()
    const tour = TOURS.find((t) => t.id === state.activeTour!.id)
    if (!tour) return
    navigate(tour.route)
    setTimeout(() => setActiveTour(state.activeTour), 300)
  }

  function closeTour(id: string, completed: boolean) {
    const g = loadGuide(tenantId, userId)
    const completedTours = completed && !g.completedTours.includes(id) ? [...g.completedTours, id] : g.completedTours
    saveGuide(tenantId, userId, { ...g, activeTour: null, completedTours })
    setActiveTour(null)
  }

  const activeTourDef = activeTour ? TOURS.find((t) => t.id === activeTour.id) : undefined

  if (wizard) return <SetupWizard onClose={() => setWizard(false)} />

  if (activeTourDef && activeTour) {
    return (
      <TourOverlay
        tourTitle={activeTourDef.title}
        steps={activeTourDef.steps}
        step={activeTour.step}
        onNext={() => {
          const next = activeTour.step + 1
          if (next >= activeTourDef.steps.length) {
            closeTour(activeTour.id, true)
          } else {
            const g = loadGuide(tenantId, userId)
            saveGuide(tenantId, userId, { ...g, activeTour: { id: activeTour.id, step: next } })
            setActiveTour({ id: activeTour.id, step: next })
          }
        }}
        onPrev={() => {
          const prev = Math.max(0, activeTour.step - 1)
          const g = loadGuide(tenantId, userId)
          saveGuide(tenantId, userId, { ...g, activeTour: { id: activeTour.id, step: prev } })
          setActiveTour({ id: activeTour.id, step: prev })
        }}
        onSkip={() => closeTour(activeTour.id, false)}
        onClose={() => {
          // Guarda progreso para reanudar después
          const g = loadGuide(tenantId, userId)
          saveGuide(tenantId, userId, { ...g, activeTour })
          setActiveTour(null)
        }}
      />
    )
  }

  return (
    <Modal open title="Guía interactiva" onClose={onClose} footer={<Button variant="secondary" onClick={onClose}>Cerrar</Button>}>
      <div className="space-y-4">
        {/* Reanudar */}
        {state.activeTour && TOURS.find((t) => t.id === state.activeTour!.id) && (
          <div className="flex items-center gap-3 rounded-xl border border-primary/30 bg-primary/5 p-3">
            <Icon name="play_circle" className="text-[24px] text-primary shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="text-sm font-semibold">
                {TOURS.find((t) => t.id === state.activeTour!.id)?.title}
              </div>
              <div className="text-xs text-muted">
                Paso {state.activeTour.step + 1} de {TOURS.find((t) => t.id === state.activeTour!.id)?.steps.length}
              </div>
            </div>
            <Button onClick={resumeTour} className="shrink-0 !py-1.5 !px-3 !text-sm">
              Continuar
            </Button>
          </div>
        )}

        {/* Asistente de configuración */}
        <button
          type="button"
          onClick={() => setWizard(true)}
          className="flex w-full items-center gap-3 rounded-xl border border-line bg-card p-3 text-left hover:border-primary/40 transition-colors"
        >
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <Icon name="rocket_launch" className="text-[22px]" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="font-semibold text-sm">Asistente de configuración</div>
            <div className="text-xs text-muted">Lista de pasos para dejar tu tienda lista para vender</div>
          </div>
          <Icon name="chevron_right" className="text-[20px] text-muted shrink-0" />
        </button>

        {/* Tours disponibles */}
        <div>
          <div className="mb-2 text-xs font-bold tracking-wider text-muted uppercase">Recorridos guiados</div>
          <div className="space-y-2">
            {TOURS.map((tour) => {
              const done = state.completedTours.includes(tour.id)
              return (
                <button
                  key={tour.id}
                  type="button"
                  onClick={() => startTour(tour.id)}
                  className="flex w-full items-center gap-3 rounded-xl border border-line bg-card p-3 text-left hover:border-primary/40 transition-colors"
                >
                  <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${done ? 'bg-success-soft text-success-ink' : 'bg-canvas text-muted'}`}>
                    <Icon name={done ? 'check_circle' : tour.icon} className="text-[22px]" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-semibold text-sm">{tour.title}</div>
                    <div className="text-xs text-muted truncate">{tour.description}</div>
                  </div>
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${done ? 'bg-success-soft text-success-ink' : 'bg-line-soft text-muted'}`}>
                    {done ? 'Completado' : `${tour.steps.length} pasos`}
                  </span>
                </button>
              )
            })}
          </div>
        </div>

        {/* Ayudas contextuales */}
        <div className="flex items-center justify-between rounded-xl border border-line bg-card p-3">
          <div className="flex items-center gap-3">
            <Icon name="help_outline" className="text-[22px] text-muted shrink-0" />
            <div>
              <div className="font-semibold text-sm">Ayudas contextuales</div>
              <div className="text-xs text-muted">Iconos de ayuda junto a los campos del sistema</div>
            </div>
          </div>
          <button
            type="button"
            onClick={toggleContextual}
            aria-checked={showContextual}
            role="switch"
            className={`relative h-6 w-11 rounded-full transition-colors ${showContextual ? 'bg-primary' : 'bg-line'}`}
          >
            <span
              className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${showContextual ? 'translate-x-5' : 'translate-x-0.5'}`}
            />
          </button>
        </div>
      </div>
    </Modal>
  )
}
