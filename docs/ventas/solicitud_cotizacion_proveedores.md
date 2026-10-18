# Solicitud de cotización — proveedor tecnológico de facturación electrónica

Para enviar a **Dataico**, **The Factory HKA** y **Alegra** (como tercera opción). Antes de contratar, verificar en
el listado oficial de la DIAN que el proveedor esté autorizado.

Los volúmenes son estimados: ajústalos antes de enviar si tienes mejores cifras.

---

## 1. Correo (copiar y pegar)

**Asunto:** Cotización como integrador: facturación electrónica para POS multiempresa (Ecomerxio POS)

> Buenos días,
>
> Soy [tu nombre], de Ecomerxio. Tenemos **Ecomerxio POS**, un punto de venta en la nube para tiendas de barrio,
> minimercados, boutiques y negocios similares en Colombia. Es un sistema **multiempresa**: cada comercio tiene su
> propio NIT y su propia resolución de numeración.
>
> Queremos integrar la facturación electrónica en nuestro sistema **mediante su API**, manteniendo nuestra propia
> experiencia de usuario, y ofrecérsela a nuestros comercios **en paquetes de documentos** dentro de nuestros planes.
> Ya tenemos construido el flujo de emisión, numeración, reintentos y notas crédito. Nos falta conectar el proveedor.
>
> Les agradecemos una **propuesta escrita** como integrador, respondiendo el cuestionario adjunto y con acceso a su
> **ambiente de pruebas** para validar la integración.
>
> Volumen estimado:
> - Inicio (primeros 3 meses): 10 a 30 comercios, unos 30.000 a 90.000 documentos al mes.
> - A 12 meses: 100 a 250 comercios, unos 300.000 a 750.000 documentos al mes.
> - La mayoría serán **documentos equivalentes POS electrónicos** a consumidor final; una parte menor, facturas
>   electrónicas, notas crédito y documentos soporte.
>
> Quedo atento,
> [tu nombre] · [tu cargo] · [teléfono / WhatsApp] · [correo]

---

## 2. Cuestionario

### A. Modelo comercial y precios
1. Precio por documento según volumen (tabla por rangos). ¿Hay mínimo mensual o anticipo?
2. ¿Cobran por **intento de envío** o por **documento aceptado** por la DIAN? ¿Cuánto cuesta un rechazo?
3. ¿Qué tipos de documento cobran y a qué precio?
   - factura electrónica de venta
   - documento equivalente POS electrónico
   - nota crédito
   - nota débito
   - documento soporte
   - nota de ajuste del documento soporte
   - eventos de recepción (RADIAN)
4. ¿Permiten que **revendamos o incluyamos los documentos en paquetes propios** para nuestros comercios? ¿Con qué
   condiciones?
5. ¿Hay costos de **habilitación** por comercio (set de pruebas, certificado de firma, alta de NIT)? ¿Quién los asume?
6. Forma de pago, facturación a nosotros, permanencia y condiciones para terminar el contrato.

### B. Multiempresa
7. ¿Un **solo contrato de integrador** cubre muchos NIT, o cada comercio necesita su propio contrato o usuario?
8. ¿Cómo se da de alta un comercio nuevo por API? ¿Cuánto tarda, y qué debe hacer él en el portal de la DIAN?
9. ¿El **certificado de firma digital** lo pone el proveedor o el comercio? ¿Cuánto cuesta y cada cuánto se renueva?
10. ¿Manejan varias resoluciones y prefijos por comercio, y ambientes de **habilitación y producción** separados?

### C. API y técnica
11. Documentación de la API y de los ambientes (pruebas y producción). ¿Es REST con JSON?
12. ¿Aceptan una **referencia externa propia** (idempotencia) y permiten **consultar el estado por esa referencia**?
    Nuestro sistema nunca reenvía sin consultar primero.
13. ¿Notifican el resultado con **webhooks**? ¿Vienen firmados (HMAC u otro)? ¿Qué pasa si nuestro servidor no responde?
14. Tiempos de respuesta típicos y en hora pico. Límites de peticiones por minuto.
15. ¿Quién asigna el **consecutivo**: nosotros o el proveedor?
16. Formato de los **errores**: ¿entregan el código y el mensaje de la DIAN tal cual, más una explicación propia?
17. ¿Generan y guardan el **XML, el PDF y la representación gráfica**? ¿Envían el documento por correo al comprador,
    o lo hacemos nosotros?
18. ¿Cuánto tiempo **conservan** los documentos y cómo los descargamos si cambiamos de proveedor (portabilidad)?

### D. Documentos y casos especiales
19. Soporte del **documento equivalente POS electrónico** y de sus reglas (consumidor final, cuándo el comprador puede
    pedir factura).
20. **Notas crédito y débito**, totales y parciales (devoluciones de algunos productos).
21. **Contingencia**: ventas hechas sin internet que se transmiten después. ¿Cómo lo soportan y en qué plazos?
22. **Documento soporte** para compras a proveedores no obligados a facturar.
23. **Recepción de facturas de proveedores** (XML) y eventos RADIAN para nuestros comercios.
24. ¿Cómo manejan los cambios de la norma (resoluciones nuevas de la DIAN)? ¿Están incluidos en el precio?

### E. Servicio
25. Disponibilidad comprometida (SLA), canal y tiempos de soporte técnico para integradores.
26. ¿Tienen otros integradores tipo POS o software multiempresa como clientes? Referencias.
27. Estado de su autorización como proveedor tecnológico ante la DIAN (número o enlace al listado oficial).

---

## 3. Tabla para comparar las respuestas

| Criterio | Dataico | The Factory HKA | Alegra |
|---|---|---|---|
| Precio por documento (30k / 300k al mes) | | | |
| Mínimo mensual / anticipo | | | |
| ¿Cobra los rechazos? | | | |
| Permite reventa en paquetes | | | |
| Un contrato para muchos NIT | | | |
| Alta de comercio por API (tiempo) | | | |
| Certificado de firma (quién y costo) | | | |
| Idempotencia + consulta por referencia | | | |
| Webhooks firmados | | | |
| Documento POS electrónico | | | |
| Notas parciales | | | |
| Contingencia (ventas sin internet) | | | |
| Documento soporte | | | |
| XML/PDF y envío al comprador | | | |
| Conservación y portabilidad | | | |
| SLA y soporte | | | |
| Autorizado DIAN (verificado) | | | |

**Para decidir:** pesan más la idempotencia y la consulta por referencia, la reventa en paquetes, el contrato para
muchos NIT y el costo real a 300.000 documentos al mes, que el precio del primer mes.

**Lo que cambia en Ecomerxio POS al elegir:** solo se escribe el adaptador del proveedor (una clase que implementa
`EInvoiceProvider`, ver `backend/src/main/java/com/tiendaposs/einvoice`). El flujo de emisión, la numeración, la cola,
los reintentos, las notas y las pantallas ya existen.
