package com.tiendaposs.auth;

import com.tiendaposs.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** Límite simple en memoria por IP para las rutas públicas de autenticación (ventana fija). */
@Component
public class RateLimiter {

    private record Window(long start, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int max;
    private final long windowMillis;

    public RateLimiter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                       @Value("${app.rate-limit.max-requests:20}") int max,
                       @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {
        this.enabled = enabled;
        this.max = max;
        this.windowMillis = windowSeconds * 1000L;
    }

    public void check(String bucket, String ip) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        Window w = windows.compute(bucket + "|" + ip, (k, old) ->
                old == null || now - old.start() > windowMillis ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        if (w.count() > max) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera un minuto e intenta de nuevo.");
        }
        // Solo se descartan ventanas vencidas: vaciar todo reiniciaría los contadores de quien esté atacando.
        if (windows.size() > 50_000) windows.values().removeIf(old -> now - old.start() > windowMillis);
    }
}
