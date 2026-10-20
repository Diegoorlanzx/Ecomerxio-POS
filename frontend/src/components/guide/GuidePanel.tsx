import { Button, Icon, Modal, Toggle } from '../ui'
import { TOURS } from '../../lib/tours'
import { useGuide } from './GuideProvider'

/** Panel de la guía: recorridos, asistente de configuración y ayudas contextuales. */
export default function GuidePanel({ onClose }: { onClose: () => void }) {
  const guide = useGuide()
  if (!guide) return null
  const { state, update, startTour, openWizard } = guide
  const paused = state.activeTour ? TOURS.find((t) => t.id === state.activeTour!.id) : undefined

  return (
    <Modal
      open
      title="Guía interactiva"
      onClose={onClose}
      footer={
        <Button variant="secondary" className="ml-auto" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      <div className="space-y-4">
        {paused && state.activeTour && (
          <div className="flex items-center gap-3 rounded-xl border border-primary/30 bg-primary-tint p-3">
            <Icon name="play_circle" className="shrink-0 text-[26px] text-primary" />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold">Continuar: {paused.title}</div>
              <div className="text-xs text-muted">
                Ibas en el paso {state.activeTour.step + 1} de {paused.steps.length}
              </div>
            </div>
            <Button size="sm" onClick={() => startTour(paused.id, state.activeTour!.step)}>
              Reanudar
            </Button>
          </div>
        )}

        <button
          type="button"
          onClick={() => (onClose(), openWizard())}
          className="flex w-full items-center gap-3 rounded-xl border border-line bg-card p-3 text-left transition-colors hover:border-primary/40"
        >
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-tint text-primary">
            <Icon name="rocket_launch" className="text-[22px]" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-semibold">Asistente de configuración</span>
            <span className="block text-xs text-muted">Los 10 pasos para dejar tu tienda lista para vender</span>
          </span>
          <Icon name="chevron_right" className="shrink-0 text-[20px] text-muted" />
        </button>

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
                  className="flex w-full items-center gap-3 rounded-xl border border-line bg-card p-3 text-left transition-colors hover:border-primary/40"
                >
                  <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${done ? 'bg-success-soft text-success-ink' : 'bg-primary-tint text-primary'}`}>
                    <Icon name={done ? 'check_circle' : tour.icon} className="text-[22px]" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold">{tour.title}</span>
                    <span className="block truncate text-xs text-muted">{tour.description}</span>
                  </span>
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${done ? 'bg-success-soft text-success-ink' : 'bg-line-soft text-muted'}`}>
                    {done ? 'Repetir' : 'Empezar'}
                  </span>
                </button>
              )
            })}
          </div>
        </div>

        <div className="rounded-xl border border-line px-3">
          <Toggle
            checked={state.showContextualHelp}
            onChange={(v) => update({ showContextualHelp: v })}
            label="Mostrar ayudas (i) y (?)"
            hint="Muestra el ícono de ayuda junto a campos como Costo, Stock mínimo o Precio mínimo"
          />
        </div>

        {state.completedTours.length > 0 && (
          <button type="button" onClick={() => update({ completedTours: [], activeTour: null })} className="text-xs font-semibold text-muted hover:text-ink">
            Reiniciar el progreso de los recorridos
          </button>
        )}
      </div>
    </Modal>
  )
}
