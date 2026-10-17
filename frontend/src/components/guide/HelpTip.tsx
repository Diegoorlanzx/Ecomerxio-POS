import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '../ui'

interface Props {
  /** Texto de ayuda a mostrar */
  text: string
  /** Texto opcional del título */
  title?: string
  className?: string
}

/** Icono de ayuda inline que muestra un tooltip al hacer clic. */
export default function HelpTip({ text, title, className = '' }: Props) {
  const [open, setOpen] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState({ top: 0, left: 0, above: false })

  useEffect(() => {
    if (!open) return
    const el = btnRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const above = r.top > window.innerHeight * 0.6
    setPos({
      top: above ? r.top - 8 : r.bottom + 8,
      left: Math.min(Math.max(r.left - 120, 12), window.innerWidth - 280),
      above,
    })

    const close = (e: MouseEvent) => {
      if (!el.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label="Ayuda"
        className={`inline-flex items-center justify-center rounded-full text-muted hover:text-primary transition-colors ${className}`}
      >
        <Icon name="help_outline" className="text-[16px]" />
      </button>

      {open &&
        createPortal(
          <div
            role="tooltip"
            style={{
              position: 'fixed',
              top: pos.above ? undefined : pos.top,
              bottom: pos.above ? window.innerHeight - pos.top : undefined,
              left: pos.left,
              zIndex: 8000,
            }}
            className="w-64 rounded-xl border border-line bg-card p-3 shadow-tier3 text-sm pointer-events-none animate-fade-in"
          >
            {title && <div className="font-semibold mb-1">{title}</div>}
            <p className="text-muted leading-relaxed">{text}</p>
          </div>,
          document.body,
        )}
    </>
  )
}
