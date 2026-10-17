import { useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button, Icon } from '../ui'
import type { TourStep } from '../../lib/tours'

interface SpotRect {
  top: number
  left: number
  width: number
  height: number
}

interface Props {
  tourTitle: string
  steps: TourStep[]
  step: number
  onNext: () => void
  onPrev: () => void
  onSkip: () => void
  onClose: () => void
}

const PAD = 10

/** Capa superpuesta que resalta un elemento y muestra la instrucción del paso actual. */
export default function TourOverlay({ tourTitle, steps, step, onNext, onPrev, onSkip, onClose }: Props) {
  const s = steps[step]
  const [spot, setSpot] = useState<SpotRect | null>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const isLast = step === steps.length - 1

  useLayoutEffect(() => {
    if (!s.target) {
      setSpot(null)
      return
    }
    const el = document.querySelector(`[data-guide="${s.target}"]`) as HTMLElement | null
    if (!el) {
      setSpot(null)
      return
    }

    el.scrollIntoView({ block: 'center', behavior: 'smooth' })

    const measure = () => {
      const r = el.getBoundingClientRect()
      setSpot({ top: r.top - PAD, left: r.left - PAD, width: r.width + PAD * 2, height: r.height + PAD * 2 })
    }
    measure()
    const t = setTimeout(measure, 400)
    return () => clearTimeout(t)
  }, [s.target, step])

  // Posiciona el tooltip arriba o abajo del spotlight
  const tooltipStyle = (): React.CSSProperties => {
    if (!spot) {
      return { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' }
    }
    const vw = window.innerWidth
    const left = Math.min(Math.max(spot.left, 12), vw - 316)
    const below = spot.top < window.innerHeight * 0.55
    if (below) {
      return { top: spot.top + spot.height + 12, left }
    }
    return { bottom: window.innerHeight - spot.top + 12, left }
  }

  return createPortal(
    <div className="fixed inset-0 z-[9000]" role="dialog" aria-label={`Tutorial: ${tourTitle}`}>
      {/* Fondo oscuro — clic fuera pausa/cierra */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Spotlight */}
      {spot && (
        <div
          aria-hidden
          className="pointer-events-none absolute rounded-xl ring-2 ring-primary ring-offset-0 transition-all duration-300"
          style={{
            top: spot.top,
            left: spot.left,
            width: spot.width,
            height: spot.height,
            boxShadow: '0 0 0 9999px rgba(0,0,0,0.55)',
            zIndex: 1,
          }}
        />
      )}

      {/* Tooltip */}
      <div
        ref={tooltipRef}
        className="absolute z-10 w-[300px] rounded-2xl border border-line bg-card p-4 shadow-tier3 pointer-events-auto"
        style={tooltipStyle()}
      >
        {/* Encabezado */}
        <div className="flex items-start gap-2 mb-2">
          <div className="flex-1 min-w-0">
            <div className="text-[10px] font-bold tracking-wider text-muted uppercase truncate">{tourTitle}</div>
            <div className="font-bold text-[15px] leading-snug mt-0.5">{s.title}</div>
          </div>
          <button
            onClick={onClose}
            aria-label="Cerrar guía"
            className="shrink-0 rounded-lg p-1 text-muted hover:bg-canvas hover:text-foreground transition-colors"
          >
            <Icon name="close" className="text-[18px]" />
          </button>
        </div>

        <p className="text-sm text-muted leading-relaxed">{s.text}</p>

        {/* Puntos de progreso */}
        <div className="flex items-center gap-1.5 mt-3">
          {steps.map((_, i) => (
            <span
              key={i}
              className={`rounded-full transition-all duration-200 ${
                i === step ? 'w-4 h-1.5 bg-primary' : i < step ? 'w-1.5 h-1.5 bg-primary/40' : 'w-1.5 h-1.5 bg-line'
              }`}
            />
          ))}
          <span className="ml-auto text-[11px] font-semibold text-muted">
            {step + 1} / {steps.length}
          </span>
        </div>

        {/* Acciones */}
        <div className="flex items-center gap-2 mt-3">
          <button
            onClick={onSkip}
            className="text-xs text-muted hover:text-foreground transition-colors shrink-0"
          >
            Omitir guía
          </button>
          <div className="flex-1" />
          {step > 0 && (
            <Button variant="secondary" onClick={onPrev} className="!py-1.5 !px-3 !text-sm">
              Anterior
            </Button>
          )}
          <Button onClick={isLast ? onClose : onNext} icon={isLast ? 'check' : undefined} className="!py-1.5 !px-3 !text-sm">
            {isLast ? 'Terminar' : 'Siguiente'}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
