# TiendaPOSS — Checklist completo (15 módulos)

Cada ítem de la lista original, sin omitir ninguno. Estado: ⬜ pendiente · 🟡 en construcción · ✅ hecho y probado · 🔌 hecho, su funcionamiento final depende de un servicio externo.
`[Fn]` = fase en la que se construye.

## 1. Dashboard principal
- ⬜ [F4] Resumen de ventas del día
- ⬜ [F4] Ventas semanales, quincenales y mensuales
- ⬜ [F4] Utilidad bruta estimada
- ⬜ [F4] Gastos y utilidad neta estimada
- ⬜ [F4] Estado de caja y dinero esperado
- ⬜ [F4] Ventas por método de pago
- ⬜ [F4] Productos más vendidos
- ⬜ [F4] Productos con baja rotación
- ⬜ [F4] Productos agotados o próximos a agotarse
- ⬜ [F4] Pedidos pendientes de realizar
- ⬜ [F4] Mercancía pendiente de recibir
- ⬜ [F4] Pagos pendientes de verificar
- ⬜ [F4] Alertas importantes del negocio
- ⬜ [F4] Comparación con períodos anteriores
- ⬜ [F4] Accesos rápidos a las funciones principales

## 2. Punto de venta (POS)
- ✅ [F1] Búsqueda de productos por nombre
- ✅ [F1] Búsqueda por código de barras
- ✅ [F1] Búsqueda por categoría
- ✅ [F1] Escáner de código de barras — lector USB/Bluetooth y cámara del celular
- ✅ [F1] Venta rápida desde teclado
- ✅ [F1] Carrito de compras
- ✅ [F1] Modificación de cantidades
- ✅ [F1] Eliminación de productos del carrito
- ✅ [F1] Aplicación de descuentos autorizados
- ✅ [F1] Manejo de promociones
- ✅ [F1] Venta por unidad, paquete, caja o presentación
- ✅ [F1] Precios especiales autorizados
- ✅ [F1] Cálculo automático de subtotales y totales
- ✅ [F1] Cálculo del cambio para pagos en efectivo
- ✅ [F1] Pagos mixtos, por ejemplo, efectivo más transferencia
- ✅ [F1] Guardar ventas pendientes cuando sea necesario
- ✅ [F1] Recuperar una venta pendiente
- ✅ [F1] Registrar datos opcionales del cliente
- ✅ [F1] Generar comprobantes de venta
- ✅ [F1] Imprimir comprobantes, si hay impresora compatible — tiquetera 58/80 mm desde el navegador (probar con tu impresora real)
- ✅ [F1] Consultar historial de ventas
- ✅ [F1] Anular ventas con autorización
- ✅ [F1] Registrar devoluciones y reembolsos
- ✅ [F1] Revertir correctamente el inventario cuando corresponda
- ✅ [F1] Registrar el usuario que realizó cada operación
- ✅ [F1] **(Añadido)** Cuentas abiertas simultáneas, hasta 20 (el stock se descuenta al cobrar)
- ✅ [F1] **(Añadido)** Venta sin internet con sincronización posterior

## 3. Caja y turnos
- ✅ [F1] Apertura de caja
- ✅ [F1] Registro del efectivo inicial
- ✅ [F1] Asignación de caja por empleado
- ✅ [F1] Registro de ingresos adicionales
- ✅ [F1] Registro de retiros de efectivo
- ✅ [F1] Registro de gastos pagados desde caja
- ✅ [F1] Cierre de caja por turno
- ✅ [F1] Arqueo de efectivo
- ✅ [F1] Comparación entre dinero esperado y dinero contado
- ✅ [F1] Registro de sobrantes y faltantes
- ✅ [F1] Desglose de ventas por medio de pago
- ✅ [F1] Historial de cierres
- ✅ [F1] Reportes por cajero y turno
- ✅ [F1] Autorización de ajustes de caja
- ✅ [F1] Auditoría de movimientos

## 4. Productos y catálogo
- ✅ [F1] Crear, editar y desactivar productos
- ✅ [F1] Nombre, descripción y fotografía
- ✅ [F1] Código de barras y SKU
- ✅ [F1] Categorías y subcategorías
- ✅ [F1] Marca y presentación
- ✅ [F1] Unidad de medida
- ✅ [F1] Productos vendidos por unidad o caja
- ✅ [F1] Conversión entre unidades y presentaciones
- ✅ [F1] Precio de compra
- ✅ [F1] Costo promedio o costo calculado según el método seleccionado
- ✅ [F1] Precio de venta
- ✅ [F1] Margen de ganancia
- ✅ [F1] Precio mínimo autorizado
- ✅ [F1] Impuestos aplicables
- ✅ [F2] Proveedor principal
- ✅ [F2] Proveedores alternativos
- ✅ [F1] Stock mínimo y máximo
- ✅ [F1] Stock de seguridad
- ✅ [F1] Estado del producto: activo, agotado o descontinuado
- ✅ [F1] Historial de cambios de precios
- ✅ [F1] Importación y exportación de productos mediante Excel o CSV
- ✅ [F1] Actualización masiva de precios y existencias — precios en Inventario › selección; existencias por importación CSV y /inventory/bulk-count

