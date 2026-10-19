# TiendaPOSS — Fase 14: contabilidad básica automática

Diseño, todavía sin código. Va **después de la Fase 13 (factura electrónica DIAN)**, porque la contabilidad se apoya en
documentos electrónicos válidos.

Como las fases anteriores, se construye encima de lo que ya existe y termina con:
- pruebas;
- verificación en el navegador;
- datos en la tienda de ejemplo;
- commit.

## Objetivo
Que el dueño vea su **estado de resultados y su balance general sin digitar nada**, y que su contador reciba la
información **lista para su propio software**.

Cada venta, compra, gasto, pago o ajuste genera solo su asiento contable de partida doble.

## Qué entra y qué no

| Entra en esta fase | Queda por fuera (por ahora) |
|---|---|
| Plan de cuentas PUC precargado y editable | Declaraciones de IVA, retención y renta |
| Asientos automáticos desde cada operación | Información exógena (medios magnéticos) |
| Terceros con cédula o NIT y dígito de verificación | Conciliación bancaria con extractos |
| IVA, impuesto al consumo y retenciones básicas en compras | Activos fijos y depreciación automática |
| Notas contables manuales | Nómina y nómina electrónica |
| Cierre de mes y de año | Contabilidad multimoneda |
| Libro diario, mayor, balance de prueba y auxiliares | Centros de costo |
| Estado de resultados y balance general | |
| Exportación para Siigo, World Office y Alegra | |

Lo que queda por fuera es lo de mayor riesgo legal y mayor mantenimiento. El contador del cliente lo sigue haciendo
en su software con la información que exportamos.

## Lo que ya existe y se reutiliza
- **Ventas:** devoluciones y anulaciones, con el impuesto por línea (`SaleLine.taxRate`, `tax`) y el costo de lo
  vendido.
- **Pagos:** por método (efectivo, Nequi, tarjeta, Bre-B, fiado).
- **Compras:** recibidas con número de factura del proveedor, cuentas por pagar y abonos (`finance/SupplierPayment`).
- **Gastos e ingresos:** por categoría (`finance/FinanceEntry`), incluidos los gastos pagados desde la caja.
- **Movimientos de caja:** gasto, retiro, ingreso, ajustes y abono de fiado.
- **Inventario:** ajustes, conteos con faltantes o sobrantes y bajas de seriales.
- **Terceros:**
  - Proveedores con `taxId`.
  - Clientes con `docNumber`.
  - Falta el tipo de documento y el dígito de verificación.

---

### 14.1 Plan de cuentas y configuración
- Al activar el módulo se precarga un **PUC para comerciantes** (clases 1 a 6) con las cuentas más usadas en una
  tienda. El contador puede crear, renombrar o desactivar cuentas, pero no borrar las que ya tienen movimientos.
- **Mapa contable**, una pantalla donde se elige la cuenta de cada cosa:
  - Cada método de pago: Efectivo → 1105 Caja; Nequi → la cuenta de banco o billetera; tarjeta → 1110 Bancos.
  - Cada categoría de gasto: arriendo, servicios, sueldos, transporte…
  - Ventas, devoluciones, costo de ventas, inventario.
  - Impuestos: IVA generado, IVA descontable, impuesto al consumo, retenciones.
  - Clientes (fiado), proveedores, y faltantes y sobrantes de inventario y de caja.
- **Fecha de inicio y saldos iniciales:** caja, bancos, inventario valorizado (se toma del inventario actual),
  cuentas por cobrar (fiados vigentes), cuentas por pagar (facturas de proveedor pendientes) y capital.
  Así el balance cuadra desde el primer día.

### 14.2 Motor de asientos automáticos
Cada operación terminada genera **un comprobante con sus líneas débito y crédito**, enlazado al documento de origen.
El motor:
- **valida que débitos = créditos** antes de guardar;
- si falta una cuenta en el mapa, deja la operación en una bandeja de **"Pendientes por contabilizar"** en vez de
  inventar una cuenta.

Asientos propuestos. **Las cuentas son una propuesta y las debe validar un contador.**

