# Ecomerxio POS — Plan "funciona sin internet y no pierde datos"

Fecha: 20 oct 2026. Estado: **S1 hecha (20 oct 2026); S2-S6 por hacer.**

## 1. Cómo está hoy

| Qué | Sin internet hoy |
|---|---|
| Abrir la app (pantallas) | ✅ El service worker guarda la app completa |
| Sesión iniciada | ✅ Se recuerda en el equipo (`tp_me`) |
| Vender y cobrar | ✅ Catálogo, promociones y ajustes guardados; la venta va a una cola y se envía sola al volver (sin duplicar: `clientRef`) |
| Carrito a medio armar | ❌ Vive solo en memoria: un apagón lo borra |
| Cuentas abiertas / en espera | ❌ Están en el servidor: sin red no se ven ni se cobran |
| Caja: abrir/cerrar turno, gastos, retiros | ❌ Necesita servidor |
| Anular, devolver | ❌ Necesita servidor |
| Inventario: crear/editar producto, ajustes, conteos, recibir compras | ❌ Necesita servidor |
| Fiado: abonos, nuevo cliente | ❌ Necesita servidor |
| Gastos e ingresos | ❌ Necesita servidor |
| Dashboard, informes, historial | ❌ Pantalla en blanco o error |
| Facturación electrónica | 🟡 La venta queda; el documento se emite al llegar al servidor |

### Fallas encontradas que ponen en riesgo datos ante un apagón
1. **La cola se da por guardada antes de tiempo.** `idb.ts` responde "listo" cuando la petición termina, no cuando la
   transacción se confirma en disco (`oncomplete`). Un apagón en ese instante pierde la venta aunque al cliente ya se le
   mostró el tiquete.
2. **Durabilidad "relajada".** Chrome confirma las transacciones de IndexedDB antes de escribir a disco por defecto.
   Hay que pedir `durability: 'strict'` en lo que no se puede perder.
3. **El navegador puede borrar los datos** si el disco se llena (no se pide `navigator.storage.persist()`).
4. **Cerrar sesión no revisa** si hay ventas sin enviar.
5. **El carrito no se guarda**: un apagón a mitad de una venta obliga a empezar de cero.

## 2. Escenarios a resistir

| # | Escenario | Qué debe pasar |
|---|---|---|
| E1 | Se cae internet a mitad del día | Todo lo operativo sigue; arriba un aviso "Sin internet · N pendientes" |
| E2 | Se va la luz con el carrito armado | Al volver, el carrito sigue ahí |
| E3 | Se va la luz justo después de cobrar | La venta está guardada (se confirma en disco ANTES de mostrar el tiquete) |
| E4 | Se va la luz mientras se envían pendientes | Al volver se reenvían; el servidor no duplica (`clientRef`) |
| E5 | Se va la luz con internet, mientras el servidor guarda | Si no llegó respuesta, la operación pasa a la cola y se reintenta; el servidor no duplica |
| E6 | Varios días sin internet | La cola aguanta miles de operaciones; nada caduca; el aviso sube de tono |
| E7 | Dos cajas vendiendo sin internet el mismo producto | Ambas ventas valen; el stock puede quedar negativo y se avisa para revisar |
| E8 | Cambió un precio en el servidor mientras la caja estaba sin red | La venta conserva el precio cobrado; queda marcada |
| E9 | La sesión vence estando sin internet | Se sigue trabajando; al volver la red se pide entrar y la cola se envía después |
| E10 | Alguien quiere cerrar sesión o borrar datos con pendientes | Se bloquea y se explica; opción de descargar un respaldo de la cola |
| E11 | El equipo se daña o borran el navegador | Solo se pierde lo no enviado; mitigación: respaldo local descargable y envío inmediato apenas hay red |
| E12 | La hora del equipo está mal | Se guarda la hora del equipo y la del servidor al recibir; informes usan la del equipo, auditoría ambas |
| E13 | Dos pestañas abiertas | Un solo envío a la vez (Web Locks); el resto espera |
| E14 | Se cae el servidor (Render) o la base (Supabase) | Para la caja es igual a "sin internet"; los datos del servidor tienen copia nocturna + respaldo de Supabase |

## 3. Qué funcionará sin internet (alcance)

- **Operación diaria — escribe sin internet:** vender, cobrar (efectivo, fiado y digitales "por verificar"), cuentas
  abiertas y en espera, abrir/cerrar turno, gastos/retiros/ingresos de caja, devoluciones y anulaciones (con PIN
  guardado), abonos y clientes de fiado, crear/editar productos y precios, ajustes de inventario, conteos, recibir
  compras, gastos e ingresos.
- **Consulta — última copia guardada, con la hora:** dashboard, historial de ventas, inventario, proveedores, fiados,
  finanzas, informes, contabilidad, facturación.