## 5. Inventario inteligente
- ✅ [F1] Consulta de existencias en tiempo real
- ✅ [F1] Registro de entradas y salidas
- ✅ [F1] Descuento automático de stock por ventas confirmadas
- ✅ [F1] Reposición automática de existencias al revertir operaciones válidas
- ✅ [F2] Registro de compras recibidas
- ✅ [F1] Ajustes manuales con justificación
- ✅ [F2] Conteos físicos
- ✅ [F2] Comparación entre stock real y teórico
- ✅ [F1] Historial de movimientos por producto
- ✅ [F2] Alertas de stock mínimo
- ✅ [F2] Alertas de agotamiento inminente
- ✅ [F2] Cálculo de días de cobertura
- ✅ [F2] Velocidad de venta por producto
- ✅ [F2] Detección de productos sin rotación
- ✅ [F2] Detección de inventario excesivo
- ✅ [F2] Identificación de mercancía dañada o vencida, cuando aplique
- ✅ [F2] Gestión de lotes y fechas de vencimiento, si el negocio lo necesita — se vende primero lo que vence antes (FEFO)
- ✅ [F2] Valor total del inventario a costo
- ✅ [F2] Capital inmovilizado en productos
- ✅ [F2] Clasificación ABC por importancia comercial
- ✅ [F2] Alertas por diferencias inusuales de inventario
- ✅ [F2] Historial de ajustes por empleado

## 6. Analítica de ventas
- ⬜ [F4] Informe diario
- ⬜ [F4] Informe semanal
- ⬜ [F4] Informe quincenal
- ⬜ [F4] Informe mensual
- ⬜ [F4] Informes por períodos personalizados
- ⬜ [F4] Comparación entre períodos equivalentes
- ⬜ [F4] Ventas por producto
- ⬜ [F4] Ventas por categoría
- ⬜ [F4] Ventas por marca
- ⬜ [F4] Ventas por día y hora
- ⬜ [F4] Ventas por empleado
- ⬜ [F4] Ventas por turno
- ⬜ [F4] Ventas por método de pago
- ⬜ [F4] Unidades vendidas
- ⬜ [F4] Ingresos brutos y netos
- ⬜ [F4] Cantidad de transacciones
- ⬜ [F4] Valor promedio por compra
- ⬜ [F4] Productos más vendidos por unidades
- ⬜ [F4] Productos con mayores ingresos
- ⬜ [F4] Productos con mayor utilidad bruta
- ⬜ [F4] Productos con menor utilidad
- ⬜ [F4] Productos cuyas ventas están disminuyendo
- ⬜ [F4] Tendencias por temporada y día de la semana
- ⬜ [F4] Exportación de informes a Excel, CSV o PDF

## 7. Rentabilidad por producto
- ⬜ [F4] Ingresos netos por producto
- ⬜ [F4] Costo de mercancía vendida
- ⬜ [F4] Utilidad bruta por unidad
- ⬜ [F4] Utilidad bruta total
- ⬜ [F4] Margen porcentual
- ⬜ [F4] Comparación de margen entre productos
- ⬜ [F4] Comparación de rentabilidad por categoría
- ⬜ [F4] Evolución del costo de compra
- ⬜ [F4] Evolución de precios de venta
- ⬜ [F4] Impacto de descuentos en la utilidad
- ⬜ [F4] Rentabilidad después de costos adicionales asignados, si se registran
- ⬜ [F4] Identificación de productos que venden mucho pero dejan poco margen
- ⬜ [F4] Identificación de productos que venden poco pero son rentables
- ⬜ [F4] Estimación de la utilidad neta del negocio después de gastos operativos

