import { useEffect, useState } from 'react'
import { http } from './http'

/** Nombre comercial de la app: TiendaPOSS se vende como el punto de venta de Ecomerxio. */
export const APP_NAME = 'Ecomerxio POS'

/** Página comercial de Ecomerxio (planes y precios). */
export const ECOMERXIO_URL = 'https://ecomerxio.com'

/** WhatsApp de ventas y soporte de Ecomerxio (Colombia). */
export const SUPPORT_WHATSAPP = '573058225928'

/** Enlace a WhatsApp con el mensaje ya escrito. */
export function whatsappLink(message: string): string {
  return `https://wa.me/${SUPPORT_WHATSAPP}?text=${encodeURIComponent(message)}`
}

export type PlanCode = 'TIENDA' | 'NEGOCIO' | 'PRO'

export type Plan = { code: PlanCode; name: string; priceMonthly: number; maxRegisters: number | null; maxUsers: number | null; extra: string }

/**
 * Planes de Ecomerxio POS. Precios y topes los define el Super Admin de Ecomerxio y llegan de `/api/public/plans`
 * ({@link usePlans}); estos valores solo se ven mientras responde el servidor. `extra` es el texto propio de cada plan.
 */
export const PLANS: Plan[] = [
  { code: 'TIENDA', name: 'Tienda', priceMonthly: 39900, maxRegisters: 1, maxUsers: 3, extra: '' },
  { code: 'NEGOCIO', name: 'Negocio', priceMonthly: 69900, maxRegisters: 3, maxUsers: 10, extra: 'compras y proveedores' },
  { code: 'PRO', name: 'Pro', priceMonthly: 119900, maxRegisters: null, maxUsers: null, extra: 'tienda en línea y soporte prioritario' },
]

/** "1 caja y 3 usuarios", "Cajas y usuarios ilimitados, soporte prioritario"… */
export function planSummary(p: Plan): string {
  const registers = p.maxRegisters == null ? null : p.maxRegisters === 1 ? '1 caja' : `${p.maxRegisters} cajas`
  const users = p.maxUsers == null ? null : p.maxUsers === 1 ? '1 usuario' : `${p.maxUsers} usuarios`
  const limits = !registers && !users ? 'Cajas y usuarios ilimitados'
    : `${registers ?? 'Cajas ilimitadas'}${p.extra ? ', ' : ' y '}${users ?? 'usuarios ilimitados'}`
  return p.extra ? `${limits}, ${p.extra}` : limits
}

/** Los planes con los precios y topes vigentes del servidor (si no responde, los de arriba). */
export function usePlans(): Plan[] {
  const [plans, setPlans] = useState(PLANS)
  useEffect(() => {
    let alive = true
    http<{ code: string; priceMonthly: number; maxRegisters: number | null; maxUsers: number | null }[]>('/public/plans')
      .then((rows) => {
        if (!alive) return
        setPlans(PLANS.map((p) => {
          const r = rows.find((x) => x.code === p.code)
          return r ? { ...p, priceMonthly: r.priceMonthly, maxRegisters: r.maxRegisters, maxUsers: r.maxUsers } : p
        }))
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])
  return plans
}

/** Plan pedido en la URL (`/registro?plan=TIENDA`); si no viene o no existe, el recomendado. */
export function planFromQuery(search: string): PlanCode {
  const raw = new URLSearchParams(search).get('plan')?.toUpperCase()
  return PLANS.find((p) => p.code === raw)?.code ?? 'NEGOCIO'
}
