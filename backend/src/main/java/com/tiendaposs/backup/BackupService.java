package com.tiendaposs.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import com.tiendaposs.users.User;
import com.tiendaposs.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Copias de seguridad lógicas por tienda: todas sus tablas en un JSON comprimido, guardado en la
 * base (automáticas cada noche y manuales) y descargable. La restauración reemplaza los datos de la
 * tienda por los de la copia (mismos identificadores), solo la hace el dueño confirmando su
 * contraseña, y antes guarda una copia del estado actual por si hay que deshacerla.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final String FORMAT = "tiendaposs-backup";
    static final int KEEP_AUTO = 7, KEEP_MANUAL = 20;
    static final long MAX_UPLOAD = 60L * 1024 * 1024;

    /** Tablas de la tienda en orden de dependencia (padres primero) y cómo se filtran por tienda. */
    static final List<String[]> TABLES = List.of(
            t("store_settings"), t("tenant_counters"), t("branches"), t("roles"),
            new String[]{"role_permissions", "x.role_id in (select id from roles where tenant_id = :t)"},
            t("users"),
            new String[]{"user_roles", "x.user_id in (select id from users where tenant_id = :t)"},
            t("registers"), t("categories"), t("customers"), t("products"), t("product_presentations"), t("price_history"), t("combo_components"),
            t("suppliers"), t("supplier_products"), t("supplier_price_history"), t("purchase_orders"),
            new String[]{"purchase_order_lines", "x.order_id in (select id from purchase_orders where tenant_id = :t)"},
            t("goods_receipts"),
            new String[]{"goods_receipt_lines", "x.receipt_id in (select id from goods_receipts where tenant_id = :t)"},
            t("product_lots"), t("supplier_returns"), t("inventory_counts"),
            new String[]{"inventory_count_lines", "x.count_id in (select id from inventory_counts where tenant_id = :t)"},
            t("shifts"), t("cash_movements"), t("promotions"), t("sales"), t("sale_lines"), t("sale_line_components"),
            t("payments"), t("payment_events"),
            t("sale_returns"),
            new String[]{"sale_return_lines", "x.return_id in (select id from sale_returns where tenant_id = :t)"},
            t("product_serials"), t("credit_entries"), t("finance_categories"), t("finance_entries"), t("supplier_payments"),
            new String[]{"supplier_payment_allocations", "x.payment_id in (select id from supplier_payments where tenant_id = :t)"},
            t("payment_accounts"), t("payment_integrations"), t("reconciliations"),
            new String[]{"reconciliation_rows", "x.reconciliation_id in (select id from reconciliations where tenant_id = :t)"},
            t("stock_movements"), t("notification_rules"), t("notification_channels"), t("ai_recommendations"),
            t("assistant_conversations"),
            new String[]{"assistant_messages", "x.conversation_id in (select id from assistant_conversations where tenant_id = :t)"},
            t("assistant_usage"), t("audit_events"));

    private static String[] t(String table) {
        return new String[]{table, "x.tenant_id = :t"};
    }

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SettingsService settings;
    private final AuditService audit;
    private final boolean schedulerEnabled;

    public BackupService(EntityManager em, TransactionTemplate tx, ObjectMapper json, TenantRepository tenants, UserRepository users,
                         PasswordEncoder encoder, SettingsService settings, AuditService audit,
                         @Value("${app.scheduler.enabled:true}") boolean schedulerEnabled) {
        this.em = em;
        this.tx = tx;
        this.json = json;
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.settings = settings;
        this.audit = audit;
        this.schedulerEnabled = schedulerEnabled;
    }

    public record BackupDto(Long id, String kind, String schemaVersion, long sizeBytes, String checksum, Map<String, Integer> rowCounts,
                            String createdBy, Instant createdAt) {
    }

    // ---------------- Crear ----------------

    /** Arma la copia de la tienda (JSON comprimido) dentro de la transacción actual. */
    @SuppressWarnings("unchecked")
    byte[] build(Long t, Map<String, Integer> counts) {
        Tenant tenant = tenants.findById(t).orElseThrow();
        StringBuilder sb = new StringBuilder(64 * 1024);
        try {
            sb.append("{\"format\":\"").append(FORMAT).append("\",\"version\":1,\"schemaVersion\":").append(json.writeValueAsString(schemaVersion()))
                    .append(",\"tenantId\":").append(t).append(",\"tenantName\":").append(json.writeValueAsString(tenant.getName()))
                    .append(",\"createdAt\":\"").append(Instant.now()).append("\",\"tables\":{");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        boolean first = true;
        for (String[] tb : TABLES) {
            String rows = (String) em.createNativeQuery("select coalesce(json_agg(x), '[]'::json)::text from " + tb[0] + " x where " + tb[1])
                    .setParameter("t", t).getSingleResult();
            Number n = (Number) em.createNativeQuery("select count(*) from " + tb[0] + " x where " + tb[1]).setParameter("t", t).getSingleResult();
            counts.put(tb[0], n.intValue());
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(tb[0]).append("\":").append(rows);
        }
        sb.append("}}");
        return gzip(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Crea y guarda una copia. `who` = nombre de quien la pidió (o "Sistema"). */
    BackupDto store(Long t, String kind, String who) {
        return tx.execute(s -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            byte[] data = build(t, counts);
            String checksum = sha256(data);
            Number id = (Number) em.createNativeQuery("""
                            insert into backups (tenant_id, kind, schema_version, size_bytes, checksum, row_counts, created_by, data)
                            values (:t, :k, :v, :sz, :c, :rc, :by, :d) returning id""")
                    .setParameter("t", t).setParameter("k", kind).setParameter("v", schemaVersion()).setParameter("sz", (long) data.length)
                    .setParameter("c", checksum).setParameter("rc", countsText(counts)).setParameter("by", who).setParameter("d", data)
                    .getSingleResult();
            prune(t, kind);
            return new BackupDto(id.longValue(), kind, schemaVersion(), data.length, checksum, counts, who, Instant.now());
        });
    }

    public BackupDto createManual() {
        AuthUser me = requireAdmin();
        BackupDto b = store(me.tenantId(), "MANUAL", me.fullName());
        tx.executeWithoutResult(s -> audit.sensitive("COPIA_SEGURIDAD", "TENANT", me.tenantId(), "Creó una copia de seguridad (" + kb(b.sizeBytes()) + ")"));
        return b;
    }

    private void prune(Long t, String kind) {
        int keep = "AUTOMATICA".equals(kind) ? KEEP_AUTO : KEEP_MANUAL;
        em.createNativeQuery("""
                        delete from backups where tenant_id = :t and kind = :k and id not in
                          (select id from backups where tenant_id = :t and kind = :k order by created_at desc limit :n)""")
                .setParameter("t", t).setParameter("k", kind).setParameter("n", keep).executeUpdate();
    }

    /** Copia automática cada noche (3:30 a. m.) de las tiendas que la tengan activa. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Bogota")
    public void nightly() {
        if (!schedulerEnabled) return;
        for (Tenant tenant : tenants.findAll()) {
            try {
                if (!settings.forTenant(tenant.getId()).autoBackup) continue;
                BackupDto b = store(tenant.getId(), "AUTOMATICA", "Sistema");
                tx.executeWithoutResult(s -> audit.logInCurrentTx(tenant.getId(), null, "Sistema", "COPIA_SEGURIDAD", "Copia automática (" + kb(b.sizeBytes()) + ")"));
            } catch (Exception e) {
                log.warn("Falló la copia automática de la tienda {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    // ---------------- Consultar y descargar ----------------

    @SuppressWarnings("unchecked")
    public List<BackupDto> list() {
        requireAdmin();
        Long t = TenantContext.require();
        List<Object[]> rows = em.createNativeQuery("""
                        select id, kind, schema_version, size_bytes, checksum, row_counts, created_by, created_at from backups
                        where tenant_id = :t order by created_at desc""").setParameter("t", t).getResultList();
        return rows.stream().map(r -> new BackupDto(((Number) r[0]).longValue(), (String) r[1], (String) r[2], ((Number) r[3]).longValue(),
                (String) r[4], parseCounts((String) r[5]), (String) r[6], instant(r[7]))).toList();
    }

    public record Download(String fileName, byte[] data) {
    }

    public Download download(Long id) {
        AuthUser me = requireAdmin();
        byte[] data = tx.execute(s -> {
            List<?> r = em.createNativeQuery("select data from backups where id = :id and tenant_id = :t")
                    .setParameter("id", id).setParameter("t", me.tenantId()).getResultList();
            return r.isEmpty() ? null : (byte[]) r.get(0);
        });
        if (data == null) throw ApiException.notFound("Copia no encontrada");
        tx.executeWithoutResult(s -> audit.sensitive("COPIA_DESCARGADA", "TENANT", me.tenantId(), "Descargó la copia de seguridad #" + id));
        return new Download("tiendaposs_copia_" + id + "_" + LocalDate.now(zone(me.tenantId())) + ".tpbak", data);
    }

    // ---------------- Restaurar ----------------

    public record RestoreResult(Long safetyBackupId, Map<String, Integer> restored, boolean reloginRequired) {
    }

    public RestoreResult restoreStored(Long id, String password) {
        AuthUser me = requireOwner(password);
        byte[] data = tx.execute(s -> {
            List<?> r = em.createNativeQuery("select data from backups where id = :id and tenant_id = :t")
                    .setParameter("id", id).setParameter("t", me.tenantId()).getResultList();
            return r.isEmpty() ? null : (byte[]) r.get(0);
        });
        if (data == null) throw ApiException.notFound("Copia no encontrada");
        return restore(me, data, "la copia #" + id);
    }

    public RestoreResult restoreUpload(byte[] data, String password) {
        AuthUser me = requireOwner(password);
        if (data == null || data.length == 0) throw ApiException.badRequest("Sube el archivo de la copia (.tpbak)");
        if (data.length > MAX_UPLOAD) throw ApiException.badRequest("El archivo es demasiado grande");
        return restore(me, data, "un archivo subido");
    }

    private RestoreResult restore(AuthUser me, byte[] data, String source) {
        Long t = me.tenantId();
        JsonNode root;
        try {
            root = json.readTree(gunzip(data));
        } catch (Exception e) {
            throw ApiException.badRequest("El archivo no es una copia de TiendaPOSS válida");
        }
        if (!FORMAT.equals(root.path("format").asText())) throw ApiException.badRequest("El archivo no es una copia de TiendaPOSS");
        if (root.path("tenantId").asLong() != t) throw ApiException.badRequest("Esta copia es de otra tienda (" + root.path("tenantName").asText() + ")");
        if (!schemaVersion().equals(root.path("schemaVersion").asText())) {
            throw ApiException.badRequest("La copia es de otra versión del sistema (" + root.path("schemaVersion").asText() + "); la actual es " + schemaVersion());
        }
        JsonNode tables = root.path("tables");
        for (String[] tb : TABLES) {
            if (!tables.has(tb[0]) || !tables.get(tb[0]).isArray()) throw ApiException.badRequest("La copia está incompleta: falta " + tb[0]);
        }
        if (tables.get("users").isEmpty()) throw ApiException.badRequest("La copia no tiene usuarios");
        // Antes de tocar nada: toda fila con tienda debe ser de esta tienda.
        for (String[] tb : TABLES) {
            if (!tb[1].equals("x.tenant_id = :t")) continue;
            for (JsonNode row : tables.get(tb[0])) {
                if (row.path("tenant_id").asLong(-1) != t) throw ApiException.badRequest("La copia tiene datos de otra tienda en " + tb[0]);
            }
        }
        int versionBefore = users.findById(me.userId()).map(User::getTokenVersion).orElse(0);
        BackupDto safety = store(t, "ANTES_RESTAURAR", me.fullName());
        Map<String, Integer> restored = new LinkedHashMap<>();
        try {
            tx.executeWithoutResult(s -> {
            em.flush();
            em.createNativeQuery("delete from password_reset_tokens where user_id in (select id from users where tenant_id = :t)")
                    .setParameter("t", t).executeUpdate();
            List<String[]> reversed = new ArrayList<>(TABLES);
            Collections.reverse(reversed);
            for (String[] tb : reversed) {
                em.createNativeQuery("delete from " + tb[0] + " x where " + tb[1]).setParameter("t", t).executeUpdate();
            }
            for (String[] tb : TABLES) {
                JsonNode rows = tables.get(tb[0]);
                if (rows.isEmpty()) {
                    restored.put(tb[0], 0);
                    continue;
                }
                int n = em.createNativeQuery("insert into " + tb[0] + " select * from json_populate_recordset(null::" + tb[0] + ", cast(:j as json))")
                        .setParameter("j", rows.toString()).executeUpdate();
                // Cada fila tiene que quedar dentro de esta tienda (un archivo alterado no puede tocar otra).
                Number mine = (Number) em.createNativeQuery("select count(*) from " + tb[0] + " x where " + tb[1]).setParameter("t", t).getSingleResult();
                if (mine.intValue() != n) throw ApiException.badRequest("La copia tiene datos de otra tienda en " + tb[0]);
                restored.put(tb[0], n);
            }
            String foreign = crossTenantReference(t);
            if (foreign != null) throw ApiException.badRequest("La copia tiene referencias a otra tienda (" + foreign + ")");
            em.clear();
            audit.logInCurrentTx(t, null, me.fullName(), "RESTAURACION", "Restauró la tienda desde " + source
                    + ". Copia del estado anterior: #" + safety.id());
            });
        } catch (org.springframework.dao.DataAccessException | jakarta.persistence.PersistenceException e) {
            log.warn("Restauración rechazada para la tienda {}: {}", t, e.getMessage());
            throw ApiException.badRequest("La copia tiene datos inconsistentes y no se aplicó; la tienda quedó como estaba");
        }
        int versionAfter = users.findById(me.userId()).map(User::getTokenVersion).orElse(-1);
        return new RestoreResult(safety.id(), restored, versionAfter != versionBefore);
    }

    /** Busca filas de la tienda que apunten (por llave foránea) a filas de otra tienda. */
    @SuppressWarnings("unchecked")
    private String crossTenantReference(Long t) {
        Map<String, String> preds = new HashMap<>();
        for (String[] tb : TABLES) preds.put(tb[0], tb[1]);
        List<Object[]> fks = em.createNativeQuery("""
                        select kcu.table_name, kcu.column_name, ccu.table_name, ccu.column_name
                        from information_schema.table_constraints tc
                        join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                        join information_schema.constraint_column_usage ccu on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
                        where tc.constraint_type = 'FOREIGN KEY' and tc.table_schema = 'public' and ccu.table_name <> 'tenants'
                          and exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = ccu.table_name
                                      and c.column_name = 'tenant_id')""").getResultList();
        for (Object[] fk : fks) {
            String child = (String) fk[0], col = (String) fk[1], parent = (String) fk[2], pcol = (String) fk[3];
            if (!preds.containsKey(child)) continue;
            Number n = (Number) em.createNativeQuery("select count(*) from " + child + " x join " + parent + " p on p." + pcol + " = x." + col
                    + " where (" + preds.get(child) + ") and p.tenant_id <> :t").setParameter("t", t).getSingleResult();
            if (n.intValue() > 0) return child + "." + col;
        }
        return null;
    }

    // ---------------- Exportación de datos (CSV en un ZIP) ----------------

    static final Map<String, String> EXPORTS = new LinkedHashMap<>();

    static {
        EXPORTS.put("productos", """
                select p.sku as codigo, p.name as nombre, c.name as categoria, p.brand as marca, p.unit as unidad, pr.barcode as codigo_barras,
                       pr.price as precio, p.cost as costo, p.stock as existencias, p.stock_min as minimo, p.tax_rate as impuesto_pct,
                       p.status as estado
                from products p left join categories c on c.id = p.category_id
                left join product_presentations pr on pr.product_id = p.id and pr.is_base
                where p.tenant_id = :t order by p.name""");
        EXPORTS.put("presentaciones", """
                select p.sku as codigo_producto, p.name as producto, pr.name as presentacion, pr.factor, pr.barcode as codigo_barras,
                       pr.price as precio, pr.min_price as precio_minimo
                from product_presentations pr join products p on p.id = pr.product_id where p.tenant_id = :t order by p.name, pr.factor""");
        EXPORTS.put("clientes", """
                select name as nombre, doc_type as tipo_documento, doc_number as documento, phone as celular, email as correo, address as direccion,
                       credit_limit as cupo_fiado, credit_days as plazo_fiado, credit_blocked as fiado_bloqueado
                from customers where tenant_id = :t order by name""");
        EXPORTS.put("proveedores", """
                select name as nombre, legal_name as razon_social, tax_id as nit, contact_name as contacto, phone as telefono, whatsapp, email as correo,
                       city as ciudad, credit_days as dias_credito, lead_time_days as dias_entrega, active as activo
                from suppliers where tenant_id = :t order by name""");
        EXPORTS.put("ventas", """
                select s.number as numero, s.status as estado, s.completed_at as fecha, s.user_name as vendedor, s.customer_name as cliente,
                       s.subtotal, s.discount_total as descuentos, s.tax_total as impuestos, s.total, s.refunded_total as devuelto,
                       s.offline as sin_internet, s.void_reason as motivo_anulacion
                from sales s where s.tenant_id = :t and s.number is not null order by s.number""");
        EXPORTS.put("lineas_de_venta", """
                select s.number as venta, s.completed_at as fecha, l.product_name as producto, l.presentation_name as presentacion,
                       l.quantity as cantidad, l.unit_price as precio_unitario, l.discount as descuento, l.tax as impuesto,
                       l.line_total as total, l.unit_cost as costo_unitario, l.returned_qty as devuelto
                from sale_lines l join sales s on s.id = l.sale_id where s.tenant_id = :t and s.number is not null order by s.number, l.id""");
        EXPORTS.put("pagos", """
                select s.number as venta, p.created_at as fecha, p.method as medio, p.amount as valor, p.reference as referencia,
                       p.status as estado, p.verified_by as verificado_por, p.user_name as cajero
                from payments p join sales s on s.id = p.sale_id where p.tenant_id = :t order by p.created_at""");
        EXPORTS.put("movimientos_inventario", """
                select m.created_at as fecha, p.name as producto, m.type as tipo, m.quantity as cantidad, m.stock_after as existencias_despues,
                       m.unit_cost as costo_unitario, m.reason as motivo, m.user_name as usuario
                from stock_movements m join products p on p.id = m.product_id where m.tenant_id = :t order by m.created_at""");
        EXPORTS.put("compras", """
                select o.number as numero, s.name as proveedor, o.status as estado, o.created_at as creada, o.expected_date as entrega_esperada,
                       o.approved_by as aprobada_por, o.received_at as recibida
                from purchase_orders o join suppliers s on s.id = o.supplier_id where o.tenant_id = :t order by o.number""");
        EXPORTS.put("gastos_e_ingresos", """
                select e.entry_date as fecha, e.kind as tipo, c.name as categoria, e.description as descripcion, e.amount as valor,
                       e.method as medio, e.paid_from as origen, e.voided_at is not null as anulado
                from finance_entries e left join finance_categories c on c.id = e.category_id where e.tenant_id = :t order by e.entry_date""");
        EXPORTS.put("fiado", """
                select ce.created_at as fecha, cu.name as cliente, ce.type as tipo, ce.amount as valor, ce.paid_amount as abonado,
                       ce.due_date as vence, ce.method as medio, ce.note as nota
                from credit_entries ce join customers cu on cu.id = ce.customer_id where ce.tenant_id = :t order by ce.created_at""");
        EXPORTS.put("turnos_de_caja", """
                select s.number as turno, r.name as caja, s.user_name as cajero, s.opened_at as apertura, s.closed_at as cierre,
                       s.opening_cash as base, s.expected_cash as esperado, s.counted_cash as contado, s.difference as diferencia
                from shifts s join registers r on r.id = s.register_id where s.tenant_id = :t order by s.number""");
        EXPORTS.put("usuarios", """
                select u.full_name as nombre, u.email as correo, u.phone as telefono, u.active as activo, u.last_login_at as ultimo_ingreso
                from users u where u.tenant_id = :t order by u.full_name""");
    }

    /** ZIP con un CSV por entidad (separador ; y BOM para que Excel lo abra bien). */
    public Download exportData() {
        AuthUser me = requireAdmin();
        Long t = me.tenantId();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tx.executeWithoutResult(s -> em.unwrap(Session.class).doWork(conn -> {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> e : EXPORTS.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey() + ".csv"));
                    StringBuilder sb = new StringBuilder("﻿");
                    try (var ps = conn.prepareStatement(e.getValue().replace(":t", "?"))) {
                        ps.setLong(1, t);
                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData md = rs.getMetaData();
                            for (int i = 1; i <= md.getColumnCount(); i++) sb.append(i > 1 ? ";" : "").append(md.getColumnLabel(i));
                            sb.append("\r\n");
                            while (rs.next()) {
                                for (int i = 1; i <= md.getColumnCount(); i++) {
                                    Object v = rs.getObject(i);
                                    sb.append(i > 1 ? ";" : "").append(csv(v));
                                }
                                sb.append("\r\n");
                            }
                        }
                    }
                    zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("LEEME.txt"));
                zip.write(("Exportación de datos de TiendaPOSS - " + Instant.now() + "\r\nArchivos CSV separados por punto y coma (;), "
                        + "codificación UTF-8. Se abren directamente en Excel.\r\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }));
        tx.executeWithoutResult(s -> audit.sensitive("DATOS_EXPORTADOS", "TENANT", t, "Exportó todos los datos de la tienda (CSV)"));
        return new Download("tiendaposs_datos_" + LocalDate.now(zone(t)) + ".zip", out.toByteArray());
    }

    // ---------------- Utilidades ----------------

    private AuthUser requireAdmin() {
        CurrentUser.require(Permission.CONFIG_EDITAR);
        return CurrentUser.get();
    }

    private AuthUser requireOwner(String password) {
        AuthUser me = requireAdmin();
        if (!me.owner()) throw ApiException.forbidden("Solo el dueño de la tienda puede restaurar una copia");
        User u = users.findById(me.userId()).orElseThrow();
        if (password == null || !encoder.matches(password, u.getPasswordHash())) throw ApiException.field("password", "la contraseña no es correcta");
        return me;
    }

    String schemaVersion() {
        return tx.execute(s -> (String) em.createNativeQuery("select max(version) from flyway_schema_history where success").getSingleResult());
    }

    private ZoneId zone(Long t) {
        return ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v instanceof java.sql.Timestamp ts ? ts.toInstant().toString() : v instanceof java.math.BigDecimal b ? b.stripTrailingZeros().toPlainString()
                : v instanceof Boolean b ? (b ? "sí" : "no") : v.toString();
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String countsText(Map<String, Integer> c) {
        StringBuilder sb = new StringBuilder();
        for (var e : c.entrySet()) if (e.getValue() > 0) sb.append(sb.isEmpty() ? "" : ",").append(e.getKey()).append('=').append(e.getValue());
        return sb.length() > 2000 ? sb.substring(0, 2000) : sb.toString();
    }

    private static Map<String, Integer> parseCounts(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String p : s.split(",")) {
            String[] kv = p.split("=");
            if (kv.length == 2) m.put(kv[0], Integer.parseInt(kv[1]));
        }
        return m;
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(o.toString());
    }

    static String kb(long bytes) {
        return bytes < 1024 * 1024 ? (bytes / 1024 + 1) + " KB" : String.format("%.1f MB", bytes / 1048576.0);
    }

    private static byte[] gzip(byte[] in) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] in) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(in))) {
            // Límite de seguridad: una copia descomprimida no debería pasar de 500 MB.
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = gz.read(buf)) > 0) {
                total += n;
                if (total > 500L * 1024 * 1024) throw new IOException("demasiado grande");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
