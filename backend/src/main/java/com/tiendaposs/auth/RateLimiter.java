package com.tiendaposs.auth;

import com.tiendaposs.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite simple en memoria (ventana fija), sin servicios externos. Lo usan las rutas públicas de autenticación (por IP),
 * el filtro general de la API ({@code web.RequestGuardFilter}) y el bloqueo por intentos fallidos de PIN y contraseña.
 */
@Component
public class RateLimiter {

    private record Window(long start, long millis, int count) {
        boolean expired(long now) {
            return now - start > millis;
        }
    }

    private static final int MAX_KEYS = 200_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int max;
    private final long windowMillis;
    private volatile long lastPrune;

    public RateLimiter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                       @Value("${app.rate-limit.max-requests:20}") int max,
                       @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {
        this.enabled = enabled;
        this.max = max;
        this.windowMillis = windowSeconds * 1000L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void check(String bucket, String ip) {
        check(bucket, ip, max, windowMillis / 1000);
    }

    /** Como {@link #check(String, String)} pero con su propio tope y ventana (p. ej. 5 registros por hora). */
    public void check(String bucket, String key, int max, long windowSeconds) {
        if (!enabled) return;
        if (!tryAcquire(bucket, key, max, windowSeconds)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera un momento e intenta de nuevo.");
        }
    }

    /** Suma una petición y dice si todavía está dentro del tope. No mira el interruptor {@code enabled}. */
    public boolean tryAcquire(String bucket, String key, int max, long windowSeconds) {
        return hit(bucket, key, windowSeconds) <= max;
    }

    /** Suma un evento (p. ej. un PIN equivocado) y devuelve cuántos van en la ventana. */
    public int hit(String bucket, String key, long windowSeconds) {
        long now = System.currentTimeMillis();
        long millis = windowSeconds * 1000L;
        Window w = windows.compute(bucket + "|" + key, (k, old) ->
                old == null || old.expired(now) ? new Window(now, millis, 1) : new Window(old.start(), old.millis(), old.count() + 1));
        prune(now);
        return w.count();
    }

    /** Cuántos eventos van en la ventana vigente, sin sumar. */
    public int count(String bucket, String key) {
        Window w = windows.get(bucket + "|" + key);
        return w == null || w.expired(System.currentTimeMillis()) ? 0 : w.count();
    }

    public void reset(String bucket, String key) {
        windows.remove(bucket + "|" + key);
    }

    private void prune(long now) {
        // Solo se descartan ventanas vencidas: vaciar todo reiniciaría los contadores de quien esté atacando.
        // Como mucho una limpieza por segundo: recorrer el mapa en cada petición sería otro punto de ataque.
        if (windows.size() > MAX_KEYS / 4 && now - lastPrune > 1000) {
            lastPrune = now;
            windows.values().removeIf(old -> old.expired(now));
        }
        // Ataque con muchísimas IPs distintas: se sacrifican los contadores antes que la memoria del servidor.
        if (windows.size() > MAX_KEYS) windows.clear();
    }
}
