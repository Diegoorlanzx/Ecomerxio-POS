import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Icon, useToast, HelpVisibility } from '../ui'
import { useAuth } from '../../lib/auth'
import { loadGuide, saveGuide, type GuideState } from '../../lib/guide'
import { tourById } from '../../lib/tours'
import GuidePanel from './GuidePanel'
import SetupWizard from './SetupWizard'
import TourOverlay from './TourOverlay'

interface GuideCtx {
  state: GuideState
  update: (patch: Partial<GuideState>) => void
  startTour: (id: string, step?: number) => void
  openPanel: () => void
  openWizard: () => void
}

const Ctx = createContext<GuideCtx | null>(null)

export function useGuide() {
  return useContext(Ctx)
}

/** Dueño del estado de la guía: recorrido en curso, panel, asistente y aviso de recorrido pausado. */
export function GuideProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const tenantId = me?.tenant.id ?? 0
  const userId = me?.id ?? 0
  const [state, setState] = useState(() => loadGuide(tenantId, userId))
  const [running, setRunning] = useState<{ id: string; step: number; dir: 1 | -1 } | null>(null)
  const [panel, setPanel] = useState(false)
  const [wizard, setWizard] = useState(false)

  useEffect(() => setState(loadGuide(tenantId, userId)), [tenantId, userId])

  const update = useCallback(
    (patch: Partial<GuideState>) =>
      setState((prev) => {
        const next = { ...prev, ...patch }
        saveGuide(tenantId, userId, next)
        return next
      }),
    [tenantId, userId],
  )

  const tour = running ? tourById(running.id) : undefined

  const startTour = useCallback(
    (id: string, step = 0) => {
      const t = tourById(id)
      if (!t) return
      setPanel(false)
      setWizard(false)
      if (!pathname.startsWith(t.route)) navigate(t.route)
      setRunning({ id, step, dir: 1 })
      update({ activeTour: { id, step } })
    },
    [navigate, pathname, update],
  )

  const goTo = useCallback(
    (step: number, dir: 1 | -1) => {
      if (!running || !tour) return
      if (step >= tour.steps.length) {
        setRunning(null)
        update({ activeTour: null, completedTours: [...new Set([...state.completedTours, tour.id])] })
        toast(`Recorrido "${tour.title}" completado`)
        return
      }
      const s = Math.max(0, step)
      setRunning({ id: tour.id, step: s, dir: s === 0 ? 1 : dir })
      update({ activeTour: { id: tour.id, step: s } })
    },
    [running, tour, update, state.completedTours, toast],
  )

  // Un paso puede abrir otra pestaña de la misma pantalla antes de señalar su elemento.
  const stepGo = running && tour ? tour.steps[running.step]?.go : undefined
  useEffect(() => {
    if (stepGo && pathname + search !== stepGo) navigate(stepGo, { replace: true })
  }, [stepGo, pathname, search, navigate])

  const pause = useCallback(() => {
    setRunning(null)
    toast('Guía pausada. Puedes reanudarla cuando quieras.')
  }, [toast])

  // Si el usuario sale de la pantalla del recorrido (después de haber llegado), queda pausado.
  const reached = useRef(false)
  useEffect(() => {
    if (!running || !tour) {
      reached.current = false
      return
    }
    if (pathname.startsWith(tour.route)) reached.current = true
    else if (reached.current) setRunning(null)
  }, [pathname, running, tour])

  useEffect(() => {
    if (pathname.startsWith('/dashboard') && !state.seenDashboard) update({ seenDashboard: true })
  }, [pathname, state.seenDashboard, update])

  const value = useMemo<GuideCtx>(
    () => ({ state, update, startTour, openPanel: () => setPanel(true), openWizard: () => setWizard(true) }),
    [state, update, startTour],
  )

  const paused = !running && state.activeTour ? tourById(state.activeTour.id) : undefined

  return (
    <Ctx.Provider value={value}>
      <HelpVisibility.Provider value={state.showContextualHelp}>{children}</HelpVisibility.Provider>
      {running && tour && (
        <TourOverlay
          key={tour.id}
          tourTitle={tour.title}
          steps={tour.steps}
          step={running.step}
          onNext={() => goTo(running.step + 1, 1)}
          onPrev={() => goTo(running.step - 1, -1)}
          onMissing={() => goTo(running.step + running.dir, running.dir)}
          onSkip={() => (setRunning(null), update({ activeTour: null }))}
          onPause={pause}
        />
      )}
      {paused && state.activeTour && (
        <div
          role="status"
          className="fixed right-3 bottom-36 z-40 flex max-w-[calc(100vw-1.5rem)] items-center gap-2 rounded-2xl border border-line bg-card py-2 pr-2 pl-3 shadow-tier3 lg:right-6 lg:bottom-6 print:hidden"
        >
          <Icon name="school" className="text-[22px] text-primary" />
          <div className="min-w-0 text-sm leading-tight">
            <div className="truncate font-semibold">Guía pausada: {paused.title}</div>
            <div className="text-xs text-muted">
              Paso {state.activeTour.step + 1} de {paused.steps.length}
            </div>
          </div>
          <Button size="sm" onClick={() => startTour(paused.id, state.activeTour!.step)}>
            Reanudar
          </Button>
          <button type="button" aria-label="Descartar guía pausada" onClick={() => update({ activeTour: null })} className="rounded-lg p-1.5 text-muted hover:bg-canvas">
            <Icon name="close" className="text-[18px]" />
          </button>
        </div>
      )}
      {panel && <GuidePanel onClose={() => setPanel(false)} />}
      {wizard && <SetupWizard onClose={() => setWizard(false)} />}
    </Ctx.Provider>
  )
}
