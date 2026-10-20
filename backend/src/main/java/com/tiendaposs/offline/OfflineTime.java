package com.tiendaposs.offline;

import java.time.Duration;
import java.time.Instant;

/**
 * Hora real de algo hecho sin internet. Se acepta la hora del equipo si es del pasado y de los últimos 60 días (un
 * reloj adelantado o una fecha absurda no se aceptan: se usa la hora del servidor).
 */
public final class OfflineTime {

    private static final Duration MAX_AGE = Duration.ofDays(60);

    private OfflineTime() {
    }

    public static Instant when(Instant offlineAt) {
        Instant now = Instant.now();
        if (offlineAt == null || offlineAt.isAfter(now) || offlineAt.isBefore(now.minus(MAX_AGE))) return now;
        return offlineAt;
    }
}
