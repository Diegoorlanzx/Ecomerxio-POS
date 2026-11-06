/**
 * Arreglos para todos los campos de número de la app (los que abren el teclado numérico: inputMode numeric/decimal),
 * hechos una sola vez para el documento en vez de campo por campo:
 *  - Al tocar un campo que solo tiene "0", queda seleccionado: lo que se escribe reemplaza el 0 (antes quedaba "05").
 *  - Si aun así queda un cero a la izquierda ("05", "007"), se quita mientras se escribe. "0.5" y "0,5" no se tocan.
 * No se tocan los campos donde los ceros a la izquierda son parte del dato: PIN, documento, cédula, NIT/DV, códigos,
 * PLU, números de venta o factura.
 */

const KEEP_ZEROS = /pin|clave|contraseña|documento|c[eé]dula|nit|\bdv\b|c[oó]digo|plu|n[uú]mero|n\.º|celular|tel[eé]fono|cuenta/i
const ONLY_ZERO = /^0([.,]0*)?$/
const LEADING_ZEROS = /^(-?)0+(?=\d)/

/** El setter propio del navegador: cambiar el valor con él deja que React vea el cambio en el evento "input". */
const nativeValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set

function numberField(e: Event): HTMLInputElement | null {
  // Dentro del Shadow DOM (app incrustada) el evento llega con el anfitrión como target: el campo real va primero.
  const el = e.composedPath()[0]
  if (!(el instanceof HTMLInputElement)) return null
  if (el.type === 'password' || el.readOnly || el.disabled) return null
  if (el.inputMode !== 'numeric' && el.inputMode !== 'decimal') return null
  const label = [el.getAttribute('aria-label'), el.placeholder, el.name, ...Array.from(el.labels ?? [], (l) => l.textContent)].join(' ')
  return KEEP_ZEROS.test(label) ? null : el
}

let installed = false

/** "focusin" e "input" atraviesan el Shadow DOM, así que basta escuchar en el documento (también con la app incrustada). */
export function installNumberFieldFixes() {
  if (installed) return
  installed = true

  document.addEventListener('focusin', (e) => {
    const el = numberField(e)
    if (el && ONLY_ZERO.test(el.value)) {
      // Después del clic que enfoca (que movería el cursor y quitaría la selección).
      setTimeout(() => el.select(), 0)
    }
  })

  document.addEventListener(
    'input',
    (e) => {
      const el = numberField(e)
      if (!el || !nativeValueSetter) return
      const fixed = el.value.replace(LEADING_ZEROS, '$1')
      if (fixed !== el.value) {
        const end = el.selectionEnd ?? fixed.length
        const removed = el.value.length - fixed.length
        nativeValueSetter.call(el, fixed)
        el.setSelectionRange(Math.max(0, end - removed), Math.max(0, end - removed))
      }
    },
    true,
  )
}
