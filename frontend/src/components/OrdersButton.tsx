import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { Icon } from './ui'

/** Dos tonos cortos (no necesita archivos de sonido). */
function beep() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    const ctx = new Ctx()
    ;[880, 1320].forEach((f, i) => {
      const o = ctx.createOscillator()
      const g = ctx.createGain()
      o.frequency.value = f
      o.type = 'sine'
      g.gain.setValueAtTime(0.0001, ctx.currentTime + i * 0.18)
      g.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + i * 0.18 + 0.02)
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + i * 0.18 + 0.16)
      o.connect(g).connect(ctx.destination)
      o.start(ctx.currentTime + i * 0.18)
      o.stop(ctx.currentTime + i * 0.18 + 0.17)
    })
    setTimeout(() => ctx.close(), 800)
  } catch {
    /* sin audio */
  }
}

/** Pedidos en línea por atender, con aviso sonoro cuando llega uno nuevo. Revisa cada 20 segundos. */
export default function OrdersButton({ online }: { online: boolean }) {
  const [n, setN] = useState(0)
  const last = useRef<number | null>(null)
  useEffect(() => {
    if (!online) return
    const load = () =>
      api<{ nuevos: number; activos: number }>('/online-orders/counts')
        .then((c) => {
          if (last.current !== null && c.nuevos > last.current) {
            beep()
            navigator.vibrate?.([120, 80, 120])
          }
          last.current = c.nuevos
          setN(c.nuevos)
        })
        .catch(() => {})
    load()
    const t = setInterval(load, 20000)
    return () => clearInterval(t)
  }, [online])
  return (
    <Link
      to="/pedidos"
      aria-label={n ? `${n} pedidos en línea nuevos` : 'Pedidos en línea'}
      className={`relative flex h-10 items-center gap-1.5 rounded-xl px-2.5 text-sm font-semibold ${n ? 'bg-danger-soft text-danger-ink' : 'text-muted hover:bg-line-soft'}`}
    >
      <Icon name="shopping_bag" className="text-[22px]" />
      <span className="hidden sm:inline">Pedidos</span>
      {n > 0 && <span className="num flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1 text-[11px] font-bold text-white">{n}</span>}
    </Link>
  )
}
