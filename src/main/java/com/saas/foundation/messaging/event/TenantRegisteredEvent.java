package com.saas.foundation.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record TenantRegisteredEvent(
        UUID eventId,
        String tenantId,
        String organizationName,
        String adminEmail,
        Instant occurredAt
) {
    public static TenantRegisteredEvent of(String tenantId, String organizationName, String adminEmail) {
        return new TenantRegisteredEvent(
                UUID.randomUUID(),
                tenantId,
                organizationName,
                adminEmail,
                Instant.now()
        );
    }
}
