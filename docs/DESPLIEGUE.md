# Desplegar TiendaPOSS

TiendaPOSS se publica como **un solo servicio**: el servidor (Java) entrega la API y también la app web instalable
(PWA). Se necesita una base de datos PostgreSQL y un dominio con HTTPS (obligatorio para instalar la app, para el
modo sin internet y para la app de Play Store).

## Opción recomendada: un servidor (VPS) con Docker
Sirve un VPS de 2 GB de RAM (DigitalOcean, Hetzner, Contabo, AWS Lightsail…) con Ubuntu y Docker.

1. Apunta el dominio (p. ej. `app.tudominio.com`) a la IP del servidor (registro DNS tipo A).
2. Copia el proyecto al servidor (`git clone …`) y entra a la carpeta.
3. `cp .env.example .env` y completa:
   - `DOMAIN` (sin https://), `DB_PASSWORD` y `JWT_SECRET` (genéralos con `openssl rand -base64 48`).
   - Opcionales: correo saliente (`MAIL_*`), `ANTHROPIC_API_KEY` (asistente IA), `APP_ANDROID_*` (Play Store).
4. `docker compose up -d --build`
   - `db`: PostgreSQL 16 con sus datos en un volumen.
   - `app`: TiendaPOSS (aplica las migraciones de la base al arrancar).
   - `caddy`: HTTPS automático con Let's Encrypt.
   - `backup`: copia completa de la base cada día en `./backups` (se guardan 14 días).
5. Abre `https://app.tudominio.com` y crea la primera tienda en "Crear cuenta".

Actualizar a una versión nueva: `git pull && docker compose up -d --build`. Las migraciones corren solas y la app
web se actualiza en los equipos al abrirla (el service worker trae la versión nueva).

## Sin Docker (cualquier servidor con Java 21)
```bash
cd frontend && npm ci && npm run build
cd ../backend && mvn -Pprod -DskipTests package
SPRING_PROFILES_ACTIVE=prod PORT=8080 \
DATABASE_URL=jdbc:postgresql://HOST:5432/tiendaposs DATABASE_USERNAME=… DATABASE_PASSWORD=… \
JWT_SECRET=… FRONTEND_URL=https://app.tudominio.com \
java -jar target/tiendaposs-backend-0.1.0.jar
```
Ponlo detrás de un proxy con HTTPS (Caddy o Nginx) que reenvíe a `localhost:8080`.

## Variables de entorno
| Variable | Obligatoria | Para qué |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | sí | Modo producción (exige secretos reales, no arranca con valores de prueba) |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | sí | PostgreSQL 14 o superior |
| `JWT_SECRET` | sí | Firma de las sesiones; 32+ caracteres aleatorios. Cambiarlo cierra todas las sesiones |
| `FRONTEND_URL` | sí | Dirección pública con `https://` (enlaces de correos y CORS) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | no | Recuperar contraseña y notificaciones por correo |
| `ANTHROPIC_API_KEY` | no | Asistente con IA (sin ella funcionan las consultas rápidas) |
| `APP_ANDROID_PACKAGE`, `APP_ANDROID_SHA256` | no | Vincular la app de Play Store (ver `PLAY_STORE.md`) |

## Copias de seguridad
- **Por tienda**, dentro de la app: automática cada noche (se guardan 7) y manual; se descargan y se restauran
  desde Más → Copias de seguridad.
- **De todo el servidor**: el servicio `backup` de docker-compose (`pg_dump` diario en `./backups`). Copia esa
  carpeta fuera del servidor (otro disco o almacenamiento en la nube). Restaurar:
  `docker compose exec -T db pg_restore -U tiendaposs -d tiendaposs --clean < backups/tiendaposs_AAAA-MM-DD.dump`.

## Lista antes de abrir al público
- [ ] HTTPS funcionando y `FRONTEND_URL` con el dominio final.
- [ ] `JWT_SECRET` y `DB_PASSWORD` generados al azar y guardados en un lugar seguro.
- [ ] Correo saliente configurado (para recuperar contraseñas).
- [ ] La carpeta `backups` se copia fuera del servidor.
- [ ] Texto de política de privacidad / tratamiento de datos (Habeas Data, Ley 1581 de 2012) publicado.
- [ ] Probar instalar la app en un Android y un iPhone, y vender sin internet.
