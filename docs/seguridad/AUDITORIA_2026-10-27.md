# Auditoría Ecomerxio POS — funcionalidades y ataque masivo (27 oct 2026)

Alcance: backend completo (263 clases, ~400 endpoints). Se priorizó lo que puede tumbar el servicio o mezclar datos entre tiendas.
Todas las correcciones van dentro del propio servidor: **no requieren servicios pagos**.

## 1. Aislamiento entre tiendas (lo más grave en un SaaS) — OK

Prueba nueva `security/TenantIsolationIT`: el dueño de la tienda B recorre **todos** los endpoints que reciben un id en la ruta
(GET, POST, PUT, PATCH, DELETE), usando los ids de los datos de la tienda A (productos, clientes, ventas, proveedores, compras,
cotizaciones, gastos, usuarios…). Comprueba que:

- ninguna respuesta exitosa trae datos de A (marca `ZZSECRETO`);
- los datos de A en todas las tablas con `tenant_id` quedan idénticos (huella md5 antes y después);
- ningún id ajeno produce un error 500.

Resultado: **0 fugas, 0 cambios y 0 errores 500 en 9.570 peticiones** (suite completa: 193 pruebas OK). Los permisos por rol se revisaron a mano: todos los endpoints que
modifican datos exigen permiso (en el controlador con `@PreAuthorize` o en el servicio con `CurrentUser.require`).

Limitación: en las peticiones que modifican datos se envía un cuerpo vacío. Las que validan el cuerpo antes de buscar el id no
llegan a probar el aislamiento. Las que no llevan cuerpo (anular, aprobar, borrar, cerrar) sí quedan cubiertas.

## 2. ¿Qué pasaba con millones de peticiones? (antes de corregir)

| # | Riesgo | Gravedad |
|---|---|---|
| A | No había límite general de peticiones: solo login, registro, recuperación y algunas rutas públicas. Cada petición con sesión hace 2 consultas a la base, así que una avalancha agotaba las 15 conexiones y **tumbaba el servicio para todas las tiendas** | ALTA |
| B | El catálogo público, las cotizaciones públicas y los webhooks no tenían tope por IP | ALTA |
| C | Sin tope de tamaño para cuerpos JSON enviados por partes: unas cuantas peticiones gigantes llenaban la memoria | ALTA |
| D | **PIN de supervisor sin bloqueo**: un cajero podía probar las 10.000 combinaciones de un PIN de 4 dígitos (y cada intento cuesta CPU por bcrypt) | ALTA |
| E | Registro: 20 por minuto por IP, unas 28.000 tiendas falsas al día desde una sola conexión, y cada una crea roles, caja y ajustes | MEDIA |
| F | "Olvidé mi contraseña": se podía llenar de correos el buzón de cualquier persona | MEDIA |
| G | Cambiar contraseña, PIN o restaurar copia piden la contraseña actual sin tope: con una sesión robada se podía adivinar | MEDIA |
| H | El limitador recorría todo su mapa en cada petición cuando pasaba de 50.000 IPs: el propio limitador se volvía un punto de ataque | MEDIA |
| I | IPv6: un atacante tiene millones de direcciones en su bloque /64 y esquivaba el límite por IP | MEDIA |
| J | Sin tiempo máximo por consulta ni por espera de conexión: las peticiones se acumulaban colgadas | MEDIA |
| K | Conexiones lentas a propósito (slowloris): tiempos de Tomcat por defecto | BAJA |

## 3. Correcciones aplicadas

| # | Cambio | Archivo |
|---|---|---|
| A, B, C, I | **Escudo de la API**: 600 peticiones/min por IP (120 en rutas sin sesión: públicas, webhooks, auth, plataforma), responde 429 con `Retry-After` **antes** de tocar la base. Cuerpo máximo de 15 MB (200 MB solo para restaurar copias), también cuando llega por partes. IPv6 agrupada por /64. Configurable con `GUARD_*` | `web/RequestGuardFilter.java`, `application.yml` |
| D | PIN de supervisor: 5 errores bloquean a ese supervisor 15 min (ni el PIN correcto pasa); 10 errores de un mismo cajero lo bloquean; un acierto limpia los errores | `pos/ApprovalService.java` |
| E | Registro: además, máximo 5 tiendas por hora por IP | `auth/AuthController.java` |
| F | Recuperación: máximo 3 correos por dirección y hora | `auth/AuthController.java` |
| G | Cambiar contraseña, PIN y restaurar copia: máximo 10 intentos por usuario cada 15 min. Pedir códigos de handoff: máximo 10 por minuto | `auth/AuthController.java`, `backup/BackupController.java` |
| H | El limitador limpia como mucho una vez por segundo y tiene un tope de memoria | `auth/RateLimiter.java` |
| J | Base de datos: espera máxima de conexión de 5 s (luego responde 503 "ocupado" y la app reintenta) y `statement_timeout` de 60 s por consulta (`DB_STATEMENT_TIMEOUT`) | `application.yml`, `GlobalExceptionHandler.java` |
| K | Tomcat: `connection-timeout` y `keep-alive` de 15 s, cabeceras ≤16 KB, formularios ≤2 MB | `application.yml` |

## 4. Errores de funcionamiento corregidos

- **Errores 500 con ids inexistentes**: 93 `orElseThrow()` sin mensaje respondían "error inesperado". Ahora responden 404
  ("No encontrado"). Ejemplo: `GET /api/products/{id}/lots`.
- **Dos cajas tocando lo mismo a la vez** (índice único o bloqueo): respondía 500. Ahora responde 409 "Intenta de nuevo".
- `days` gigantes en `/inventory/expiring` y `/adjustments-by-user` provocaban un desbordamiento de fecha (500). Ahora se limitan a 10 años.
- Content-Type o cabecera faltante: respondía 500. Ahora responde 400.

Ya estaba bien: tope de tamaño de página (≤100–500) en todos los listados, rango de analítica ≤1 año, XML de proveedores
protegido contra XXE, ZIP y gzip con límite de tamaño descomprimido (protección contra "bombas zip"), aislamiento por tienda en
las consultas, token revalidado contra la base en cada petición y webhooks firmados con HMAC.

## 5. Lo que el servidor solo no puede frenar

Un ataque **distribuido** con miles de máquinas (DDoS volumétrico) satura el ancho de banda antes de llegar a la app. Para eso
no hace falta pagar:

1. **Cloudflare plan Free** delante de `pos.ecomerxio.com` y de la API: protección DDoS ilimitada sin costo. Activar "Bot Fight
   Mode" y una regla gratuita de límite de peticiones para `/api/auth/*`.
2. Detrás de Cloudflare, la IP real llega en `CF-Connecting-IP`. Antes de activarlo hay que ajustar
   `SERVER_TOMCAT_REMOTEIP_*` para que el límite por IP no cuente a Cloudflare como un solo cliente.
3. El límite vive en memoria de **una** instancia. Si algún día hay varias instancias en Render, cada una cuenta por separado.

## 6. Riesgo aceptado

- El bloqueo de cuenta por 5 contraseñas fallidas permite que alguien bloquee a propósito a un usuario durante 15 minutos.
  Es el costo de impedir que adivinen la contraseña. Mitigación: el bloqueo es corto y el dueño puede desbloquear.
