package com.saas.foundation.tenant.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.tenant.TenancyProperties;
import com.saas.foundation.tenant.TenantContext;
import com.saas.foundation.tenant.TenantResolver;
import com.saas.foundation.tenant.exception.InvalidTenantException;
import com.saas.foundation.tenant.exception.MissingTenantException;
import com.saas.foundation.tenant.exception.TenantAccessDeniedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds the request's tenant onto {@link TenantContext} for the request lifecycle.
 *
 * <p>For JWT-authenticated requests the tenant comes from the token's
 * {@value JwtTokenService#TENANT_ID_CLAIM} claim; a tenant header, if sent, must match it.
 * Unauthenticated requests (only reachable where security permits them) fall back to the
 * {@code X-Tenant-ID} header (configurable).
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String SUBSCRIPTION_REQUIRED = "An active subscription is required";

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );

    private final TenancyProperties tenancyProperties;
    private final TenantResolver tenantResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TenantInterceptor(TenancyProperties tenancyProperties, TenantResolver tenantResolver) {
        this.tenancyProperties = tenancyProperties;
        this.tenantResolver = tenantResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isExcluded(request)) {
            return true;
        }

        String headerName = tenancyProperties.headerName();
        String rawHeaderTenantId = request.getHeader(headerName);
        boolean headerPresent = rawHeaderTenantId != null && !rawHeaderTenantId.isBlank();

        String tenantId;
        String tokenTenantId = tenantIdFromToken();
        if (tokenTenantId != null) {
            if (headerPresent && !tokenTenantId.equals(normalize(rawHeaderTenantId))) {
                throw new TenantAccessDeniedException(
                        "Header '" + headerName + "' does not match the authenticated tenant");
            }
            tenantId = tokenTenantId;
        } else {
            if (!headerPresent) {
                throw new MissingTenantException("Request header '" + headerName + "' is required");
            }
            tenantId = normalize(rawHeaderTenantId);
        }

        if (!tenantResolver.isActive(tenantId) && !billingPath(request)) {
            throw new InvalidTenantException(tenantId);
        }
        if (productPath(request) && !tenantResolver.allowsProductAccess(tenantId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, SUBSCRIPTION_REQUIRED);
        }

        TenantContext.setTenantId(tenantId);
        log.debug("Bound tenant context: tenantId={} fromToken={}", tenantId, tokenTenantId != null);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private static String tenantIdFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return null;
        }
        String claim = jwtAuthentication.getToken().getClaimAsString(JwtTokenService.TENANT_ID_CLAIM);
        if (claim == null || claim.isBlank()) {
            throw new TenantAccessDeniedException("Access token has no tenant");
        }
        return normalize(claim);
    }

    /** Billing stays available after a subscription ends so the owner can subscribe again. */
    private boolean billingPath(HttpServletRequest request) {
        return pathMatcher.match("/api/v1/billing/**", request.getRequestURI());
    }

    private boolean productPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return pathMatcher.match("/api/v1/products", path)
                || pathMatcher.match("/api/v1/products/**", path);
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return tenancyProperties.excludedPathPatterns().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /** Validates the identifier and returns it as a canonical lowercase UUID string. */
    private static String normalize(String rawTenantId) {
        String tenantId = rawTenantId.trim();
        if (!isValidUuid(tenantId)) {
            throw new InvalidTenantException(tenantId);
        }
        return UUID.fromString(tenantId).toString();
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
