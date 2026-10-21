# Documentos legales de Ecomerxio POS — pendientes antes de vender

Los textos están en `frontend/src/lib/legal.ts` (versión `LEGAL_VERSION`, igual a `LegalService.VERSION` del backend).
Se ven en `/legal/terminos`, `/legal/licencia`, `/legal/servicio-y-responsabilidad`, `/legal/encargo-de-datos`,
`/legal/privacidad` y dentro de la app en Más → Legal y privacidad.

## 1. Datos que faltan (objeto `TITULAR` en `legal.ts`)
- Número de cédula del titular.
- Ciudad de domicilio y dirección de notificaciones.
- Ciudad cuyos jueces conocen los conflictos (`jurisdiccion`).
- Confirmar el horario de soporte (`horarioSoporte`, hoy lunes a sábado 8 a. m. – 6 p. m.) y los tiempos objetivo
  (crítico 2 h, normal 1 día hábil, consultas 3 días hábiles) en la sección 7 de la Política de Servicio.
- Si se constituye una S.A.S., cambiar el titular por la sociedad (y ceder el software a ella por escrito).

## 2. Para que un abogado colombiano revise
- Tope de responsabilidad (hoy: lo pagado en los últimos 3 meses) y exclusión de lucro cesante, redactadas
  "en la medida en que la ley lo permita" y sin perjuicio de dolo o culpa grave.
- Compensación por caídas (sección 6): 1 a 3 días de servicio solo por interrupciones imputables al titular;
  las de terceros (nube, DIAN, bancos) no cuentan. Es el único remedio por indisponibilidad.
- Anexo de facturación electrónica: aclarar quién contrata al proveedor tecnológico cuando se elija.
- Declaración de relación entre empresarios (no consumo, art. 5 Ley 1480): confirmar que aplica a tenderos
  personas naturales.
- Suspensión por mora (aviso + 5 días) y plazo de 30 días para descargar la información.
- Lista de subencargados y transferencia internacional (Render, Vercel, Supabase, correo, pasarela, proveedor de
  facturación, Anthropic si se activa la IA).
- Texto modelo de autorización que el comercio usa con sus propios clientes (Acuerdo de encargo, sección 2).

## 3. Pendientes técnicos que los textos mencionan
- Las copias de seguridad se guardan en la misma base de datos: falta una copia externa (otro proveedor) con
  pruebas de restauración.
- Monitor externo de disponibilidad (p. ej. UptimeRobot sobre `/api/health`) para medir la compensación.

## 4. Trámites recomendados
- Registrar el software en la Dirección Nacional de Derecho de Autor (registro en línea, gratuito): prueba de
  autoría y fecha.
- Registrar la marca "Ecomerxio" en la Superintendencia de Industria y Comercio (clases 9 y 42).
- Registro Nacional de Bases de Datos: verificar si la obligación aplica según los activos.

## 5. Cómo publicar un cambio
1. Editar el texto en `legal.ts`.
2. Subir `LEGAL_VERSION` (frontend) y `LegalService.VERSION` (backend) a la misma fecha.
3. Al entrar, cada usuario verá la pantalla "Actualizamos nuestros documentos" y deberá aceptar. Queda registro en
   `legal_acceptances` (fecha, IP, navegador, versión).
