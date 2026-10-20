package com.tiendaposs.offline;

import com.tiendaposs.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operaciones idempotentes para la app sin internet. Si una petición que cambia datos trae {@code Idempotency-Key}:
 * <ol>
 *   <li>se abre UNA transacción y se reserva la llave (tienda + llave);</li>
 *   <li>se ejecuta la operación dentro de esa transacción (los servicios se unen a ella);</li>
 *   <li>si salió bien (2xx), se guarda la respuesta y se confirma todo junto; si no, se deshace todo.</li>
 * </ol>
 * Un reenvío con la misma llave devuelve la respuesta guardada (cabecera {@code Idempotent-Replay: true}) sin repetir
 * nada. Dos envíos simultáneos con la misma llave: el segundo espera al primero (índice único) y luego recibe su
 * respuesta. La respuesta solo sale al cliente después de confirmar en la base: si se va la luz antes, la app no
 * recibió nada y reintenta; si se va después, el reintento recibe la respuesta guardada.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IdempotencyFilter(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    private record Stored(Integer status, String contentType, String body) {
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !METHODS.contains(request.getMethod()) || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Long tenantId = TenantContext.get();
        String key = request.getHeader(HEADER).trim();
        if (tenantId == null || !KEY.matcher(key).matches()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        Stored replay;
        try {
            replay = tx.execute(status -> {
                int inserted = jdbc.update("INSERT INTO client_operations (tenant_id, client_ref, method, path) VALUES (?,?,?,?) "
                        + "ON CONFLICT DO NOTHING", tenantId, key, request.getMethod(), truncate(path, 300));
                if (inserted == 0) {
                    List<Stored> rows = jdbc.query("SELECT status, content_type, response FROM client_operations WHERE tenant_id = ? AND client_ref = ?",
                            (rs, i) -> new Stored((Integer) rs.getObject(1), rs.getString(2), rs.getString(3)), tenantId, key);
                    return rows.isEmpty() ? new Stored(null, null, null) : rows.get(0);
                }
                try {
                    chain.doFilter(request, wrapper);
                } catch (IOException | ServletException e) {
                    throw new WrappedException(e);
                }
                int code = wrapper.getStatus();
                if (code >= 200 && code < 300) {
                    String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                    jdbc.update("UPDATE client_operations SET status = ?, content_type = ?, response = ? WHERE tenant_id = ? AND client_ref = ?",
                            code, truncate(wrapper.getContentType(), 120), body, tenantId, key);
                } else {
                    status.setRollbackOnly(); // falló: ni la operación ni la llave quedan guardadas
                }
                return null;
            });
        } catch (WrappedException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw (ServletException) e.getCause();
        }
        if (replay == null) {
            wrapper.copyBodyToResponse(); // la respuesta sale DESPUÉS de confirmar en la base
            return;
        }
        if (replay.status() == null) {
            // Llave reservada sin respuesta (no debería pasar: se guarda en la misma transacción). Que reintente.
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"La operación todavía se está procesando. Intenta de nuevo.\"}");
            return;
        }
        response.setStatus(replay.status());
        response.setHeader("Idempotent-Replay", "true");
        if (replay.contentType() != null) response.setContentType(replay.contentType());
        if (replay.body() != null && !replay.body().isEmpty()) {
            byte[] bytes = replay.body().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final class WrappedException extends RuntimeException {
        WrappedException(Exception cause) {
            super(cause);
        }
    }
}
