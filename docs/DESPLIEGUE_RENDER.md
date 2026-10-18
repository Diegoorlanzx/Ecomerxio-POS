# Publicar Ecomerxio POS con la tienda de muestra (Render + base propia en Neon o Supabase)

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
- **Base de datos:** una **nueva y propia** para el POS (proyecto aparte en Neon o Supabase), separada de la de
  Ecomerxio. Las dos apps no comparten datos.

Archivos de este repositorio que se usan:

- `render.yaml`: el servicio web.
- `deploy/demo/cigarreria-el-paisa.sql`: la base local con **solo** la Cigarrería (11 productos, 443 ventas,
  turnos, fiados y sus 2 usuarios). No lleva claves de pago, tokens ni enlaces de
  recuperación.

El orden importa: **la tienda se carga antes de arrancar la app por primera vez**.

## 1. GitHub

Hecho: el código está en `https://github.com/Diegoorlanzx/Ecomerxio-POS` (privado, rama `main`).

## 2. Crear la base nueva y cargar la tienda

1. Crea un proyecto **nuevo** (no uses el de Ecomerxio):
   - **Neon:** console.neon.tech → **New project** → nombre `ecomerxio-pos`, región **AWS US East (N. Virginia)**.
     En **Connect** copia la cadena `postgresql://usuario:clave@ep-xxx.us-east-1.aws.neon.tech/neondb?sslmode=require`.
   - **Supabase:** supabase.com → **New project** → nombre `ecomerxio-pos`, región **East US (North Virginia)**, anota
     la clave de la base. En **Connect** elige **Session pooler** (puerto **5432**; la conexión directa es solo IPv6 y
     el modo Transaction, puerto 6543, no sirve) y copia la cadena
     `postgresql://postgres.<ref>:clave@aws-0-us-east-1.pooler.supabase.com:5432/postgres`.
2. Desde esta carpeta, con el `psql` de tu PostgreSQL 16 (agrega `?sslmode=require` al final si la cadena no lo trae):

   ```bash
   "C:/Program Files/PostgreSQL/16/bin/psql.exe" "postgresql://USUARIO:CLAVE@HOST:PUERTO/BASE?sslmode=require" -v ON_ERROR_STOP=1 -f deploy/demo/cigarreria-el-paisa.sql
   ```

   Muestra varias líneas `setval` y termina **sin la palabra ERROR**. Debe hacerse con la base **vacía** y **antes**
   de arrancar la app.
3. Comprobación (SQL Editor del proveedor): `select name from tenants;` muestra "Cigarrería El Paisa".

## 3. Render (la app)

1. dashboard.render.com → **New** → **Blueprint** → elige el repositorio `Ecomerxio-POS`.
2. Render lee `render.yaml` y pide tres valores, que salen de la cadena del paso 2:
   - `DATABASE_URL`: `jdbc:postgresql://HOST:PUERTO/BASE?sslmode=require` (empieza por `jdbc:` y **sin** usuario ni clave).
     Neon: `jdbc:postgresql://ep-xxx.us-east-1.aws.neon.tech/neondb?sslmode=require`.
     Supabase: `jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require`.
   - `DATABASE_USERNAME`: el usuario (en Supabase, `postgres.<ref>`).
   - `DATABASE_PASSWORD`: la clave.
3. **Apply**. El primer despliegue tarda unos 10 minutos. Al arrancar, la app pone su esquema al día sola
   (migración V11: la Cigarrería queda en el plan Pro).
4. Prueba en `https://ecomerxio-pos.onrender.com`: debe abrir el inicio de sesión (la app sola).
5. Con el servicio en Live, publica Ecomerxio (push de `ecommerce-platform`): `https://www.ecomerxio.com/pos` debe
   abrir el mismo inicio de sesión dentro de Ecomerxio.

`JWT_SECRET` lo genera Render. El **asistente con IA está apagado** (`ASSISTANT_ENABLED`, por defecto `false`): no aparece
en la app y su API responde 404. Para encenderlo más adelante: `ASSISTANT_ENABLED=true` y `ANTHROPIC_API_KEY` con tu clave.
Las recomendaciones de precio no usan IA y siguen activas.

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

- Conexiones: el POS usa hasta 8 (`DB_POOL_MAX_SIZE`), dentro de lo que dan los planes gratis de Neon y Supabase.
- Copias de seguridad: las del proveedor de la base.
- Volver a dejar la muestra como estaba: en el SQL Editor `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`
  (borra todo el POS; la base es solo suya), repetir el paso 2.2 y en Render **Manual Deploy** → **Restart**.
