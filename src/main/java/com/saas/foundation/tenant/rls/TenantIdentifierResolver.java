package com.saas.foundation.tenant.rls;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import com.saas.foundation.tenant.TenantContext;

/**
 * Supplies the tenant id Hibernate applies to the {@code @TenantId} filter on every session.
 * The value is a {@link UUID} because {@code tenant_id} is a UUID column. When no request
 * tenant is bound, a nil UUID is used so session creation still succeeds; row-level security
 * hides tenant data until a real tenant is set.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    static final UUID FALLBACK_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return FALLBACK_TENANT;
        }
        return UUID.fromString(tenantId);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
