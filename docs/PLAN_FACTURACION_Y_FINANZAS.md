# Ecomerxio POS — Plan de Facturación electrónica y Finanzas (fases 13 a 15)

Análisis y mejora de la propuesta "Facturación y Finanzas" (motor de facturación, motor contable, paquetes de
documentos). Reemplaza la Fase 13 de `FASES_8_A_13.md` y se apoya en `FASE_14_CONTABILIDAD.md`, que sigue vigente.

Todo lo normativo (tipos de documento, plazos, contingencia, conservación, IVA del software) está marcado
**"a confirmar"**: se valida por escrito con el proveedor tecnológico y con un contador antes de programarlo.

---

## 1. Qué está bien de la propuesta y se conserva

- Separar **venta (POS)**, **documento electrónico (DIAN)**, **pago** y **contabilidad**: son cuatro estados
  distintos de la misma operación y el tablero debe mostrarlos por separado.
- **No construir la transmisión**: integrarse con un proveedor tecnológico autorizado y controlar nosotros la
  experiencia, los errores, el historial, los permisos y el consumo.
- **Regla de oro**: nunca emitir una segunda factura sin saber qué pasó con la primera; consultar estado antes de
  reintentar; corregir con nota crédito o débito, no editando ni borrando.
- **Centro de errores** con explicación en español, acción recomendada e historial inalterable.
- **Saldo de documentos como libro de movimientos**, no como un número editable.
- Distinguir las facturas que emiten los comercios de las que Ecomerxio emite por vender planes y paquetes.
- Validar proveedor y flujo de emisión **antes** de construir formularios contables.

## 2. Qué cambia y por qué

### 2.1 La mitad de "Finanzas" ya existe: no se reconstruye
| Lo que pide la propuesta | Estado en Ecomerxio POS hoy |
|---|---|
| Gastos e ingresos por categoría, gastos desde la caja | ✅ `finance/FinanceEntry`, movimientos de caja |
| Compras, recepción parcial, devoluciones a proveedor | ✅ `purchasing` (órdenes, recepción con faltantes, lotes) |
| Cuentas por pagar, pagos parciales (FIFO) | ✅ `finance/SupplierPayment` + asignaciones |
| Cartera: saldos, abonos, vencimientos, mora | ✅ Fiado (`credit`) con cupo, plazo, mora y abonos FIFO |
| Conciliación con extracto bancario | ✅ `payments/Reconciliation` (CSV) y pagos digitales por verificar |
| Rentabilidad por producto, margen, gastos repartidos | ✅ `/rentabilidad` |
| Alertas | ✅ Notificaciones por rol |
| Plan de cuentas, partida doble, asientos, cierres | 📐 Diseñado en `FASE_14_CONTABILIDAD.md` |
| **Bancos con saldo, flujo de caja proyectado, calendario tributario** | ❌ Falta (Fase 15) |
| **Todo lo de facturación electrónica y paquetes** | ❌ Falta (Fase 13) |

Por eso la fase "3. Finanzas" de la propuesta se reduce a lo que falta, y la "4. Contabilidad" es la Fase 14 ya diseñada.

### 2.2 Faltaba la fase que más soporte va a generar: datos fiscales y habilitación
La propuesta empieza por emitir. Pero hoy **no se puede emitir nada correcto** porque faltan datos:
- **Clientes:** tienen número de documento, pero no tipo de documento, dígito de verificación, responsabilidades
  fiscales, municipio ni correo de facturación.
- **Comercio:** tiene NIT y régimen como texto libre. Faltan las responsabilidades fiscales, la actividad económica,
  el municipio y la resolución de numeración (prefijo, rango, vigencia y tipo de documento).
- **Productos:** tienen tarifa de IVA, pero no si son excluidos, exentos o gravados, ni impuesto al consumo, ni la
  unidad de medida que exige el documento.

