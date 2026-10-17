/** Estado de la guía interactiva — persistido por tenant+usuario en localStorage */

export interface GuideState {
  showContextualHelp: boolean
  /** Recorrido pausado o interrumpido, para reanudarlo */
  activeTour: { id: string; step: number } | null
  completedTours: string[]
  /** IDs de pasos del asistente marcados a mano como hechos */
  wizardManual: string[]
  seenDashboard: boolean
}

const DEFAULT: GuideState = {
  showContextualHelp: true,
  activeTour: null,
  completedTours: [],
  wizardManual: [],
  seenDashboard: false,
}

function lsKey(tenantId: number, userId: number) {
  return `tp_guide_${tenantId}_${userId}`
}

export function loadGuide(tenantId: number, userId: number): GuideState {
  try {
    const raw = localStorage.getItem(lsKey(tenantId, userId))
    if (!raw) return { ...DEFAULT }
    return { ...DEFAULT, ...JSON.parse(raw) }
  } catch {
    return { ...DEFAULT }
  }
}

export function saveGuide(tenantId: number, userId: number, state: GuideState) {
  try {
    localStorage.setItem(lsKey(tenantId, userId), JSON.stringify(state))
  } catch {
    /* sin almacenamiento: la guía funciona igual durante la sesión */
  }
}
