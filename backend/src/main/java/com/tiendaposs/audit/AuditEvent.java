package com.tiendaposs.audit;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;
    private Long userId;
    private String userName;
    private String action;
    private String entityType;
    private Long entityId;
    private String summary;
    private boolean sensitive;
    private String ip;
    private Instant createdAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(Long tenantId, Long userId, String userName, String action, String entityType,
                      Long entityId, String summary, boolean sensitive, String ip) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.userName = userName;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = summary.length() > 500 ? summary.substring(0, 500) : summary;
        this.sensitive = sensitive;
        this.ip = ip;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getSummary() { return summary; }
    public boolean isSensitive() { return sensitive; }
    public String getIp() { return ip; }
    public Instant getCreatedAt() { return createdAt; }
}
