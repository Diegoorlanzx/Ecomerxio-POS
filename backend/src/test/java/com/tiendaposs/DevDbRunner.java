package com.tiendaposs;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.Path;

/**
 * Solo desarrollo: PostgreSQL embebido persistente en el puerto 55434 para correr el backend
 * real en local sin Docker. (55432 Ecomerxio, 55433 Firulitos y 5432 es el PostgreSQL propio del equipo.)
 */
public class DevDbRunner {
    public static void main(String[] args) throws Exception {
        // TP_DB_PORT / TP_DB_DIR permiten una base aparte (p. ej. pruebas de punta a punta) sin tocar la de siempre.
        // Ojo: la carpeta por defecto está en la carpeta temporal y Windows puede borrar archivos de ahí;
        // para datos que se quieran conservar, usar TP_DB_DIR fuera de Temp.
        int port = Integer.parseInt(System.getenv().getOrDefault("TP_DB_PORT", "55434"));
        String dir = System.getenv("TP_DB_DIR");
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(dir != null && !dir.isBlank() ? Path.of(dir) : Path.of(System.getProperty("java.io.tmpdir"), "tiendaposs-dev-pg"))
                .setCleanDataDirectory(false)
                .start();
        System.out.println("READY on port " + pg.getPort());
        Thread.currentThread().join();
    }
}
