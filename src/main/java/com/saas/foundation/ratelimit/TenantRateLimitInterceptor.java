package com.saas.foundation.ratelimit;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import com.saas.foundation.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
/**
 * Runs after {@link com.saas.foundation.tenant.web.TenantInterceptor} and enforces
 * per-tenant quotas. Sets {@code X-RateLimit-Limit} / {@code Retry-After} on rejection via exception handler.
 */
@Component
public class TenantRateLimitInterceptor implements HandlerInterceptor, Ordered {

    private final TenantRateLimiter tenantRateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TenantRateLimitInterceptor(TenantRateLimiter tenantRateLimiter, RateLimitProperties rateLimitProperties) {
        this.tenantRateLimiter = tenantRateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!rateLimitProperties.enabled() || isExcluded(request) || !TenantContext.isPresent()) {
            return true;
        }
        tenantRateLimiter.checkOrThrow(TenantContext.requireTenantId());
        return true;
    }

    @Override
    public int getOrder() {
        // After tenant binding (which uses default Ordered.LOWEST_PRECEDENCE - 0 style via registry order)
        return 100;
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return rateLimitProperties.excludedPathPatterns().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}
