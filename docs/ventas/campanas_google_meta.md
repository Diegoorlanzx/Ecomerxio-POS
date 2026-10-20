# Campañas de pauta — Ecomerxio POS (Google Ads + Meta)

Listas para copiar. Segmento B (tienda física). Moneda: COP. Cifras de costo = estimadas, **por validar**.
Reemplazar `[DOMINIO]` y `[WHATSAPP]` antes de publicar.

---

## A. Medición (hacer primero, en ambas plataformas)

| Evento | Cuándo se dispara | Google Ads | Meta |
|---|---|---|---|
| Contacto | Clic en botón de WhatsApp de la landing | Conversión secundaria | `Contact` |
| Registro | Termina de crear la tienda | **Conversión principal** | `CompleteRegistration` |
| Primera venta | La tienda registra su 1.ª venta | Conversión importada (offline) | `StartTrial` (API de conversiones) |
| Pago | Paga su primer plan | Conversión importada con valor | `Purchase` con valor |

UTM para todos los enlaces:
`?utm_source={google|meta}&utm_medium=cpc&utm_campaign=pos_fundadores&utm_content={nombre_anuncio}`

---

## B. GOOGLE ADS

### Configuración de la campaña

| Campo | Valor |
|---|---|
| Nombre | `POS · Search · CO · Fundadores` |
| Objetivo | Clientes potenciales |
| Tipo | Búsqueda |
| Redes | **Desmarcar** Red de Display y socios de búsqueda |
| Ubicación | Bogotá, Medellín, Cali, Barranquilla — opción **"Presencia: personas en o que suelen estar en"** |
| Idioma | Español |
| Presupuesto | $8.000 – $10.000 / día |
| Puja (inicio) | Maximizar clics con CPC máx. $1.500 |
| Puja (después de 30 registros) | Maximizar conversiones → luego CPA objetivo |
| Horario | Todos los días (revisar a las 2 semanas cuál hora convierte) |

### Grupos de anuncios y palabras clave

Usar concordancia de **frase** `"..."` y **exacta** `[...]`.

**Grupo 1 — POS genérico** → landing principal
```
"programa pos"
"sistema pos para tienda"
"software pos colombia"
[programa para tienda]
"programa para facturar en tienda"
"caja registradora en el celular"
"app para vender en mi negocio"
"punto de venta para negocio"
```

**Grupo 2 — Inventario** → landing, sección inventario
```
"programa de inventario para tienda"
"app de inventario para negocio"
"control de inventario tienda"
"como llevar el inventario de una tienda"
"software inventario pequeño negocio"
```

**Grupo 3 — Fiado y caja** → landing, sección fiado
```
"app para llevar el fiado"
"control de fiados"
"cuaderno de fiados digital"
"cuadre de caja tienda"
"control de caja negocio"
```

**Grupo 4 — Por tipo de negocio** → landing con video según tipo
```
"programa para minimercado"
"software para tienda de barrio"
"programa para tienda de ropa"
"sistema pos para boutique"
"programa para fruver"
"programa para carniceria con bascula"
"programa para ferreteria"
"programa para papeleria"
```

### Palabras clave negativas (a nivel de campaña)
```
gratis descargar, crack, full, apk mod, curso, tutorial excel, plantilla excel,
empleo, trabajo, vacante, dian factura electronica, nomina, siigo, alegra,
restaurante, bar, discoteca, tesis, que es, definicion, pdf
```
> Quitar "restaurante" y "dian" de negativas cuando estén las Fases 10 y 13.

### Anuncio adaptable de búsqueda (RSA) — uno por grupo

**URL final:** `https://[DOMINIO]/pos` · **Ruta visible:** `[DOMINIO]/pos/tienda`

**Títulos (máx. 30 caracteres)** — fijar el 1 en posición 1:
1. Programa POS para tu tienda
2. Ecomerxio POS
3. Cobra desde tu celular
4. Inventario siempre al día
5. Controla el fiado sin cuaderno
6. Cierra caja sin descuadres
7. Vende aunque no haya internet
8. Desde $39.900 al mes
9. 50% menos para fundadores
10. Pruébalo gratis
11. Sabe qué producto deja más
12. Hecho para tiendas de barrio
13. Alertas de producto agotado
14. Permisos para cada empleado
15. Te ayudamos a configurarlo

