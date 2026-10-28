package com.tiendaposs.einvoice;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.fiscal.FiscalService;
import com.tiendaposs.notifications.NotificationService;
import com.tiendaposs.notifications.NotificationType;
import com.tiendaposs.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Solicitud de facturación electrónica: la tienda que la quiere completa el asistente del módulo, sube sus documentos
 * (RUT, cédula del representante y, si es empresa, cámara de comercio) y la envía. Ecomerxio la revisa desde su Super
 * Admin (API de plataforma). La facturación se vende por paquetes: solo se activa después de verificar los documentos y el
 * pago (hoy el pago lo confirma Ecomerxio a mano; el cobro en línea del paquete es un plan a futuro).
 */
@Service
public class EInvoiceRequestService {

    public static final String ENVIADA = "ENVIADA", CORREGIR = "CORREGIR", APROBADA = "APROBADA", ACTIVA = "ACTIVA";
    public static final List<String> KINDS = List.of("RUT", "CEDULA", "CAMARA", "OTRO");
    static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Doc(Long id, String kind, String filename, String contentType, int sizeBytes, Instant uploadedAt) {
    }

    public record RequestView(String status, String adminComment, Instant submittedAt, Instant reviewedAt, List<Doc> documents,
                              List<String> requiredKinds, List<String> missing, boolean canSubmit, boolean einvoiceAddon) {
    }

    public record DocFile(String filename, String contentType, byte[] data) {
    }

    private final JdbcTemplate jdbc;
    private final FiscalService fiscal;
    private final AuditService audit;
    private final NotificationService notifications;

    public EInvoiceRequestService(JdbcTemplate jdbc, FiscalService fiscal, AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.fiscal = fiscal;
        this.audit = audit;
        this.notifications = notifications;
    }

    // =================================================================== Tienda

