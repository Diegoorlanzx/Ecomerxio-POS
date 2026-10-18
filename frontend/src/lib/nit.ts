/** Dígito de verificación del NIT (módulo 11 con los pesos de la DIAN). Igual que fiscal/Nit.java en el servidor. */
const WEIGHTS = [3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71]

export const nitDigits = (value: string) => value.replace(/\D/g, '')

export function nitDv(value: string): string {
  const d = nitDigits(value)
  if (!d || d.length > WEIGHTS.length) return ''
  let sum = 0
  for (let i = 0; i < d.length; i++) sum += Number(d[d.length - 1 - i]) * WEIGHTS[i]
  const r = sum % 11
  return String(r > 1 ? 11 - r : r)
}