**Descripciones (máx. 90 caracteres):**
1. Cobra, controla la caja y el inventario desde el celular o el computador. Pruébalo gratis.
2. Fiado con cupo y abonos, cierre de caja por turno y alertas cuando algo se va a acabar.
3. Mira qué productos te dejan más margen y cuáles no rotan. Sin pelearte con Excel.
4. Primeros 50 negocios: 50% de descuento por 12 meses. Te acompañamos a montarlo.

> Grupo 2: subir títulos 4, 13, 11. Grupo 3: subir 5 y 6. Grupo 4: añadir "Para minimercados y fruvers" / "Para tiendas de ropa".

### Extensiones (recursos)

**Enlaces de sitio** (texto ≤25 · descripción ≤35 c/u):
| Texto | Línea 1 | Línea 2 |
|---|---|---|
| Planes y precios | Desde $39.900 al mes | Pago anual: 2 meses gratis |
| Mira cómo funciona | Video de 1 minuto | Con la pantalla real |
| Cupos de fundador | 50% por 12 meses | Solo 50 negocios |
| Escríbenos por WhatsApp | Te respondemos hoy | Te ayudamos a montarlo |

**Textos destacados (≤25):** Funciona sin internet · Fiado con cupo y abonos · Cierre de caja por turno · Alertas de agotados · Varias cajas y empleados · Soporte en Colombia

**Fragmento estructurado** — Tipos: Minimercados, Boutiques, Fruvers, Misceláneas, Ferreterías

**Recurso de llamada / mensaje:** [WHATSAPP]

---

## C. META (Facebook + Instagram)

### Campaña 1 — WhatsApp (50% del presupuesto)

| Campo | Valor |
|---|---|
| Nombre | `POS · Mensajes WA · CO` |
| Objetivo | **Interacción** → Mensajes → WhatsApp |
| Presupuesto | Advantage (de campaña) $10.000 – $12.000 / día |
| Conjunto 1 | `Tenderos · intereses` — Colombia (4 ciudades), 25-55, intereses: Pequeña empresa, Emprendimiento, Comercio minorista, Propietarios de pequeñas empresas |
| Conjunto 2 | `Abierto · Advantage+` — mismas ciudades y edad, sin intereses |
| Ubicaciones | Advantage+ (automáticas) |
| Anuncios | Los 6 de abajo en ambos conjuntos |

**Mensaje de bienvenida en WhatsApp (plantilla del anuncio):**
> ¡Hola! 👋 Gracias por escribir a Ecomerxio POS. ¿Qué tipo de negocio tienes?
Respuestas rápidas: `Tienda / minimercado` · `Ropa o calzado` · `Fruver / carnicería` · `Otro`

### Campaña 2 — Registro en la landing (25%)

| Campo | Valor |
|---|---|
| Nombre | `POS · Registro · CO` |
| Objetivo | **Clientes potenciales** → Sitio web → evento `CompleteRegistration` |
| Presupuesto | $5.000 – $6.000 / día |
| Público | Advantage+ con sugerencia de intereses (los mismos) |
| Anuncios | Los 2 ganadores de la Campaña 1 (cuando haya datos) |

### Campaña 3 — Remarketing (5%)

| Campo | Valor |
|---|---|
| Nombre | `POS · Remarketing` |
| Objetivo | Ventas / Clientes potenciales |
| Público | Vieron ≥50% de un video (30 días) + visitaron la landing (30 días) − ya registrados |
| Presupuesto | $2.000 / día |
| Anuncio | El #7 (oferta fundadores) |

### Anuncios

Texto principal: la primera línea es lo único que se ve sin "Ver más" (~125 caracteres).
Título ≤40 · Descripción ≤30.

**Anuncio 1 — Caja** · `pos_hook_caja` · Reel 15 s (cierre de turno real)
- **Texto:** ¿Cerraste la caja y no te cuadró? 🧾
  Con Ecomerxio POS cada turno tiene su cierre: lo que entró en efectivo, en transferencia y lo que salió. Sabes al peso dónde está la diferencia.
  👉 Escríbenos y te mostramos cómo funciona en tu negocio.
