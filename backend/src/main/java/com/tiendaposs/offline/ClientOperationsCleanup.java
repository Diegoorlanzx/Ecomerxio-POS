package com.tiendaposs.offline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Las llaves de operaciones se guardan 60 días (de sobra para una caja que estuvo sin internet) y luego se borran. */
@Component
public class ClientOperationsCleanup {

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public ClientOperationsCleanup(JdbcTemplate jdbc, @Value("${app.scheduler.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "America/Bogota")
    public void purge() {
        if (enabled) purgeOlderThan(60);
    }

    public int purgeOlderThan(int days) {
        return jdbc.update("DELETE FROM client_operations WHERE created_at < now() - make_interval(days => ?)", days);
    }
}
