package com.saas.foundation.support;

import java.util.List;
import java.util.UUID;

import com.saas.foundation.security.JwtTokenService;

/**
 * Issues real signed access tokens through the application's {@link JwtTokenService},
 * so integration tests exercise the same validation as production requests.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String bearer(JwtTokenService tokenService, String tenantId) {
        return bearer(tokenService, tenantId, "OWNER");
    }

    public static String bearer(JwtTokenService tokenService, String tenantId, String role) {
        String token = tokenService.issueAccessToken(
                UUID.randomUUID(), UUID.fromString(tenantId), List.of(role)).value();
        return "Bearer " + token;
    }
}
