package com.tiendaposs.legal;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.*;

/**
 * Términos, licencia, política de servicio, anexo de facturación, encargo de datos y privacidad. Los textos viven en la app web
 * (frontend/src/lib/legal); aquí solo está qué versión rige y quién la aceptó, cuándo y desde dónde.
 *
 * <p>Al cambiar un texto de fondo se sube {@link #VERSION}: los usuarios que aceptaron la anterior la vuelven a ver
 * al entrar y no pueden seguir sin aceptarla.</p>
 */
@Service
public class LegalService {

    /** Debe coincidir con LEGAL_VERSION de frontend/src/lib/legal.ts. */
    public static final String VERSION = "2026-10-21";

    /** El contrato con la tienda: lo acepta el dueño. */
    public static final List<String> OWNER_DOCS = List.of("TERMINOS", "LICENCIA", "SERVICIO", "FACTURACION", "ENCARGO", "PRIVACIDAD");
    /** Los empleados autorizan el tratamiento de sus propios datos. */
    public static final List<String> EMPLOYEE_DOCS = List.of("PRIVACIDAD");

    public interface AcceptanceRepository extends JpaRepository<LegalAcceptance, Long> {
        boolean existsByUserIdAndDocumentAndVersion(Long userId, String document, String version);

        List<LegalAcceptance> findTop200ByTenantIdOrderByAcceptedAtDesc(Long tenantId);
    }

    private final AcceptanceRepository acceptances;
    private final UserRepository users;
    private final AuditService audit;

    public LegalService(AcceptanceRepository acceptances, UserRepository users, AuditService audit) {
        this.acceptances = acceptances;
        this.users = users;
        this.audit = audit;
    }

    public static List<String> required(User u) {
        return u.isOwner() ? OWNER_DOCS : EMPLOYEE_DOCS;
    }

    /** Documentos de la versión vigente que este usuario todavía no ha aceptado. */
    public List<String> pending(User u) {
        if (u.getId() == null) return required(u);
        return required(u).stream().filter(d -> !acceptances.existsByUserIdAndDocumentAndVersion(u.getId(), d, VERSION)).toList();
    }

    /** Guarda la aceptación con fecha, IP y navegador (dentro de la transacción de quien llama). */
    public void record(User u, Collection<String> docs, String context) {
        String ua = userAgent();
        for (String d : docs) {
            if (acceptances.existsByUserIdAndDocumentAndVersion(u.getId(), d, VERSION)) continue;
            LegalAcceptance a = new LegalAcceptance();
            a.tenantId = u.getTenantId();
            a.userId = u.getId();
            a.userName = u.getFullName();
            a.email = u.getEmail();
            a.document = d;
            a.version = VERSION;
            a.ip = AuditService.clientIp();
            a.userAgent = ua;
            a.context = context;
            acceptances.save(a);
        }
    }

    public record AcceptRequest(List<String> documents, String version) {
    }

    public record AcceptanceDto(String userName, String email, String document, String version, Instant acceptedAt,
                                String ip, String context) {
    }

    public record Status(String version, List<String> required, List<String> pending, List<AcceptanceDto> history) {
    }

    public Status status() {
        User u = users.findById(CurrentUser.get().userId()).orElseThrow();
        boolean seeAll = u.isOwner() || u.effectivePermissions().contains(Permission.AUDITORIA_VER.name());
        List<AcceptanceDto> history = seeAll
                ? acceptances.findTop200ByTenantIdOrderByAcceptedAtDesc(u.getTenantId()).stream()
                .map(a -> new AcceptanceDto(a.userName, a.email, a.document, a.version, a.acceptedAt, a.ip, a.context)).toList()
                : List.of();
        return new Status(VERSION, required(u), pending(u), history);
    }

    @Transactional
    public Status accept(AcceptRequest r) {
        User u = users.findById(CurrentUser.get().userId()).orElseThrow();
        if (r.version() == null || !VERSION.equals(r.version())) {
            throw ApiException.conflict("Los documentos cambiaron mientras los leías. Recarga la página y revísalos de nuevo.");
        }
        Set<String> asked = new HashSet<>(r.documents() == null ? List.of() : r.documents());
        // Solo se exigen los que faltan: los que ya aceptó en esta versión no se vuelven a pedir.
        List<String> pend = pending(u);
        List<String> missing = pend.stream().filter(d -> !asked.contains(d)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Para continuar debes aceptar todos los documentos: falta " + String.join(", ", missing).toLowerCase());
        }
        if (!pend.isEmpty()) {
            record(u, pend, "ACTUALIZACION");
            audit.log("LEGAL_ACEPTADO", "USER", u.getId(), "Aceptó los documentos legales versión " + VERSION + ": "
                    + String.join(", ", pend).toLowerCase());
        }
        return status();
    }

    private static String userAgent() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest req = attrs.getRequest();
        String ua = req.getHeader("User-Agent");
        return ua == null ? null : ua.length() > 300 ? ua.substring(0, 300) : ua;
    }
}
