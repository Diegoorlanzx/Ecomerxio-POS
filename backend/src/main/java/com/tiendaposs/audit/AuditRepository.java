package com.tiendaposs.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    @Query("""
            select e from AuditEvent e
            where e.tenantId = :tenantId
              and (:userId is null or e.userId = :userId)
              and (:action is null or e.action = :action)
              and (:sensitiveOnly = false or e.sensitive = true)
              and e.createdAt >= :from and e.createdAt < :to
            order by e.createdAt desc, e.id desc
            """)
    Page<AuditEvent> search(Long tenantId, Long userId, String action, boolean sensitiveOnly,
                            Instant from, Instant to, Pageable pageable);
}
