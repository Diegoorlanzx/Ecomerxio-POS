# Fase 16 — POS con y sin factura electrónica, recibos configurables y todos los documentos de Factus

Diseño del 22 oct 2026. Solo diseño, sin código. Se apoya en lo ya hecho: 13A (datos fiscales), 13B (emisión con simulador),
13C (centro de errores), 13E (documento soporte), 14 (contabilidad) y 3 (fiado).

---

## 0. Qué hay hoy (revisado en el código)

| Tema | Estado actual | Dónde |
|---|---|---|
| Activar facturación | Interruptor por tienda (`invoicingEnabled`). Si está prendido, **toda** venta genera documento: factura a nombre del cliente si se marcó "factura", si no, documento a consumidor final. | `einvoice/EInvoiceService.onSaleCompleted` |
| Cliente con/sin factura | No existe en la ficha del cliente. El cajero marca "factura electrónica" en cada cobro. | `pos/Sale.invoiceRequested` |
| Documentos | Factura, nota crédito (por devolución o anulación), documento soporte. **Faltan:** nota débito, nota de ajuste al soporte, nómina electrónica. | `EInvoiceService` |
| Factus | No tiene "documento equivalente POS": las ventas sin cliente irían como **factura a consumidor final** (222222222222). Adaptador real aún no hecho. | memoria Factus |
| Recibo | Solo encabezado, pie, ancho y "mostrar impuestos". | `settings/StoreSettings` |
| Fiado e inventario | **El producto sale del inventario al cobrar**, igual que una venta de contado. La deuda queda en cartera (cuenta 1305). | `pos/SaleService.finish` (línea ~539) |
| Fiado en la factura | La factura no indica "forma de pago: crédito" ni fecha de vencimiento. **Hay que corregirlo.** | `EInvoiceService` (payload) |

### Respuesta sobre los fiados
Salen del inventario **en el momento de la venta**, y así debe quedarse:
- La mercancía ya salió físicamente de la tienda; si se esperara al pago, el stock diría que hay productos que no están, y la reposición automática fallaría.
- Contablemente es una venta a crédito: el ingreso existe desde la entrega; lo pendiente es el cobro (cartera), no el inventario.
- Si el fiado se anula, el producto vuelve al inventario (ya funciona así).
- Lo único que falta: cuando la tienda factura, el fiado debe salir en la factura como **crédito con fecha de vencimiento** (el plazo del cupo del cliente), y si se cobran intereses por mora, esos van en una **nota débito** (fase 16E).

---

## 1. ¿Es viable? Resumen

| Pedido | Viable | Comentario |
|---|---|---|
| Clientes con factura y sin factura que no se mezclen | Sí | Depende de si la tienda está **obligada** a facturar. Si está obligada, la ley no permite "ventas sin documento": las de clientes sin factura salen a consumidor final. Si no está obligada, puede facturar solo a quien lo pida. |
| Pasar de POS normal a POS con factura y ocultar ventas anteriores | Sí, como **fecha de corte** | Las ventas anteriores quedan fuera de todo lo fiscal y ocultas por defecto. **No se borran** (respaldo, analítica del dueño, devoluciones). Ocultarlas no cambia lo que el negocio deba declarar por su régimen. |
| Recibo configurable simple | Sí, fácil | Con candado en los campos que la DIAN exige cuando el recibo es una factura. |
| Todos los documentos de Factus | Sí | Nota débito y ajustes: fáciles. **Nómina electrónica: grande** (necesita módulo de empleados y liquidación). |

### Alerta de costos (importante antes de aprobar)
Con la bolsa de Factus cada documento cuesta entre **$21** (1 millón) y **$63** (10.000), más **$130.000 por NIT al año** del certificado.
Una tienda obligada con 100 ventas al día gasta ~3.000 documentos al mes = **36.000 al año**, más que la bolsa de 20.000.
A $45 c/u son ~$135.000 al mes **solo en documentos**, más que el plan Negocio ($69.900).
Por eso la fase 16G (cupos por plan + paquetes extra + certificado cobrado aparte) es requisito antes de vender facturación a tiendas obligadas.
En modo "factura a pedido" el gasto es bajo (solo los clientes que piden).

---

## 2. Fases propuestas

### 16.0 Decisiones (sin código, del usuario y el contador)
- Firmar o no con Factus (o pedir contrapropuesta: nota de bolsa por cliente, SLA).
- Confirmar con el contador: quién está obligado, tope de 5 UVT para consumidor final, uso de factura a consumidor final en vez de POS electrónico, plazos de nómina.
- Precio del certificado y de los paquetes de documentos para el cliente.

