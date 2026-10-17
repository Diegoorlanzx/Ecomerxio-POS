/**
 * Dónde está corriendo la app. Sola (pos.ecomerxio.com o localhost) todo cuelga de la raíz; incrustada como
 * microfrontend en ecomerxio.com/pos (ver src/remote.tsx) sus archivos y su API llegan por ecomerxio.com/pos-app
 * y sus rutas viven bajo /pos.
 */

/** Carpeta desde la que se sirvió la app ("/" sola, "/pos-app/" incrustada). Se deduce de dónde vino este archivo. */
const SERVED_FROM: string = import.meta.env.DEV ? '/' : new URL('../', import.meta.url).pathname

let basename = ''
let portalTarget: HTMLElement | null = null
let shadow: ShadowRoot | null = null
let styles = ''

/** Lo llama src/remote.tsx al montarse dentro de otra página. */
export function configureHost(opts: { basename: string; portalRoot: HTMLElement; shadowRoot: ShadowRoot; css: string }) {
  basename = opts.basename.replace(/\/$/, '')
  portalTarget = opts.portalRoot
  shadow = opts.shadowRoot
  styles = opts.css
}

/** true si corre incrustada en otra página. */
export const isEmbedded = () => shadow !== null

/** Prefijo de las rutas de la app ("" sola, "/pos" incrustada). */
export const routerBasename = () => basename

/** Archivo de public/ ("ecomerxio-simbolo.svg" → "/pos-app/ecomerxio-simbolo.svg" si está incrustada). */
export const asset = (file: string) => SERVED_FROM + file.replace(/^\//, '')

/** Prefijo de la API: "/api" sola, "/pos-app/api" incrustada. */
export const apiBase = () => SERVED_FROM + 'api'

/** Dirección pública completa de una ruta de la app (para compartir enlaces y QR). */
export const appUrl = (path: string) => window.location.origin + basename + path

/** Dirección pública completa de algo del servidor ("/api/..." → con /pos-app delante si está incrustada). */
export const serverUrl = (path: string) => window.location.origin + SERVED_FROM.replace(/\/$/, '') + path

/** Dónde se pintan las ventanas flotantes: dentro del contenedor aislado cuando está incrustada. */
export const portalRoot = () => portalTarget ?? document.body

/** Dónde buscar elementos de la app (la guía interactiva): su contenedor aislado o el documento. */
export const queryRoot = (): ParentNode => shadow ?? document

/**
 * Hoja para imprimir el comprobante. Sola: el propio <body> (index.css imprime solo #print-area). Incrustada: un
 * #print-area propio en el <body> de la página anfitriona, con los estilos del POS adentro y una regla de impresión
 * que oculta todo lo demás. Existe solo mientras haya un comprobante listo; `release` lo quita.
 */
export function acquirePrintRoot(): { root: Element | DocumentFragment; release: () => void } {
  if (!shadow) return { root: document.body, release: () => {} }
  const hostEl = document.createElement('div')
  hostEl.id = 'print-area'
  hostEl.style.display = 'none'
  const inner = hostEl.attachShadow({ mode: 'open' })
  const sheet = document.createElement('style')
  sheet.textContent = styles
  inner.appendChild(sheet)
  const rule = document.createElement('style')
  rule.textContent =
    '@media print { body > *:not(#print-area) { display: none !important } #print-area { display: block !important } html, body { background: white !important } }'
  document.head.appendChild(rule)
  document.body.appendChild(hostEl)
  return { root: inner, release: () => { hostEl.remove(); rule.remove() } }
}
