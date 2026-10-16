/**
 * Báscula: etiquetas de balanza (EAN-13 que empieza por 2) y lectura de peso por puerto serial.
 * La lectura de etiquetas es igual a la del servidor y funciona sin internet con los ajustes guardados.
 */
import type { PosProduct, StoreSettings } from './posTypes'

/** Dígito de control EAN-13 de los primeros 12 dígitos. */
export function ean13Check(first12: string): number {
  let sum = 0
  for (let i = 0; i < 12; i++) sum += Number(first12[i]) * (i % 2 === 0 ? 1 : 3)
  return (10 - (sum % 10)) % 10
}

export interface ScaleHit {
  product: PosProduct
  presentationId: number
  quantity: number
  total: number
}

type ScaleSettings = Pick<StoreSettings, 'scalePrefixes' | 'scaleMode' | 'scalePluDigits'>

/** Lee una etiqueta de balanza contra el catálogo local. null si el código no es de balanza. */
export function parseScaleCode(code: string, settings: ScaleSettings, catalog: PosProduct[]): ScaleHit | { error: string } | null {
  const c = code.trim()
  if (!/^\d{13}$/.test(c)) return null
  const prefixes = (settings.scalePrefixes || '20,21,22,23,24,25,26,27,28,29').split(',')
  if (!prefixes.includes(c.slice(0, 2))) return null
  if (ean13Check(c.slice(0, 12)) !== Number(c[12])) return { error: `El código ${c} está mal leído. Vuelve a escanearlo.` }
  const n = settings.scalePluDigits || 5
  const plu = c.slice(2, 2 + n).replace(/^0+(?=\d)/, '')
  const value = Number(c.slice(2 + n, 12))
  const product = catalog.find((p) => p.plu === plu)
  if (!product) return { error: `Ningún producto tiene el código de báscula ${plu}` }
  const base = product.presentations.find((x) => x.isBase) ?? product.presentations[0]
  if (!base) return { error: `${product.name} no tiene precio` }
  let quantity: number
  let total: number
  if (settings.scaleMode === 'PRECIO') {
    total = value
    quantity = base.price > 0 ? Math.round((value / base.price) * 1000) / 1000 : 1
  } else {
    quantity = ['KILOGRAMO', 'LIBRA', 'LITRO', 'METRO'].includes(product.unit) ? value / 1000 : value
    total = Math.round(quantity * base.price)
  }
  if (!(quantity > 0)) return { error: 'La etiqueta no trae cantidad' }
  return { product, presentationId: base.id, quantity, total }
}

/** Arma el código EAN-13 de una etiqueta (para imprimirla desde la app). */
export function buildScaleCode(prefix: string, plu: string, value: number, pluDigits: number): string {
  const valueDigits = 10 - pluDigits
  const v = String(Math.round(value)).padStart(valueDigits, '0').slice(-valueDigits)
  const first12 = prefix + plu.padStart(pluDigits, '0').slice(-pluDigits) + v
  return first12 + ean13Check(first12)
}

// ---------------- Código de barras EAN-13 en SVG ----------------

const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011']
const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111']
const R = ['1110010', '1100110', '1101100', '1000010', '1011100', '1001110', '1010000', '1000100', '1001000', '1110100']
const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL']

/** Barras (1 = negra) de un EAN-13 válido: 95 módulos. */
export function ean13Bars(code: string): string {
  const d = code.split('').map(Number)
  const parity = PARITY[d[0]!]!
  let bits = '101'
  for (let i = 1; i <= 6; i++) bits += (parity[i - 1] === 'L' ? L : G)[d[i]!]
  bits += '01010'
  for (let i = 7; i <= 12; i++) bits += R[d[i]!]
  return bits + '101'
}

// ---------------- Báscula por puerto serial (Web Serial) ----------------

interface SerialPortLike {
  open(o: { baudRate: number }): Promise<void>
  close(): Promise<void>
  readable: ReadableStream<Uint8Array> | null
}

export function scaleSupported(): boolean {
  return typeof navigator !== 'undefined' && 'serial' in navigator
}

/**
 * Lee un peso de una báscula conectada por USB/serial. La mayoría envía el peso como texto
 * ("  1.250 kg"); se toma el primer número estable que llega. Devuelve kilogramos.
 */
export async function readScaleWeight(baudRate = 9600, timeoutMs = 4000): Promise<number> {
  const serial = (navigator as unknown as { serial: { requestPort(): Promise<SerialPortLike> } }).serial
  const port = await serial.requestPort()
  await port.open({ baudRate })
  const reader = port.readable!.getReader()
  const decoder = new TextDecoder()
  let text = ''
  const deadline = Date.now() + timeoutMs
  try {
    while (Date.now() < deadline) {
      const { value, done } = await Promise.race([
        reader.read(),
        new Promise<{ value: undefined; done: true }>((r) => setTimeout(() => r({ value: undefined, done: true }), deadline - Date.now())),
      ])
      if (done) break
      text += decoder.decode(value)
      const m = text.match(/(\d+[.,]\d+|\d+)\s*(kg|g|lb)?\s*[\r\n]/i)
      if (m) {
        const n = Number(m[1]!.replace(',', '.'))
        const unit = (m[2] ?? 'kg').toLowerCase()
        return unit === 'g' ? n / 1000 : unit === 'lb' ? n * 0.453592 : n
      }
    }
    throw new Error('La báscula no envió un peso. Revisa que esté encendida y conectada.')
  } finally {
    reader.releaseLock()
    await port.close().catch(() => {})
  }
}
