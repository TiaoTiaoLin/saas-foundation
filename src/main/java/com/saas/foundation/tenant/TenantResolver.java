package com.saas.foundation.tenant;

/**
 * Whether a tenant exists and is allowed to serve requests.
 * The caller validates the identifier format before this is invoked.
 */
public interface TenantResolver {

    /** The organization exists and has not been suspended. */
    boolean isActive(String tenantId);

    /** Product routes are open only while the subscription is active. */
    boolean allowsProductAccess(String tenantId);
}
