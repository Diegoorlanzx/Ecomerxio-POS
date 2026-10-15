import { Card, Chip, Icon } from '../components/ui'

/** Marcador de un módulo aún no construido: dice qué trae y en qué fase llega. */
export default function ComingSoon({ icon, title, phase, items }: { icon: string; title: string; phase: number; items: string[] }) {
  return (
    <Card className="overflow-hidden">
      <div className="bg-primary px-5 py-6 text-white">
        <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-white/15">
          <Icon name={icon} className="text-[28px]" />
        </div>
        <div className="text-xs font-bold tracking-[0.08em] text-blue-100 uppercase">Se construye en la Fase {phase}</div>
        <h2 className="mt-1 text-[22px] font-semibold tracking-tight">{title}</h2>
      </div>
      <ul className="divide-y divide-line-soft">
        {items.map((it) => (
          <li key={it} className="flex items-center gap-3 px-5 py-3 text-sm">
            <Icon name="radio_button_unchecked" className="text-[18px] text-muted" />
            <span className="flex-1">{it}</span>
          </li>
        ))}
      </ul>
      <div className="px-5 py-3">
        <Chip>Pendiente</Chip>
      </div>
    </Card>
  )
}
