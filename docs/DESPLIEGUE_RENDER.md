# Publicar Ecomerxio POS (API en Render, frontend en Vercel, base en Supabase)

Resultado: **`https://pos.ecomerxio.com`** (la app sola, instalable y que abre sin internet) y la misma app dentro de
**`https://www.ecomerxio.com/pos`** (microfrontend), con la tienda de muestra **Cigarrería El Paisa**.

## Cómo queda armado

| Pieza | Dónde | Archivo |
|---|---|---|
| API (Spring Boot) | Render, servicio `ecomerxio-pos`, plan Starter, Virginia | `backend/Dockerfile`, `render.yaml` |
| Frontend (React) | Vercel, proyecto nuevo con carpeta raíz `frontend`, dominio `pos.ecomerxio.com` | `frontend/vercel.json` |
| Base de datos | Supabase, proyecto propio del POS (no la de Ecomerxio) | `deploy/demo/cigarreria-el-paisa.sql` |

- El frontend en Vercel reenvía `/api/*` a Render: para el navegador todo sale de `pos.ecomerxio.com`.
- Ecomerxio (`ecommerce-platform/frontend/vercel.json`) reenvía `ecomerxio.com/pos-app/api/*` a Render y
  `ecomerxio.com/pos-app/*` a `pos.ecomerxio.com`. Su página `/pos` carga `/pos-app/remoteEntry.js` (Module
  Federation) y pinta el POS dentro de un Shadow DOM. **Si Render le pone otro nombre al servicio, cambia
  `ecomerxio-pos.onrender.com` en los dos `vercel.json`.**
- Incrustada en ecomerxio.com no se instala ni abre sin internet; eso lo hace `pos.ecomerxio.com`.
- `deploy/Dockerfile.todo-en-uno` + `docker-compose.yml` siguen sirviendo para un VPS (API y web juntas).

El orden importa: **la tienda se carga en la base antes de arrancar la API por primera vez**.

## 1. Base de datos (Supabase)

1. supabase.com → **New project** `ecomerxio-pos`, región **East US (North Virginia)**; anota la clave de la base.
2. **Connect** → **Session pooler** (puerto **5432**; no la directa, que es solo IPv6, ni Transaction, puerto 6543).
3. Desde la carpeta `tiendaposs`, con el `psql` de tu PostgreSQL 16 (cambia `TU_CLAVE`):

   ```bash
   "C:/Program Files/PostgreSQL/16/bin/psql.exe" "postgresql://postgres.<ref>:TU_CLAVE@aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require" -v ON_ERROR_STOP=1 -f deploy/demo/cigarreria-el-paisa.sql
   ```

   Termina **sin la palabra ERROR**. Comprobación en el SQL Editor: `select name from tenants;` → "Cigarrería El Paisa".

## 2. API en Render

1. **New** → **Web Service** → repositorio `Ecomerxio-POS` (o **New → Blueprint**, que lee `render.yaml`).
2. Si es Web Service a mano:
   - **Name:** `ecomerxio-pos` · **Region:** Virginia · **Instance:** Starter
   - **Language:** Docker · **Root Directory:** `backend` · **Dockerfile Path:** `./Dockerfile`
   - **Health Check Path:** `/api/health`
3. **Environment Variables** → **Add from .env** → pega el archivo local `.env.render` (no está en Git; completa
   antes `DATABASE_PASSWORD`). Variables:

   | Variable | Valor |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DATABASE_URL` | `jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require` |
   | `DATABASE_USERNAME` | `postgres.<ref>` |
   | `DATABASE_PASSWORD` | la clave de la base |
   | `DB_POOL_MAX_SIZE` | `8` |
   | `JWT_SECRET` | aleatorio de 32+ caracteres (viene en `.env.render`) |
   | `FRONTEND_URL` | `https://pos.ecomerxio.com` |
   | `CORS_ALLOWED_ORIGINS` | `https://pos.ecomerxio.com,https://www.ecomerxio.com,https://ecomerxio.com` |
   | `ASSISTANT_ENABLED` | `false` |

4. Al arrancar, Flyway pone la base al día sola (V11: la Cigarrería queda en plan Pro).
   Prueba: `https://ecomerxio-pos.onrender.com/api/health` responde.

## 3. Frontend en Vercel

1. vercel.com → **Add New** → **Project** → importa `Ecomerxio-POS`.
2. **Root Directory:** `frontend`. Vercel lee `frontend/vercel.json` (instala con `npm ci`, compila con
   `npm run build:ci`, que usa el `src/assets/icons.woff2` guardado en el repositorio).
3. **Deploy**. Prueba en la dirección `.vercel.app` que te da: debe abrir el inicio de sesión.
4. **Settings → Domains** → agrega `pos.ecomerxio.com`. En Namecheap (ecomerxio.com → **Advanced DNS** →
   **Add New Record**) crea el registro que Vercel indique, normalmente **CNAME** `pos` → `cname.vercel-dns.com`.

Íconos nuevos: si agregas un ícono a la app, corre `npm run icons` en tu equipo y sube el `icons.woff2` que cambia.

## 4. Ecomerxio

Con `pos.ecomerxio.com` y la API funcionando, publica `ecommerce-platform` (push a `main`): `www.ecomerxio.com/pos`
abre el mismo inicio de sesión dentro de Ecomerxio.

## 5. Entrar a la tienda de muestra

Las cuentas de la Cigarrería son las de `docs/DEV.md` (dueño y cajero, con el PIN del dueño). Como la muestra es
pública, cualquiera con esas claves entra como dueño: no guardes ahí nada real.

## Notas

- El asistente con IA está apagado (`ASSISTANT_ENABLED=false`). Para encenderlo: `ASSISTANT_ENABLED=true` y
  `ANTHROPIC_API_KEY`. Las recomendaciones de precio no usan IA y siguen activas.
- Correo (recuperar contraseña): `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` (puede ser la
  misma cuenta de Brevo de Ecomerxio).
- Si la API arrancó antes de cargar la tienda: en el SQL Editor `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`,
  repite el paso 1.3 y en Render **Manual Deploy → Restart**.
