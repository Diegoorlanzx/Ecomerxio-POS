/** Minúsculas y sin tildes: "aguila" encuentra "Águila". */
export const fold = (s: string) => s.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '')
export const words = (s: string) => fold(s).split(/[^\p{L}\p{N}]+/u).filter(Boolean)

/** Lo que se compara de cada elemento: el nombre, textos secundarios (marca, variante...) y códigos (SKU, barras). */
export type SearchFields = { name: string; extra?: (string | null | undefined)[]; codes?: (string | null | undefined)[] }

/**
 * Qué tan bien coincide (menor = mejor; null = no coincide):
 * 0 el nombre empieza así, 1 cada palabra escrita es el comienzo de una palabra del nombre ("agua sab"),
 * 2 lo mismo en marca/variante o el código empieza así, 3 el texto aparece en medio de una palabra ("ma" en "crema").
 */
export function searchRank(f: SearchFields, q: string): number | null {
  const tokens = words(q).length ? words(q) : [q]
  const name = fold(f.name)
  if (name.startsWith(q)) return 0
  const startsWord = (text: string) => {
    const ws = words(text)
    return tokens.every((t) => ws.some((w) => w.startsWith(t)))
  }
  if (startsWord(f.name)) return 1
  const extra = (f.extra ?? []).filter(Boolean) as string[]
  const codes = ((f.codes ?? []).filter(Boolean) as string[]).map((c) => c.toLowerCase())
  if (extra.some(startsWord) || codes.some((c) => c.startsWith(q))) return 2
  if (name.includes(q) || extra.some((e) => fold(e).includes(q)) || codes.some((c) => c.includes(q))) return 3
  return null
}

/**
 * Filtra y ordena: primero lo que empieza con lo escrito. Lo que solo lo contiene en medio de una palabra
 * aparece al final y solo con 3 letras o más (o si no hay nada mejor), para que "ma" no traiga "crema".
 */
export function searchList<T>(list: T[], query: string, fields: (item: T) => SearchFields): T[] {
  const q = fold(query.trim())
  if (!q) return list
  const ranked = list.map((item) => ({ item, rank: searchRank(fields(item), q) })).filter((x): x is { item: T; rank: number } => x.rank !== null)
  const hasGood = ranked.some((x) => x.rank < 3)
  return ranked
    .filter((x) => x.rank < 3 || q.length >= 3 || !hasGood)
    .sort((a, b) => a.rank - b.rank)
    .map((x) => x.item)
}
