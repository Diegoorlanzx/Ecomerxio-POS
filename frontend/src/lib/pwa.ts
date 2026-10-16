import { useEffect, useState } from 'react'

/** Evento que da Chrome/Edge (Android y PC) cuando la app se puede instalar. */
interface InstallEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

let deferred: InstallEvent | null = null
const listeners = new Set<() => void>()
const notify = () => listeners.forEach((l) => l())

// Se escucha desde que carga la página: el navegador avisa una sola vez y muy temprano.
if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault()
    deferred = e as InstallEvent
    notify()
  })
  window.addEventListener('appinstalled', () => {
    deferred = null
    notify()
  })
}

export function isStandalone(): boolean {
  return window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true
}

export function isIos(): boolean {
  return /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

/** Estado de instalación: 'instalada', 'lista' (botón), 'ios' (instrucciones) o 'no' (navegador sin soporte o en desarrollo). */
export function useInstall() {
  const [, force] = useState(0)
  useEffect(() => {
    const l = () => force((n) => n + 1)
    listeners.add(l)
    return () => {
      listeners.delete(l)
    }
  }, [])
  const state: 'instalada' | 'lista' | 'ios' | 'no' = isStandalone() ? 'instalada' : deferred ? 'lista' : isIos() ? 'ios' : 'no'
  async function install() {
    if (!deferred) return false
    await deferred.prompt()
    const choice = await deferred.userChoice
    deferred = null
    notify()
    return choice.outcome === 'accepted'
  }
  return { state, install }
}
