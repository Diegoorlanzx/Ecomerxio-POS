import { useEffect, useState } from 'react'

/**
 * Pide al navegador que NO borre los datos de la app aunque el disco se llene (sin esto, Chrome puede vaciar
 * IndexedDB y con él las ventas sin enviar). En la app instalada se concede solo; en el navegador depende del uso.
 */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false
    if (await navigator.storage.persisted()) return true
    return await navigator.storage.persist()
  } catch {
    return false
  }
}

export interface StorageInfo {
  usage: number
  quota: number
  persisted: boolean
}

export async function storageInfo(): Promise<StorageInfo | null> {
  try {
    if (!navigator.storage?.estimate) return null
    const { usage = 0, quota = 0 } = await navigator.storage.estimate()
    const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : false
    return { usage, quota, persisted }
  } catch {
    return null
  }
}

/** Uso del espacio del equipo; se revisa al abrir y cada 10 minutos. */
export function useStorageInfo(): StorageInfo | null {
  const [info, setInfo] = useState<StorageInfo | null>(null)
  useEffect(() => {
    const check = () => void storageInfo().then(setInfo)
    check()
    const id = setInterval(check, 10 * 60_000)
    return () => clearInterval(id)
  }, [])
  return info
}

/** El espacio está casi lleno (más del 80 % de lo que el navegador le da a la app). */
export const storageLow = (i: StorageInfo | null) => !!i && i.quota > 0 && i.usage / i.quota > 0.8
