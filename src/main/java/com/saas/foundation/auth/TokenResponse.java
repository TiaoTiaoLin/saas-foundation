package com.saas.foundation.auth;

import java.time.Instant;
import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID tenantId,
        String organizationName
) {
}
