import { useEffect, useState } from 'react'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, Field, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { ProductDto, StoreSettings } from '../../lib/posTypes'
import { buildScaleCode, ean13Bars } from '../../lib/scale'

/** Código de barras EAN-13 en SVG (se imprime nítido en cualquier impresora). */
export function Ean13({ code, height = 46 }: { code: string; height?: number }) {
  const bars = ean13Bars(code)
  const w = 2
  return (
    <svg viewBox={`0 0 ${(bars.length + 14) * w} ${height + 14}`} className="h-auto w-full" role="img" aria-label={`Código ${code}`}>
      <rect width="100%" height="100%" fill="#fff" />
      {bars.split('').map((b, i) => (b === '1' ? <rect key={i} x={(i + 7) * w} y={2} width={w} height={height} fill="#000" /> : null))}
      <text x="50%" y={height + 12} textAnchor="middle" fontFamily="monospace" fontSize="11" fill="#000" letterSpacing="2">
        {code}
      </text>
    </svg>
  )
}

/** Etiquetas de peso o precio para productos de báscula (fruver, carnes, queso, granos a granel). */
export default function ScaleLabelsPage() {
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [product, setProduct] = useState<ProductDto | null>(null)
  const [value, setValue] = useState('')
  const [copies, setCopies] = useState('1')
  const [labels, setLabels] = useState<{ code: string; name: string; quantity: number; unitPrice: number; total: number; unit: string }[]>([])

  useEffect(() => {
    api<StoreSettings>('/settings/pos').then(setSettings).catch(() => {})
  }, [])
  if (!settings) return <Spinner />

  const base = product?.presentations.find((p) => p.isBase)
  const prefix = (settings.scalePrefixes || '20').split(',')[0] ?? '20'
  const byPrice = settings.scaleMode === 'PRECIO'
  const n = Number(value.replace(',', '.') || 0)
  const weightUnit = product && ['KILOGRAMO', 'LIBRA', 'LITRO', 'METRO'].includes(product.unit)
  const quantity = byPrice ? (base?.price ? n / base.price : 0) : n
  const total = byPrice ? n : Math.round(n * (base?.price ?? 0))
  const encoded = byPrice ? n : weightUnit ? n * 1000 : n

  function add() {
    if (!product?.plu || !base || !(n > 0)) return
    const code = buildScaleCode(prefix, product.plu, encoded, settings!.scalePluDigits)
    const one = { code, name: product.name, quantity, unitPrice: base.price, total, unit: product.unit }
    setLabels([...labels, ...Array.from({ length: Math.min(50, Math.max(1, Number(copies) || 1)) }, () => one)])
    setValue('')
  }

  return (
    <div className="space-y-4">
      <div className="print:hidden">
        <h1 className="text-2xl font-bold tracking-tight">Etiquetas de báscula</h1>
        <p className="text-sm text-muted">
          Pesa, imprime la etiqueta y pégala en la bolsa: en la caja se escanea y entra con su {byPrice ? 'precio' : 'peso'}.
        </p>
      </div>
      <Card className="space-y-3 p-4 print:hidden">
        <ProductPicker onPick={setProduct} placeholder="Producto con código de báscula (PLU)…" />
        {product && !product.plu && <Banner tone="warning">{product.name} no tiene código de báscula (PLU). Agrégalo en Editar producto.</Banner>}
        {product?.plu && base && (
          <>
            <div className="text-sm">
              <span className="font-semibold">{product.name}</span> · PLU {product.plu} · {money(base.price)} por {product.unit.toLowerCase()}
            </div>
            <div className="grid gap-3 sm:grid-cols-[1fr_120px_auto]">
              <Field
                label={byPrice ? 'Valor a cobrar' : `Peso (${product.unit === 'GRAMO' ? 'gramos' : product.unit === 'LIBRA' ? 'libras' : 'kg'})`}
                inputMode="decimal"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                hint={n > 0 ? (byPrice ? `${qty(Math.round(quantity * 1000) / 1000)} ${product.unit.toLowerCase()}` : `Total ${money(total)}`) : undefined}
              />
              <Field label="Copias" inputMode="numeric" value={copies} onChange={(e) => setCopies(e.target.value.replace(/\D/g, ''))} />
              <div className="flex items-end">
                <Button icon="add" onClick={add} disabled={!(n > 0)}>
                  Agregar
                </Button>
              </div>
            </div>
          </>
        )}
        {labels.length > 0 && (
          <div className="flex gap-2">
            <Button icon="print" onClick={() => window.print()}>
              Imprimir {labels.length} etiquetas
            </Button>
            <Button variant="ghost" onClick={() => setLabels([])}>
              Limpiar
            </Button>
          </div>
        )}
      </Card>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 print:grid-cols-3 print:gap-2">
        {labels.map((l, i) => (
          <div key={i} className="break-inside-avoid rounded-lg border border-black/30 bg-white p-2 text-black">
            <div className="truncate text-sm font-bold">{l.name}</div>
            <div className="num flex justify-between text-[11px]">
              <span>
                {qty(Math.round(l.quantity * 1000) / 1000)} {l.unit === 'KILOGRAMO' ? 'kg' : l.unit.toLowerCase()} × {money(l.unitPrice)}
              </span>
              <span className="font-bold">{money(l.total)}</span>
            </div>
            <Ean13 code={l.code} />
          </div>
        ))}
      </div>
    </div>
  )
}
