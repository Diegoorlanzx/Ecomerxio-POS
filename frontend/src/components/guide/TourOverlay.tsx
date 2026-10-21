import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { Button, Icon } from '../ui'
import type { TourStep } from '../../lib/tours'
import { portalRoot, queryRoot } from '../../lib/host'

interface Rect {
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
  /** El elemento del paso no está en pantalla (p. ej. depende de si hay caja abierta): saltarlo. */
  onMissing: () => void
  onSkip: () => void
  onPause: () => void
}

const PAD = 8
const GAP = 12
const MARGIN = 12
const WAIT_MS = 2500
const READY_WAIT_MS = 500

/** Si ya hay otros puntos de la guía en pantalla, la página cargó y el elemento que falta no va a aparecer. */
const pageReady = () =>
  [...queryRoot().querySelectorAll<HTMLElement>('[data-guide], main h1')].some((e) => e.offsetParent !== null)

/** Botones, enlaces y títulos que un paso puede señalar por su texto visible ("text:Nuevo producto"). */
const TEXT_TARGETS = 'button, a, h1, h2, h3, label, b, strong, span'

function candidates(target: string): Iterable<HTMLElement> {
  const root = queryRoot()
  if (target.startsWith('css:')) return root.querySelectorAll<HTMLElement>(`main ${target.slice(4)}`)
  if (target.startsWith('text:')) {
    const wanted = target.slice(5).toLowerCase()
    return [...root.querySelectorAll<HTMLElement>(`main :is(${TEXT_TARGETS})`)].filter((e) => (e.textContent ?? '').toLowerCase().includes(wanted))
  }
  return root.querySelectorAll<HTMLElement>(`[data-guide="${target}"]`)
}

/**
 * Primer elemento visible para el paso (hay pantallas con versión de PC y de celular). El target es un data-guide, o
 * "css:<selector>" / "text:<texto>" dentro del contenido de la página, para no tener que marcar cada pantalla.
 */
function findTarget(target: string): HTMLElement | null {
  for (const el of candidates(target)) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.height > 0) return el
  }
  return null
}