Además, **cada comercio tiene que habilitarse**: inscribirse como facturador, autorizar al proveedor, sacar la
resolución de numeración y pasar el set de pruebas (pasos y nombres **a confirmar** con el proveedor). Para una tienda
de barrio esto es lo más difícil, así que va una fase propia con asistente paso a paso (Fase 13A).

### 2.3 Faltaba decidir el documento correcto para cada venta
Para un tendero, la mayoría de ventas no son "factura electrónica" con datos del comprador: son ventas a
consumidor final, que pueden ir como **documento equivalente POS electrónico**, y solo cuando el cliente lo pide
se expide factura con sus datos. Montos, reglas y obligatoriedad: **a confirmar**. El sistema debe:
- Proponer el tipo según la configuración del comercio y si la venta tiene cliente identificado.
- Permitir "el cliente pide factura" en el cobro sin frenar la fila.
- No dejar emitir si falta un dato obligatorio, y decir cuál y dónde corregirlo.

### 2.4 Ventas sin internet: un caso que la propuesta no contempla
Ecomerxio POS vende sin conexión (`sales.client_ref`, `offline`). Esa venta **no puede emitirse en el momento**:
- Queda en estado **"Pendiente de emisión"** con su **fecha y hora real**, y el tiquete impreso lo dice.
- Al volver la conexión se emite en orden. Si pasó el plazo, se aplica el procedimiento de contingencia
  (**a confirmar** cuál y cómo lo soporta el proveedor).
- El centro de errores muestra las pendientes con su antigüedad y una alerta si se acercan al plazo.

### 2.5 Las compras también son documentos: documento soporte y facturas de proveedores
Las tiendas le compran mucho a proveedores que no facturan (plaza, campesinos, informales). Para deducir esos costos
hace falta el **documento soporte** electrónico (**a confirmar** alcance), que también consume saldo y se genera
desde la recepción de compras que ya existe. Más adelante, **importar las facturas electrónicas de los proveedores**
(XML) evita digitar compras y alimenta el costo y la contabilidad sin doble registro.

### 2.6 Los paquetes chocan con lo que ya se prometió en los planes
El estudio de precios (`docs/ventas/estudio_precios.html`) dice:
- **Plan Negocio:** "factura electrónica con cupo mensual".
- **Plan Tienda:** factura electrónica como adicional de $20.000/mes.

La propuesta vende solo paquetes. Modelo que une las dos cosas:
- **Cupo mensual incluido** en Negocio y Pro (no acumulable): da previsibilidad y se vende fácil.
- **Paquetes prepagados** para lo que pase del cupo y para el plan Tienda. No vencen, o vencen a los 12 meses
  (decisión comercial).
- **El consumo usa primero el cupo del mes y luego el paquete.** Las dos cosas quedan en el mismo libro de movimientos.

### 2.7 El saldo necesita "reservar" para que la regla de oro funcione
La propuesta dice "registrar el consumo al emitir". Con estados desconocidos y doble clic eso no basta. El libro
maneja **tres movimientos por documento**:

| Momento | Movimiento | Saldo disponible |
|---|---|---|
| Se inicia la emisión | **Reserva** (1 documento) | baja |
| El proveedor/DIAN lo acepta | **Consumo** (confirma la reserva) | igual |
| Se rechaza **antes** de transmitirse (validación propia o del proveedor) | **Liberación** | sube |
| Rechazo DIAN después de transmitido | Según cómo cobre el proveedor (**a confirmar**) | — |
| Estado desconocido | La reserva **se queda** hasta consultar | — |

La reserva es atómica: si no hay saldo, no se emite. Se guarda la referencia de la venta para que dos clics no
generen dos reservas. Las regla de qué consume, por tipo de documento, va en configuración y se ajusta al contrato.

### 2.8 Prerrequisito comercial que faltaba: cobrar la suscripción del POS
Comprar un paquete exige cobrar en línea, y **Ecomerxio POS todavía no cobra suscripciones** (los planes existen,
pero no hay pasarela ni límites aplicados). Antes de vender paquetes hay que montar el cobro con Wompi de la
plataforma (se puede reutilizar el modelo de Ecomerxio: widget, sin cobro automático, conciliación) y aplicar los
límites de cajas y usuarios de cada plan.

