import type { ReactNode } from 'react'
import { InfoTip } from '../ui'
import { useGuide } from './GuideProvider'

interface Props {
  text: ReactNode
  title?: string
  className?: string
  /** Se muestra aunque el usuario haya apagado las ayudas contextuales (explica un número, no un campo). */
  always?: boolean
  /** Ícono: "help" (?) o "info" (i). */
  icon?: 'help' | 'info'
}

/** Ícono de ayuda junto a un campo (se oculta si el usuario apagó las ayudas contextuales, salvo `always`). */
export default function HelpTip({ always = false, icon = 'help', ...rest }: Props) {
  const guide = useGuide()
  if (guide && !guide.state.showContextualHelp && !always) return null
  return <InfoTip icon={icon} {...rest} />
}
