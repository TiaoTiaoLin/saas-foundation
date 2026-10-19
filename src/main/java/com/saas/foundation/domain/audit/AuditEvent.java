package com.saas.foundation.domain.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

/** One recorded action. Rows are inserted and never updated. */
@Entity
@Table(name = "audit_events")
@Getter
public class AuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private AuditTargetType targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    public AuditEvent() {
    }

    public AuditEvent(UUID id,
                      UUID tenantId,
                      UUID actorUserId,
                      AuditAction action,
                      AuditTargetType targetType,
                      UUID targetId,
                      Map<String, String> metadata,
                      Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
