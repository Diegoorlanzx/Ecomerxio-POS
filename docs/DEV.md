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
