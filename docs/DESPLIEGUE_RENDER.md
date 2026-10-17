# Publicar Ecomerxio POS con la tienda de muestra (Render + la base de Ecomerxio)

Resultado: Ecomerxio POS dentro de **`https://www.ecomerxio.com/pos`** (microfrontend), siempre despierto, con la
tienda **Cigarrería El Paisa** lista para mostrar y los botones "Empieza gratis" de ecomerxio.com funcionando.

### Cómo se ve dentro de ecomerxio.com (microfrontend)

- La página `/pos/*` de Ecomerxio (`ecommerce-platform/frontend/src/pages/PosApp.tsx`) carga
  `/pos-app/remoteEntry.js` con Module Federation y llama a `mount()` (`frontend/src/remote.tsx` de este repo).
- La app se pinta dentro de un **Shadow DOM** con su propio React y sus estilos: no choca con los de Ecomerxio.
- `vercel.json` de Ecomerxio reenvía `ecomerxio.com/pos-app/*` a este servicio (`https://ecomerxio-pos.onrender.com`).
  Para el navegador todo es el mismo dominio: código, imágenes y API llegan por `/pos-app`. **Si Render le pone
  otro nombre al servicio, hay que cambiar esa línea de `vercel.json`.**
- Incrustada no se instala como app ni abre sin internet (eso lo hace la app sola); las ventas sin conexión sí siguen
  guardándose en el navegador mientras la pestaña esté abierta.

## Cómo queda armado

- **Servidor:** un servicio nuevo en Render, `ecomerxio-pos`, plan **Starter** y región **virginia**, igual que
  `ecomerxio-api`. Render cobra por servicio, así que es una mensualidad más (la del plan Starter).
- **Base de datos:** la **misma** que ya pagas para Ecomerxio, pero en su propio **esquema `pos`**. Las dos apps
  tienen tablas con el mismo nombre (`products`, `users`...), así que no se mezclan: Ecomerxio sigue en `public`
  y Ecomerxio POS vive en `pos`. Mismo servidor, mismo pago, datos separados. Probado: la app arranca, migra
  y entra solo dentro de `pos`, sin tocar `public`.

Archivos de este repositorio que se usan:

- `render.yaml`: el servicio web.
- `deploy/demo/cigarreria-el-paisa.sql`: la base local con **solo** la Cigarrería (11 productos, 443 ventas,
  turnos, fiados y sus 2 usuarios), ya dentro del esquema `pos`. No lleva claves de pago, tokens ni enlaces de
  recuperación.

El orden importa: **la tienda se carga antes de arrancar la app por primera vez**.

## 1. GitHub

Hecho: el código está en `https://github.com/Diegoorlanzx/Ecomerxio-POS` (privado, rama `main`).

## 2. Cargar la tienda en la base de Ecomerxio

1. En Render abre `ecomerxio-api` → **Environment** y mira `DATABASE_URL`, `DATABASE_USERNAME` y `DATABASE_PASSWORD`.
   Con esos datos arma la cadena para `psql` (quitando el `jdbc:` y poniendo usuario y clave):
   `postgresql://USUARIO:CLAVE@HOST:PUERTO/BASE?sslmode=require`
   (si la clave tiene caracteres como `@` o `#`, cópiala desde el panel del proveedor, que la da ya armada).
2. Desde esta carpeta, con el `psql` de tu PostgreSQL 16:

   ```bash
   "C:/Program Files/PostgreSQL/16/bin/psql.exe" "postgresql://USUARIO:CLAVE@HOST:PUERTO/BASE?sslmode=require" -v ON_ERROR_STOP=1 -f deploy/demo/cigarreria-el-paisa.sql
   ```

   Crea el esquema `pos` con todo adentro y no toca nada de `public`. Si dice que el esquema `pos` ya existe,
   para y avisa: alguien ya cargó la muestra.
3. Comprobación (SQL Editor del proveedor): `select name from pos.tenants;` muestra "Cigarrería El Paisa".

## 3. Render (la app)

1. dashboard.render.com → **New** → **Blueprint** → elige el repositorio `Ecomerxio-POS`.
2. Render lee `render.yaml` y pide tres valores; son los mismos de `ecomerxio-api` con un cambio:
   - `DATABASE_URL`: la de `ecomerxio-api` **agregando al final** `&currentSchema=pos`
     (o `?currentSchema=pos` si no tenía ningún `?`). Ejemplo:
     `jdbc:postgresql://HOST:5432/postgres?sslmode=require&currentSchema=pos`
   - `DATABASE_USERNAME` y `DATABASE_PASSWORD`: iguales a los de `ecomerxio-api`.
3. **Apply**. El primer despliegue tarda unos 10 minutos. Al arrancar, la app pone su esquema al día sola
   (migración V11: la Cigarrería queda en el plan Pro).
4. Prueba en `https://ecomerxio-pos.onrender.com`: debe abrir el inicio de sesión (la app sola).
5. Con el servicio en Live, publica Ecomerxio (push de `ecommerce-platform`): `https://www.ecomerxio.com/pos` debe
   abrir el mismo inicio de sesión dentro de Ecomerxio.

`JWT_SECRET` lo genera Render. La clave de Claude (`ANTHROPIC_API_KEY`) se agrega después si quieres el asistente.

## 4. Dominio `pos.ecomerxio.com` (opcional)

Solo si además quieres la app sola en su propio dominio; ecomerxio.com/pos funciona sin esto.

1. En Render, en `ecomerxio-pos` → **Settings** → **Custom Domains** → `pos.ecomerxio.com`.
2. En el DNS de ecomerxio.com crea el registro que Render indique: **CNAME** `pos` → `ecomerxio-pos.onrender.com`
   (si es Cloudflare, con la nube en gris "solo DNS" hasta que Render emita el certificado).
3. Cuando Render marque el dominio como verificado, abre `https://pos.ecomerxio.com`.

## 5. Entrar a la tienda de muestra

Las cuentas de la Cigarrería son las de `docs/DEV.md` (dueño y cajero, con el PIN del dueño para autorizaciones).
Como la muestra es pública, cualquiera con esas claves entra como dueño: no guardes ahí nada real.

## Notas

- Conexiones: las dos apps comparten el límite de conexiones de la base. `ecomerxio-api` usa hasta 8 y
  `ecomerxio-pos` hasta 4 (`DB_POOL_MAX_SIZE`).
- Copias de seguridad: las del proveedor de la base cubren los dos esquemas.
- Volver a dejar la muestra como estaba: `DROP SCHEMA pos CASCADE;` (solo borra Ecomerxio POS), repetir el paso 2.2
  y en Render **Manual Deploy** → **Restart**.