## 8. Proveedores
- ✅ [F2] Crear y editar proveedores
- ✅ [F2] Nombre comercial
- ✅ [F2] NIT y datos de identificación, cuando corresponda
- ✅ [F2] Nombre del contacto
- ✅ [F2] Teléfono y WhatsApp
- ✅ [F2] Correo electrónico
- ✅ [F2] Dirección
- ✅ [F2] Catálogo de productos suministrados
- ✅ [F2] Precio de cada producto por proveedor
- ✅ [F2] Presentaciones y cantidades mínimas de compra
- ✅ [F2] Múltiplos de empaque
- ✅ [F2] Días habituales de pedido y entrega
- ✅ [F2] Tiempo promedio de entrega
- ✅ [F2] Condiciones de pago
- ✅ [F2] Saldos pendientes
- ✅ [F2] Historial de compras
- ✅ [F2] Historial de precios
- ✅ [F2] Cumplimiento de entregas
- ✅ [F2] Registro de faltantes y devoluciones — faltantes/incorrectos/dañados al recibir y devoluciones al proveedor
- ✅ [F2] Comparación entre proveedores
- ✅ [F2] Proveedor principal y alternativos por producto

## 9. Compras y reposición automática
- ✅ [F2] Crear órdenes de compra manualmente
- ✅ [F2] Generar sugerencias de compra según el stock
- ✅ [F2] Calcular cantidades recomendadas
- ✅ [F2] Considerar ventas promedio y tendencias
- ✅ [F2] Considerar tiempo de entrega
- ✅ [F2] Considerar stock de seguridad
- ✅ [F2] Descontar pedidos que ya están en camino
- ✅ [F2] Respetar cantidades mínimas y presentaciones del proveedor
- ✅ [F2] Agrupar productos por proveedor
- ✅ [F2] Mostrar costo estimado por pedido
- ✅ [F2] Comparar alternativas de proveedores
- ✅ [F2] Definir un presupuesto de compras
- ✅ [F2] Priorizar productos críticos
- ✅ [F2] Crear pedidos en borrador
- ✅ [F2] Aprobación o modificación por el dueño
- ✅ [F2] Envío del pedido por correo o WhatsApp, según la integración — WhatsApp abre el chat con el pedido escrito; el correo se envía si hay servidor de correo configurado
- ✅ [F2] Registrar fecha prevista de entrega
- ✅ [F2] Registrar recepción parcial o total
- ✅ [F2] Registrar productos faltantes o incorrectos
- ✅ [F2] Actualizar existencias al recibir mercancía
- ✅ [F2] Actualizar costos de inventario
- ✅ [F2] Comparar cantidades pedidas y recibidas
- ✅ [F2] Historial de órdenes de compra
- ✅ [F2] Alertas de pedidos retrasados
- ✅ [F2] Propuesta de próxima fecha de pedido

## 10. Pagos digitales
- ✅ [F1] Registro de pagos en efectivo
- ✅ [F1] Registro de transferencias bancarias
- ✅ [F1] Registro de Nequi
- ✅ [F1] Registro de Daviplata
- ✅ [F3] Registro de pagos mediante Bre-B y llaves, según la integración disponible — con referencia y verificación; confirmación automática vía webhook 🔌
- ✅ [F3] Generación o presentación de QR cuando el proveedor lo permita — se muestra el QR de la cuenta (Nequi/Daviplata/Bre-B) al cobrar, también sin internet; QR dinámico por venta depende del proveedor 🔌
- ✅ [F1] Registro de referencias de pago
- ✅ [F1] Asociación de pagos con ventas
- ✅ [F1/F3] Pagos parciales y mixtos — mixtos (efectivo + digital + fiado) y abonos parciales al fiado
- ✅ [F3] Estados: pendiente, confirmado, rechazado o reembolsado (y fiado como crédito)
- 🔌 [F3] Confirmación automática mediante integraciones compatibles — webhook firmado HMAC-SHA256 listo y probado; falta conectar el proveedor real
- ✅ [F3] Verificación manual como alternativa
- ✅ [F3] Conciliación entre ventas y movimientos recibidos
- ✅ [F3] Detección de referencias duplicadas
- ✅ [F3] Alertas de importes inconsistentes
- ✅ [F3] Historial de eventos de pago
- ✅ [F3] Notificaciones de pago — en la campana (por verificar, referencia repetida); canales externos en la Fase 5
- ✅ [F3] Auditoría de quién confirmó manualmente una transacción

## 11. Gastos y finanzas
- ✅ [F3] Registro de gastos operativos
- ✅ [F3] Categorías de gastos
- ✅ [F3] Arriendo, servicios, transporte y otros conceptos
- ✅ [F3] Registro de ingresos no asociados a ventas
- ✅ [F3] Cuentas por pagar a proveedores
- ✅ [F3] Fechas de vencimiento
- ✅ [F3] Historial de pagos a proveedores
- ✅ [F3] Flujo de caja
- ✅ [F3] Resumen de ingresos y egresos
- ✅ [F3] Utilidad bruta
- ✅ [F3] Utilidad neta estimada
- ✅ [F3] Presupuesto para compras
- ✅ [F3] Reporte financiero mensual
- ✅ [F3] Exportación de informes
- ✅ [F3] **(Añadido)** Fiado / cuentas por cobrar a clientes

