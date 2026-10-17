/** Estado de la guía interactiva — persistido por tenant+usuario en localStorage */

export interface GuideState {
  enabled: boolean
  showContextualHelp: boolean
  activeTour: { id: string; step: number } | null
  completedTours: string[]
  wizardDismissed: boolean
  /** IDs de pasos del wizard marcados manualmente como hechos */
  wizardManual: string[]
}

const DEFAULT: GuideState = {
  enabled: true,
  showContextualHelp: true,
  activeTour: null,
  completedTours: [],
  wizardDismissed: false,
  wizardManual: [],
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
  } catch {}
}

export function clearGuide(tenantId: number, userId: number) {
  try {
    localStorage.removeItem(lsKey(tenantId, userId))
  } catch {}
}
