package com.saas.foundation.tenant;

import org.springframework.stereotype.Component;

/**
 * Permissive resolver for the foundation skeleton.
 * Replace with a registry-backed implementation (DB / Redis) in production.
 */
@Component
public class PermissiveTenantResolver implements TenantResolver {

    @Override
    public boolean isActive(String tenantId) {
        return tenantId != null && !tenantId.isBlank();
    }
}
