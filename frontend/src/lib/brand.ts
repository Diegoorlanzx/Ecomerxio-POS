/** Nombre comercial de la app: TiendaPOSS se vende como el punto de venta de Ecomerxio. */
export const APP_NAME = 'Ecomerxio POS'

/** Página comercial de Ecomerxio (planes y precios). */
export const ECOMERXIO_URL = 'https://ecomerxio.com'

export type PlanCode = 'TIENDA' | 'NEGOCIO' | 'PRO'

/** Planes de Ecomerxio POS. Mantener en sync con `tenant/Plans.java` y con POS_PLANS de la landing de Ecomerxio. */
export const PLANS: { code: PlanCode; name: string; priceMonthly: number; summary: string }[] = [
  { code: 'TIENDA', name: 'Tienda', priceMonthly: 39900, summary: '1 caja y 3 usuarios' },
  { code: 'NEGOCIO', name: 'Negocio', priceMonthly: 69900, summary: '3 cajas, 10 usuarios, compras y tienda en línea' },
  { code: 'PRO', name: 'Pro', priceMonthly: 119900, summary: 'Cajas y usuarios ilimitados, soporte prioritario' },
]

/** Plan pedido en la URL (`/registro?plan=TIENDA`); si no viene o no existe, el recomendado. */
export function planFromQuery(search: string): PlanCode {
  const raw = new URLSearchParams(search).get('plan')?.toUpperCase()
  return PLANS.find((p) => p.code === raw)?.code ?? 'NEGOCIO'
}