## 12. Usuarios, roles y permisos
- ✅ [F0] Crear, editar y desactivar usuarios
- ✅ [F0] Inicio y cierre de sesión
- ✅ [F0] Recuperación de contraseña
- ✅ [F0] Roles predefinidos
- ✅ [F0] Crear roles personalizados
- ✅ [F0] Asignar múltiples permisos por rol
- ✅ [F0] Asignar roles a cada empleado
- ✅ [F0] Restringir acceso a costos y ganancias
- ✅ [F0] Restringir descuentos y anulaciones
- ✅ [F0] Autorizar devoluciones y ajustes
- ✅ [F0] Controlar acceso a caja y pagos
- ✅ [F0] Restringir gestión de usuarios
- ✅ [F0] Registro de actividad por empleado
- ✅ [F0] Historial de accesos y operaciones sensibles

Roles iniciales sugeridos
- ✅ [F0] Dueño
- ✅ [F0] Administrador
- ✅ [F0] Supervisor
- ✅ [F0] Cajero
- ✅ [F0] Encargado de inventario
- ✅ [F0] Compras
- ✅ [F0] Contador o analista
- ✅ [F0] Auditor de solo lectura

## 13. Notificaciones y alertas
- ⬜ [F5] Stock crítico
- ⬜ [F5] Próximo agotamiento
- ⬜ [F5] Productos sin rotación
- ⬜ [F5] Exceso de inventario
- ⬜ [F5] Pedido recomendado
- ⬜ [F5] Pedido pendiente de aprobación
- ⬜ [F5] Pedido retrasado
- ⬜ [F5] Mercancía recibida
- ✅ [F3] Pago pendiente de verificación (campana; canales externos en F5)
- ✅ [F3] Pago duplicado o inconsistente (campana; canales externos en F5)
- ⬜ [F5] Diferencias de caja
- ⬜ [F5] Devoluciones y anulaciones inusuales
- ⬜ [F5] Reporte diario de ventas
- ⬜ [F5] Resumen semanal y mensual
- ✅ [F3] Recordatorios de pagos a proveedores (campana: facturas por vencer y vencidas; canales externos en F5)
- ⬜ [F5] Notificaciones dentro del sistema y por canales externos compatibles

## 14. Configuración y administración
- ✅ [F0] Datos generales de la tienda
- ✅ [F0] Logo y nombre comercial
- ✅ [F0] Dirección y datos de contacto
- ✅ [F0] Moneda y zona horaria
- ✅ [F1] Impuestos aplicables
- ✅ [F1] Configuración de comprobantes
- ✅ [F1] Métodos de pago habilitados
- ✅ [F2] Parámetros de inventario
- ⬜ [F4] Parámetros de analítica
- ⬜ [F5] Umbrales de alertas
- ⬜ [F5] Configuración de integraciones
- ✅ [F0] Registro de auditoría
- ⬜ [F5] Copias de seguridad y recuperación
- ⬜ [F5] Exportación de datos
- ⬜ [F5] Gestión de sucursales futuras (estructura preparada; multisucursal fuera de alcance por ahora)
- ✅ [F0] Configuración de permisos por negocio

## 15. Asistente de inteligencia artificial
- ⬜ [F6] Preguntar cuánto se vendió en un período
- ⬜ [F6] Consultar qué productos generan mayor utilidad
- ⬜ [F6] Preguntar qué mercancía comprar esta semana
- ⬜ [F6] Consultar cuánto dinero se necesita para reponer inventario
- ⬜ [F6] Identificar productos con ventas decrecientes
- ⬜ [F6] Explicar las razones de una recomendación de compra
- ⬜ [F6] Comparar proveedores
- ⬜ [F6] Resumir informes semanales y mensuales
- ⬜ [F6] Detectar anomalías para revisión
- ⬜ [F6] Generar recomendaciones de precios y promociones para revisión humana
- ⬜ [F6] Responder preguntas usando los datos autorizados del usuario

## Fase 7 — Salir al mundo
- ⬜ [F7] App instalable (PWA) en Android, iPhone y PC
- ⬜ [F7] Empaquetado para Play Store (TWA)
- ⬜ [F7] Despliegue en servidor
- ⬜ [F7] **Tienda de ejemplo para pruebas** (inventario, roles, usuarios, ventas, proveedores, compras)
