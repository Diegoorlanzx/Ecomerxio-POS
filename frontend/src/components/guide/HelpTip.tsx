import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '../ui'
import { useGuide } from './GuideProvider'
import { portalRoot } from '../../lib/host'

interface Props {
  text: ReactNode
  title?: string
  className?: string
  /** Se muestra aunque el usuario haya apagado las ayudas contextuales (explica un número, no un campo). */
  always?: boolean
  /** Ícono: "help" (?) o "info" (i). */
  icon?: 'help' | 'info'
}

const W = 256

/** Ícono de ayuda junto a un campo: al tocarlo muestra una explicación corta. */
export default function HelpTip({ text, title, className = '', always = false, icon = 'help' }: Props) {
  const guide = useGuide()
  const id = useId()
  const btnRef = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState<{ top?: number; bottom?: number; left: number } | null>(null)

  useEffect(() => {
    if (!pos) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent && e.key !== 'Escape') return
      if (e instanceof MouseEvent && btnRef.current?.contains(e.target as Node)) return
      setPos(null)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    window.addEventListener('scroll', close, true)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [pos])

  if (guide && !guide.state.showContextualHelp && !always) return null

  function open() {
    const r = btnRef.current!.getBoundingClientRect()
    const left = Math.min(Math.max(r.left + r.width / 2 - W / 2, 12), window.innerWidth - W - 12)
    setPos(r.top > window.innerHeight * 0.6 ? { bottom: window.innerHeight - r.top + 8, left } : { top: r.bottom + 8, left })
  }

  function toggle(e: React.MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    if (pos) return setPos(null)
    open()
  }

  // Con mouse, se abre al pasar por encima; en pantallas táctiles, al tocar.
  const hover = typeof window !== 'undefined' && window.matchMedia?.('(hover: hover)').matches

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        onClick={toggle}
        onMouseEnter={hover ? open : undefined}
        onMouseLeave={hover ? () => setPos(null) : undefined}
        aria-label={title ? `Ayuda: ${title}` : 'Ayuda'}
        aria-expanded={!!pos}
        aria-describedby={pos ? id : undefined}
        className={`inline-flex h-5 w-5 items-center justify-center rounded-full text-muted transition-colors hover:text-primary focus-visible:ring-2 focus-visible:ring-primary ${className}`}
      >
        <Icon name={icon} className="text-[16px]" />
      </button>
      {pos &&
        createPortal(
          <div
            id={id}
            role="tooltip"
            style={{ position: 'fixed', width: W, zIndex: 8000, ...pos }}
            className="rounded-xl border border-line bg-card p-3 text-left text-sm normal-case tracking-normal shadow-tier3"
          >
            {title && <div className="mb-1 font-semibold text-ink">{title}</div>}
            <div className="leading-relaxed text-muted">{text}</div>
          </div>,
          portalRoot(),
        )}
    </>
  )
}
