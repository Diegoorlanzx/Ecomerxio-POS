import { useState } from 'react'
import { Button, Card, Chip, Icon } from '../../components/ui'
import { useAuth } from '../../lib/auth'
import { loadGuide } from '../../lib/guide'
import { TOURS } from '../../lib/tours'
import { GuidePanel } from '../../components/guide'

/** Página dedicada a la guía interactiva — accesible desde Más → Guía interactiva. */
export default function GuidePage() {
  const { me } = useAuth()
  const tenantId = me?.tenant.id ?? 0
  const userId = me?.id ?? 0
  const state = loadGuide(tenantId, userId)
  const [panelOpen, setPanelOpen] = useState(false)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="flex-1 text-2xl font-bold tracking-tight">Guía interactiva</h1>
        <Button icon="rocket_launch" onClick={() => setPanelOpen(true)}>
          Abrir guía
        </Button>
      </div>

      {/* Asistente de configuración */}
      <Card className="p-4">
        <div className="flex items-start gap-3">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <Icon name="rocket_launch" className="text-[26px]" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="font-semibold">Asistente de configuración</h2>
            <p className="text-sm text-muted mt-0.5">Lista de pasos para dejar tu tienda lista para vender por primera vez.</p>
          </div>
          <Button variant="secondary" onClick={() => setPanelOpen(true)} className="shrink-0">
            Ver
          </Button>
        </div>
      </Card>

      {/* Recorridos */}
      <section>
        <h2 className="mb-3 text-sm font-bold tracking-wider text-muted uppercase">Recorridos guiados disponibles</h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {TOURS.map((tour) => {
            const done = state.completedTours.includes(tour.id)
            const active = state.activeTour?.id === tour.id
            return (
              <button
                key={tour.id}
                type="button"
                onClick={() => setPanelOpen(true)}
                className="flex items-start gap-3 rounded-2xl border border-line bg-card p-4 text-left shadow-tier1 hover:border-primary/40 transition-colors"
              >
                <div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl ${done ? 'bg-success-soft text-success-ink' : 'bg-primary-tint text-primary'}`}>
                  <Icon name={done ? 'check_circle' : tour.icon} className="text-[24px]" />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-semibold">{tour.title}</span>
                    {done && <Chip tone="success">Completado</Chip>}
                    {active && !done && <Chip tone="primary">En curso</Chip>}
                  </div>
                  <p className="text-xs text-muted mt-0.5">{tour.description}</p>
                  <p className="text-xs text-muted mt-1">{tour.steps.length} pasos · empieza en {tour.route}</p>
                </div>
              </button>
            )
          })}
        </div>
      </section>

      {/* Ayudas contextuales */}
      <Card className="p-4">
        <div className="flex items-start gap-3">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-canvas text-muted">
            <Icon name="help_outline" className="text-[26px]" />
          </div>
          <div className="flex-1">
            <h2 className="font-semibold">Ayudas contextuales</h2>
            <p className="text-sm text-muted mt-0.5">
              Iconos de ayuda <Icon name="help_outline" className="text-[16px] align-[-3px]" /> junto a campos como Costo, Stock mínimo y Margen de ganancia.
              Toca uno para ver la explicación en el momento en que lo necesitas.
            </p>
            <p className="text-xs text-muted mt-1">
              Estado actual: <span className="font-semibold">{state.showContextualHelp ? 'activadas' : 'desactivadas'}</span> —
              cámbialas en <button onClick={() => setPanelOpen(true)} className="text-primary font-semibold">Abrir guía</button>.
            </p>
          </div>
        </div>
      </Card>

      {panelOpen && <GuidePanel onClose={() => setPanelOpen(false)} />}
    </div>
  )
}
