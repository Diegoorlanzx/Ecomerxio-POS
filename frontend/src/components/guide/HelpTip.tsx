import type { ReactNode } from 'react'
import { InfoTip } from '../ui'

interface Props {
  text: ReactNode
  title?: string
  className?: string
  /** Ícono: "help" (?) o "info" (i). */
  icon?: 'help' | 'info'
}

/** Ícono de ayuda junto a un campo. Se oculta con todas las demás si el usuario apaga las ayudas. */
export default function HelpTip({ icon = 'help', ...rest }: Props) {
  return <InfoTip icon={icon} {...rest} />
}
