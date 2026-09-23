package com.saas.foundation.tenant.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import com.saas.foundation.tenant.TenancyProperties;
import com.saas.foundation.tenant.TenantContext;
import com.saas.foundation.tenant.TenantResolver;
import com.saas.foundation.tenant.exception.InvalidTenantException;
import com.saas.foundation.tenant.exception.MissingTenantException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds {@code X-Tenant-ID} (configurable) onto {@link TenantContext} for the request lifecycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );

    private final TenancyProperties tenancyProperties;
    private final TenantResolver tenantResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isExcluded(request)) {
            return true;
        }

        String headerName = tenancyProperties.headerName();
        String rawTenantId = request.getHeader(headerName);

        if (rawTenantId == null || rawTenantId.isBlank()) {
            throw new MissingTenantException("Request header '" + headerName + "' is required");
        }

        String tenantId = rawTenantId.trim();
        if (!isValidUuid(tenantId)) {
            throw new InvalidTenantException(tenantId);
        }

        // Normalize to canonical UUID string
        String normalized = UUID.fromString(tenantId).toString();
        if (!tenantResolver.isActive(normalized)) {
            throw new InvalidTenantException(normalized);
        }

        TenantContext.setTenantId(normalized);
        log.debug("Bound tenant context: tenantId={}", normalized);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return tenancyProperties.excludedPathPatterns().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private static boolean isValidUuid(String value) {
        if (!TENANT_ID_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
