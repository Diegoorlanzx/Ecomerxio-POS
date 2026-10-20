/**
 * Almacén local (IndexedDB) del equipo: copias para consultar sin internet (kv), el buzón de salida de operaciones
 * (queue) y un historial corto de lo ya enviado (sent). Sin dependencias. Si el navegador no lo permite (ventana
 * privada, bloqueado), todo devuelve vacío en vez de fallar. (Adaptado del POS de Ecomerxio.)
 *
 * Las escrituras se dan por hechas solo cuando la transacción se confirmó en disco (oncomplete con durabilidad
 * "strict"): así un apagón justo después de "guardado" no pierde nada.
 */
const DB_NAME = 'tiendaposs'
const VERSION = 2

type Store = 'kv' | 'queue' | 'sent'

let opening: Promise<IDBDatabase | null> | null = null

function open(): Promise<IDBDatabase | null> {
  if (opening) return opening
  opening = new Promise((resolve) => {
    try {
      if (typeof indexedDB === 'undefined') return resolve(null)
      const req = indexedDB.open(DB_NAME, VERSION)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv')
        if (!db.objectStoreNames.contains('queue')) db.createObjectStore('queue', { keyPath: 'clientRef' })
        if (!db.objectStoreNames.contains('sent')) db.createObjectStore('sent', { keyPath: 'clientRef' })
      }
      req.onsuccess = () => {
        const db = req.result
        // Otra pestaña con una versión nueva de la app pide actualizar la base: se cierra esta conexión.
        db.onversionchange = () => {
          db.close()
          opening = null
        }
        resolve(db)
      }
      req.onerror = () => resolve(null)
      req.onblocked = () => resolve(null)
    } catch {
      resolve(null)
    }
  })
  return opening
}

function run<T>(store: Store, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  return open().then(
    (db) =>
      new Promise<T | null>((resolve) => {
        if (!db) return resolve(null)
        try {
          const tx = mode === 'readwrite' ? db.transaction(store, mode, { durability: 'strict' }) : db.transaction(store, mode)
          let result: T | null = null
          const req = fn(tx.objectStore(store))
          req.onsuccess = () => {
            result = req.result
            if (mode === 'readonly') resolve(result)
          }
          // Escritura: solo cuenta cuando el navegador confirma la transacción completa.
          tx.oncomplete = () => resolve(mode === 'readwrite' ? (result ?? (true as T)) : result)
          tx.onerror = () => resolve(null)
          tx.onabort = () => resolve(null)
        } catch {
          resolve(null)
        }
      }),
  )
}

export const kvGet = <T>(key: string) => run<T>('kv', 'readonly', (s) => s.get(key) as IDBRequest<T>)
export const kvSet = (key: string, value: unknown) => run('kv', 'readwrite', (s) => s.put(value, key)).then((r) => r !== null)
export const kvDelete = (key: string) => run('kv', 'readwrite', (s) => s.delete(key)).then((r) => r !== null)
export const kvKeys = () => run<IDBValidKey[]>('kv', 'readonly', (s) => s.getAllKeys()).then((r) => (r ?? []).map(String))
export const queueAll = <T>() => run<T[]>('queue', 'readonly', (s) => s.getAll() as IDBRequest<T[]>).then((r) => r ?? [])
export const queuePut = (item: unknown) => run('queue', 'readwrite', (s) => s.put(item)).then((r) => r !== null)
export const queueDelete = (key: string) => run('queue', 'readwrite', (s) => s.delete(key)).then((r) => r !== null)
export const sentAll = <T>() => run<T[]>('sent', 'readonly', (s) => s.getAll() as IDBRequest<T[]>).then((r) => r ?? [])
export const sentPut = (item: unknown) => run('sent', 'readwrite', (s) => s.put(item)).then((r) => r !== null)
export const sentDelete = (key: string) => run('sent', 'readwrite', (s) => s.delete(key)).then((r) => r !== null)

/**
 * Mueve una operación del buzón al historial de enviadas en UNA sola transacción: si se va la luz a la mitad, queda
 * en uno de los dos lados, nunca en ninguno.
 */
export function markSent(clientRef: string, record: unknown): Promise<boolean> {
  return open().then(
    (db) =>
      new Promise<boolean>((resolve) => {
        if (!db) return resolve(false)
        try {
          const tx = db.transaction(['queue', 'sent'], 'readwrite', { durability: 'strict' })
          tx.objectStore('sent').put(record)
          tx.objectStore('queue').delete(clientRef)
          tx.oncomplete = () => resolve(true)
          tx.onerror = () => resolve(false)
          tx.onabort = () => resolve(false)
        } catch {
          resolve(false)
        }
      }),
  )
}
