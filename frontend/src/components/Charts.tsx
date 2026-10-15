import { money } from '../lib/format'
import { Button, Field, Icon } from './ui'

const iso = (d: Date) => d.toLocaleDateString('en-CA')
const parse = (s: string) => new Date(`${s}T12:00:00`)
const addDays = (s: string, n: number) => {
  const d = parse(s)
  d.setDate(d.getDate() + n)
  return iso(d)
}

export type PeriodKind = 'dia' | 'semana' | 'quincena' | 'mes' | 'personalizado'

/** Rango del período que contiene `anchor`: día, semana (lun-dom), quincena (1-15 / 16-fin) o mes. */
export function periodRange(kind: PeriodKind, anchor: string): [string, string] {
  const d = parse(anchor)
  if (kind === 'dia') return [anchor, anchor]
  if (kind === 'semana') {
    const dow = (d.getDay() + 6) % 7
    const from = addDays(anchor, -dow)
    return [from, addDays(from, 6)]
  }
  const y = d.getFullYear(),
    m = d.getMonth()
  const last = new Date(y, m + 1, 0).getDate()
  const pad = (n: number) => String(n).padStart(2, '0')
  const ym = `${y}-${pad(m + 1)}`
  if (kind === 'quincena') return d.getDate() <= 15 ? [`${ym}-01`, `${ym}-15`] : [`${ym}-16`, `${ym}-${pad(last)}`]
  return [`${ym}-01`, `${ym}-${pad(last)}`]
}

export function clampToday(to: string) {
  const t = iso(new Date())
  return to > t ? t : to
}

/** Selector de período: diario, semanal, quincenal, mensual o personalizado, con flechas para moverse. */
export function PeriodPicker({ kind, from, to, onChange }: { kind: PeriodKind; from: string; to: string; onChange: (kind: PeriodKind, from: string, to: string) => void }) {
  const today = iso(new Date())
  const set = (k: PeriodKind, anchor = today) => {
    if (k === 'personalizado') return onChange(k, from, to)
    const [f, t] = periodRange(k, anchor)
    onChange(k, f, clampToday(t))
  }
  const move = (dir: -1 | 1) => {
    if (kind === 'personalizado') {
      const len = Math.round((parse(to).getTime() - parse(from).getTime()) / 86400000) + 1
      return onChange(kind, addDays(from, dir * len), clampToday(addDays(to, dir * len)))
    }
    set(kind, dir < 0 ? addDays(from, -1) : addDays(periodRange(kind, from)[1], 1))
  }
  const labels: [PeriodKind, string][] = [
    ['dia', 'Día'],
    ['semana', 'Semana'],
    ['quincena', 'Quincena'],
    ['mes', 'Mes'],
    ['personalizado', 'Otro'],
  ]
  const atEnd = periodRange(kind === 'personalizado' ? 'dia' : kind, from)[1] >= today || to >= today
  return (
    <div className="space-y-2 print:hidden">
      <div className="flex gap-1 overflow-x-auto rounded-xl bg-primary-tint p-1">
        {labels.map(([k, l]) => (
          <button
            key={k}
            type="button"
            onClick={() => set(k)}
            className={`h-10 flex-1 rounded-lg px-3 text-sm font-semibold whitespace-nowrap ${kind === k ? 'bg-card text-ink shadow-tier1' : 'text-muted hover:text-ink'}`}
          >
            {l}
          </button>
        ))}
      </div>
      {kind === 'personalizado' ? (
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Desde" type="date" value={from} max={to} onChange={(e) => onChange(kind, e.target.value, to)} className="w-40" />
          <Field label="Hasta" type="date" value={to} max={today} onChange={(e) => onChange(kind, from, e.target.value)} className="w-40" />
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" aria-label="Período anterior" onClick={() => move(-1)}>
            <Icon name="chevron_left" />
          </Button>
          <span className="flex-1 text-center text-sm font-semibold">{rangeLabel(from, to)}</span>
          <Button size="sm" variant="secondary" aria-label="Período siguiente" onClick={() => move(1)} disabled={atEnd}>
            <Icon name="chevron_right" />
          </Button>
        </div>
      )}
    </div>
  )
}

