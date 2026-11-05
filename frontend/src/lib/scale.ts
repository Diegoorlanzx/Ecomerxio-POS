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

interface SerialOptions {
  baudRate: number
  dataBits: 7 | 8
  parity: 'none' | 'even' | 'odd'
  stopBits: 1 | 2
}

interface SerialPortLike {
  open(o: SerialOptions & { bufferSize?: number }): Promise<void>
  close(): Promise<void>
  readable: ReadableStream<Uint8Array> | null
  writable: WritableStream<Uint8Array> | null
  getInfo(): { usbVendorId?: number; usbProductId?: number }
}

interface SerialLike {
  requestPort(o?: object): Promise<SerialPortLike>
  getPorts(): Promise<SerialPortLike[]>
}

/** Báscula configurada en ESTE computador: cuál es (identificador USB) y a qué velocidad habla. */
export interface ScaleConfig extends SerialOptions {
  usbVendorId?: number
  usbProductId?: number
}

const KEY = 'tp_scale'

/** Velocidades que se prueban al detectar, de la más común a la menos común en básculas de mostrador. */
const CANDIDATES: SerialOptions[] = [
  { baudRate: 9600, dataBits: 8, parity: 'none', stopBits: 1 },
  { baudRate: 4800, dataBits: 8, parity: 'none', stopBits: 1 },
  { baudRate: 2400, dataBits: 8, parity: 'none', stopBits: 1 },
  { baudRate: 9600, dataBits: 7, parity: 'even', stopBits: 1 },
  { baudRate: 19200, dataBits: 8, parity: 'none', stopBits: 1 },
  { baudRate: 1200, dataBits: 8, parity: 'none', stopBits: 1 },
  { baudRate: 4800, dataBits: 7, parity: 'even', stopBits: 1 },
  { baudRate: 2400, dataBits: 7, parity: 'even', stopBits: 1 },
]

export function scaleSupported(): boolean {
  return typeof navigator !== 'undefined' && 'serial' in navigator
}

function serialApi(): SerialLike {
  return (navigator as unknown as { serial: SerialLike }).serial
}

export function loadScaleConfig(): ScaleConfig | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as ScaleConfig) : null
  } catch {
    return null
  }
}

function saveScaleConfig(c: ScaleConfig) {
  try {
    localStorage.setItem(KEY, JSON.stringify(c))
  } catch {
    /* sin almacenamiento: se vuelve a detectar la próxima vez */
  }
}

/** Olvida la báscula de este computador: la próxima lectura pregunta cuál es y detecta la velocidad. */
export function forgetScale() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* nada que olvidar */
  }
}

export function describeScale(c: ScaleConfig | null): string {
  if (!c) return 'Sin configurar'
  const frame = `${c.dataBits}${c.parity === 'none' ? 'N' : c.parity === 'even' ? 'E' : 'O'}${c.stopBits}`
  return `${c.baudRate} baudios · ${frame}`
}

/** Error de lectura con su causa, para decidir si vale la pena probar otra velocidad. */
export class ScaleError extends Error {
  readonly kind: 'NO_DATA' | 'GARBLED' | 'NO_PORT'
  constructor(message: string, kind: 'NO_DATA' | 'GARBLED' | 'NO_PORT') {
    super(message)
    this.kind = kind
  }
}

/**
 * El puerto de la báscula: el que se guardó en este computador (por su identificador USB) entre los que el
 * navegador ya autorizó. Solo pregunta si no hay ninguno autorizado o si se pide cambiar de báscula.
 */
async function scalePort(saved: ScaleConfig | null, choose: boolean): Promise<SerialPortLike> {
  const serial = serialApi()
  if (!choose) {
    const known = await serial.getPorts().catch(() => [] as SerialPortLike[])
    const same =
      saved?.usbVendorId != null
        ? known.find((p) => p.getInfo().usbVendorId === saved.usbVendorId && p.getInfo().usbProductId === saved.usbProductId)
        : undefined
    const port = same ?? known[known.length - 1]
    if (port) return port
  }
  try {
    return await serial.requestPort()
  } catch {
    throw new ScaleError('No se eligió la báscula.', 'NO_PORT')
  }
}

/** Kilos de una línea de la báscula ("ST,GS,  1.250kg", "  1,250 kg", "1250 g"). null si no trae un peso estable. */
export function parseWeightLine(line: string): number | null {
  if (/\bUS\b|unstable/i.test(line)) return null // peso moviéndose: se espera el estable
  const m = line.match(/(\d+(?:[.,]\d+)?)\s*(kg|g|lb)?\b/i)
  if (!m) return null
  const n = Number(m[1]!.replace(',', '.'))
  if (!Number.isFinite(n) || n < 0) return null
  const unit = (m[2] ?? 'kg').toLowerCase()
  return unit === 'g' ? n / 1000 : unit === 'lb' ? n * 0.453592 : n
}