    public RequestView view(Long tenantId) {
        Map<String, Object> r = row(tenantId);
        List<Doc> docs = documents(tenantId);
        boolean company = "JURIDICA".equals(jdbc.query("SELECT person_type FROM tenant_fiscal_profiles WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId));
        List<String> required = company ? List.of("RUT", "CEDULA", "CAMARA") : List.of("RUT", "CEDULA");
        List<String> missing = new ArrayList<>();
        boolean dataOk = fiscal.readiness().steps().stream().anyMatch(s -> "DATOS".equals(s.key()) && "OK".equals(s.status()));
        if (!dataOk) missing.add("Datos fiscales del negocio completos");
        Set<String> have = new HashSet<>();
        docs.forEach(d -> have.add(d.kind()));
        for (String k : required) if (!have.contains(k)) missing.add(label(k));
        String status = r == null ? null : (String) r.get("status");
        boolean addon = Boolean.TRUE.equals(jdbc.queryForObject("SELECT einvoice_addon FROM tenants WHERE id = ?", Boolean.class, tenantId));
        boolean open = status == null || CORREGIR.equals(status);
        return new RequestView(status, r == null ? null : (String) r.get("admin_comment"), ts(r, "submitted_at"), ts(r, "reviewed_at"), docs,
                required, missing, open && !addon && missing.isEmpty(), addon);
    }

    @Transactional
    public Doc upload(Long tenantId, String kind, String filename, byte[] data) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(k)) throw ApiException.badRequest("Tipo de documento no válido");
        requireEditable(tenantId);
        if (data == null || data.length == 0) throw ApiException.badRequest("El archivo está vacío");
        if (data.length > MAX_BYTES) throw ApiException.badRequest("El archivo pesa más de 5 MB");
        String type = sniff(data);
        if (type == null) throw ApiException.badRequest("Sube el documento en PDF, JPG o PNG");
        String name = cleanName(filename, type);
        String who = CurrentUser.get().fullName();
        jdbc.update("DELETE FROM einvoice_request_documents WHERE tenant_id = ? AND kind = ?", tenantId, k);
        jdbc.update("INSERT INTO einvoice_request_documents (tenant_id, kind, filename, content_type, size_bytes, sha256, data, uploaded_by) VALUES (?,?,?,?,?,?,?,?)",
                tenantId, k, name, type, data.length, sha256(data), data, who);
        audit.log("FACTURACION_DOCUMENTO", "TENANT", tenantId, "Subió " + label(k) + " (" + name + ") para la solicitud de facturación electrónica");
        return documents(tenantId).stream().filter(d -> d.kind().equals(k)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteDocument(Long tenantId, Long id) {
        requireEditable(tenantId);
        if (jdbc.update("DELETE FROM einvoice_request_documents WHERE tenant_id = ? AND id = ?", tenantId, id) == 0) {
            throw ApiException.notFound("Documento no encontrado");
        }
    }

    @Transactional
    public RequestView submit(Long tenantId, boolean acceptedTerms) {
        if (!acceptedTerms) throw ApiException.field("acceptedTerms", "acepta las condiciones para enviar la solicitud");
        RequestView v = view(tenantId);
        if (v.einvoiceAddon()) throw ApiException.badRequest("La facturación electrónica ya está activa para tu tienda.");
        if (v.status() != null && !CORREGIR.equals(v.status())) throw ApiException.badRequest("Tu solicitud ya fue enviada.");
        if (!v.missing().isEmpty()) throw ApiException.badRequest("Falta: " + String.join(", ", v.missing()));
        String who = CurrentUser.get().fullName();
        jdbc.update("""
                INSERT INTO einvoice_requests (tenant_id, status, accepted_terms, submitted_by, submitted_at, updated_at) VALUES (?, ?, TRUE, ?, now(), now())
                ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status, accepted_terms = TRUE, submitted_by = EXCLUDED.submitted_by,
                    submitted_at = now(), updated_at = now()""", tenantId, ENVIADA, who);
        audit.log("FACTURACION_SOLICITADA", "TENANT", tenantId, "Envió la solicitud de facturación electrónica a Ecomerxio");
        return view(tenantId);
    }

    private void requireEditable(Long tenantId) {
        Map<String, Object> r = row(tenantId);
        String st = r == null ? null : (String) r.get("status");
        if (st != null && !CORREGIR.equals(st)) {
            throw ApiException.badRequest("Tu solicitud está en revisión: no se pueden cambiar los documentos ahora.");
        }
    }

    // =================================================================== Plataforma (Super Admin de Ecomerxio)

    public record AdminRow(Long tenantId, String storeName, String status, Instant submittedAt, Instant reviewedAt, String submittedBy, int documents) {
    }

    public List<AdminRow> listForPlatform() {
        return jdbc.query("""
                SELECT r.tenant_id, t.name, r.status, r.submitted_at, r.reviewed_at, r.submitted_by,
                       (SELECT count(*) FROM einvoice_request_documents d WHERE d.tenant_id = r.tenant_id) AS docs
                FROM einvoice_requests r JOIN tenants t ON t.id = r.tenant_id
                ORDER BY CASE r.status WHEN 'ENVIADA' THEN 0 WHEN 'APROBADA' THEN 1 WHEN 'CORREGIR' THEN 2 ELSE 3 END, r.submitted_at DESC""",
                (rs, i) -> new AdminRow(rs.getLong(1), rs.getString(2), rs.getString(3), inst(rs.getTimestamp(4)), inst(rs.getTimestamp(5)),
                        rs.getString(6), rs.getInt(7)));
    }

    public Map<String, Object> detailForPlatform(Long tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> r = row(tenantId);
        out.put("status", r == null ? null : r.get("status"));
        out.put("adminComment", r == null ? null : r.get("admin_comment"));
        out.put("submittedBy", r == null ? null : r.get("submitted_by"));
        out.put("submittedAt", ts(r, "submitted_at"));
        out.put("reviewedAt", ts(r, "reviewed_at"));
        out.put("documents", documents(tenantId));
        List<Map<String, Object>> fp = jdbc.queryForList("""
                SELECT person_type, id_type, id_number, dv, legal_name, trade_name, iva_responsible, responsibilities, ciiu, address,
                       municipality_name, department_name, invoicing_email, phone, registered_as_issuer, provider_authorized, environment
                FROM tenant_fiscal_profiles WHERE tenant_id = ?""", tenantId);
        out.put("fiscalProfile", fp.isEmpty() ? null : fp.get(0));
        out.put("resolutions", jdbc.queryForList("SELECT document_type, prefix, range_from, range_to, valid_to FROM numbering_resolutions WHERE tenant_id = ? ORDER BY id", tenantId));
        return out;
    }

    public DocFile documentForPlatform(Long tenantId, Long docId) {
        List<DocFile> f = jdbc.query("SELECT filename, content_type, data FROM einvoice_request_documents WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new DocFile(rs.getString(1), rs.getString(2), rs.getBytes(3)), tenantId, docId);
        if (f.isEmpty()) throw ApiException.notFound("Documento no encontrado");
        audit.logInCurrentTx(tenantId, null, "Soporte Ecomerxio", "FACTURACION_DOCUMENTO_VISTO", "Soporte de Ecomerxio abrió " + f.get(0).filename());
        return f.get(0);
    }

    /** Ecomerxio aprueba la solicitud o pide corregir algo; la tienda recibe el aviso en su campana. */
    @Transactional
    public void review(Long tenantId, String status, String comment) {
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!st.equals(APROBADA) && !st.equals(CORREGIR)) throw ApiException.field("status", "APROBADA o CORREGIR");
        if (st.equals(CORREGIR) && (comment == null || comment.isBlank())) throw ApiException.field("comment", "di qué debe corregir la tienda");
        Map<String, Object> r = row(tenantId);
        if (r == null) throw ApiException.notFound("La tienda no ha enviado una solicitud");
        String text = comment == null || comment.isBlank() ? null : comment.trim().substring(0, Math.min(1000, comment.trim().length()));
        jdbc.update("UPDATE einvoice_requests SET status = ?, admin_comment = ?, reviewed_at = now(), updated_at = now() WHERE tenant_id = ?", st, text, tenantId);
        audit.logInCurrentTx(tenantId, null, "Soporte Ecomerxio", "FACTURACION_SOLICITUD_" + st,
                (st.equals(APROBADA) ? "Ecomerxio aprobó la solicitud de facturación electrónica" : "Ecomerxio pidió corregir la solicitud") + (text == null ? "" : ": " + text));
        notifications.notifyNow(tenantId, NotificationType.SOLICITUD_FACTURACION,
                st.equals(APROBADA) ? "Solicitud de facturación aprobada" : "Corrige tu solicitud de facturación",
                st.equals(APROBADA) ? "Tus documentos están en orden. Te contactamos para el pago de tu paquete de facturación y la habilitación ante la DIAN."
                        : "Ecomerxio te pide: " + text,
                "/facturacion", "solicitud-fe-" + st + "-" + System.currentTimeMillis());
    }

    /** Al activar o retirar la facturación para la tienda, la solicitud (si existe) refleja el cambio. */
    public void onAddonChanged(Long tenantId, boolean on) {
        jdbc.update("UPDATE einvoice_requests SET status = ?, updated_at = now() WHERE tenant_id = ?", on ? ACTIVA : APROBADA, tenantId);
        if (on && row(tenantId) != null) {
            notifications.notifyNow(tenantId, NotificationType.SOLICITUD_FACTURACION, "Facturación electrónica activada",
                    "Ya puedes emitir el documento de prueba y activar la facturación en tu tienda.", "/facturacion", "solicitud-fe-activa-" + System.currentTimeMillis());
        }
    }

    // =================================================================== Apoyo

    private Map<String, Object> row(Long tenantId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM einvoice_requests WHERE tenant_id = ?", tenantId);
        return r.isEmpty() ? null : r.get(0);
    }

    private List<Doc> documents(Long tenantId) {
        return jdbc.query("SELECT id, kind, filename, content_type, size_bytes, uploaded_at FROM einvoice_request_documents WHERE tenant_id = ? ORDER BY id",
                (rs, i) -> new Doc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), inst(rs.getTimestamp(6))), tenantId);
    }

    static String label(String kind) {
        return switch (kind) {
            case "RUT" -> "RUT actualizado";
            case "CEDULA" -> "Cédula del representante legal";
            case "CAMARA" -> "Certificado de cámara de comercio";
            default -> "Otro documento";
        };
    }

    /** Tipo real por los primeros bytes (no por la extensión que manda el navegador). */
    static String sniff(byte[] d) {
        if (d.length >= 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        return null;
    }

    private static String cleanName(String filename, String type) {
        String base = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) base = "documento";
        if (base.length() > 120) base = base.substring(0, 120);
        String ext = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        return base.toLowerCase(Locale.ROOT).endsWith(ext) || (ext.equals(".jpg") && base.toLowerCase(Locale.ROOT).endsWith(".jpeg")) ? base : base + ext;
    }

    private static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant ts(Map<String, Object> r, String col) {
        return r == null || r.get(col) == null ? null : ((Timestamp) r.get(col)).toInstant();
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
