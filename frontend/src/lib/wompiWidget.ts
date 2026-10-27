/**
 * Checkout de Wompi como ventana sobre el POS (copiado de Ecomerxio): el dueño paga la suscripción con tarjeta, PSE, Nequi o
 * Bancolombia sin salir de la app. Los datos y la firma los prepara el servidor; aquí solo se carga el script y se abre la ventana.
 * El estado que devuelve la ventana NO es prueba de pago: el servidor lo verifica con Wompi.
 */
export interface WidgetParams {
  publicKey: string
  currency: string
  amountInCents: number
  reference: string
  integritySignature: string
}

export interface WompiTransaction {
  id: string
  status?: string
}

interface WidgetCheckoutInstance {
  open: (callback: (result: { transaction?: WompiTransaction }) => void) => void
}

declare global {
  interface Window {
    WidgetCheckout?: new (config: Record<string, unknown>) => WidgetCheckoutInstance
  }
}

const SCRIPT_URL = 'https://checkout.wompi.co/widget.js'
let loading: Promise<void> | null = null

function loadWidgetScript(): Promise<void> {
  if (window.WidgetCheckout) return Promise.resolve()
  if (loading) return loading
  loading = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => (window.WidgetCheckout ? resolve() : reject(new Error('El pago de Wompi no se pudo iniciar')))
    script.onerror = () => {
      loading = null
      reject(new Error('No se pudo cargar el pago de Wompi. Revisa tu conexión.'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/** Abre el checkout y espera a que el cliente termine o lo cierre. Devuelve la transacción, o `null` si cerró sin pagar. */
export async function openWompiWidget(params: WidgetParams, customer?: { email?: string; fullName?: string }): Promise<WompiTransaction | null> {
  await loadWidgetScript()
  const Widget = window.WidgetCheckout
  if (!Widget) throw new Error('El pago de Wompi no está disponible')
  return new Promise((resolve) => {
    const checkout = new Widget({
      currency: params.currency,
      amountInCents: params.amountInCents,
      reference: params.reference,
      publicKey: params.publicKey,
      signature: { integrity: params.integritySignature },
      customerData: customer?.email ? { email: customer.email, fullName: customer.fullName } : undefined,
    })
    checkout.open((result) => resolve(result?.transaction ?? null))
  })
}