- **Solo con internet (por naturaleza), con aviso claro:** primer inicio de sesión en un equipo, usuarios/roles,
  copias de seguridad y restauración, pedidos de la tienda en línea, conciliación bancaria, envío a la DIAN (queda en
  cola como contingencia), correos/WhatsApp, asistente IA.

## 4. Cómo se construye

**Un solo "buzón de salida" para todo.** Cada acción hecha sin internet se guarda como una operación con su
identificador único (`clientRef`), tipo, datos, usuario y hora. Se envían en orden y el servidor guarda cada
`clientRef` para no repetir nada (lo que ya hace con las ventas, extendido a todo).

**Una copia local para leer.** Cada pantalla guarda su última respuesta del servidor; sin red muestra esa copia con
"Datos de hace X min".

**Ids provisionales.** Lo creado sin internet (turno, cliente, producto) tiene un id temporal; al enviarse, el
servidor devuelve el real y las operaciones siguientes se corrigen solas.

**Conflictos.** Regla general: lo que pasó en la tienda (ventas, pagos, movimientos) siempre se acepta y se marca para
revisar; lo que es configuración (precio, datos de un producto) gana el cambio más reciente y queda en la actividad.

## 5. Fases

| Fase | Qué | Tamaño |
|---|---|---|
| **S1 — Blindaje (urgente)** | Confirmar en disco antes del tiquete (`oncomplete` + `strict`), `storage.persist()`, carrito guardado en cada cambio, bloqueo de cerrar sesión con pendientes, respaldo descargable de la cola, envío con Web Locks, operaciones online con `clientRef` que pasan a la cola si se corta la respuesta (E5) | Pequeño |
| **S2 — Buzón general** | Cola genérica de operaciones + idempotencia en el servidor (tabla `client_operations`) + pantalla "Pendientes por enviar" con reintento | Mediano |
| **S3 — Caja sin internet** | Turnos, movimientos, cierre, cuentas abiertas y en espera locales; anulaciones y devoluciones con PIN guardado | Mediano |
| **S4 — Inventario y fiado sin internet** | Productos, precios, ajustes, conteos, recepción de compras, clientes y abonos | Grande |
| **S5 — Consultas con copia** | Caché de lectura en todas las pantallas, aviso de antigüedad, descarga previa al cerrar turno | Mediano |
| **S6 — Pruebas de apagón** | Pruebas automáticas que cortan la red y "matan" la página a mitad de cada operación; prueba manual desenchufando un equipo real | Mediano |

## S1 — hecho (20 oct 2026)
- `idb.ts` v2: las escrituras se confirman con `oncomplete` y `durability: 'strict'`; nuevo almacén `sent`; `markSent`
  mueve del buzón al historial en una sola transacción.
- **Buzón siempre:** toda venta se guarda en el equipo ANTES de enviarse (estado `SENDING`); con respuesta del servidor
  pasa a `sent`; si se corta la red queda `PENDING`; si el servidor la rechaza o se cancela la autorización, se borra.
  Al abrir la app, lo que quedó `SENDING` (apagón) vuelve a `PENDING` y se reenvía.
- Historial de enviadas por 7 días; luego se borra solo.
- Envío desde cualquier pantalla (AppShell: al volver la red y cada 20 s), un solo envío a la vez entre pestañas
  (Web Locks); chip "Por enviar · N" también con internet.
- Carrito en localStorage + IndexedDB por tienda y usuario (sobrevive a cerrar, recargar o apagón).
- Cerrar sesión con pendientes: aviso con "Enviar ahora", "Descargar respaldo" (JSON) o salir (no se borra nada).
  Sin pendientes, se borran del equipo las copias de consulta y el carrito de la tienda.
- `navigator.storage.persist()` al abrir; aviso si el espacio de la app pasa del 80 %.
- Probado en el navegador: carrito recuperado de IndexedDB tras borrar localStorage y recargar; venta con internet
  (buzón → servidor → historial); "apagón" con dos ventas en `SENDING` (una nueva se creó, la ya enviada NO se
  duplicó); venta con el servidor apagado (tiquete provisional, `PENDING`, aviso al cerrar sesión) y envío automático
  al encenderlo.
- Pendiente para S2: el mismo buzón para los demás tipos de operación (hoy cubre las ventas).

## 6. Terminado cuando
- Con el cable de red desconectado, una jornada completa (abrir turno → vender → fiado → gasto → devolución → cerrar
  turno) se hace sin un solo error.
- Apagando el equipo de golpe en cualquier momento de esa jornada, al encender no falta nada de lo que se confirmó en
  pantalla.
- Al volver internet, todo se envía solo, sin duplicados, y el balance de prueba contable cuadra.