export function rangeLabel(from: string, to: string) {
  const f = new Intl.DateTimeFormat('es-CO', { day: 'numeric', month: 'short' })
  const y = new Intl.DateTimeFormat('es-CO', { day: 'numeric', month: 'short', year: 'numeric' })
  if (from === to) return new Intl.DateTimeFormat('es-CO', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(parse(from))
  return `${f.format(parse(from))} – ${y.format(parse(to))}`
}

export interface BarDatum {
  key: string
  label: string
  value: number
  hint?: string
}

/** Barras verticales simples; resalta la mayor. */
export function BarChart({ data, height = 160, tone = 'primary', every = 1, format = money }: { data: BarDatum[]; height?: number; tone?: 'primary' | 'success' | 'wallet'; every?: number; format?: (v: number) => string }) {
  const max = Math.max(1, ...data.map((d) => d.value))
  const top = data.reduce<BarDatum | null>((a, d) => (d.value > (a?.value ?? 0) ? d : a), null)
  const colors = { primary: ['bg-primary-soft', 'bg-primary-active'], success: ['bg-emerald-200', 'bg-success'], wallet: ['bg-violet-200', 'bg-wallet'] }[tone]
  return (
    <div className="flex items-end gap-1" style={{ height }} role="img" aria-label="Gráfico de barras">
      {data.map((d, i) => {
        const isTop = top?.key === d.key && d.value > 0
        return (
          <div key={d.key} className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1" title={`${d.label}: ${format(d.value)}${d.hint ? ` · ${d.hint}` : ''}`}>
            <div className={`w-full rounded-t ${isTop ? colors[1] : colors[0]}`} style={{ height: `${d.value > 0 ? Math.max(3, (d.value / max) * 100) : 0}%` }} />
            <span className={`w-full truncate text-center text-[10px] ${isTop ? 'font-bold text-ink' : 'text-muted'}`}>{i % every === 0 || isTop ? d.label : ''}</span>
          </div>
        )
      })}
    </div>
  )
}

/** Barras horizontales con valor y porcentaje. */
export function HBars({ items, tone = 'bg-primary', format = money }: { items: { label: string; value: number; sub?: string; pct?: number }[]; tone?: string; format?: (v: number) => string }) {
  const max = Math.max(1, ...items.map((i) => Math.abs(i.value)))
  if (!items.length) return <p className="py-3 text-sm text-muted">Sin datos en este período.</p>
  return (
    <div className="space-y-2.5">
      {items.map((it) => (
        <div key={it.label}>
          <div className="flex items-baseline justify-between gap-2 text-sm">
            <span className="truncate">{it.label}</span>
            <span className="num shrink-0 font-semibold">
              {format(it.value)}
              {it.pct !== undefined && <span className="ml-1 text-xs font-normal text-muted">{it.pct.toLocaleString('es-CO', { maximumFractionDigits: 1 })}%</span>}
            </span>
          </div>
          <div className="mt-1 h-2 overflow-hidden rounded-full bg-line-soft">
            <div className={`h-full rounded-full ${it.value < 0 ? 'bg-danger' : tone}`} style={{ width: `${Math.max(2, (Math.abs(it.value) / max) * 100)}%` }} />
          </div>
          {it.sub && <div className="mt-0.5 text-xs text-muted">{it.sub}</div>}
        </div>
      ))}
    </div>
  )
}

/** Dos líneas (p. ej. precio y costo promedio por mes). */
export function TwoLines({ labels, a, b, aLabel, bLabel }: { labels: string[]; a: (number | null)[]; b: (number | null)[]; aLabel: string; bLabel: string }) {
  const vals = [...a, ...b].filter((v): v is number => v !== null)
  if (!vals.length) return <p className="py-3 text-sm text-muted">Sin ventas en los últimos 12 meses.</p>
  const max = Math.max(...vals) * 1.1,
    min = Math.min(0, Math.min(...vals))
  const W = 600,
    H = 180
  const x = (i: number) => (labels.length <= 1 ? W / 2 : (i / (labels.length - 1)) * (W - 20) + 10)
  const y = (v: number) => H - 20 - ((v - min) / (max - min || 1)) * (H - 40)
  const path = (s: (number | null)[]) =>
    s
      .map((v, i) => (v === null ? null : `${x(i)},${y(v)}`))
      .reduce<string[]>((acc, p, i, arr) => {
        if (p) acc.push(`${i === 0 || arr[i - 1] === null ? 'M' : 'L'}${p}`)
        return acc
      }, [])
      .join(' ')
  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-44 w-full" role="img" aria-label={`${aLabel} y ${bLabel} por mes`}>
        <path d={path(a)} fill="none" className="stroke-primary" strokeWidth={3} />
        <path d={path(b)} fill="none" className="stroke-danger" strokeWidth={3} strokeDasharray="6 4" />
        {a.map((v, i) => v !== null && <circle key={`a${i}`} cx={x(i)} cy={y(v)} r={4} className="fill-primary" />)}
        {b.map((v, i) => v !== null && <circle key={`b${i}`} cx={x(i)} cy={y(v)} r={4} className="fill-danger" />)}
        {labels.map((l, i) => (
          <text key={l} x={x(i)} y={H - 4} textAnchor="middle" className="fill-muted text-[11px]">
            {l}
          </text>
        ))}
      </svg>
      <div className="flex gap-4 text-xs">
        <span className="flex items-center gap-1">
          <span className="h-1 w-4 rounded bg-primary" /> {aLabel}
        </span>
        <span className="flex items-center gap-1">
          <span className="h-1 w-4 rounded bg-danger" /> {bLabel}
        </span>
      </div>
    </div>
  )
}
