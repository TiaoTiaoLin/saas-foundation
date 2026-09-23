package com.saas.foundation.tenant;

/**
 * Extension point for resolving whether a tenant is active / provisioned.
 * Default implementation accepts any well-formed UUID (format already validated by interceptor).
 */
public interface TenantResolver {

    boolean isActive(String tenantId);
}
