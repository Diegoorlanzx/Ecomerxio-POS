package com.tiendaposs.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Al arrancar con R2 encendido, pasa a R2 las imágenes que siguen dentro de la base (las de antes de encenderlo y las
 * que traiga una copia de seguridad restaurada). Corre en segundo plano, por tandas, y se puede repetir sin daño:
 * solo toca filas que aún tienen una data URL.
 */
@Component
public class MediaMigration {

    private static final Logger log = LoggerFactory.getLogger(MediaMigration.class);
    private static final int BATCH = 50;

    /** Tabla, columna con la imagen, columna del negocio, carpeta en R2 y si tiene updated_at (para que el POS la vuelva a bajar). */
    private record Target(String table, String column, String tenantColumn, String folder, boolean touch) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("products", "photo", "tenant_id", "productos", true),
            new Target("tenants", "logo", "id", "logo", true),
            new Target("payment_accounts", "qr_image", "tenant_id", "qr", false));

    private final MediaStorage media;
    private final JdbcTemplate jdbc;

    public MediaMigration(MediaStorage media, JdbcTemplate jdbc) {
        this.media = media;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!media.enabled()) return;
        Thread t = new Thread(this::run, "media-migration");
        t.setDaemon(true);
        t.start();
    }

    void run() {
        for (Target t : TARGETS) {
            int moved = 0;
            try {
                while (true) {
                    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, " + t.tenantColumn() + " AS tenant, " + t.column() + " AS img FROM "
                            + t.table() + " WHERE " + t.column() + " LIKE 'data:%' ORDER BY id LIMIT " + BATCH);
                    if (rows.isEmpty()) break;
                    for (Map<String, Object> r : rows) {
                        String img = (String) r.get("img");
                        String url = media.save(((Number) r.get("tenant")).longValue(), t.folder(), img);
                        // Si alguien cambió la imagen mientras tanto, gana su cambio.
                        jdbc.update("UPDATE " + t.table() + " SET " + t.column() + " = ?" + (t.touch() ? ", updated_at = now()" : "")
                                + " WHERE id = ? AND " + t.column() + " = ?", url, r.get("id"), img);
                        moved++;
                    }
                }
                if (moved > 0) log.info("Imágenes pasadas a R2 en {}: {}", t.table(), moved);
            } catch (Exception e) {
                log.warn("No se pudieron pasar a R2 las imágenes de {} ({} pasadas); se reintenta en el próximo arranque", t.table(), moved, e);
            }
        }
    }
}
