/**
 * Almacén local (IndexedDB) para vender sin internet: el catálogo bajado y las ventas pendientes.
 * Sin dependencias. Si el navegador no lo permite (ventana privada, bloqueado), todo devuelve vacío
 * en vez de fallar. (Adaptado del POS de Ecomerxio.)
 */
const DB_NAME = 'tiendaposs'
const VERSION = 1

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
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => resolve(null)
      req.onblocked = () => resolve(null)
    } catch {
      resolve(null)
    }
  })
  return opening
}

function run<T>(store: 'kv' | 'queue', mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  return open().then(
    (db) =>
      new Promise<T | null>((resolve) => {
        if (!db) return resolve(null)
        try {
          const tx = db.transaction(store, mode)
          const req = fn(tx.objectStore(store))
          req.onsuccess = () => resolve(req.result)
          req.onerror = () => resolve(null)
          tx.onabort = () => resolve(null)
        } catch {
          resolve(null)
        }
      }),
  )
}

export const kvGet = <T>(key: string) => run<T>('kv', 'readonly', (s) => s.get(key) as IDBRequest<T>)
export const kvSet = (key: string, value: unknown) => run('kv', 'readwrite', (s) => s.put(value, key)).then((r) => r !== null)
export const queueAll = <T>() => run<T[]>('queue', 'readonly', (s) => s.getAll() as IDBRequest<T[]>).then((r) => r ?? [])
export const queuePut = (item: unknown) => run('queue', 'readwrite', (s) => s.put(item)).then((r) => r !== null)
export const queueDelete = (key: string) => run('queue', 'readwrite', (s) => s.delete(key)).then(() => undefined)
