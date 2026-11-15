import { useCallback, useEffect, useRef, useState, type HTMLAttributes, type ReactNode } from 'react'
import { Icon } from './ui'

/**
 * Fila de categorías deslizable. Si no caben, se difuminan los bordes para que se note que hay más y, en
 * computador, aparecen flechas. En el celular se desliza con el dedo de borde a borde.
 */
export function CategoryBar({
  children,
  className = '',
  scrollerClassName = '',
  fade = 'card',
  ...rest
}: {
  children: ReactNode
  /** Contenedor (ancho, márgenes). */
  className?: string
  /** Relleno de la fila deslizable. */
  scrollerClassName?: string
  /** Color del fondo detrás de la fila, para el difuminado de los bordes. */
  fade?: 'card' | 'canvas'
} & HTMLAttributes<HTMLDivElement>) {
  const ref = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState({ left: false, right: false })

  const measure = useCallback(() => {
    const el = ref.current
    if (!el) return
    setEdges({ left: el.scrollLeft > 4, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4 })
  }, [])

  useEffect(() => {
    const el = ref.current
    if (!el) return
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [measure])

  const scroll = (dir: 1 | -1) => ref.current?.scrollBy({ left: dir * ref.current.clientWidth * 0.7, behavior: 'smooth' })

  return (
    <div className={`relative ${className}`} {...rest}>
      <div ref={ref} onScroll={measure} className={`no-scrollbar flex gap-2 overflow-x-auto scroll-smooth ${scrollerClassName}`}>
        {children}
      </div>
      <div
        aria-hidden
        className={`pointer-events-none absolute inset-y-0 left-0 w-12 bg-gradient-to-r ${fade === 'card' ? 'from-card' : 'from-canvas'} to-transparent transition-opacity ${edges.left ? 'opacity-100' : 'opacity-0'}`}
      />
      <div
        aria-hidden
        className={`pointer-events-none absolute inset-y-0 right-0 w-12 bg-gradient-to-l ${fade === 'card' ? 'from-card' : 'from-canvas'} to-transparent transition-opacity ${edges.right ? 'opacity-100' : 'opacity-0'}`}
      />
      {edges.left && (
        <button
          type="button"
          aria-label="Ver categorías anteriores"
          onClick={() => scroll(-1)}
          className="absolute top-0.5 left-1 hidden h-9 w-9 items-center justify-center rounded-full bg-card text-ink shadow-tier2 ring-1 ring-line hover:text-primary [@media(hover:hover)]:flex"
        >
          <Icon name="chevron_left" className="text-[22px]" />
        </button>
      )}
      {edges.right && (
        <button
          type="button"
          aria-label="Ver más categorías"
          onClick={() => scroll(1)}
          className="absolute top-0.5 right-1 hidden h-9 w-9 items-center justify-center rounded-full bg-card text-ink shadow-tier2 ring-1 ring-line hover:text-primary [@media(hover:hover)]:flex"
        >
          <Icon name="chevron_right" className="text-[22px]" />
        </button>
      )}
    </div>
  )
}