| Operación | Débito | Crédito |
|---|---|---|
| Venta de contado | Caja o banco según método de pago | Ingresos por ventas (4135) · IVA generado (2408) · Impuesto al consumo |
| Costo de esa venta | Costo de ventas (6135) | Inventario de mercancías (1435) |
| Venta a fiado | Clientes (1305) | Ingresos · IVA generado |
| Abono de fiado | Caja o banco | Clientes (1305) |
| Devolución de venta | Devoluciones en ventas (4175) · IVA generado | Caja, banco o clientes |
| Reintegro de inventario por devolución | Inventario (1435) | Costo de ventas (6135) |
| Anulación de venta | Reversa exacta del asiento original | |
| Compra recibida | Inventario (1435) · IVA descontable (2408) | Proveedores (2205) · Retenciones por pagar (2365/2367/2368) |
| Pago a proveedor | Proveedores (2205) | Caja o banco |
| Gasto | Cuenta de gasto según categoría (51xx / 52xx) | Caja o banco · retención si aplica |
| Faltante de inventario o baja de serial | Pérdida de inventario (configurable) | Inventario (1435) |
| Sobrante de inventario | Inventario (1435) | Ingreso por sobrantes (configurable) |
| Diferencia de caja al cerrar turno | Faltante o sobrante de caja (configurable) | Caja (o al revés) |
| Retiro o consignación de caja | Banco o socio | Caja |

**Combos y variantes:** el costo sale de los componentes descontados (ya se registra en `sale_line_components`).

**Ventas sin internet:** se contabilizan con su fecha real de venta, no con la de sincronización. Es la misma lección
que ya aprendimos con las garantías.

### 14.3 Terceros e impuestos
- **Terceros:**
  - Tipo de documento (CC, NIT, CE, pasaporte).
  - Número y dígito de verificación calculado.
  - Persona natural o jurídica.
  - Régimen: responsable de IVA, no responsable, régimen simple.
  - Si es gran contribuyente o autorretenedor.
- **Retenciones en compras y gastos:** retención en la fuente por concepto (compras, servicios, arriendos), ReteIVA
  y ReteICA.
  - Se calculan con **tarifa y base mínima en UVT** según el concepto y el tercero.
  - La UVT del año se configura en un solo lugar.
  - El sistema propone la retención y el usuario la confirma.
- **Ventas a consumidor final:** se contabilizan al tercero genérico "Consumidor final" (222222222222). Las ventas
  con datos del cliente van a su tercero.

### 14.4 Notas contables, periodos y cierres
- **Notas contables manuales:** para depreciaciones, provisiones, préstamos, aportes de socios y correcciones.
  - Obligan a cuadrar.
  - Llevan soporte (foto o PDF) y quedan en la auditoría.
- **Cierre de mes:** bloquea crear, editar o anular operaciones con fecha de ese mes.
  - Para corregir, se reabre con PIN del dueño y queda registrado quién y por qué.
  - Las anulaciones de un mes cerrado se registran en el mes abierto.
- **Cierre de año:** traslada ingresos, costos y gastos a la utilidad del ejercicio.
- **Numeración consecutiva** de comprobantes por tipo: ventas, compras, egresos, notas.

### 14.5 Informes
- **Libro diario:** todos los comprobantes con sus líneas, por rango de fechas.
- **Libro mayor y balance de prueba:** saldo inicial, débitos, créditos y saldo final por cuenta.
- **Auxiliares por cuenta y por tercero:** por ejemplo, todo lo que se le ha comprado y pagado a un proveedor.
- **Estado de resultados del mes y del año:** comparado con el periodo anterior. Su utilidad debe coincidir con la
  utilidad neta que ya muestra Finanzas; si no coincide, hay un error en el motor.
- **Balance general.**
- **Todos los informes:** se exportan a Excel (CSV) y PDF, y desde cada saldo se baja al comprobante y de ahí a la
  venta, compra o gasto original.

### 14.6 Exportación para el contador
- **Exportar comprobantes contables** del periodo en el formato de importación de **Siigo, World Office y Alegra**
  (un formato por software, más un CSV genérico).
