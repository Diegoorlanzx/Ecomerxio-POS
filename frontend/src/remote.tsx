/**
 * Ecomerxio POS como microfrontend (Module Federation, expuesto como "./mount" en vite.config.ts).
 * La página anfitriona (ecomerxio.com/pos) llama a mount(el) y la app se pinta dentro de un Shadow DOM: sus
 * estilos no tocan la página y los de la página no la tocan a ella. Trae su propio React (no se comparte).
 * Sus archivos y su API llegan por la misma dirección de donde se cargó este código (ver lib/host.ts).
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { ToastProvider } from './components/ui'
import cssText from './index.css?inline'
import { AuthProvider } from './lib/auth'
import { asset, configureHost } from './lib/host'

export interface MountOptions {
  /** Ruta bajo la que vive la app en la página anfitriona, p. ej. "/pos". */
  basename: string
}

/** Las url(...) de la hoja apuntan a /assets: se resuelven contra el servidor del POS, no contra la página. */
function absoluteUrls(css: string): string {
  return css.replace(/url\((['"]?)(?:\.{0,2}\/)*assets\/([^'")]+)\1\)/g, (_m, q, file) => `url(${q}${asset('assets/' + file)}${q})`)
}

/** @font-face y @property no funcionan dentro de un Shadow DOM: van al documento (no pintan nada por sí solos). */
let globalsInjected = false
function injectGlobals(css: string) {
  if (globalsInjected) return
  const rules = css.match(/@font-face\s*{[^}]*}|@property\s+[^{]+{[^}]*}/g) ?? []
  const style = document.createElement('style')
  style.dataset.ecomerxioPos = ''
  style.textContent = rules.join('\n')
  document.head.appendChild(style)
  globalsInjected = true
}

export function mount(el: HTMLElement, opts: MountOptions): () => void {
  const css = absoluteUrls(cssText)
  injectGlobals(css)

  const shadowRoot = el.shadowRoot ?? el.attachShadow({ mode: 'open' })
  shadowRoot.replaceChildren()
  const sheet = document.createElement('style')
  sheet.textContent = css
  const appEl = document.createElement('div')
  appEl.className = 'min-h-dvh bg-canvas font-sans text-ink antialiased'
  const portalEl = document.createElement('div')
  shadowRoot.append(sheet, appEl, portalEl)

  configureHost({ basename: opts.basename, portalRoot: portalEl, shadowRoot, css })

  const root = createRoot(appEl)
  root.render(
    <StrictMode>
      <BrowserRouter basename={opts.basename}>
        <AuthProvider>
          <ToastProvider>
            <App />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </StrictMode>,
  )
  return () => root.unmount()
}

export default mount
