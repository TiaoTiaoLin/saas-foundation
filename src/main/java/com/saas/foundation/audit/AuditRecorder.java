package com.saas.foundation.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.saas.foundation.domain.audit.AuditAction;
import com.saas.foundation.domain.audit.AuditEvent;
import com.saas.foundation.domain.audit.AuditEventRepository;
import com.saas.foundation.domain.audit.AuditTargetType;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;

/**
 * Inserts an audit row into the caller's transaction.
 * A failed business change rolls the row back with it.
 */
@Service
public class AuditRecorder {

    private final AuditEventRepository auditEventRepository;
    private final TenantMembershipRepository tenantMembershipRepository;

    public AuditRecorder(AuditEventRepository auditEventRepository,
                         TenantMembershipRepository tenantMembershipRepository) {
        this.auditEventRepository = auditEventRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
    }

    public void record(AuditAction action,
                       UUID tenantId,
                       UUID actorUserId,
                       AuditTargetType targetType,
                       UUID targetId,
                       Map<String, String> metadata) {
        auditEventRepository.save(new AuditEvent(
                null,
                tenantId,
                actorUserId,
                action,
                targetType,
                targetId,
                clean(metadata),
                null));
    }

    /** One row for each organization the user belongs to. No memberships leaves the tenant empty. */
    public void recordForEachMembership(AuditAction action,
                                        UUID actorUserId,
                                        AuditTargetType targetType,
                                        UUID targetId,
                                        Map<String, String> metadata) {
        List<TenantMembership> memberships = tenantMembershipRepository.findByUserId(actorUserId);
        if (memberships.isEmpty()) {
            record(action, null, actorUserId, targetType, targetId, metadata);
            return;
        }
        for (TenantMembership membership : memberships) {
            record(action, membership.getTenantId(), actorUserId, targetType, targetId, metadata);
        }
    }

    private static Map<String, String> clean(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}
