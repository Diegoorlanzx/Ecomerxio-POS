/**
 * Medición de Google Ads: la etiqueta solo se carga en las páginas públicas de acceso (registro, ingreso,
 * recuperar clave), nunca dentro de la app, y la conversión se avisa una sola vez, cuando la tienda queda creada.
 * Sin ID configurado no se carga nada.
 */

/**
 * ID de la cuenta de Google Ads de Ecomerxio y etiqueta de la conversión "Enviar formulario de clientes potenciales"
 * (el registro a la prueba gratis). No son secretos: Google los publica en cada página. En desarrollo no se carga
 * nada, salvo que se pasen por variables de entorno, para no contar registros de prueba.
 */
const ADS_ID: string = import.meta.env.VITE_GOOGLE_ADS_ID ?? (import.meta.env.PROD ? 'AW-18552514423' : '')
const SIGNUP_LABEL: string = import.meta.env.VITE_GOOGLE_ADS_SIGNUP_LABEL ?? (import.meta.env.PROD ? 'E1omCNbl4rAdEPfOw45F' : '')

type Gtag = (...args: unknown[]) => void
declare global {
  interface Window {
    dataLayer?: unknown[]
    gtag?: Gtag
  }
}

let loaded = false

export function loadGoogleTag() {
  if (loaded || !ADS_ID || typeof document === 'undefined') return
  loaded = true
  window.dataLayer = window.dataLayer || []
  // gtag exige recibir el objeto `arguments`, no un arreglo.
  window.gtag = function gtag() {
    // eslint-disable-next-line prefer-rest-params
    window.dataLayer!.push(arguments)
  }
  window.gtag('js', new Date())
  window.gtag('config', ADS_ID)
  const s = document.createElement('script')
  s.async = true
  s.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(ADS_ID)}`
  document.head.appendChild(s)
}

/** Avisa a Google Ads que se creó una cuenta de prueba. Espera como máximo 1 s para no frenar la entrada. */
export function trackSignup(): Promise<void> {
  if (!ADS_ID || !SIGNUP_LABEL || !window.gtag) return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => resolve()
    window.gtag!('event', 'conversion', { send_to: `${ADS_ID}/${SIGNUP_LABEL}`, value: 1.0, currency: 'COP', event_callback: done })
    setTimeout(done, 1000)
  })
}