### 2.9 Ecomerxio también tiene que facturar
Cada plan y cada paquete vendido exige que **Ecomerxio emita su propia factura electrónica** al comercio. Eso
requiere una cuenta propia con el proveedor (o un software de facturación aparte) y que un contador defina el
tratamiento tributario de vender software en la nube y paquetes de documentos (**a confirmar**, por ejemplo si
causa IVA).

### 2.10 Arquitectura: más simple de lo propuesto, sin perder garantías
- **Sin Kafka ni Redis.** Hoy hay un solo servidor y una base PostgreSQL. La cola de trabajos y el *outbox* van en
  tablas con `FOR UPDATE SKIP LOCKED` y un programador, igual que las notificaciones actuales. Si algún día hay
  varios servidores, sigue funcionando.
- **Adaptador de proveedor detrás de una interfaz** (`ProveedorFacturacion`: emitir, consultar estado, anular con
  nota, descargar XML/PDF, recibir aviso). Primero se hace una implementación **simulada** que reproduce aceptación,
  rechazos, demoras y caídas. Así el módulo se construye y se prueba antes de firmar contrato, y cambiar de
  proveedor es cambiar el adaptador.
- **Idempotencia de punta a punta:** la venta lleva una clave única y esa misma clave va como referencia externa al
  proveedor. Antes de cualquier reintento se consulta por esa referencia.
- **Numeración:** se reserva el consecutivo de la resolución de forma atómica y sin huecos. Si el proveedor asigna el
  número, se guarda el que devuelve (**a confirmar** quién lo asigna).
- **Secretos:** las credenciales del proveedor van cifradas en la base, con una llave en variable de entorno. Ese
  cifrado no existe hoy en el POS y hay que agregarlo. El webhook se firma, como el webhook HMAC que ya existe.
- **Archivos XML/PDF:** se guarda la referencia y una copia en almacenamiento de objetos. El plazo de conservación es
  **a confirmar**, al igual que si basta con lo que guarda el proveedor.
- **Copias de seguridad por tienda:** deben incluir las tablas nuevas, y restaurar una copia **nunca** debe
  re-emitir documentos.

## 3. Fases mejoradas

### Fase 13.0 — Decisiones sin código (en paralelo con 13A)
- Cotizar con **Dataico** y **The Factory HKA**, y con **Alegra** como tercera opción. Usar la lista de la propuesta
  y agregar tres preguntas:
  - Si cobran por intento o por documento aceptado.
  - Cómo soportan la contingencia y el documento soporte.
  - Si el modelo de integrador permite un solo contrato para muchos NIT.
- Verificar en el listado oficial de la DIAN que el proveedor está autorizado.
- **Contador:** que revise y confirme los puntos "a confirmar" de este documento y la Fase 14.
- **Ecomerxio como emisor:** cuenta para facturar sus propios planes y paquetes.
- **Decisión comercial:** cupo mensual por plan, precios de los paquetes y si vencen.

**Terminado cuando:** hay un proveedor elegido con propuesta escrita y acceso a su ambiente de pruebas, y una lista
de reglas confirmada por el contador.

### Fase 13A — Datos fiscales y habilitación
> ✅ **Hecha (18 oct 2026).** V13; paquete `fiscal` (perfil, `Nit` con DV, `FiscalCodes`, resoluciones, clasificación,
> preparación) y `/api/fiscal/*`; permiso `FACTURACION_CONFIGURAR`; alertas `RESOLUCION_POR_VENCER` y
> `RESOLUCION_POR_AGOTAR`; datos fiscales de clientes en `/api/pos/customers`; clasificación también en el formulario
> del producto; pantalla **Más → Facturación electrónica** (`/facturacion`); tablas en las copias de seguridad.
> Pruebas: `NitTest`, `FiscalIT`.

