/**
 * Cliente HTTP directo al backend (sin copia ni buzón). Las pantallas usan `api` de ./api, que agrega la copia para
 * consultar sin internet y el buzón de salida. El token vive en localStorage; un 401 cierra la sesión local.
 */

import { apiBase } from './host'

const TOKEN_KEY = 'tp_token'

/** Mensaje de una escritura que quedó en el buzón por falta de internet (se muestra como éxito, no como error). */
export const QUEUED_MESSAGE = 'Guardado en este equipo (sin internet). Se enviará solo cuando vuelva la conexión: no lo repitas.'

export class ApiError extends Error {
  status: number
  fields: Record<string, string>
  /** Permiso que un supervisor debe autorizar (cuando el servidor lo pide). */
  approval?: string
  /** Sin internet: la operación quedó guardada en el buzón del equipo y se enviará al volver. */
  queued?: boolean
  constructor(status: number, message: string, fields: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fields = fields
    this.approval = fields?.approval
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* almacenamiento bloqueado: la sesión dura lo que dure la pestaña */
  }
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

export async function http<T = unknown>(path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const headers: Record<string, string> = { ...options.headers }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  let res: Response
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method: options.method ?? (options.body !== undefined ? 'POST' : 'GET'),
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    throw new ApiError(0, 'Sin conexión con el servidor. Revisa tu internet.')
  }
  // El proxy o el balanceador responden 502/503/504 cuando el servidor está caído: es "sin conexión".
  if (res.status === 502 || res.status === 503 || res.status === 504) {
    throw new ApiError(0, 'Sin conexión con el servidor. Revisa tu internet.')
  }
  if (res.status === 401 && token) {
    setToken(null)
    onUnauthorized()
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  // Un 5xx sin cuerpo viene del proxy (nuestro servidor siempre responde con un mensaje): no hay servidor.
  if (res.status >= 500 && !text) throw new ApiError(0, 'Sin conexión con el servidor. Revisa tu internet.')
  let data: { message?: string; fields?: Record<string, string> } | undefined
  try {
    data = text ? JSON.parse(text) : undefined
  } catch {
    data = undefined
  }
  if (res.ok && text && data === undefined) return text as T
  if (!res.ok) {
    const fallback = res.status === 403 ? 'No tienes permiso para esta acción' : res.status >= 500 ? 'El servidor tuvo un problema. Intenta de nuevo.' : 'Ocurrió un error'
    throw new ApiError(res.status, data?.message ?? fallback, data?.fields ?? {})
  }
  return data as T
}

/** Descarga un archivo autenticado (exportaciones). */
export async function apiBlob(path: string): Promise<Blob> {
  const token = getToken()
  const res = await fetch(`${apiBase()}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  if (!res.ok) throw new ApiError(res.status, 'No se pudo descargar el archivo')
  return res.blob()
}

/** Sube un archivo binario (p. ej. una copia de seguridad) con cabeceras extra. */
export async function apiUpload<T = unknown>(path: string, file: Blob, extraHeaders: Record<string, string> = {}): Promise<T> {
  const token = getToken()
  let res: Response
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extraHeaders },
      body: file,
    })
  } catch {
    throw new ApiError(0, 'Sin conexión con el servidor. Revisa tu internet.')
  }
  const text = await res.text()
  let data: { message?: string; fields?: Record<string, string> } | undefined
  try {
    data = text ? JSON.parse(text) : undefined
  } catch {
    data = undefined
  }
  if (!res.ok) throw new ApiError(res.status, data?.message ?? 'No se pudo subir el archivo', data?.fields ?? {})
  return data as T
}
