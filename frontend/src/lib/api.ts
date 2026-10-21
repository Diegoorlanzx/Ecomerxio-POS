/**
 * Cliente de la API para las pantallas. Igual que `http`, más dos cosas para trabajar sin internet:
 *  - Lecturas (GET): cada respuesta se guarda en el equipo; sin conexión se devuelve la última copia y se avisa
 *    (evento STALE_EVENT) para que la app muestre "datos guardados de hace X".
 *  - Escrituras: pasan por el buzón de salida con su Idempotency-Key. Sin conexión quedan en cola y se lanza un
 *    ApiError con `queued = true` ("guardado en este equipo"); se envían solas al volver, en orden, sin repetirse.
 * Lo que por seguridad exige al servidor en el momento (sesión, usuarios, roles, PIN, restauración, DIAN) no se
 * guarda ni se encola: sin internet falla como siempre.
 */
import { ApiError, http, QUEUED_MESSAGE } from './http'
import { kvGet, kvSet } from './offline/idb'
import { runOp, outboxSnapshot } from './offline/queue'
import { isPractice, PRACTICE_MESSAGE } from './practice'

export { ApiError, apiBlob, apiUpload, getToken, QUEUED_MESSAGE, setToken, setUnauthorizedHandler } from './http'

export const STALE_EVENT = 'tiendaposs:stale'
export const QUEUED_EVENT = 'tiendaposs:queued'

/** Escrituras que nunca se encolan: dependen de que el servidor valide en el momento. */
const ONLINE_ONLY_WRITES = [/^\/auth\//, /^\/approvals/, /^\/backups/, /^\/users/, /^\/roles/, /^\/assistant/, /^\/einvoice/, /^\/fiscal/, /^\/accounting\/enable/, /^\/settings\/pos\/modules/, /^\/quotes/, /^\/legal/]
/** Lecturas que no se guardan (sesión, conversaciones, archivos). */
const NO_CACHE_READS = [/^\/auth\//, /^\/assistant/, /^\/backups\/.*\/download/, /\/export/]

interface Me {
  id: number
  tenant: { id: number }
}
function currentMe(): Me | null {
  try {
    return JSON.parse(localStorage.getItem('tp_me') ?? 'null')
  } catch {
    return null
  }
}

const readKey = (me: Me, path: string) => `get:${me.tenant.id}:${me.id}:${path}`

/** Nombre corto de una escritura para "Pendientes por enviar". */
export function describeWrite(method: string, path: string): string {
  const p = path.split('?')[0]
  const rules: [RegExp, string][] = [
    [/^\/products\/[^/]+\/adjust/, 'Ajuste de inventario'],
    [/^\/products/, method === 'POST' ? 'Producto nuevo' : 'Cambio de producto'],
    [/^\/categories/, 'Categoría'],
    [/^\/(customers|credit)\/[^/]+\/payments/, 'Abono de fiado'],
    [/^\/customers/, method === 'POST' ? 'Cliente nuevo' : 'Cambio de cliente'],
    [/^\/credit/, 'Fiado'],
    [/^\/finance|^\/expenses|^\/incomes/, 'Gasto o ingreso'],
    [/^\/payables/, 'Pago a proveedor'],
    [/^\/purchases\/[^/]+\/receive/, 'Recepción de compra'],
    [/^\/purchases/, 'Compra'],
    [/^\/suppliers/, 'Proveedor'],
    [/^\/counts/, 'Conteo de inventario'],
    [/^\/movements|^\/inventory/, 'Movimiento de inventario'],
    [/^\/promotions/, 'Promoción'],
    [/^\/payments/, 'Pago digital'],
    [/^\/pos\/holds/, 'Venta en espera'],
    [/^\/pos\/accounts/, 'Cuenta abierta'],
    [/^\/pos\/sales\/[^/]+\/void/, 'Anulación de venta'],
    [/^\/pos\/sales\/[^/]+\/returns/, 'Devolución'],
    [/^\/settings/, 'Ajustes'],
    [/^\/notifications/, 'Notificaciones'],
    [/^\/online/, 'Tienda en línea'],
  ]
  const hit = rules.find(([re]) => re.test(p))
  const verb = method === 'DELETE' ? 'Borrar: ' : ''
  return verb + (hit ? hit[1] : `${method} ${p}`)
}

export async function api<T = unknown>(path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const method = (options.method ?? (options.body !== undefined ? 'POST' : 'GET')).toUpperCase()
  const me = currentMe()

  if (method === 'GET') {
    const cacheable = !!me && !NO_CACHE_READS.some((re) => re.test(path))
    try {
      const data = await http<T>(path, options)
      if (cacheable) void kvSet(readKey(me!, path), { data, savedAt: new Date().toISOString() })
      return data
    } catch (e) {
      if (cacheable && e instanceof ApiError && e.status === 0) {
        const hit = await kvGet<{ data: T; savedAt: string }>(readKey(me!, path))
        if (hit) {
          window.dispatchEvent(new CustomEvent(STALE_EVENT, { detail: { path, savedAt: hit.savedAt } }))
          return hit.data
        }
      }
      throw e
    }
  }

  // Modo de práctica: no se guarda nada (salvo entrar y salir de la sesión).
  if (isPractice() && !path.startsWith('/auth/')) throw new ApiError(400, PRACTICE_MESSAGE)

  // Escrituras: las que ya traen su llave (ventas, caja) las maneja quien llama; las de seguridad van directo.
  const queueable = !!me && !options.headers?.['Idempotency-Key'] && !ONLINE_ONLY_WRITES.some((re) => re.test(path))
  if (!queueable) return http<T>(path, options)

  // El mismo cambio ya está esperando en el buzón (alguien volvió a tocar "Guardar"): no se repite.
  const body = options.body
  const same = outboxSnapshot().some(
    (i) => i.kind === 'OP' && i.status !== 'ERROR' && i.tenantId === me!.tenant.id && i.method === method && i.path === path && JSON.stringify(i.body) === JSON.stringify(body),
  )
  if (same) throw queuedError()

  const r = await runOp<T>({
    tenantId: me!.tenant.id,
    method: method as 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path,
    body,
    label: describeWrite(method, path),
  })
  if (r.queued) {
    window.dispatchEvent(new CustomEvent(QUEUED_EVENT, { detail: { path } }))
    throw queuedError()
  }
  return r.data
}

function queuedError() {
  const e = new ApiError(0, QUEUED_MESSAGE)
  e.queued = true
  return e
}
