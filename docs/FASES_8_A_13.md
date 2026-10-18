# TiendaPOSS — Fases 8 a 13: más tipos de negocio y tienda en línea

Diseño (sin código todavía). Amplía TiendaPOSS más allá de la tienda de barrio sin romper lo que ya funciona: cada
fase se construye encima de lo existente (ventas, cuentas abiertas, inventario por movimientos, fiado, permisos,
notificaciones, copias de seguridad) y termina, como las anteriores, con pruebas, verificación en el navegador y commit.

## Principio que guía todo: perfiles de negocio
Una cigarrería no debe ver mesas ni agenda, y un restaurante no necesita el pedido sugerido de Bavaria igual que una
tienda. Por eso la Fase 8 crea **perfiles de negocio** (Tienda, Restaurante/Café, Servicios, Distribuidora, Mixto) que
encienden o apagan módulos y ajustan los valores por defecto. Cada fase nueva es un módulo que se activa por perfil
(y más adelante por plan de suscripción). Nada de sistemas separados: una sola base, un solo POS.

## Orden recomendado y por qué
| Fase | Módulo | Para quién | Tamaño |
|---|---|---|---|
| 8 | Perfiles de negocio + catálogo avanzado (variantes, combos, seriales, básculas) | Base para todo; ropa, celulares, fruver, carnicería | Grande |
| 9 | Tienda en línea y catálogo por QR | Todos: es el plan superior y el camino para cobrar más | Grande |
| 10 | Restaurante: mesas, comandas, cocina y recetas | Restaurantes, cafés, bares, heladerías | Grande |
| 11 | Servicios: agenda, personal, comisiones y órdenes de servicio | Peluquerías, spas, talleres, técnicos | Mediana |
| 12 | Distribución: preventa, rutas, listas de precios y remisiones | Distribuidoras y mayoristas pequeños | Grande |
| 13 | Facturación electrónica DIAN (vía proveedor autorizado) | Negocios formales obligados | Mediana |
| 14 | Contabilidad básica automática (diseño en `FASE_14_CONTABILIDAD.md`) | Negocios con contador; plan Pro | Grande |

Nota de numeración: la guía interactiva se hizo como fase extra entre la 9 y la 10, y en el código quedó como
"Fase 10" (commit 879be7e). En este plan la 10 sigue siendo Restaurante.

La 8 va primero porque las variantes cambian cómo se identifica lo que se vende y se cuenta en inventario; hacerlo
después obligaría a rehacer ventas, compras y reportes. La 9 va segunda porque es la de mayor valor comercial.
La 13 no la pediste ahora, pero es la condición para venderle a negocios formales medianos.

---

## Fase 8 — Perfiles de negocio y catálogo avanzado
> **Estado: hecha (16 oct 2026).** 8 perfiles y 8 módulos activables; variantes, combos, seriales con garantía y
> etiquetas de báscula funcionando y probados. Quedan para la Fase 10: repartir el precio del combo entre sus
> componentes en la rentabilidad y los combos con opción a elegir. La lectura directa de la báscula (Web Serial) falta
> probarla con una báscula real. Detalle en `CHECKLIST.md`.

**Objetivo:** que cualquier comercio de productos pueda modelar lo que vende: tallas y colores, combos, equipos con
número de serie y productos pesados en báscula.

### 8.1 Perfiles de negocio y módulos
- Al registrarse, la tienda elige su tipo; se puede cambiar en Configuración.
- Tabla de módulos por tienda (activo/inactivo); el menú, las pantallas y las rutas del servidor respetan los módulos.
- Valores por defecto por perfil (p. ej. restaurante: cuentas abiertas como mesas; servicios: sin control de stock en servicios).

### 8.2 Variantes (talla, color, sabor, tamaño…)
- **Diseño:** cada variante es un producto propio (con su SKU, código de barras, precio, costo y existencias) agrupado
  bajo un **producto padre** con atributos. Así el inventario, las ventas, las compras, la analítica y la rentabilidad
  siguen funcionando igual, sin rehacerlos.
- Pantallas: crear el padre con sus atributos y generar la matriz (S/M/L × Negro/Blanco) con precios y existencias;
  en el POS, al tocar el producto se elige la variante; en inventario y reportes, ver por variante o agrupado.
- Compras y conteos por variante; pedido sugerido por variante.

### 8.3 Combos y kits
- Producto tipo **combo** sin existencias propias, con componentes y cantidades (p. ej. "Combo desayuno": café +
  pandebono + jugo). Al venderlo se descuentan los componentes.