### 16A — Modo fiscal de la tienda y cliente con/sin factura
**Modo fiscal** (en Facturación → Configuración, reemplaza el interruptor actual):
1. **Sin facturación** — POS normal. Recibo dice "Comprobante interno — no es factura".
2. **Factura a pedido** (no obligados que facturan por voluntad): solo se factura cuando el cliente lo pide o su ficha dice "requiere factura". El resto sale con comprobante interno.
3. **Obligado** — toda venta genera documento: a nombre del cliente si lo requiere, si no, consumidor final.

**Ficha del cliente:** campo "Factura electrónica": *Siempre* / *Nunca* / *Preguntar*.
- *Siempre*: al elegir el cliente en el POS el interruptor se prende solo y queda bloqueado; exige NIT/DV, correo de factura, municipio y responsabilidades completos antes de cobrar.
- Cobrar a ese cliente **sin** su factura requiere PIN de supervisor y queda en auditoría (en modo Obligado, sale a consumidor final).

**Que no se mezclen:**
- Dos numeraciones separadas y visibles: venta interna `V-1234` y documento fiscal `FE-SETP 567`. El comprobante interno nunca muestra CUFE, QR DIAN ni la palabra "factura".
- Etiqueta grande en el cobro: "Con factura a: Ferretería X — NIT 900.123.456-7" o "Sin factura / Consumidor final".
- Filtros y reportes por "con factura / sin factura / consumidor final".
- **Refacturar**: si un cliente vuelve pidiendo factura de una venta a consumidor final → nota crédito al documento original + factura nueva a su nombre (dentro del mes y con permiso). Una factura a nombre de un cliente nunca se "pasa" a otro: siempre nota crédito + nueva.
- Fiado con factura: forma de pago crédito y vencimiento según el plazo del cliente.

Pruebas: cliente "Siempre" no puede salir como consumidor final sin PIN; modo "a pedido" no gasta documentos en ventas comunes; numeraciones nunca se cruzan.

### 16B — Paso de POS normal a POS con factura (fecha de corte)
- El asistente de activación registra la **fecha de corte** (`fiscal_start_at`) al pasar la primera vez a modo "a pedido" u "Obligado" en producción.
- Ventas antes del corte:
  - Nunca se envían ni se pueden facturar ni refacturar (la DIAN no acepta facturas con fechas viejas).
  - Excluidas de Documentos, informes fiscales, calendario tributario y del informe para el contador ("desde el corte").
  - **Ocultas por defecto** en Ventas, Analítica y Dashboard: un filtro "Incluir ventas antes de facturación" solo para el dueño (permiso nuevo `VENTAS_HISTORIAL_PREVIO`).
  - Siguen existiendo: copias de seguridad, devoluciones de esas ventas (con comprobante interno, sin nota crédito), cartera de fiados viejos.
- Contabilidad: saldos iniciales a la fecha de corte (inventario, cartera, cuentas por pagar) para que los libros empiecen limpios.
- Pantalla de confirmación clara: qué cambia, qué no, cuántos documentos consume al mes según su promedio de ventas (une con 16G).
- Volver atrás (desactivar) solo el dueño con contraseña; las facturas ya emitidas no se tocan.

### 16C — Configurador de recibo
**Plantillas de un clic:** *Mínimo* (solo total), *Simple* (nombre de productos + total), *Detallado* (cantidades, precio unitario, descuentos, impuestos), *Personalizado*.

**Casillas por grupo** con vista previa en vivo al lado:
- Encabezado: logo, nombre, NIT, dirección, teléfono, texto libre.
- Venta: número, fecha y hora, cajero, caja, cliente.
- Productos: nombre, código, cantidad, precio unitario, descuento por línea, subtotal; opción "agrupar todo como 'Varios'".
- Totales: subtotal, descuentos, impuestos desglosados, total, "usted ahorró".
- Pago: medios de pago, cambio, saldo del fiado, cupo disponible.
- Pie: texto libre, política de devoluciones, QR de la tienda en línea, redes.
- Formato: 58/80 mm, tamaño de letra, copias, abrir cajón, imprimir siempre / preguntar / nunca, enviar por WhatsApp o correo.

**Un perfil por tipo de comprobante:** comprobante interno, factura electrónica, precuenta (cuenta abierta), fiado (con línea de firma y saldo), cotización.
En el perfil **factura electrónica** los campos que exige la DIAN aparecen con candado y no se pueden quitar (emisor y NIT, numeración y resolución, cliente, IVA desglosado, CUFE, QR, proveedor tecnológico).

