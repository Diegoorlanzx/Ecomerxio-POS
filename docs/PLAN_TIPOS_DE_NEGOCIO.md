# Plan: nuevos tipos de negocio en Ecomerxio POS (27 oct 2026)

Regla del usuario: **cada negocio es distinto**. Un tipo de negocio (perfil) enciende solo los módulos que ese negocio
usa; nunca todos. Cada perfil nuevo tendrá su propia tienda demo (no se abren módulos ajenos en una demo).

Hoy hay 8 perfiles: tienda de barrio, ropa, tecnología, fruver/carnicería, restaurante, servicios, distribuidora y mixto.

## Fase 1 — Perfiles con lo que ya existe
Se agregan los perfiles al registro y a Más → Tipo de negocio, cada uno con los módulos actuales que le sirven.
Las funciones propias de cada negocio llegan en las fases siguientes.

| Código | Negocio | Módulos que enciende |
|---|---|---|
| SUPERMERCADO | Supermercado o autoservicio | Lotes, báscula, combos, fiado, compras, tienda en línea |
| DROGUERIA | Droguería o farmacia | Lotes, fiado, compras, tienda en línea |
| FERRETERIA | Ferretería o depósito de materiales | Báscula (venta por peso), variantes, fiado, compras |
| PAPELERIA | Papelería o miscelánea | Variantes, combos, fiado, compras |
| LICORERA | Licorera o estanco | Cuentas abiertas, combos, compras, tienda en línea |
| PANADERIA | Panadería o pastelería | Báscula, lotes, combos, fiado, cuentas abiertas |
| CAFETERIA | Cafetería, heladería o comidas rápidas | Combos, cuentas abiertas, lotes, compras |
| LAVANDERIA | Lavandería, modistería o remontadora | Servicios (órdenes con anticipo), fiado |
| TALLER | Taller de motos o carros | Servicios (órdenes con repuestos), compras, fiado |
| VETERINARIA | Veterinaria o tienda de mascotas | Servicios (agenda), lotes, compras, fiado |
| AGRO | Agropecuaria o agroinsumos | Lotes, báscula, fiado, compras |
| OPTICA | Óptica | Servicios (órdenes con anticipo), variantes, compras, fiado |
| GIMNASIO | Gimnasio o centro deportivo | Servicios (agenda de clases), combos, fiado |

"Fruver, carnicería o panadería" pasa a ser "Fruver o carnicería" (la panadería tiene su perfil).

## Fase 2 — Droguería
Registro sanitario INVIMA por producto, lote y vencimiento obligatorios al recibir, venta fraccionada (unidad, blíster,
caja), marca "requiere fórmula" con registro del médico/fórmula en la venta, alertas de vencimiento (90/60/30 días) y
no vender vencidos.

## Fase 3 — Ferretería y materiales
Venta por medida con decimales (metros, kilos, galones) desde la caja, cotizaciones para maestros de obra (ya existen)
y precios por cliente.

## Fase 4 — Mostrador y órdenes
- Cafetería: venta en mostrador con número de turno y pantalla de pedidos listos.
- Lavandería/modistería: ticket con estado de la prenda y aviso por WhatsApp cuando esté lista.
- Taller: historial por placa del vehículo.

## Fase 5 — Panadería, veterinaria, agro, óptica, papelería y licorera
- Panadería: producción con recetas y pedidos por encargo con anticipo y fecha de entrega.
- Veterinaria: ficha de cada mascota con vacunas y recordatorios.
- Agro: registro ICA y fiado a plazo largo (hasta la cosecha).
- Óptica: fórmula del paciente y orden de trabajo al laboratorio con anticipo.
- Papelería: listas de útiles por colegio.
- Licorera: aviso de mayoría de edad y horario de domicilios.

## Fase 6 — Gimnasio y supermercado
- Gimnasio: membresías que se renuevan, control de entrada y clases.
- Supermercado: pruebas de rendimiento con varias cajas a la vez y balanzas conectadas.

Cada fase: pruebas automáticas, revisión en el navegador, demo propia y commit.

## Estado (28 oct 2026): las 6 fases hechas
| Fase | Qué quedó | Migración |
|---|---|---|
| 1 | 13 perfiles nuevos en el registro y en Tipo de negocio | — |
| 2 | Droguería: INVIMA, principio activo (también en la búsqueda de la caja), fórmula obligatoria al cobrar, libro "Fórmulas despachadas" con control especial y CSV, lote y vencimiento obligatorios al recibir, nunca vender vencidos | V35 |
| 3 | Venta por medida: al tocar un producto por metro/kilo/litro la caja pregunta la cantidad (con botón Pesar si hay báscula) | — |
| 4 | Módulo MOSTRADOR (turno del día en el tiquete, pantalla de pedidos y vista para clientes en /mostrador); órdenes con el vocabulario de cada negocio (prenda, vehículo/placa, encargo, trabajo); historial por placa | V36 |
| 5 | Módulos ORDENES (órdenes sin agenda), MASCOTAS, OPTICA, LISTAS_UTILES, MAYORES_EDAD y AGRO (registro ICA; el fiado ya admite 365 días) | V37 |
| 6 | Módulo MEMBRESIAS (días por producto, entrada de socios, por vencer) y prueba de 20 ventas simultáneas desde dos cajas | V38 |

Al probar varias cajas a la vez apareció un error que ya existía: el inventario perdía unidades cuando dos ventas del mismo
producto llegaban juntas (el bloqueo reutilizaba la copia del producto leída antes). Se corrigió en `ProductLockingImpl`.
Demos: `scripts/demo_drogueria.py` y `scripts/demo_tipos_negocio.py` (cuentas en docs/DEV.md).
Pendiente: producción con recetas en panadería, horario de domicilios en licorera y pantalla de clientes pública (sin sesión).