- El precio del combo se reparte entre los componentes en proporción a su precio, para que la rentabilidad por
  producto siga siendo correcta. Combos con opción a elegir (p. ej. "gaseosa a elección").

### 8.4 Números de serie y garantía
- Productos con **serial** (celulares por IMEI, electrodomésticos, herramientas).
- Al recibir mercancía se registran los seriales; al vender se exige escanear o elegir el serial; en devoluciones el
  serial vuelve al inventario o queda como defectuoso.
- Garantía en meses por producto; consulta "¿quién compró el IMEI X y hasta cuándo tiene garantía?".

### 8.5 Básculas y productos pesados
- **Etiquetas de báscula EAN-13** (prefijos 20–29 con PLU + peso o precio): el POS reconoce el código, busca el
  producto por PLU y calcula la cantidad. El formato y el prefijo se configuran por tienda.
- **Lectura directa de la báscula** por puerto serial desde Chrome/Edge (Web Serial), con botón "Pesar" en el POS;
  si el equipo no lo permite, se digita el peso como hoy.
- Impresión de etiquetas de precio para productos pesados.

**Terminado cuando:** una tienda de ropa crea un producto con 3 tallas × 2 colores, lo vende y ve existencias por
variante; un combo descuenta sus componentes; un celular se vende por IMEI y aparece en la consulta de garantía; una
etiqueta de báscula se escanea y cobra el peso correcto.

---

## Fase 9 — Tienda en línea y catálogo por QR
**Objetivo:** que cada tienda tenga su vitrina en internet y reciba pedidos, sin un sistema aparte: el pedido llega
al mismo POS y descuenta del mismo inventario.

### 9.1 Catálogo público
- Página pública por tienda (`dominio/t/mi-tienda`, y dominio propio opcional): logo, horario, dirección, WhatsApp,
  categorías, búsqueda, fotos, precio y "agotado" (en vivo desde el inventario).
- Qué productos se publican y con qué precio (puede diferir del de mostrador); ocultar existencias exactas.
- Optimizada para celular y para compartir por WhatsApp; buena carga en señal lenta.

### 9.2 Catálogo por QR
- Cartel imprimible con el QR de la tienda (vitrina, mesas, volantes) y QR en el comprobante de venta.
- Modo "carta" (solo ver precios, sin comprar) para quien no quiere pedidos en línea.

### 9.3 Pedidos
- Carrito → datos del cliente → domicilio o recoger → forma de pago (contra entrega, transferencia/Nequi con
  verificación, o pasarela cuando exista la integración de Wompi).
- Opción simple: el pedido se envía también por **WhatsApp** a la tienda con el resumen.
- En el POS, una bandeja **Pedidos en línea** con estados (nuevo → aceptado → preparando → listo → en camino →
  entregado / cancelado), sonido de aviso y notificación; al aceptarlo se reservan las existencias y al entregarlo
  se cobra como una venta normal (queda en caja, analítica y rentabilidad).
- Zonas y valor del domicilio, pedido mínimo, horario de atención (fuera de horario: "abrimos a las 7 a. m.").
- El cliente sigue su pedido con un enlace; el cliente queda registrado (y puede ser cliente de fiado si la tienda quiere).

### 9.4 Comercial
- Es el **plan superior**: Básico = POS; Pro = POS + tienda en línea. La vitrina muestra "Hecho con TiendaPOSS"
  en el plan gratuito o de entrada.

**Terminado cuando:** desde un celular se hace un pedido con domicilio, la tienda lo recibe en el POS, lo prepara y
lo cobra; el inventario y los reportes lo reflejan; el QR impreso abre el catálogo.

---

## Fase 10 — Restaurante: mesas, comandas, cocina y recetas
**Objetivo:** que un restaurante, café o bar atienda mesas y su cocina reciba las órdenes, sin papelitos.

- **Mesas y salones:** plano sencillo (cuadrícula) con mesas libres/ocupadas/por cobrar y tiempo ocupadas. Usa las
  cuentas abiertas que ya existen (una cuenta = una mesa). Mover y unir mesas.
- **Meseros:** cada pedido queda a nombre del mesero; vista del mesero en el celular.
- **Modificadores:** "sin cebolla", "término medio", adiciones con precio (extra queso).
- **Comandas a cocina y bar:** cada producto va a su estación; pantalla de cocina (KDS) con pendientes, en
  preparación y listos, con tiempos; o impresión de la comanda en la impresora de cocina. Aviso al mesero cuando
  está listo.
- **Recetas (escandallo):** cada plato descuenta sus ingredientes del inventario (con mermas); costo del plato y
  margen real en la rentabilidad; pedido sugerido de ingredientes.
