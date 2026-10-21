/**
 * Modo de práctica: se vende y se cobra en la caja como si fuera real, pero nada se guarda en el servidor ni descuenta
 * inventario. Lo enciende cada usuario en la Guía interactiva (GuideProvider sincroniza este valor con su preferencia).
 * Mientras está activo, cualquier otra operación que guarde datos se bloquea con PRACTICE_MESSAGE.
 */
let active = false

export const PRACTICE_MESSAGE = 'Estás en modo práctica: esto no se guarda. Sal del modo práctica (arriba) para trabajar de verdad.'

export const isPractice = () => active

export function setPractice(on: boolean) {
  active = on
}
