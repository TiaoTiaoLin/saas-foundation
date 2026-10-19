package com.saas.foundation.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.saas.foundation.domain.audit.AuditEvent;

public record AuditEventResponse(
        UUID id,
        String action,
        String targetType,
        UUID targetId,
        UUID actorUserId,
        Map<String, String> metadata,
        Instant createdAt
) {

    static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getAction().name(),
                event.getTargetType().name(),
                event.getTargetId(),
                event.getActorUserId(),
                event.getMetadata() == null ? Map.of() : event.getMetadata(),
                event.getCreatedAt());
    }
}
