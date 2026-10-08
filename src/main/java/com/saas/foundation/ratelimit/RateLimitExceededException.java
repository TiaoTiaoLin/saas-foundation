package com.saas.foundation.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String tenantId, long retryAfterSeconds) {
        this("Rate limit exceeded for tenant " + tenantId, tenantId, retryAfterSeconds);
    }

    private RateLimitExceededException(String message, String tenantId, long retryAfterSeconds) {
        super(message);
        this.tenantId = tenantId;
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    /** For limits on unauthenticated endpoints (signup, login, refresh) where there is no tenant. */
    public static RateLimitExceededException withoutTenant(long retryAfterSeconds) {
        return new RateLimitExceededException("Too many requests; try again later", null, retryAfterSeconds);
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
