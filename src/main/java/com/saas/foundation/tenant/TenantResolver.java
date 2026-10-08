package com.saas.foundation.tenant;

/**
 * Whether a tenant exists and is allowed to serve requests.
 * The caller validates the identifier format before this is invoked.
 */
public interface TenantResolver {

    boolean isActive(String tenantId);
}
