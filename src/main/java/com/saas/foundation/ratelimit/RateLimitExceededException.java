package com.saas.foundation.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String tenantId, long retryAfterSeconds) {
        super("Rate limit exceeded for tenant " + tenantId);
        this.tenantId = tenantId;
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