- **Título:** Cierra caja sin descuadres
- **Descripción:** Desde $39.900 al mes
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 2 — Fiado** · `pos_hook_fiado` · Reel + carrusel
- **Texto:** El fiado en el cuaderno se te olvida… al cliente también 😅
  Ponle cupo a cada cliente, registra sus abonos y mándale el saldo por WhatsApp con un toque.
  👉 Escríbenos y te ayudamos a pasarlo del cuaderno al celular.
- **Título:** Tu fiado, ordenado
- **Descripción:** Cupos, abonos y recordatorios
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 3 — Rentabilidad (diferencial)** · `pos_hook_margen` · Reel 20 s (pantalla de rentabilidad)
- **Texto:** ¿Sabes cuál producto te deja más plata? No el que más vendes: el que más te deja. 📊
  Ecomerxio POS te muestra el margen de cada producto, cuáles no rotan y cuánta plata tienes quieta en el estante.
  👉 Pruébalo con tus propios productos.
- **Título:** Mira qué te deja plata
- **Descripción:** Analítica clara, sin Excel
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 4 — Sin internet** · `pos_hook_offline` · Reel 10 s
- **Texto:** Se fue el internet… y seguiste vendiendo. 📶❌
  Ecomerxio POS guarda las ventas en tu equipo y las sube solas cuando vuelve la conexión.
  👉 Escríbenos y lo ves funcionando.
- **Título:** Vende aunque se caiga el internet
- **Descripción:** Se sincroniza solo
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 5 — Agotados** · `pos_hook_agotado` · Carrusel 5 tarjetas
- **Texto:** Sábado, tienda llena… y se acabó la gaseosa. 🥤
  Ecomerxio POS te avisa antes de que se acabe y te sugiere cuánto pedirle a cada proveedor según lo que vendes.
  👉 Escríbenos y te mostramos cómo.
- **Título:** Que no se te acabe nada
- **Descripción:** Alertas y pedidos sugeridos
- **Tarjetas:** 1 "Se acabó justo hoy" · 2 "Te avisa antes" · 3 "Te dice cuánto pedir" · 4 "Le mandas el pedido al proveedor" · 5 "Desde $39.900/mes — Escríbenos"
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 6 — Empleados** · `pos_hook_permisos` · Reel 15 s
- **Texto:** Tu empleado no puede anular una venta ni dar descuentos sin tu permiso. 🔐
  Cada persona entra con su usuario, y lo delicado pide tu PIN. Tú ves todo desde el celular, estés donde estés.
  👉 Escríbenos y te contamos cómo.
- **Título:** Tu negocio bajo control
- **Descripción:** Usuarios, roles y PIN
- **Botón:** Enviar mensaje de WhatsApp

**Anuncio 7 — Oferta fundadores (remarketing)** · `pos_oferta_fundador` · Imagen 1080×1350
- **Texto:** Quedan pocos cupos de fundador 🚀
  Los primeros 50 negocios pagan la mitad durante 12 meses: plan Tienda a $19.950/mes. Y te ayudamos a montarlo.
  👉 Toma tu cupo.
- **Título:** 50% por 12 meses
- **Descripción:** Solo 50 negocios
- **Botón:** Registrarte
- **Texto en la imagen (máx. 3 líneas):** "Ecomerxio POS · 50% para fundadores · Desde $19.950/mes"

---

## D. Reglas de optimización (las mismas en ambas)

- No tocar nada los primeros **3 días** (fase de aprendizaje).
- Apagar un anuncio con CTR < 0,6% tras 3.000 impresiones, o costo por conversación > $10.000.
- Google: revisar el **informe de términos de búsqueda** cada 3 días y añadir negativas.
- Escalar +20% el presupuesto cada 3–4 días mientras el costo por registro se mantenga.
- Decidir por **registro → primera venta → pago**, nunca por likes.
- Antes de publicar: pasar todo por el agente `mkt-guardian-marca`.

## E. Lo que NO puede aparecer en ningún anuncio

- "Duplica tus ventas", "gana más", o cualquier promesa de plata.
- Testimonios o cifras de clientes que no existan.
- Factura electrónica DIAN, restaurantes o distribuidoras (aún no están).
- Burlarse del cuaderno o de quien usa Excel.
- "El único", "revolucionario", "next level".
