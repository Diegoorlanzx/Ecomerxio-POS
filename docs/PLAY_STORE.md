# Publicar TiendaPOSS en Google Play (Trusted Web Activity)

La app de Android es la misma PWA abierta a pantalla completa con Chrome (TWA): no hay un segundo código
que mantener. Cada vez que se despliega una versión nueva del servidor, la app de Play Store la usa al instante.

## Requisitos
1. TiendaPOSS desplegado con HTTPS en un dominio propio (ver `docs/DESPLIEGUE.md`), p. ej. `app.tudominio.com`.
2. Cuenta de desarrollador de Google Play (pago único de USD 25).
3. En el equipo que empaqueta: Node 18+ y JDK 17 (Bubblewrap descarga el SDK de Android la primera vez).

## Pasos
1. Instala Bubblewrap: `npm i -g @bubblewrap/cli`
2. Copia `android/twa-manifest.json` a una carpeta de trabajo y reemplaza `DOMINIO` por tu dominio.
   (O genera uno nuevo con `bubblewrap init --manifest https://app.tudominio.com/manifest.webmanifest`.)
3. En esa carpeta: `bubblewrap build`. La primera vez crea la llave de firma `tiendaposs-upload.keystore`:
   **guárdala junto con su contraseña en un lugar seguro**; sin ella no se pueden publicar actualizaciones.
4. Saca la huella de la llave: `keytool -list -v -keystore tiendaposs-upload.keystore -alias tiendaposs`
   y copia el valor `SHA256`.
5. En el servidor pon en `.env`:
   - `APP_ANDROID_PACKAGE=com.tiendaposs.app`
   - `APP_ANDROID_SHA256=<huella de tu llave>,<huella de la llave de firma de Play>`
   (la segunda aparece en Play Console → Integridad de la app → Firma de apps, después de subir la primera versión)
   y reinicia: `docker compose up -d`. Comprueba `https://app.tudominio.com/.well-known/assetlinks.json`.
6. En Play Console crea la app, sube `app-release-bundle.aab`, completa la ficha (capturas, descripción,
   política de privacidad, clasificación de contenido) y envíala a revisión (prueba interna primero).

Si `assetlinks.json` no coincide con la llave, Android muestra la barra del navegador arriba: revisa las huellas.

## Versiones nuevas de la app de Android
Solo hacen falta si cambia el ícono, el nombre o los accesos directos: sube `appVersionCode` en
`twa-manifest.json`, `bubblewrap update` y `bubblewrap build`. Los cambios de pantallas o funciones llegan solos
con cada despliegue del servidor.
