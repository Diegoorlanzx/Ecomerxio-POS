# Ecomerxio POS — Paquete de revisión para el contador

**Fecha:** 22 de octubre de 2026 · **Estado:** propuesta. Nada de esto se vende como contabilidad oficial hasta que un
contador público lo revise y firme las respuestas de la sección 6.

Este documento explica qué hace el sistema solo, con qué cuentas y bajo qué supuestos. Cada punto marcado con **❓**
necesita una respuesta del contador.

---

## 1. Cómo funciona
- La contabilidad se activa con una **fecha de inicio** y no contabiliza nada anterior. Los saldos de ese día se cargan
  con una **nota manual de saldos iniciales**.
- Cada operación genera **un comprobante en partida doble** (débitos = créditos) en la misma transacción, enlazado a su
  origen. Una operación no se puede contabilizar dos veces.
- Si a una operación le falta la cuenta en el **mapa contable**, queda en **"Pendientes por contabilizar"** y no se
  inventa ninguna cuenta. Una llave nueva toma la cuenta propuesta (tabla de la sección 2); si alguien la deja sin
  cuenta, queda pendiente.
- **Anulaciones:** se reversa exactamente el comprobante original, con fecha de hoy. Nunca se borra.
- **Ventas hechas sin internet:** se contabilizan con su fecha real.
- **Mes cerrado:** lo que llegue después con fecha de ese mes se registra el primer día del siguiente mes abierto y
  guarda la fecha original. Solo el dueño reabre un mes, con motivo, y queda en la auditoría.
- **Cierre de año:** con fecha 31 de diciembre, salda las clases 4 a 7 contra 3605 (utilidad) o 3610 (pérdida).
  Exige los 12 meses cerrados.

## 2. Plan de cuentas propuesto (PUC Decreto 2650, 4 dígitos)
| Código | Cuenta | Uso en el sistema |
|---|---|---|
| 1105 | Caja | Efectivo (ventas, abonos, gastos de caja, diferencias de caja) |
| 1110 | Bancos | Transferencia, Nequi, Daviplata, Bre-B, datáfono y otros medios |
| 1305 | Clientes | Ventas a fiado y sus abonos |
| 1435 | Mercancías no fabricadas | Inventario: compras, costo de ventas, ajustes |
| 2205 | Proveedores nacionales | Compras a crédito y pagos |
| 2365 | Retención en la fuente | Retenciones practicadas en compras |
| 2367 | Impuesto a las ventas retenido | ReteIVA |
| 2368 | ICA retenido | ReteICA |
| 2408 | IVA por pagar | IVA generado en ventas **y** IVA descontable en compras |
| 2805 | Anticipos y avances recibidos | Anticipos de órdenes de servicio aplicados al entregar |
| 3115 | Aportes sociales | Sugerida para saldos iniciales y aportes |
| 3605 / 3610 | Utilidad / Pérdida del ejercicio | Cierre de año |
| 4135 | Comercio al por mayor y al por menor | Ingreso por ventas |
| 4175 | Devoluciones en ventas | Devoluciones de clientes |
| 4210 | Ingresos financieros | Categorías de ingreso con "interés" o "financ" |
| 4295 | Ingresos diversos | Otros ingresos, sobrantes de inventario y de caja, ajustes a favor en bancos |
| 5105 | Gastos de personal | Categorías "nómina", "sueldo", "personal" |
| 5120 | Arrendamientos | Categoría "arriendo" |
| 5135 | Servicios | Categorías "servicios", "internet", "luz", "agua" |
| 5195 | Diversos | Otros gastos, faltantes de caja, cargos de facturas de compra |
| 5305 | Gastos financieros | Comisiones y costos bancarios |
| 5310 | Pérdida en venta y retiro de bienes | Faltantes y bajas de inventario |
| 6135 | Costo de ventas | Costo de lo vendido |

❓ **P1.** ¿Se aprueba este plan y el uso de 4 dígitos? ¿Hace falta usar auxiliares de 6 u 8 dígitos (Siigo los suele exigir)?

## 3. Comprobantes automáticos
| Operación | Débito | Crédito |
|---|---|---|
| Venta | Caja/Bancos/Clientes por cada medio de pago | 4135 (sin impuestos) · 2408 IVA |
| Costo de la venta | 6135 | 1435 |
| Devolución de venta | 4175 · 2408 | Medio de reembolso |
| Reingreso al inventario por devolución | 1435 | 6135 |
| Compra recibida | 1435 (valor recibido) · 2408 IVA descontable* · 5195 cargos | 2205 (total − retenciones) · 2365/2367/2368 |
| Pago a proveedor | 2205 | Caja o Bancos |
| Gasto | Cuenta de la categoría | Caja o Bancos |
| Otro ingreso | Caja o Bancos | Cuenta de la categoría |
| Abono de fiado | Caja o Bancos | 1305 |
| Abono rechazado (pago digital que no llegó) | Reversa del abono | |
| Faltante o baja de inventario | 5310 | 1435 |
| Sobrante de inventario | 1435 | 4295 |
| Faltante de caja al cerrar turno | 5195 | 1105 |
| Sobrante de caja | 1105 | 4295 |
| Consignación | Bancos | Caja |
| Retiro de banco | Caja | Bancos |
| Comisión bancaria | 5305 | Bancos |
| Traslado entre cuentas | Cuenta destino | Cuenta origen |
| Ajuste al extracto | Bancos / 5195 | 4295 / Bancos |

