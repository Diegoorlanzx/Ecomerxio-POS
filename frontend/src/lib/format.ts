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

/** Cantidad sin ceros sobrantes: 3, 0,75, 12,5. */
export function qty(v: number | null | undefined): string {
  const n = Number(v ?? 0)
  return Number.isInteger(n) ? String(n) : n.toLocaleString('es-CO', { maximumFractionDigits: 3 })
}

export function pct(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return `${Number(v).toLocaleString('es-CO', { maximumFractionDigits: 1 })}%`
}

export function shortDate(iso?: string | null): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat('es-CO', { day: '2-digit', month: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(iso))
}

/** Reduce una foto a máx. `max` px y la devuelve como data URL WEBP/JPEG liviana. */
export function imageToDataUrl(file: File, max = 640): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const url = URL.createObjectURL(file)
    img.onload = () => {
      const scale = Math.min(1, max / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(img.width * scale)
      canvas.height = Math.round(img.height * scale)
      canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height)
      URL.revokeObjectURL(url)
      let out = canvas.toDataURL('image/webp', 0.82)
      if (!out.startsWith('data:image/webp')) out = canvas.toDataURL('image/jpeg', 0.82)
      resolve(out)
    }
    img.onerror = () => reject(new Error('No se pudo leer la imagen'))
    img.src = url
  })
}

export function download(filename: string, content: Blob) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(content)
  a.download = filename
  document.body.appendChild(a)
  a.click()
  setTimeout(() => {
    URL.revokeObjectURL(a.href)
    a.remove()
  }, 500)
}