- **Cuenta:** dividir por persona o por productos, propina voluntaria (sugerida configurable, siempre opcional y
  separada del total, como exige la norma), precuenta impresa, cobro con varios medios.
- **Domicilios y para llevar** conectados con la tienda en línea de la Fase 9.

**Terminado cuando:** se abre la mesa 4, el mesero pide 2 platos con modificadores, la cocina los ve y los marca
listos, la cuenta se divide entre dos personas con propina, y el inventario descuenta los ingredientes de las recetas.

---

## Fase 11 — Servicios: agenda, personal, comisiones y órdenes de servicio
**Objetivo:** negocios que venden tiempo y trabajo, no solo productos.

- **Catálogo de servicios:** duración, precio, profesional que lo hace, productos que consume (p. ej. tinte).
- **Agenda:** calendario por profesional y por día/semana, citas con cliente y servicio, estados (agendada,
  confirmada, atendida, no asistió), bloqueos y horarios. Recordatorio por WhatsApp un día antes (con el sistema de
  notificaciones) y enlace para que el cliente pida su cita desde la tienda en línea.
- **Personal y comisiones:** porcentaje o valor fijo por servicio y por producto vendido; liquidación por quincena
  con el detalle; queda como gasto de nómina en finanzas.
- **Órdenes de servicio** (talleres, técnicos de celulares, electrodomésticos): recepción del equipo con estado y
  fotos, diagnóstico, cotización aprobada por el cliente, repuestos usados (salen del inventario), abonos, aviso de
  "listo para recoger" por WhatsApp y entrega con garantía.
- Cobro en el POS de servicios + productos en la misma venta.

**Terminado cuando:** se agenda una cita, llega el recordatorio, se atiende y se cobra; la comisión aparece en la
liquidación; una orden de servicio de un celular pasa de recibido a entregado con repuestos y abono.

---

## Fase 12 — Distribución: preventa, rutas, listas de precios y remisiones
**Objetivo:** distribuidoras pequeñas que venden a tiendas, con vendedores en la calle.

- **Listas de precios** por tipo de cliente (tienda, mayorista, especial) y por cliente; descuentos por volumen.
- **Clientes con ruta y día de visita;** cartera por cliente (reutiliza el fiado como crédito comercial con plazos).
- **Preventa en el celular del vendedor** (funciona sin internet, como el POS): toma pedidos en la tienda del
  cliente, ve su cartera y su historial, y sincroniza al volver la señal.
- **Despacho:** consolidado de pedidos para alistar, remisión imprimible, entrega con estado (entregado, parcial,
  devuelto) y cobro en ruta (efectivo, transferencia); cierre de ruta del conductor con cuadre de dinero.
- **Reportes:** ventas por vendedor y por ruta, efectividad de visitas, cartera vencida por ruta, comisiones.

**Terminado cuando:** un vendedor toma 3 pedidos sin internet, se sincronizan, se alistan y se despachan con
remisión, uno se entrega parcial, y el cierre de ruta cuadra el dinero.

---

## Fase 13 — Facturación electrónica DIAN
> **Reemplazada** por el plan detallado en `docs/PLAN_FACTURACION_Y_FINANZAS.md` (fases 13.0 a 13E, 14 y 15).

**Objetivo:** que los negocios obligados expidan factura electrónica o documento equivalente POS electrónico.

- Integración con un **proveedor tecnológico autorizado por la DIAN** (no se construye la firma ni la transmisión):
  configuración de resolución de numeración, datos fiscales del cliente, envío del documento al proveedor y estado.
- Desde la venta: "Factura electrónica" o "Documento POS electrónico" según el régimen; notas crédito desde las
  devoluciones; reintento si el proveedor no responde; el comprobante impreso incluye el CUFE/CUDE y el QR.
- Costo por documento según el proveedor: se traslada en el plan o por paquetes.

**Terminado cuando:** una venta genera su documento electrónico aceptado por la DIAN (en ambiente de habilitación
del proveedor), una devolución genera su nota crédito y el comprobante muestra el código y el QR.

---

## Lo que no cambia
- Una sola base de datos multi-tienda, los mismos permisos por rol (con permisos nuevos por módulo), la venta sin
  internet, las copias de seguridad por tienda (incluirán las tablas nuevas) y el asistente IA (con herramientas
  nuevas por módulo).
- Cada fase agrega su parte a la tienda de ejemplo (`scripts/crear_tienda_demo.py`) con un negocio de ese tipo:
  boutique (8), tienda con pedidos en línea (9), restaurante (10), peluquería y taller (11), distribuidora (12).
