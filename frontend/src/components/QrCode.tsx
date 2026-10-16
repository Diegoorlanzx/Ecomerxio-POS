import qrcode from 'qrcode-generator'
import { useMemo } from 'react'

/** Código QR en SVG (nítido al imprimir). Corrección de errores media: aguanta carteles algo maltratados. */
export default function QrCode({ text, size = 160, className = '' }: { text: string; size?: number; className?: string }) {
  const { cells, n } = useMemo(() => {
    const q = qrcode(0, 'M')
    q.addData(text)
    q.make()
    const count = q.getModuleCount()
    const out: [number, number][] = []
    for (let r = 0; r < count; r++) for (let c = 0; c < count; c++) if (q.isDark(r, c)) out.push([c, r])
    return { cells: out, n: count }
  }, [text])
  const pad = 2
  const d = cells.map(([x, y]) => `M${x + pad} ${y + pad}h1v1h-1z`).join('')
  return (
    <svg viewBox={`0 0 ${n + pad * 2} ${n + pad * 2}`} width={size} height={size} className={className} role="img" aria-label={`Código QR de ${text}`} shapeRendering="crispEdges">
      <rect width="100%" height="100%" fill="#fff" />
      <path d={d} fill="#000" />
    </svg>
  )
}
