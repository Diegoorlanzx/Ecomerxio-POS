# Publicar Ecomerxio POS con la tienda de muestra (Render + Neon)

Resultado: `https://pos.ecomerxio.com` con la tienda **Cigarrería El Paisa** lista para mostrar, y los botones
"Empieza gratis" de ecomerxio.com funcionando.

Archivos de este repositorio que se usan:

- `render.yaml`: el servicio web (Docker, plan gratis, chequeo en `/api/health`).
- `deploy/demo/cigarreria-el-paisa.sql`: copia de la base local con **solo** la Cigarrería (11 productos,
  443 ventas, turnos, fiados y sus 2 usuarios). No lleva claves de pago, tokens ni enlaces de recuperación.

El orden importa: **la base se carga antes de arrancar la app por primera vez**. Si la app arranca con la base
vacía, crea sus tablas y la copia ya no entra.

## 1. GitHub

1. En github.com crea un repositorio **privado** llamado `tiendaposs` (sin README ni licencia).
2. Pásale la dirección a Claude para subir el código, o desde esta carpeta:

   ```bash
   git remote add origin https://github.com/<tu-usuario>/tiendaposs.git
   git push -u origin main
   ```

## 2. Neon (base de datos)

1. En console.neon.tech → **New project** → nombre `ecomerxio-pos`, región **AWS US West 2 (Oregon)**, la misma de
   Render en `render.yaml`; versión de Postgres la que ofrezca.
2. En **Connection details** copia la cadena de conexión (`postgresql://usuario:clave@ep-xxx.neon.tech/neondb?sslmode=require`).
3. Carga la tienda de muestra desde esta carpeta (usa el `psql` de tu PostgreSQL 16):

   ```bash
   "C:/Program Files/PostgreSQL/16/bin/psql.exe" "postgresql://usuario:clave@ep-xxx.neon.tech/neondb?sslmode=require" -v ON_ERROR_STOP=1 -f deploy/demo/cigarreria-el-paisa.sql
   ```

   Debe terminar sin errores. Comprobación: en el SQL Editor de Neon, `select name from tenants;` muestra
   "Cigarrería El Paisa".

## 3. Render (la app)

1. dashboard.render.com → **New** → **Blueprint** → elige el repositorio `tiendaposs`.
2. Render lee `render.yaml` y pide tres valores, que salen de la cadena de Neon:
   - `DATABASE_URL`: `jdbc:postgresql://ep-xxx.neon.tech/neondb?sslmode=require` (empieza por `jdbc:` y **sin** usuario ni clave).
   - `DATABASE_USERNAME`: el usuario.
   - `DATABASE_PASSWORD`: la clave.
3. **Apply**. El primer despliegue tarda unos 10 minutos (arma la app web y el servidor). Al arrancar, la app
   pone la base al día sola (migración V11: la Cigarrería queda en el plan Pro).
4. Prueba en la dirección que da Render (`https://ecomerxio-pos.onrender.com`): debe abrir el inicio de sesión.

`JWT_SECRET` lo genera Render solo. No hace falta ninguna otra clave para la muestra; la de Claude
(`ANTHROPIC_API_KEY`) se agrega después si quieres el asistente con IA.

## 4. Dominio `pos.ecomerxio.com`

1. En Render, dentro del servicio → **Settings** → **Custom Domains** → `pos.ecomerxio.com`.
2. En el DNS de ecomerxio.com crea el registro que Render indique: **CNAME** `pos` → `ecomerxio-pos.onrender.com`
   (si es Cloudflare, con la nube en gris "solo DNS" hasta que Render emita el certificado).
3. Cuando Render marque el dominio como verificado, abre `https://pos.ecomerxio.com`.

## 5. Entrar a la tienda de muestra

Las cuentas de la Cigarrería son las de `docs/DEV.md` (dueño y cajero, con el PIN del dueño para autorizaciones).
Como la muestra es pública, cualquiera con esas claves entra como dueño: no guardes ahí nada real.

## Notas

- Plan gratis de Render: después de 15 minutos sin visitas la app se duerme y la primera carga tarda 30-60 s.
  Para una demo en vivo, ábrela un minuto antes.
- Plan gratis de Neon: 0,5 GB, de sobra para la muestra.
- Volver a dejar la muestra como estaba: en Neon borra y crea la base de nuevo, repite el paso 2.3 y en Render
  **Manual Deploy** → **Restart**.
