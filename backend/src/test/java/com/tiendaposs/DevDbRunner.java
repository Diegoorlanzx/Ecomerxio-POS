package com.tiendaposs;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.Path;

/**
 * Solo desarrollo: PostgreSQL embebido persistente en el puerto 55434 para correr el backend
 * real en local sin Docker. (55432 Ecomerxio, 55433 Firulitos y 5432 es el PostgreSQL propio del equipo.)
 */
public class DevDbRunner {
    public static void main(String[] args) throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(55434)
                .setDataDirectory(Path.of(System.getProperty("java.io.tmpdir"), "tiendaposs-dev-pg"))
                .setCleanDataDirectory(false)
                .start();
        System.out.println("READY on port " + pg.getPort());
        Thread.currentThread().join();
    }
}