### 16D — Adaptador real de Factus
- `FactusProvider` (implementa `EInvoiceProvider`): OAuth, `/v2/bills/validate`, rangos (21 factura, 22 NC, 23 ND, 24 soporte, 26 nómina…), consulta de estado antes de reenviar.
- PDF y XML del proveedor guardados y descargables; envío por correo al cliente.
- Primero en sandbox con las credenciales del usuario (en `.env.local`, nunca en git), luego habilitación.

**Hecho (22 oct 2026):** `einvoice/FactusProvider` + `ProviderConfig` (EINVOICE_PROVIDER=FACTUS; FACTUS_SANDBOX_* para
el ambiente de pruebas y FACTUS_PROD_* para producción, en `.env.local` o variables del servidor, nunca en git). Probado
contra un servidor falso con las rutas de la documentación; **no probado contra el sandbox real**.

**A confirmar en la primera prueba con el sandbox:**
1. Consumidor final: la DIAN usa 222222222222 (12 dígitos); el ejemplo de Factus muestra 22222222222 (11).
2. ¿`legal_organization_code` es obligatorio para consumidor final? (el ejemplo lo omite; se envía "2").
3. Bolsa multifacturador: ¿cada NIT tiene su propio usuario/credenciales o una sola cuenta emite por varios NIT?
   (hoy el adaptador usa una sola cuenta por ambiente).
4. `numbering_range_id`: hoy no se envía (Factus usa el rango activo); si una tienda tiene varios rangos activos hay que
   guardar el id de Factus en la resolución.
5. Formato exacto del cuerpo de error 422 y búsqueda por referencia para notas y documentos soporte.
6. Descarga de PDF/XML y envío por correo (Factus lo envía con `send_email`; en sandbox está apagado).

### 16E — Nota débito, notas crédito manuales y ajuste al soporte
- **Nota débito** sobre una factura aceptada: intereses de mora de un fiado, flete, error por menor valor. Conectada al fiado: "cobrar intereses" crea el cargo en cartera y su nota débito.
- **Nota crédito manual** (hoy solo nace de devolución o anulación): descuento posterior, ajuste de precio.
- **Nota de ajuste al documento soporte** (corregir/anular un soporte de compra a no obligados).
- Todo con asientos contables automáticos (14) y en el centro de errores (13C).

### 16F — Nómina electrónica (la más grande; se puede partir en 2)
**16F-1 Empleados y liquidación:**
- Empleado ≠ usuario (puede haber empleados sin acceso al POS; se pueden vincular).
- Contrato (tipo, salario, fecha de ingreso, jornada), EPS/AFP/ARL/caja, cuenta bancaria.
- Novedades del periodo: horas extra y recargos, incapacidades, licencias, vacaciones, bonificaciones, comisiones (ya existen en Servicios), préstamos y descuentos.
- Liquidación quincenal o mensual: devengados, deducciones salud/pensión, auxilio de transporte, aportes del empleador, provisiones (prima, cesantías, intereses, vacaciones). Desprendible imprimible / WhatsApp.
- Asientos contables automáticos (5105, 2505, 2370, 2610…).
- **No incluye** planilla PILA (se exporta el resumen para pagarla en el operador).

**16F-2 Transmisión:**
- Nómina individual electrónica mensual por empleado (dentro de los plazos de la DIAN) y nota de ajuste (reemplazar/eliminar).
- Recordatorio en el calendario tributario.

Requiere revisión del contador antes de venderse. Candidato a módulo de plan Pro o complemento pagado.

### 16G — Cupos, paquetes y certificado (es la 13D pendiente)
- Cada plan incluye N documentos al mes; paquetes extra; aviso al 80 % y al 100 %.
- Libro de reserva/consumo/liberación de documentos contra la bolsa anual de Factus repartida por NIT.
- Certificado digital: cobro anual por NIT (costo $130.000).
- Bloqueado hasta tener cobro de suscripción del POS.

---

## 3. Orden sugerido
1. **16A + fiado como crédito en la factura** (sin depender de Factus; funciona con el simulador).
2. **16C** recibo configurable (independiente, alto impacto visible, se puede hacer en paralelo con 16A).
3. **16B** fecha de corte.
4. **16D** Factus real (cuando haya credenciales de sandbox y decisión de firma).
5. **16E** notas.
6. **16G** cupos (cuando exista el cobro de la suscripción).
7. **16F** nómina (al final; la más larga y la que más necesita al contador).