\* Solo si el negocio es responsable de IVA y la compra viene de una **factura electrónica importada** con IVA. En las
compras digitadas a mano el costo se toma tal cual (no se separa IVA).

Terceros: el cliente identificado o "Consumidor final" (222222222222); el proveedor por su NIT sin DV.

❓ **P2.** ¿IVA generado y descontable en la misma 2408, o separar en subcuentas (240801 / 240810)?
❓ **P3.** Si el negocio **no** es responsable de IVA, el IVA de la compra se suma al costo del inventario. ¿Correcto?
❓ **P4.** Retiro de banco: hoy se asume que el dinero va a la caja. ¿Debe permitirse elegir "retiro del dueño" (3xxx)?
❓ **P5.** Cargos o descuentos de la factura de compra que no son productos (fletes, descuentos financieros): hoy van a 5195. ¿Otra cuenta?
❓ **P6.** Faltantes de inventario a 5310 y sobrantes a 4295. ¿Se aprueban?
❓ **P7.** Anticipos de órdenes de servicio: hoy no generan comprobante al recibirse; se aplican contra 2805 al entregar
la orden. ¿Debe contabilizarse el anticipo el día que entra (Caja/Bancos contra 2805)?
❓ **P8.** Recaudos de ruta (distribución) y su cierre: no tienen comprobante propio. ¿Van contra 1305 Clientes?
❓ **P9.** Inventario inicial de productos creados después de activar la contabilidad: no genera comprobante (se espera la
nota de saldos iniciales). ¿Debe generar 1435 contra 3115 u otra cuenta?
❓ **P10.** Devoluciones a proveedores: hoy solo bajan el inventario sin comprobante. ¿1435 contra 2205?

## 4. Retenciones en compras
- El sistema **propone** la retención al recibir la mercancía, solo si la tienda configuró las reglas
  (Contabilidad → Retenciones). Lo retenido baja lo que se le debe al proveedor y queda en 2365/2367/2368. Se puede
  cambiar compra por compra (Finanzas → Por pagar → Retenciones); el cambio genera un comprobante por la diferencia.
- **Retención en la fuente:** si el negocio es agente, el proveedor no es autorretenedor ni del régimen simple, y la
  base (valor antes de IVA) es mayor o igual a *base mínima × UVT*. Tarifa declarante / no declarante según el proveedor.
- **ReteIVA:** si el negocio es agente, el proveedor es responsable de IVA y no es gran contribuyente: % del IVA de la factura.
- **ReteICA:** tarifa por mil sobre la base, si la tienda la configura.
- Valores por defecto: **UVT 52.374, base 27 UVT, 2,5 % declarantes, 3,5 % no declarantes, ReteIVA 15 %.**
- Informe del período por tipo y por proveedor, para la declaración y los certificados.

❓ **P11.** Confirmar la UVT del año y las bases y tarifas vigentes para compras. Cambian con cada decreto.
❓ **P12.** ¿Hace falta manejar otros conceptos (servicios, arriendos, honorarios) en los **gastos**? Hoy los gastos no proponen retención.
❓ **P13.** ReteICA: ¿se aplica a todos los proveedores o solo a los del mismo municipio?

## 5. Documento soporte y facturación electrónica
- **Documento soporte:** sale solo al recibir mercancía de un proveedor marcado como "no obligado a facturar", sin IVA
  y con la responsabilidad R-99-PN del vendedor. ❓ **P14.** ¿Correcto?
- Proveedor tecnológico: hoy **simulado**. No hay validez ante la DIAN hasta conectar uno real (ver sección 7).

## 6. Calendario tributario (solo recordatorio)
- Sugiere obligaciones con los datos fiscales: IVA bimestral o cuatrimestral, retención mensual, régimen simple, ICA,
  renta y renovación de la matrícula mercantil (31 de marzo, la única fecha fija).
- Las demás fechas son **estimadas**: el día 10 del mes en que suelen vencer (la más temprana probable). El usuario pone
  la fecha exacta según el último dígito del NIT.

❓ **P15.** ¿Se aprueban las reglas de estimación (IVA bimestral: día 10 del mes siguiente al bimestre; cuatrimestral:
mayo, septiembre y enero; retención: día 10 del mes siguiente; renta PJ: mayo; renta PN: agosto; ICA anual: febrero)?

## 7. Exportaciones
- **Siigo Nube:** comprobantes del período en CSV (tipo de comprobante, consecutivo, fecha dd/mm/aaaa, cuenta, tercero,
  sucursal 0, descripción, débito, crédito). ❓ **P16.** Confirmar tipo de comprobante, sucursal y versión de plantilla.
- **Terceros en CSV** (identificación, nombre, tipo) para crearlos antes de importar.
- Pendiente: formatos de World Office y Alegra, y el paquete ZIP del mes.

## 8. Lo que falta del lado del negocio (no es del contador)
- **Proveedor tecnológico real de facturación electrónica:** hay que elegirlo (Factus, Dataico, Alegra, HKA…), firmar
  el contrato y obtener las credenciales del ambiente de pruebas. Con eso se programa el adaptador (la interfaz ya existe
  y el simulador sirve de guía) y se prueba la habilitación ante la DIAN.

---

**Respuestas del contador**

| Pregunta | Respuesta | Firma / fecha |
|---|---|---|
| P1 – P16 | | |