- **Paquete del mes en un solo ZIP:** comprobantes, terceros, balance de prueba y auxiliares.
- **Permiso nuevo "Contador":** rol de solo lectura para la contabilidad e informes, sin acceso a vender ni a la caja,
  para darle usuario al contador externo.

---

## Pantallas nuevas (Más → Contabilidad)
1. **Resumen:** estado de resultados y balance del mes en lenguaje sencillo, y operaciones pendientes por
   contabilizar.
2. **Comprobantes:** lista filtrable, detalle de cada asiento y nota contable nueva.
3. **Plan de cuentas y mapa contable.**
4. **Terceros.**
5. **Informes:** diario, mayor, balance de prueba, auxiliares y estados financieros.
6. **Cierres y exportación.**

La guía interactiva suma un recorrido de Contabilidad y ayudas en los campos difíciles: retención, base en UVT,
mapa contable.

## Permisos y plan comercial
- **Permisos nuevos:**
  - `CONTABILIDAD_VER`
  - `CONTABILIDAD_EDITAR` (notas y mapa contable)
  - `CONTABILIDAD_CERRAR` (cierres y reapertura)
  - Rol predefinido **Contador**.
- **Módulo `CONTABILIDAD`:** activable desde Tipo de negocio. Comercialmente va en el **plan Pro**, o como adicional
  del plan Negocio.

## Riesgos y cómo se manejan
- **Un asiento mal planteado se vuelve un error contable del cliente.**
  - Antes de programar, un contador revisa el PUC propuesto, la tabla de asientos y las reglas de retención.
  - Después revisa los informes de la tienda de ejemplo.
  - Sin esa revisión, el módulo no se vende.
- **Normas que cambian cada año** (UVT, tarifas, bases): todo va en configuración, nada fijo en el código, con una
  lista anual de qué revisar.
- **Datos viejos sin contabilizar:** el módulo empieza en la fecha de inicio con saldos iniciales. No se contabiliza
  hacia atrás.
- **Rendimiento:** los asientos se generan al terminar cada operación, en la misma transacción. Los informes leen
  saldos agregados por cuenta y mes, no recorren todas las ventas.

## Orden de construcción
1. **14.1 y 14.2:** PUC, mapa contable y asientos automáticos de ventas, costo, devoluciones y anulaciones.
   Prueba: el balance de prueba cuadra.
   > ✅ **Hecho (18 oct 2026).** V15 (`accounting_settings`, `accounting_accounts`, `accounting_map`,
   > `journal_entries` con índice único por origen, `journal_lines`, `accounting_pending`), paquete `accounting`,
   > permisos `CONTABILIDAD_VER`/`CONTABILIDAD_EDITAR` (Contador ambos, Auditor ver), pantalla Más → Contabilidad
   > (resumen y activación con fecha de inicio, mapa contable, plan de cuentas, libro diario, balance de prueba,
   > pendientes). Si falta una cuenta en el mapa la operación va a pendientes, nunca se inventa. Pruebas: `AccountingIT`
   > (121 en verde). El PUC y el mapa siguen siendo una propuesta para que la revise el contador.
2. Compras, pagos a proveedores, gastos, caja e inventario.
3. **14.3:** terceros y retenciones.
4. **14.4:** notas, cierres y numeración.
5. **14.5:** informes y estados financieros.
6. **14.6:** exportaciones y rol Contador.
7. Tienda de ejemplo con un mes contabilizado y revisión del contador.

**Tamaño:** grande, similar a la Fase 8.

## Terminado cuando
En la tienda de ejemplo, un mes con ventas de contado y a fiado, devoluciones, una anulación, compras con IVA y
retención, pagos a proveedores, gastos y un faltante de inventario:
- genera sus asientos solo;
- el balance de prueba cuadra en cero;
- la utilidad del estado de resultados coincide con la de Finanzas;
- el mes se cierra y ya no se puede alterar;
- el archivo exportado se importa sin errores en al menos uno de los programas contables;
- un contador revisa los informes y los da por buenos.
