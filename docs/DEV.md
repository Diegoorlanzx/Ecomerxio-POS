# Desarrollo local de TiendaPOSS

Puertos (no chocan con los otros proyectos del equipo):
- PostgreSQL de desarrollo: **55434** (55432 = Ecomerxio, 55433 = Firulitos, 5432 = PostgreSQL propio: no tocar)
- Backend (Spring Boot): **8090**
- Frontend (Vite): **5190** — en la app de escritorio: configuración `tiendaposs` de `Documents/claude/.claude/launch.json`

## Arrancar
```bash
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-3.9.11-bin/*/apache-maven-3.9.11/bin/mvn)
cd backend
"$MVN" -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" com.tiendaposs.DevDbRunner      # base de datos
java -cp "target/classes;$(cat target/cp.txt)" com.tiendaposs.TiendaPossApplication                # API
cd ../frontend && npm install && npm run dev
```

## Pruebas
```bash
cd backend && "$MVN" test
```

## Cuentas de prueba (solo en la base de desarrollo local)
| Tienda | Rol | Correo | Contraseña |
|---|---|---|---|
| Cigarrería El Paisa | Dueño | dueno@demo.tiendaposs.test | Demo2026pos |
| Cigarrería El Paisa | Cajero | cajero@demo.tiendaposs.test | Demo2026pos |

PIN de autorización del dueño de prueba: **4826** (para descuentos altos, anulaciones, devoluciones y ajustes de caja).

Sin servidor de correo configurado, los enlaces de "olvidé mi contraseña" se escriben en el log del backend.

## Probar la venta sin internet
Entra a /pos con el servidor encendido (guarda el catálogo en el equipo), apaga el backend y recarga: la caja
sigue vendiendo y guarda las ventas en IndexedDB. Al volver el servidor se envían solas (reintento cada 20 s).
La versión instalable (service worker) solo se activa con `npm run build && npx vite preview` (puerto 5191).

## Íconos
La fuente Material Symbols (4 MB) se recorta a los íconos usados (`npm run icons`, ~65 KB).
En `npm run dev` se regenera sola al guardar un .tsx; si un ícono nuevo sale como texto, recarga sin caché.

## Fase 3: pagos, fiado y finanzas (datos de la tienda demo)
- Pagos digitales en `/pagos`: Nequi/Daviplata/Bre-B/transferencia quedan **por verificar** hasta confirmarlos,
  rechazarlos o conciliarlos con el extracto CSV del banco. La cuenta "Nequi Cigarrería El Paisa" tiene un QR de
  muestra (ilustrativo, no es un QR de pago real).
- Fiado activo (cupo 150.000, plazo 15 días). Clientas con saldo: Doña Rosa Ramírez y Jhon Fredy Ospina (`/fiados`).
- Webhook de confirmación automática: `POST /api/webhooks/payments/{llave pública}` con cabecera
  `X-Signature` = HMAC-SHA256 (hex) del cuerpo con la llave secreta. Se crea en Pagos → Cuentas QR.
- Finanzas en `/finanzas`: resumen, gastos e ingresos (también los de la caja), por pagar a proveedores y mensual.

## Fase 4: dashboard, analítica y rentabilidad
- `/dashboard` (DASHBOARD_VER): ventas de hoy/semana/quincena/mes contra el período anterior, utilidad, caja,
  pagos digitales, curva por hora, atención inmediata, más vendidos y baja rotación.
- `/analitica` (REPORTES_VER): informe por día/semana/quincena/mes/rango con comparación, series por día, hora,
  día de la semana y 12 meses, desglose por producto/categoría/marca/empleado/turno/medio, productos en caída.
- `/rentabilidad` (COSTOS_VER): margen y utilidad por producto, impacto de descuentos, gastos repartidos,
  clasificación (estrella, vende mucho y deja poco, nicho rentable, para revisar) y evolución de precio/costo.
- La tienda demo tiene ~410 ventas de los últimos 60 días (cargadas como ventas "sin internet" con fecha pasada).

## Fase 5: notificaciones, configuración, copias y exportación
- `/notificaciones`: bandeja (cada usuario ve lo de su rol), alertas activas y "Umbrales y canales" (17 tipos de aviso
  con umbral y canales). El servidor revisa cada 5 minutos y cuando alguien abre la campana (si pasaron 2 min).
  Reportes: diario a la hora configurada, semanal (lunes) y mensual (día 1), solo si hubo ventas.
- Canales externos: correo (requiere `spring.mail.*`; sin SMTP queda en el log), webhook https firmado con
  HMAC-SHA256 en `X-Signature`, WhatsApp Cloud API (ID del número + token de Meta). Cada envío queda registrado.
- `/respaldos` (CONFIG_EDITAR): copia automática 3:30 a. m. (últimas 7) y manual, descarga `.tpbak` (JSON gzip),
  restauración solo del dueño con contraseña + "RESTAURAR" (guarda antes una copia "Antes de restaurar"),
  exportación ZIP de CSV y sucursales. Los programadores se apagan en pruebas con `app.scheduler.enabled=false`.
