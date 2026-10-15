/** Formatos colombianos: $ 12.500 y fechas cortas en español. */

export function money(value: number | null | undefined, currency = 'COP'): string {
  const v = value ?? 0
  if (currency === 'COP') {
    const s = Math.round(Math.abs(v)).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.')
    return `${v < 0 ? '-' : ''}$${s}`
  }
  return new Intl.NumberFormat('es-CO', { style: 'currency', currency }).format(v)
}

export function dateTime(iso?: string | null): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat('es-CO', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(iso))
}

export function relative(iso?: string | null): string {
  if (!iso) return 'Nunca'
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'Hace un momento'
  if (diff < 3600) return `Hace ${Math.floor(diff / 60)} min`
  if (diff < 86400) return `Hace ${Math.floor(diff / 3600)} h`
  if (diff < 86400 * 7) return `Hace ${Math.floor(diff / 86400)} d`
  return dateTime(iso)
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join('')
}
