/**
 * Avisos sonoros del restaurante (comanda nueva en la cocina, plato listo para el mesero). Se generan con Web Audio,
 * sin archivos. El navegador solo deja sonar después de que la persona toca la pantalla una vez: por eso hay
 * {@link unlockSound}, que se llama desde un botón.
 */

const KEY = 'tp_sound_on'
let ctx: AudioContext | null = null

export function soundEnabled(): boolean {
  try {
    return localStorage.getItem(KEY) !== '0'
  } catch {
    return true
  }
}

export function setSoundEnabled(on: boolean) {
  try {
    localStorage.setItem(KEY, on ? '1' : '0')
  } catch {
    /* sin almacenamiento */
  }
  if (on) unlockSound()
}

/** true si el navegador ya deja sonar (hubo un toque en la página). */
export function soundUnlocked(): boolean {
  return !!ctx && ctx.state === 'running'
}

/** Llamar desde un toque o clic: prepara el audio para que los avisos suenen después solos. */
export function unlockSound() {
  try {
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!AC) return
    if (!ctx) ctx = new AC()
    if (ctx.state === 'suspended') void ctx.resume()
  } catch {
    /* sin audio */
  }
}

function tone(freq: number, start: number, dur: number, volume = 0.25) {
  if (!ctx) return
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  osc.type = 'sine'
  osc.frequency.value = freq
  const t0 = ctx.currentTime + start
  gain.gain.setValueAtTime(0.0001, t0)
  gain.gain.exponentialRampToValueAtTime(volume, t0 + 0.02)
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  osc.connect(gain).connect(ctx.destination)
  osc.start(t0)
  osc.stop(t0 + dur + 0.05)
}

/**
 * "new": comanda nueva (tres tonos subiendo, se oye por encima del ruido de la cocina).
 * "ready": plato listo para llevar (dos tonos tipo timbre).
 * "order": pedido desde la mesa o en línea esperando.
 */
export function playAlert(kind: 'new' | 'ready' | 'order') {
  if (!soundEnabled()) return
  unlockSound()
  if (!ctx || ctx.state !== 'running') return
  if (kind === 'new') {
    tone(660, 0, 0.18)
    tone(880, 0.2, 0.18)
    tone(1100, 0.4, 0.3)
  } else if (kind === 'ready') {
    tone(1046, 0, 0.25)
    tone(784, 0.28, 0.4)
  } else {
    tone(880, 0, 0.15)
    tone(880, 0.22, 0.15)
  }
  try {
    navigator.vibrate?.(kind === 'new' ? [120, 60, 120] : 150)
  } catch {
    /* sin vibración */
  }
}