/** Proporción de caracteres legibles: con la velocidad equivocada llega basura. */
const printable = (t: string) => (t.length ? t.replace(/[^\x20-\x7e\r\n\t]/g, '').length / t.length : 1)

/** Abre el puerto con una configuración y espera un peso; los errores de trama no cortan la lectura. */
async function readOnce(port: SerialPortLike, opts: SerialOptions, timeoutMs: number): Promise<number> {
  await port.open({ ...opts, bufferSize: 4096 })
  const decoder = new TextDecoder()
  const start = Date.now()
  const deadline = start + timeoutMs
  let text = ''
  let glitches = 0
  let polled = false
  try {
    while (Date.now() < deadline && port.readable) {
      const reader = port.readable.getReader()
      try {
        while (Date.now() < deadline) {
          // Algunas básculas solo mandan el peso cuando se les pide: si no llega nada, se pregunta una vez.
          if (!polled && !text && Date.now() - start > 700 && port.writable) {
            polled = true
            const w = port.writable.getWriter()
            await w.write(new TextEncoder().encode('\x05W\r\n')).catch(() => {})
            w.releaseLock()
          }
          const wait = Math.max(50, Math.min(300, deadline - Date.now()))
          const got = await Promise.race([reader.read(), new Promise<'timeout'>((r) => setTimeout(() => r('timeout'), wait))])
          if (got === 'timeout') continue
          if (got.done) break
          text += decoder.decode(got.value, { stream: true })
          const lines = text.split(/[\r\n]+/)
          // La última parte puede venir cortada: solo se leen líneas completas (o la última si termina en la unidad).
          const complete = lines.slice(0, -1)
          if (/(kg|g|lb)\s*$/i.test(lines[lines.length - 1]!)) complete.push(lines[lines.length - 1]!)
          for (const line of complete.reverse()) {
            const kg = parseWeightLine(line)
            if (kg !== null && printable(line) > 0.9) return kg
          }
          if (text.length > 400) text = text.slice(-200)
        }
      } catch (e) {
        // Framing, paridad o "break": la báscula habla a otra velocidad. El puerto sigue abierto: se sigue leyendo.
        const what = `${(e as Error)?.name ?? ''} ${(e as Error)?.message ?? ''}`
        if (!/Framing|Parity|Break|Buffer/i.test(what)) throw e
        glitches++
      } finally {
        await reader.cancel().catch(() => {})
        reader.releaseLock()
      }
    }
  } finally {
    await port.close().catch(() => {})
  }
  if (glitches > 0 || printable(text) < 0.9) throw new ScaleError('La báscula manda datos a otra velocidad.', 'GARBLED')
  throw new ScaleError('La báscula no envió un peso. Revisa que esté encendida, conectada y con algo encima.', 'NO_DATA')
}

/**
 * Prueba las velocidades comunes hasta que la báscula entregue un peso legible y guarda la que sirvió (y cuál
 * báscula es) para este computador. `choose` = preguntar de nuevo cuál es la báscula.
 */
export async function detectScale(choose = false, onTry?: (label: string) => void): Promise<{ kg: number; config: ScaleConfig }> {
  const port = await scalePort(choose ? null : loadScaleConfig(), choose)
  const info = port.getInfo()
  let sawData = false
  for (const opts of CANDIDATES) {
    onTry?.(describeScale(opts))
    try {
      const kg = await readOnce(port, opts, 1800)
      const config: ScaleConfig = { ...opts, usbVendorId: info.usbVendorId, usbProductId: info.usbProductId }
      saveScaleConfig(config)
      return { kg, config }
    } catch (e) {
      if (!(e instanceof ScaleError)) throw e
      if (e.kind === 'GARBLED') sawData = true
    }
  }
  throw new ScaleError(
    sawData
      ? 'La báscula envía datos pero no se entienden a ninguna velocidad. Revisa en su manual la configuración de la salida RS-232.'
      : 'La báscula no envió ningún dato. Revisa el cable, que esté encendida y que tenga activada la salida de datos (RS-232) en su menú.',
    sawData ? 'GARBLED' : 'NO_DATA',
  )
}

/**
 * Peso en kilogramos de la báscula configurada en este computador, sin preguntar cuál es. La primera vez (o si la
 * velocidad guardada deja de servir) detecta sola la velocidad y la guarda.
 */
export async function readScaleWeight(): Promise<number> {
  const saved = loadScaleConfig()
  if (!saved) return (await detectScale()).kg
  const port = await scalePort(saved, false)
  try {
    return await readOnce(port, saved, 3000)
  } catch (e) {
    if (e instanceof ScaleError && e.kind === 'GARBLED') return (await detectScale()).kg
    throw e
  }
}
