package com.tiendaposs.web;

import com.tiendaposs.auth.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Primera barrera de la API contra avalanchas de peticiones, antes de tocar la base o revisar el token:
 * <ul>
 *   <li>tope de peticiones por IP y minuto (más estricto en las rutas sin sesión);</li>
 *   <li>tope de tamaño del cuerpo, también cuando llega por partes (sin Content-Length).</li>
 * </ul>
 * Va en memoria, sin servicios pagos. En producción la IP es la real del cliente (RemoteIpValve, ver application-prod.yml).
 * Las direcciones IPv6 se agrupan por su /64: un atacante suele tener millones de direcciones dentro del mismo bloque.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestGuardFilter extends OncePerRequestFilter {

    private static final String BACKUP_UPLOAD = "/api/backups/restore-file";

    private final RateLimiter limiter;
    private final int perMinute;
    private final int publicPerMinute;
    private final long maxBody;
    private final long maxBackupBody;

    public RequestGuardFilter(RateLimiter limiter,
                              @Value("${app.guard.per-minute:600}") int perMinute,
                              @Value("${app.guard.public-per-minute:120}") int publicPerMinute,
                              @Value("${app.guard.max-body-mb:15}") long maxBodyMb,
                              @Value("${app.guard.max-backup-mb:200}") long maxBackupMb) {
        this.limiter = limiter;
        this.perMinute = perMinute;
        this.publicPerMinute = publicPerMinute;
        this.maxBody = maxBodyMb * 1024 * 1024;
        this.maxBackupBody = maxBackupMb * 1024 * 1024;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (limiter.isEnabled()) {
            String ip = clientKey(request.getRemoteAddr());
            boolean open = isPublic(path);
            boolean ok = limiter.tryAcquire("api", ip, perMinute, 60)
                    && (!open || limiter.tryAcquire("api-public", ip, publicPerMinute, 60));
            if (!ok) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "Demasiadas solicitudes desde tu conexión. Espera un minuto e intenta de nuevo.");
                return;
            }
        }
        long limit = path.equals(BACKUP_UPLOAD) ? maxBackupBody : maxBody;
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(response, 413, "El archivo o los datos enviados son demasiado grandes.");
            return;
        }
        chain.doFilter(declared < 0 ? new LimitedRequest(request, limit) : request, response);
    }

    /** Rutas que se pueden llamar sin sesión: las que un atacante anónimo puede martillar. */
    static boolean isPublic(String path) {
        return path.startsWith("/api/public/") || path.startsWith("/api/webhooks/") || path.startsWith("/api/auth/")
                || path.startsWith("/api/platform/");
    }

    /** IPv4 tal cual; IPv6 recortada a su prefijo /64. */
    static String clientKey(String ip) {
        if (ip == null) return "?";
        if (ip.indexOf(':') < 0) return ip;
        String[] parts = ip.split(":", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = 0; i < parts.length && n < 4; i++, n++) {
            if (parts[i].isEmpty()) break; // "::" abrevia ceros: lo que sigue ya no es parte del prefijo
            sb.append(parts[i]).append(':');
        }
        return sb.append(":/64").toString();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }

    /** Cuerpo sin Content-Length: se corta al pasar el tope en vez de leerlo entero en memoria. */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedStream(super.getInputStream(), limit);
            return stream;
        }
    }

    static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long read;

        LimitedStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        private void count(long n) throws IOException {
            if (n > 0 && (read += n) > limit) throw new PayloadTooLargeException();
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }

    /** Corta la lectura; la petición termina como "solicitud no válida" (400) sin haber cargado el cuerpo entero. */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("Cuerpo de la petición demasiado grande");
        }
    }
}
