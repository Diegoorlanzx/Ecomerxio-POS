# Auditoría de seguridad — Ecomerxio POS (22 oct 2026)

Alcance: `backend` (Java 21 / Spring Boot), `frontend/src` (React), `deploy`, `scripts`, configuración.
Base: commit `cd6b4eb`.

## Qué se corrió

| Prueba | Herramienta | Resultado |
|---|---|---|
| SAST de código | Semgrep 1.182 (java, react, typescript, owasp-top-ten, jwt, secrets, dockerfile) | 1 hallazgo, falso positivo |
| Dependencias del frontend | `npm audit` | 0 vulnerabilidades |
| Dependencias del backend | 104 librerías de ejecución contra OSV.dev | **22 librerías con avisos conocidos** |
| Secretos en el repo e historial | detect-secrets + búsqueda de `.env`/llaves en `git log` | Sin secretos reales; `.env.local` y `.env.render` fuera de Git |
| Revisión manual | Configuración de seguridad, JWT, filtro de sesión, límite de intentos, CORS, cabeceras | Ver hallazgos |
| DAST automatizado | — | **No completado** (ver al final) |

## Hallazgos

### 1. ALTA — Dependencias con vulnerabilidades conocidas
Spring Boot **3.3.4** ya no recibe parches. Las librerías que arrastra tienen avisos publicados:

- `tomcat-embed-core` 10.1.30 — 33 avisos
- `spring-webmvc` / `spring-web` / `spring-core` / `spring-context` / `spring-expression` 6.1.13
- `spring-security-web` / `-core` / `-crypto` 6.3.3
- `jackson-core` / `jackson-databind` 2.17.2
- `postgresql` 42.7.4, `logback-core` 1.5.8, `httpclient5` 5.3.1 / `httpcore5` 5.2.5, `log4j-api` 2.23.1

**Arreglo:** subir el `parent` de Spring Boot a la última 3.5.x y volver a correr las pruebas. La mayoría
de los avisos desaparecen con ese cambio, porque Boot fija las versiones de esas librerías.

### 2. MEDIA — El límite de intentos se salta cambiando `X-Forwarded-For`
`AuditService.clientIp()` toma el **primer** valor de `X-Forwarded-For`, y ese valor lo escribe el cliente.
`RateLimiter` usa ese valor para login, registro, recuperación y cambio de contraseña, handoff y pedidos en
línea. Quien envíe un valor distinto en cada petición no tiene límite. Eso deja abierto el *credential
stuffing* (probar muchas cuentas). El bloqueo por cuenta (5 fallos / 15 min) sigue protegiendo cada cuenta
por separado. La IP que queda en la auditoría y en la aceptación de documentos legales también se puede
falsificar.

**Arreglo:** detrás de Render, usar el **último** valor de la cabecera, que es el que agrega el proxy. Otra
opción es confiar solo en `request.getRemoteAddr()` con `server.forward-headers-strategy` y una lista de
proxies de confianza. Nunca usar el primer valor.

### 3. BAJA — El limitador en memoria se vacía entero
Al pasar de 50.000 llaves, `windows.clear()` reinicia los contadores de todos. Sumado al hallazgo 2, alguien
puede llenarlo a propósito para reiniciarlo. Además no se comparte entre instancias, si algún día hay más de
una. **Arreglo:** borrar solo las ventanas vencidas (o usar Caffeine con expiración) y corregir el hallazgo 2.

### 4. INFO — Valores por defecto de desarrollo en `application.yml`
`JWT_SECRET` y la contraseña de la base tienen valores de desarrollo. `ProductionGuard` se niega a arrancar
en el perfil `prod` si `JWT_SECRET` es corto o empieza por `dev-only`. **Verificar:** que Render arranque con
el perfil `prod`, porque si no, la protección no corre.

### 5. Falso positivo de Semgrep
`AuthService.DUMMY_HASH` es un hash bcrypt de relleno que iguala los tiempos de respuesta del login. Es
intencional y no es una contraseña.

## Correcciones aplicadas (22 oct 2026)

| # | Cambio | Archivo |
|---|---|---|
| 1 | Spring Boot 3.3.4 → **3.5.16**, más versiones parcheadas de Tomcat 10.1.61, Jackson 2.21.8, PostgreSQL JDBC 42.7.14, Log4j API 2.25.5, HttpClient 5.6.5 / HttpCore 5.4.4 | `backend/pom.xml` |
| 2 | `clientIp()` usa solo `getRemoteAddr()`; producción pasa a `forward-headers-strategy: native` (RemoteIpValve de Tomcat lee `X-Forwarded-For` de derecha a izquierda y solo confía en proxies internos). Con `framework`, Spring también tomaba el primer valor | `AuditService.java`, `application-prod.yml`, prueba `ClientIpTest` |
| 3 | El limitador descarta solo ventanas vencidas, no todas | `RateLimiter.java` |
| 4 | Verificado: `backend/Dockerfile`, `deploy/Dockerfile.todo-en-uno` y `render.yaml` fijan `SPRING_PROFILES_ACTIVE=prod` | — |

Después del cambio, OSV no reporta avisos en las dependencias de ejecución, salvo dos de `spring-webmvc` 6.2.x
(GHSA-j9f9-w8pj-32f8 y GHSA-pc63-qcmh-9cmg). Solo afectan a `XsltView` y a los fragmentos de vistas por SSE, y la
app no usa ninguno de los dos. El parche existe únicamente en Spring 7 (Boot 4).

**Después del despliegue:** iniciar sesión en pos.ecomerxio.com y revisar en Auditoría que aparezca tu IP pública.
Si aparece una IP del balanceador de Render, ajusta la variable `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`.

## Lo que está bien
- El token se valida contra la base en cada petición (usuario activo, tienda activa, versión de sesión):
  desactivar un usuario o cerrar todas sus sesiones surte efecto al instante.
- Rutas `/api/**` autenticadas por defecto; lista pública corta y explícita.
- Cabeceras fuertes: CSP sin `unsafe-eval`, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`;
  `server.error.include-message: never`.
- CORS por lista de orígenes y sin cookies; CSRF apagado, lo cual es correcto con tokens Bearer.
- Contraseñas con bcrypt, bloqueo por intentos fallidos, códigos de handoff guardados solo como hash.
- Webhooks de pagos firmados con HMAC; llaves generadas con `SecureRandom`.

## DAST: no completado
Se levantaron la base (55434) y la API (8090) locales y se extrajeron los 401 endpoints de los controladores.
La fase dinámica automatizada se detuvo antes de ejecutarse, así que **no hay resultados dinámicos**. En
particular, no se verificó en ejecución el aislamiento entre tiendas (que una tienda no pueda leer ni
modificar datos de otra por id) ni la separación de permisos por rol. Es lo más importante que queda por
probar en una app multi-tienda. Se recomienda una prueba de penetración o un escaneo dinámico (por ejemplo,
OWASP ZAP) contra el entorno local o un entorno de pruebas antes de salir a vender.
