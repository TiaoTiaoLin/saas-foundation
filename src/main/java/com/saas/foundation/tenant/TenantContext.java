package com.saas.foundation.tenant;

/**
 * Thread-local holder for the active tenant identifier.
 * Always clear in a {@code finally} block (see {@link com.saas.foundation.tenant.web.TenantInterceptor})
 * to avoid leaking context across pooled threads.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }
        CURRENT_TENANT.set(tenantId.trim());
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * @throws com.saas.foundation.tenant.exception.MissingTenantException if no tenant is bound
     */
    public static String requireTenantId() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new com.saas.foundation.tenant.exception.MissingTenantException();
        }
        return tenantId;
    }

    public static boolean isPresent() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId != null && !tenantId.isBlank();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
