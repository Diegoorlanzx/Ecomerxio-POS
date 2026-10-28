package com.tiendaposs.backup;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.backup.BackupService.*;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BackupController {

    private final BackupService backups;
    private final SettingsService settings;
    private final AuditService audit;
    private final EntityManager em;
    private final com.tiendaposs.auth.RateLimiter limiter;

    public BackupController(BackupService backups, SettingsService settings, AuditService audit, EntityManager em,
                            com.tiendaposs.auth.RateLimiter limiter) {
        this.limiter = limiter;
        this.backups = backups;
        this.settings = settings;
        this.audit = audit;
        this.em = em;
    }

    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public Map<String, Object> list() {
        StoreSettings st = settings.forTenant(TenantContext.require());
        return Map.of("autoBackup", st.autoBackup, "backups", backups.list(), "schemaVersion", backups.schemaVersion());
    }

    @PostMapping("/backups")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public BackupDto create() {
        return backups.createManual();
    }

    @GetMapping("/backups/{id}/download")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Download d = backups.download(id);
        return file(d, MediaType.APPLICATION_OCTET_STREAM);
    }

    public record PasswordRequest(@NotBlank(message = "escribe tu contraseña") @Size(max = 200) String password) {
    }

    @PostMapping("/backups/{id}/restore")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public RestoreResult restore(@PathVariable Long id, @Valid @RequestBody PasswordRequest r) {
        limitPasswordTries();
        return backups.restoreStored(id, r.password());
    }

    /** Restaurar desde un archivo .tpbak: el cuerpo es el archivo; la contraseña va en la cabecera X-Password. */
    @PostMapping(value = "/backups/restore-file", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public RestoreResult restoreFile(@RequestBody byte[] data, @RequestHeader(value = "X-Password", required = false) String password) {
        if (password == null || password.isBlank()) throw ApiException.field("password", "escribe tu contraseña");
        limitPasswordTries();
        return backups.restoreUpload(data, password);
    }

    /** Restaurar pide la contraseña: con tope, una sesión abierta ajena no sirve para adivinarla (y no se repite una restauración pesada). */
    private void limitPasswordTries() {
        limiter.check("password-confirm", String.valueOf(com.tiendaposs.security.CurrentUser.get().userId()), 10, 900);
    }

    public record AutoRequest(boolean autoBackup) {
    }

    @PutMapping("/backups/settings")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    @Transactional
    public Map<String, Boolean> auto(@RequestBody AutoRequest r) {
        StoreSettings st = settings.current();
        st.autoBackup = r.autoBackup();
        st.updatedAt = Instant.now();
        audit.sensitive("CONFIG_COPIAS", "TENANT", st.tenantId, (r.autoBackup() ? "Activó" : "Desactivó") + " la copia de seguridad automática");
        return Map.of("autoBackup", st.autoBackup);
    }

    @GetMapping("/data-export")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ResponseEntity<byte[]> export() {
        return file(backups.exportData(), new MediaType("application", "zip"));
    }

    // ---------------- Sucursales (estructura preparada) ----------------

    public record BranchDto(Long id, String name, String address, String phone, boolean main, boolean active, long registers) {
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/branches")
    @Transactional(readOnly = true)
    public List<BranchDto> branches() {
        List<Object[]> rows = em.createNativeQuery("""
                        select b.id, b.name, b.address, b.phone, b.is_main, b.active, (select count(*) from registers r where r.branch_id = b.id)
                        from branches b where b.tenant_id = :t order by b.is_main desc, b.name""")
                .setParameter("t", TenantContext.require()).getResultList();
        return rows.stream().map(r -> new BranchDto(((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3], (Boolean) r[4],
                (Boolean) r[5], ((Number) r[6]).longValue())).toList();
    }

    public record BranchRequest(@NotBlank(message = "es obligatorio") @Size(max = 80) String name, @Size(max = 200) String address,
                                @Size(max = 30) String phone) {
    }

    @PutMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    @Transactional
    public List<BranchDto> updateBranch(@PathVariable Long id, @Valid @RequestBody BranchRequest r) {
        int n = em.createNativeQuery("update branches set name = :n, address = :a, phone = :p where id = :id and tenant_id = :t")
                .setParameter("n", r.name().trim()).setParameter("a", blank(r.address())).setParameter("p", blank(r.phone()))
                .setParameter("id", id).setParameter("t", TenantContext.require()).executeUpdate();
        if (n == 0) throw ApiException.notFound("Sucursal no encontrada");
        audit.log("SUCURSAL", "TENANT", id, "Actualizó la sucursal " + r.name().trim());
        return branches();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseEntity<byte[]> file(Download d, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.fileName() + "\"")
                .contentType(type).body(d.data());
    }
}