/** Capa que resalta el elemento real del paso y muestra la instrucción junto a él. */
export default function TourOverlay({ tourTitle, steps, step, onNext, onPrev, onMissing, onSkip, onPause }: Props) {
  const s = steps[step]
  const isLast = step === steps.length - 1
  const [el, setEl] = useState<HTMLElement | null>(null)
  const [searching, setSearching] = useState(!!s.target)
  const [spot, setSpot] = useState<Rect | null>(null)
  const [box, setBox] = useState<CSSProperties>({ visibility: 'hidden' })
  const tipRef = useRef<HTMLDivElement>(null)
  const missingRef = useRef(onMissing)
  missingRef.current = onMissing

  // Busca el elemento (la pantalla puede estar cargando todavía).
  useEffect(() => {
    setEl(null)
    setSpot(null)
    if (!s.target) {
      setSearching(false)
      return
    }
    setSearching(true)
    const started = Date.now()
    const t = setInterval(() => {
      const found = findTarget(s.target)
      if (found) {
        clearInterval(t)
        const big = found.getBoundingClientRect().height > window.innerHeight * 0.6
        found.scrollIntoView({ block: big ? 'start' : 'center', behavior: 'smooth' })
        setEl(found)
        setSearching(false)
      } else if (Date.now() - started > (pageReady() ? READY_WAIT_MS : WAIT_MS)) {
        clearInterval(t)
        missingRef.current()
      }
    }, 120)
    return () => clearInterval(t)
  }, [s.target, step])

  const layout = useCallback(() => {
    const vw = window.innerWidth
    const vh = window.innerHeight
    const tip = tipRef.current
    const tw = Math.min(320, vw - MARGIN * 2)
    const th = tip?.offsetHeight ?? 200
    if (!el) {
      setSpot(null)
      setBox({ width: tw, top: Math.max(MARGIN, (vh - th) / 2), left: (vw - tw) / 2 })
      return
    }
    const r = el.getBoundingClientRect()
    const sp = { top: r.top - PAD, left: r.left - PAD, width: r.width + PAD * 2, height: r.height + PAD * 2 }
    setSpot(sp)
    const clampX = (x: number) => Math.min(Math.max(x, MARGIN), vw - tw - MARGIN)
    const clampY = (y: number) => Math.min(Math.max(y, MARGIN), vh - th - MARGIN)
    const below = vh - (sp.top + sp.height)
    const above = sp.top
    let top: number
    let left = clampX(sp.left + sp.width / 2 - tw / 2)
    if (below >= th + GAP + MARGIN) top = sp.top + sp.height + GAP
    else if (above >= th + GAP + MARGIN) top = sp.top - GAP - th
    else if (sp.left >= tw + GAP + MARGIN) {
      left = sp.left - GAP - tw
      top = clampY(sp.top + sp.height / 2 - th / 2)
    } else if (vw - (sp.left + sp.width) >= tw + GAP + MARGIN) {
      left = sp.left + sp.width + GAP
      top = clampY(sp.top + sp.height / 2 - th / 2)
    } else {
      // Elemento casi del tamaño de la pantalla: la instrucción va abajo, encima de él.
      top = vh - th - MARGIN
    }
    setBox({ width: tw, top, left })
  }, [el])

  useLayoutEffect(() => {
    layout()
    const id = setInterval(layout, 250) // sigue al elemento mientras termina el scroll o cambia la pantalla
    window.addEventListener('resize', layout)
    window.addEventListener('scroll', layout, true)
    return () => {
      clearInterval(id)
      window.removeEventListener('resize', layout)
      window.removeEventListener('scroll', layout, true)
    }
  }, [layout, step, searching])

  useEffect(() => {
    tipRef.current?.focus()
  }, [step, el])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onPause()
      else if (e.key === 'ArrowRight') onNext()
      else if (e.key === 'ArrowLeft' && step > 0) onPrev()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onNext, onPrev, onPause, step])

  if (searching) {
    return createPortal(
      <div className="fixed inset-0 z-[9000] bg-black/40" aria-busy>
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 rounded-xl bg-card px-4 py-3 text-sm font-semibold shadow-tier3">Buscando el siguiente paso…</div>
      </div>,
      portalRoot(),
    )
  }

  return createPortal(
    <div className="fixed inset-0 z-[9000]">
      {/* Clic fuera = pausar. Con elemento resaltado, el oscurecido lo hace la sombra del recuadro. */}
      <div className={`absolute inset-0 ${spot ? '' : 'bg-black/55'}`} onClick={onPause} />
      {spot && (
        <div
          aria-hidden
          className="pointer-events-none absolute rounded-xl ring-[3px] ring-primary transition-all duration-200"
          style={{ ...spot, boxShadow: '0 0 0 9999px rgba(15,23,42,0.55)' }}
        />
      )}
      <div
        ref={tipRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="tour-step-title"
        aria-describedby="tour-step-text"
        className="absolute rounded-2xl border border-line bg-card p-4 shadow-tier3 outline-none transition-[top,left] duration-200"
        style={box}
      >
        <div className="mb-2 flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <div className="truncate text-[10px] font-bold tracking-wider text-muted uppercase">{tourTitle}</div>
            <div id="tour-step-title" className="mt-0.5 text-[15px] leading-snug font-bold">
              {s.title}
            </div>
          </div>
          <button type="button" onClick={onPause} aria-label="Pausar guía" title="Pausar (Esc)" className="shrink-0 rounded-lg p-1 text-muted hover:bg-canvas">
            <Icon name="pause" className="text-[18px]" />
          </button>
        </div>
        <p id="tour-step-text" className="text-sm leading-relaxed text-muted" aria-live="polite">
          {s.text}
        </p>
        <div className="mt-3 flex items-center gap-1.5" aria-hidden>
          {steps.map((_, i) => (
            <span key={i} className={`h-1.5 rounded-full transition-all ${i === step ? 'w-4 bg-primary' : i < step ? 'w-1.5 bg-primary/40' : 'w-1.5 bg-line'}`} />
          ))}
          <span className="num ml-auto text-[11px] font-semibold text-muted">
            {step + 1} / {steps.length}
          </span>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <button type="button" onClick={onSkip} className="shrink-0 text-xs font-semibold text-muted hover:text-ink">
            Omitir
          </button>
          <div className="flex-1" />
          {step > 0 && (
            <Button size="sm" variant="secondary" onClick={onPrev}>
              Anterior
            </Button>
          )}
          <Button size="sm" icon={isLast ? 'check' : undefined} onClick={onNext}>
            {isLast ? 'Terminar' : 'Siguiente'}
          </Button>
        </div>
      </div>
    </div>,
    portalRoot(),
  )
}