- **Comercio:**
  - Datos fiscales completos con validación del dígito de verificación.
  - Responsabilidades fiscales y municipio.
  - Resoluciones de numeración: tipo de documento, prefijo, rango, vigencia, y si está en prueba o en producción.
- **Clientes:** tipo de documento, dígito de verificación, correo de facturación y "consumidor final" por defecto.
- **Productos:**
  - Clasificación: gravado, exento, excluido o impuesto al consumo.
  - Unidad de medida.
  - Revisión masiva con avisos de lo que falta; el sistema no inventa la clasificación tributaria.
- **Asistente de habilitación:** pasos con estado y enlaces, ambiente de pruebas, y botón "emitir documento de
  prueba" con el simulador o el proveedor.
- **Alertas** cuando la resolución va a vencer o se va a agotar el rango (por ejemplo al 80 % y al 95 %).

**Terminado cuando:** la tienda de ejemplo queda completa y validada, y el asistente detecta y explica cada dato
faltante.

### Fase 13B — Motor de emisión
- **Desde el cobro:** documento POS electrónico o factura, según la configuración y si el cliente la pide.
- **Estados del documento:**
  - Borrador
  - Pendiente de emisión (sin internet)
  - En proceso
  - Estado desconocido
  - Aceptado
  - Rechazado
  - Anulado por nota
- **Eventos:** historial inalterable de cada solicitud y respuesta, con usuario y hora.
- **Cola de trabajos** con reintentos y espera creciente (*backoff*), y consulta de estado antes de reintentar.
  Recibe el aviso del proveedor (webhook firmado) y, como respaldo, consulta periódicamente.
- **Notas:**
  - Nota crédito desde devoluciones y anulaciones, que ya existen en el POS.
  - Nota débito para corregir valores hacia arriba.
- **Comprobante impreso:** con el código del documento (CUFE/CUDE), el QR y la leyenda que corresponda. Envío al
  cliente por correo o WhatsApp.
- **Ventas sin internet:** cola de emisión con la fecha real y alerta de plazo.
- **Adaptador simulado + adaptador real** del proveedor elegido.

**Terminado cuando:** en el ambiente de pruebas del proveedor se cumple todo esto:
- una venta genera su documento aceptado;
- un doble clic no duplica;
- una caída a mitad de emisión se recupera sin duplicar;
- una devolución genera su nota crédito;
- una venta hecha sin internet se emite al volver la conexión.

### Fase 13C — Centro de errores y cola de tareas
- **Bandeja** de documentos rechazados, desconocidos y pendientes, filtrable y con acciones en lote.
- **Ficha de cada documento:**
  - Venta y número.
  - Estado del proveedor y estado DIAN.
  - Código y mensaje original.
  - Explicación en español y acción recomendada: corregir el dato, consultar el estado, reintentar de forma segura,
    o emitir la nota.
- **Catálogo de errores** con explicación, causa probable y acción, que se va ampliando con los errores reales que
  aparezcan.
- **Permisos nuevos:** `FACTURACION_EMITIR`, `FACTURACION_CORREGIR` y `FACTURACION_CONFIGURAR`.
- **En el tablero:** ventas del POS, documentos aceptados, documentos pendientes o con error, y pagos, cada cosa
  por separado.

**Terminado cuando:** cada caso de la tabla de errores de la propuesta tiene una prueba automática con el
simulador, y su pantalla guía hasta resolverlo.

### Fase 13D — Cupo, paquetes y saldo
- **Requisito previo:** cobro de la suscripción del POS con Wompi de la plataforma, y límites por plan aplicados.
- **Libro del saldo:** compra, cupo del mes, reserva, consumo, liberación, ajuste administrativo con motivo y
  vencimiento. No se edita ni se borra.
