package com.tiendaposs.audit;

import com.tiendaposs.tenant.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final AuditRepository repo;

    public AuditController(AuditRepository repo) {
        this.repo = repo;
    }

    public record AuditDto(Long id, Long userId, String userName, String action, String entityType, Long entityId,
                           String summary, boolean sensitive, String ip, Instant createdAt) {
        static AuditDto of(AuditEvent e) {
            return new AuditDto(e.getId(), e.getUserId(), e.getUserName(), e.getAction(), e.getEntityType(),
                    e.getEntityId(), e.getSummary(), e.isSensitive(), e.getIp(), e.getCreatedAt());
        }
    }

    public record AuditPage(List<AuditDto> items, long total, int page, int pages) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDITORIA_VER')")
    public AuditPage search(@RequestParam(required = false) Long userId,
                            @RequestParam(required = false) String action,
                            @RequestParam(defaultValue = "false") boolean sensitiveOnly,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        Instant f = from == null ? Instant.EPOCH : from.atStartOfDay(ZONE).toInstant();
        Instant t = to == null ? Instant.now().plusSeconds(60) : to.plusDays(1).atStartOfDay(ZONE).toInstant();
        var result = repo.search(TenantContext.require(), userId, blankToNull(action), sensitiveOnly, f, t,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return new AuditPage(result.map(AuditDto::of).getContent(), result.getTotalElements(), result.getNumber(),
                result.getTotalPages());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
