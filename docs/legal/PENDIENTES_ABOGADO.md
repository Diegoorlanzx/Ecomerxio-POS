# Documentos legales de Ecomerxio POS — pendientes antes de vender

Los textos están en `frontend/src/lib/legal.ts` (versión `LEGAL_VERSION`, igual a `LegalService.VERSION` del backend).
Se ven en `/legal/terminos`, `/legal/licencia`, `/legal/servicio-y-responsabilidad`, `/legal/encargo-de-datos`,
`/legal/privacidad` y dentro de la app en Más → Legal y privacidad.

## 1. Datos que faltan (objeto `TITULAR` en `legal.ts`)
- Número de cédula del titular.
- Ciudad de domicilio y dirección de notificaciones.
- Ciudad cuyos jueces conocen los conflictos (`jurisdiccion`).
- Si se constituye una S.A.S., cambiar el titular por la sociedad (y ceder el software a ella por escrito).

## 2. Para que un abogado colombiano revise
- Tope de responsabilidad (hoy: lo pagado en los últimos 3 meses) y exclusión de lucro cesante.
- Declaración de relación entre empresarios (no consumo, art. 5 Ley 1480): confirmar que aplica a tenderos
  personas naturales.
- Suspensión por mora (aviso + 5 días) y plazo de 30 días para descargar la información.
- Lista de subencargados y transferencia internacional (Render, Vercel, Supabase, correo, pasarela, proveedor de
  facturación, Anthropic si se activa la IA).
- Texto modelo de autorización que el comercio usa con sus propios clientes (Acuerdo de encargo, sección 2).

## 3. Trámites recomendados
- Registrar el software en la Dirección Nacional de Derecho de Autor (registro en línea, gratuito): prueba de
  autoría y fecha.
- Registrar la marca "Ecomerxio" en la Superintendencia de Industria y Comercio (clases 9 y 42).
- Registro Nacional de Bases de Datos: verificar si la obligación aplica según los activos.

## 4. Cómo publicar un cambio
1. Editar el texto en `legal.ts`.
2. Subir `LEGAL_VERSION` (frontend) y `LegalService.VERSION` (backend) a la misma fecha.
3. Al entrar, cada usuario verá la pantalla "Actualizamos nuestros documentos" y deberá aceptar. Queda registro en
   `legal_acceptances` (fecha, IP, navegador, versión).