- **"Mi paquete":**
  - Saldo y consumo por tipo de documento y por caja.
  - Compra de paquetes.
  - Alertas al 20 %, 10 % y 0 %.
  - Qué pasa al quedarse sin saldo: la venta se hace igual y el documento queda pendiente hasta recargar
    (**a confirmar** contra los plazos).
- **Super administrador del POS** (no existe hoy):
  - Precios de los paquetes.
  - Ajustes con motivo y auditoría.
  - Consumo por comercio.
  - Margen frente al costo del proveedor.
- **Factura de Ecomerxio** por cada compra de paquete y plan.

**Terminado cuando:**
- un comercio compra un paquete y el saldo se acredita una sola vez aunque el aviso de pago llegue dos veces;
- emitir con saldo cero queda controlado;
- el libro cuadra con lo cobrado por el proveedor en un mes de prueba.

### Fase 13E — Documento soporte y facturas de proveedores
- Documento soporte desde la recepción de compras a proveedores no obligados.
- Importación de facturas electrónicas de proveedores (XML/ZIP) a compras, con su relación al producto
  (`SupplierProduct` ya existe).

### Fase 14 — Contabilidad (ya diseñada)
Sin cambios de alcance. Se agrega:
- Los **documentos electrónicos aceptados** son la fuente de los asientos de venta, con la referencia de la venta.
- Las **notas crédito** generan sus reversiones.
- La **conciliación bancaria** sube desde Fase 15 si el contador la pide.

Sigue sin venderse hasta que la revise un contador.

### Fase 15 — Finanzas que faltan
- **Bancos y billeteras** como cuentas con saldo, a partir de los métodos de pago y las conciliaciones que ya existen.
- **Flujo de caja proyectado:** cuentas por cobrar y por pagar por vencer, gastos fijos y promedio de ventas.
- **Calendario tributario como recordatorio** según la configuración del comercio. No liquida impuestos.
- **Tablero financiero único:**
  - Ventas.
  - Documentos por estado.
  - Saldo de documentos.
  - Cuentas por cobrar y por pagar.
  - Margen y utilidad estimada.
  - Conciliación.
  - Obligaciones próximas.
  - Alertas.

## 4. Orden y tamaño

| Orden | Fase | Depende de | Tamaño |
|---|---|---|---|
| 1 | 13.0 Decisiones | — | Sin código (2–4 semanas de gestiones) |
| 1 | 13A Datos fiscales y habilitación | — (en paralelo con 13.0) | Mediano |
| 2 | 13B Motor de emisión | 13A, ambiente de pruebas del proveedor (antes, el simulador) | Grande |
| 3 | 13C Centro de errores | 13B | Mediano |
| 4 | 13D Cupo y paquetes | 13B y el cobro de suscripción del POS | Mediano–grande |
| 5 | 13E Documento soporte y facturas de proveedores | 13B | Mediano |
| 6 | 14 Contabilidad | 13B; revisión del contador | Grande |
| 7 | 15 Finanzas que faltan | 14 opcional | Mediano |

**Piloto:** después de 13C, con 2 o 3 comercios reales en el ambiente de pruebas y luego en producción, antes de
vender paquetes de forma masiva.

## 5. Riesgos y cómo se cubren
- **Errores de habilitación y de datos fiscales**, que son la mayor fuente de soporte: asistente, validaciones
  antes de emitir y catálogo de errores en español.
- **Duplicados por caídas o doble clic:** idempotencia con la clave de la venta, consulta antes de reintentar y
  reserva de saldo.
- **Dependencia del proveedor:** adaptador detrás de una interfaz y simulador propio, y copias de los XML.
- **Margen de los paquetes:** con el paquete Plus a ~$80 por documento, el margen depende del costo real del
  proveedor y de las comisiones de pago. Se valida con la cotización antes de publicar precios.
- **Cambios normativos:** reglas en configuración, contrato con el proveedor que cubra las actualizaciones, y
  revisión anual con el contador.
- **Ventas sin internet fuera de plazo:** alertas por antigüedad y procedimiento de contingencia confirmado.
