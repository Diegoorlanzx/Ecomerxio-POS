import type { KitchenTicketDto } from './restaurantTypes'

/**
 * Impresión de comandas en la impresora de la cocina o el bar (térmica de 58 u 80 mm). Se imprime desde el navegador
 * del computador o tablet de la cocina, con la impresora como predeterminada. Para que imprima sin preguntar, Chrome o
 * Edge se abren con la opción --kiosk-printing (ver la ayuda de la pantalla de cocina).
 */

const KEY_AUTO = 'tp_kitchen_autoprint'
const KEY_WIDTH = 'tp_kitchen_width'

export function autoPrintEnabled(): boolean {
  try {
    return localStorage.getItem(KEY_AUTO) === '1'
  } catch {
    return false
  }
}

export function setAutoPrint(on: boolean) {
  try {
    localStorage.setItem(KEY_AUTO, on ? '1' : '0')
  } catch {
    /* sin almacenamiento */
  }
}

export function printWidth(): 58 | 80 {
  try {
    return localStorage.getItem(KEY_WIDTH) === '58' ? 58 : 80
  } catch {
    return 80
  }
}

export function setPrintWidth(w: 58 | 80) {
  try {
    localStorage.setItem(KEY_WIDTH, String(w))
  } catch {
    /* sin almacenamiento */
  }
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)
const qty = (n: number) => (Number.isInteger(n) ? String(n) : n.toLocaleString('es-CO', { maximumFractionDigits: 3 }))

/** Agrupa por comanda y estación (una hoja por comanda). */
export function groupComandas(tickets: KitchenTicketDto[]): KitchenTicketDto[][] {
  const map = new Map<string, KitchenTicketDto[]>()
  for (const t of tickets) {
    const k = `${t.station}-${t.comanda}`
    map.set(k, [...(map.get(k) ?? []), t])
  }
  return [...map.values()]
}

function sheet(list: KitchenTicketDto[]): string {
  const t = list[0]!
  const time = new Date(t.createdAt).toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' })
  const rows = list
    .map(
      (x) => `<div class="item${x.kind === 'ANULAR' ? ' cancel' : ''}">
        ${x.kind === 'ANULAR' ? '<div class="tag">*** ANULAR ***</div>' : ''}
        <div class="name"><b>${qty(Number(x.quantity))} x</b> ${esc(x.productName)}</div>
        ${x.modifiers ? `<div class="mods">${esc(x.modifiers)}</div>` : ''}
        ${x.note ? `<div class="note">» ${esc(x.note)}</div>` : ''}
      </div>`,
    )
    .join('')
  return `<section>
    <div class="head">${t.station === 'BAR' ? 'BAR' : 'COCINA'} · COMANDA ${t.comanda}</div>
    <div class="table">${esc(t.tableLabel)}</div>
    <div class="meta">${esc(t.waiterName ?? '')} · ${time}</div>
    <hr/>${rows}<hr/>
  </section>`
}

/** Imprime una o varias comandas (una hoja por comanda) en la impresora predeterminada. */
export function printComandas(groups: KitchenTicketDto[][], width: 58 | 80 = printWidth()) {
  if (!groups.length) return
  const body = groups.map(sheet).join('<div class="cut"></div>')
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>Comanda</title><style>
    @page { size: ${width}mm auto; margin: 0 }
    * { box-sizing: border-box }
    body { margin: 0; padding: 2mm 3mm; width: ${width}mm; font-family: Arial, Helvetica, sans-serif; color: #000 }
    .head { font-size: ${width === 58 ? 13 : 15}px; font-weight: 700; text-align: center }
    .table { font-size: ${width === 58 ? 22 : 28}px; font-weight: 800; text-align: center; margin: 2px 0 }
    .meta { font-size: 11px; text-align: center }
    hr { border: 0; border-top: 1px dashed #000; margin: 4px 0 }
    .item { margin: 4px 0 }
    .name { font-size: ${width === 58 ? 15 : 17}px }
    .mods { font-size: 13px; padding-left: 10px }
    .note { font-size: 13px; font-weight: 700; padding-left: 10px }
    .cancel .name { text-decoration: line-through }
    .tag { font-weight: 800; font-size: 13px }
    .cut { page-break-after: always; height: 6mm }
  </style></head><body>${body}</body></html>`
  const frame = document.createElement('iframe')
  frame.setAttribute('aria-hidden', 'true')
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
  document.body.appendChild(frame)
  const doc = frame.contentDocument
  if (!doc) return frame.remove()
  doc.open()
  doc.write(html)
  doc.close()
  setTimeout(() => {
    try {
      frame.contentWindow?.focus()
      frame.contentWindow?.print()
    } finally {
      setTimeout(() => frame.remove(), 2000)
    }
  }, 150)
}
