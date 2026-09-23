package com.saas.foundation.ratelimit;

/**
 * Per-tenant distributed rate limiter.
 */
public interface TenantRateLimiter {

    /**
     * Consumes one token for the given tenant.
     *
     * @throws RateLimitExceededException when the tenant has exhausted its quota
     */
    void checkOrThrow(String tenantId);
}
