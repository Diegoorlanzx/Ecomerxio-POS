# Tienda de ejemplo: Minimercado La Esquina

Tienda completa para probar TiendaPOSS. Se crea con `python scripts/crear_tienda_demo.py` (servidor local por defecto;
`--url https://tu-servidor` para otro). Todo se carga por la API, igual que lo haría una persona.

**Contraseña de todos los usuarios: `Demo2026!`**

| Rol | Correo | Nombre | PIN |
|---|---|---|---|
| Dueño | dueno@tiendademo.test | Diego Parada | 2468 |
| Administrador | admin@tiendademo.test | Carolina Mejía | |
| Supervisor | supervisor@tiendademo.test | Julián Restrepo | 1357 |
| Cajero | cajera@tiendademo.test | Laura Gómez | |
| Cajero | cajero@tiendademo.test | Andrés Zapata | |
| Inventario | inventario@tiendademo.test | Mateo Arias | |
| Compras | compras@tiendademo.test | Sandra Ochoa | |
| Contador | contadora@tiendademo.test | Beatriz Londoño | |
| Auditor | auditor@tiendademo.test | Felipe Cardona | |

El PIN se usa cuando la caja pide autorización (descuentos altos, anulaciones, devoluciones, fiado sobre el cupo).

## Qué trae
- **Catálogo:** 30 productos en 9 categorías (cervezas, gaseosas, licores, cigarrillos, snacks, lácteos, abarrotes,
  aseo, panadería), con códigos de barras, IVA donde aplica, presentaciones (six-pack, cartón, panal de huevos) y
  mínimos de inventario. Yogurt con lotes y fecha de vencimiento cercana.
- **Proveedores:** Bavaria, Coca-Cola FEMSA, Postobón, FLA y Distribuidora La Andina, con empaques, costos, días de
  pedido y de entrega, y un proveedor alterno más caro para comparar.
- **Ventas:** unas 1.100 ventas de los últimos 50 días hechas por 4 personas en 4 cajas (más en fines de semana y
  quincenas), con efectivo, Nequi, Daviplata, tarjeta y fiado. Turnos cerrados y cuadrados, uno con faltante de
  $3.500. Hoy: dos cajas abiertas, ventas en vivo, una anulación (autorizada con PIN), una devolución y un gasto de caja.
- **Pagos digitales:** los del historial ya verificados; los de los últimos días quedan por verificar.
- **Compras:** pedido a Bavaria recibido (con 2 unidades dañadas), pedido a La Andina recibido con lote de yogurt
  por vencer, pedido a Postobón esperando aprobación y uno a FLA retrasado. Pago parcial a Bavaria.
- **Finanzas:** arriendo, servicios, internet, nómina y fletes del mes pasado y de este; aporte del dueño.
- **Clientes:** 6, cuatro con fiado (uno pasó su cupo en una venta sin internet y quedó para revisar) y abonos.
- **Promociones:** six-pack de Águila, Chocorramo 3x2 y una de snacks inactiva.
- **Inteligencia:** recomendaciones de precios (cigarrillos y abarrotes con margen bajo) y promociones (licor y
  encendedores quietos), notificaciones, alertas de stock y la cuenta de Nequi con un QR **de muestra**.
- **Copia de seguridad** "estado inicial": para volver a dejar la demo como nueva, entra como dueño a
  Más → Copias de seguridad y restaura la primera copia.

## Recorrido sugerido (15 minutos)
1. Entra como **cajera**: abre el POS, escanea o busca "Águila", vende un six-pack (la promoción se aplica sola),
   cobra con Nequi y mira el QR. Abre una cuenta de mesa, agrega productos y cóbrala después.
2. Apaga el internet (o el servidor) y vende: la venta queda guardada y se envía sola al volver.
3. Entra como **dueño**: Dashboard (hoy, semana, quincena, mes), Notificaciones, Pagos digitales (verifica uno),
   Inventario inteligente, Compras (aprueba el pedido de Postobón), Analítica, Rentabilidad y Recomendaciones.
4. Entra como **contadora** y como **auditor** para ver qué cambia según el rol.
5. Asistente: pregunta "¿qué compro esta semana?" (con clave de Claude) o usa las consultas rápidas.

---

# Boutique de ejemplo (Fase 8): Boutique Luna
Ropa, calzado y accesorios. Se crea con `python scripts/crear_boutique_demo.py` (después de la tienda anterior, si
se quiere la báscula en el minimercado).

| Rol | Correo | Nombre | PIN |
|---|---|---|---|
| Dueña | dueno@boutiquedemo.test | Mariana Luna | 2468 |
| Supervisor | supervisor@boutiquedemo.test | Camilo Duarte | 1357 |
| Cajera | cajera@boutiquedemo.test | Valentina Ríos | |

Contraseña de todos: `Demo2026!`

## Qué trae
- **Variantes:** camiseta (talla × color), polo, jean (talla 28–36 × color), vestido (talla), tenis (número 36–42 ×
  color) y gorra (color), cada variante con su código de barras, precio (XL y talla 36 más caras) y stock.
- **Combos:** "Kit regalo" (gorra negra + medias + bolsa) y "Dúo camisetas M negra" (2 camisetas).
- **Seriales:** reloj con garantía de 12 meses y 8 seriales (TMP02600 a TMP02607); algunos ya vendidos.
- **Ventas:** unas 130 de los últimos 30 días, con clientes, tarjeta, Nequi y Bre-B. Turno de la cajera abierto.
- **Báscula en el Minimercado La Esquina:** queso campesino (PLU 101) y tomate chonto (PLU 102) por kilo.

## Recorrido sugerido (5 minutos)
1. Como **cajera**: toca "Camiseta básica algodón", elige talla y color, y cóbrala. Agrega el reloj: pide el serial
   (toca uno de los disponibles). El serial sale en el comprobante.
2. Vende el "Kit regalo" y mira en Inventario cómo bajaron la gorra, las medias y la bolsa.
3. Como **dueña**: Inventario → "Variantes" en la camiseta (agrega la talla XXL), "Seriales" en el reloj, y
   Más → Garantías y seriales (busca TMP026).
4. En el **Minimercado** (cajera@tiendademo.test): escanea o escribe en la caja la etiqueta `2000101007500`
   (0,750 kg de queso campesino). Para imprimir etiquetas: Más → Etiquetas de báscula.

## Facturación electrónica y contabilidad de ejemplo (20 oct 2026)
Para mostrar estos dos módulos en Cigarrería El Paisa (con el proveedor SIMULADO, sin validez ante la DIAN):

```
python -I scripts/demo_facturacion_contabilidad.py [URL_API] [CORREO] [CONTRASEÑA]
```
Por defecto usa `http://localhost:8090/api` y el dueño de prueba. Deja: datos fiscales (persona natural con NIT,
Medellín), resoluciones POSE (POS electrónico) y FEPA (factura), facturación y contabilidad activas, clientes
María Fernanda Gómez (CC) y Distribuidora La 70 SAS (NIT), y documentos de ejemplo: POS electrónicos, facturas,
una factura fiada, una pagada por Nequi, notas crédito por anulación y por devolución, y una factura rechazada
("Cliente de prueba SIM-RECHAZO") para el centro de errores. Se puede correr otra vez: solo completa lo que falte.
Las guías detalladas están en Más → Guía interactiva → Facturación electrónica y Contabilidad.
