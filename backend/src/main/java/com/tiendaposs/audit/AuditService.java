package com.tiendaposs.audit;

import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registro de actividad. Cada módulo llama a {@link #log} en sus operaciones importantes;
 * {@link #sensitive} marca las que el dueño debe poder revisar aparte (anulaciones, ajustes,
 * cambios de permisos, accesos fallidos...).
 */
@Service
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public void log(String action, String entityType, Long entityId, String summary) {
        write(action, entityType, entityId, summary, false);
    }

    public void sensitive(String action, String entityType, Long entityId, String summary) {
        write(action, entityType, entityId, summary, true);
    }

    private void write(String action, String entityType, Long entityId, String summary, boolean sensitive) {
        AuthUser u = CurrentUser.getOrNull();
        repo.save(new AuditEvent(u == null ? null : u.tenantId(), u == null ? null : u.userId(),
                u == null ? null : u.fullName(), action, entityType, entityId, summary, sensitive, clientIp()));
    }

    /** Para eventos sin usuario autenticado todavía (inicio de sesión). Se guarda aunque la
     * transacción que lo llama falle, para que un intento fallido siempre quede registrado. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFor(Long tenantId, Long userId, String userName, String action, String summary, boolean sensitive) {
        repo.save(new AuditEvent(tenantId, userId, userName, action, "USER", userId, summary, sensitive, clientIp()));
    }

    /** Igual que {@link #logFor} pero dentro de la transacción actual (p. ej. al crear la tienda). */
    public void logInCurrentTx(Long tenantId, Long userId, String userName, String action, String summary) {
        repo.save(new AuditEvent(tenantId, userId, userName, action, "USER", userId, summary, false, clientIp()));
    }

    public static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest req = attrs.getRequest();
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
