package com.saas.foundation.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.RefreshToken;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.RefreshTokenProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Issues, validates and rotates opaque refresh tokens. Callers own the transaction;
 * see {@link RefreshTokenReuseException} for the rollback requirement.
 */
@Slf4j
@Component
public class RefreshTokenService {

    static final String INVALID_REFRESH_TOKEN = "Invalid refresh token";

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenProperties properties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, RefreshTokenProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    public IssuedRefreshToken issue(UUID userId, UUID tenantId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(properties.ttl());

        RefreshToken saved = refreshTokenRepository.save(
                new RefreshToken(tenantId, userId, Hashing.sha256Hex(value), expiresAt));
        return new IssuedRefreshToken(saved.getId(), value, expiresAt);
    }

    /**
     * Returns the locked, still-valid token for {@code rawToken}. Presenting a token that was
     * already rotated revokes every active token of that user in that tenant.
     */
    public RefreshToken requireUsable(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(rawToken))
                .orElseThrow(RefreshTokenService::invalid);
        Instant now = Instant.now();

        if (token.isRevoked()) {
            if (token.getReplacedBy() != null) {
                int revoked = refreshTokenRepository.revokeAllActive(token.getUserId(), token.getTenantId(), now);
                log.warn("Refresh token reuse detected userId={} tenantId={} revokedSessions={}",
                        token.getUserId(), token.getTenantId(), revoked);
                throw new RefreshTokenReuseException();
            }
            throw invalid();
        }
        if (token.isExpiredAt(now)) {
            throw invalid();
        }
        return token;
    }

    public IssuedRefreshToken rotate(RefreshToken current) {
        IssuedRefreshToken next = issue(current.getUserId(), current.getTenantId());
        current.markReplaced(Instant.now(), next.id());
        return next;
    }

    /** Revokes every open session for the user, in every tenant. */
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now());
    }

    public Optional<RefreshToken> findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(rawToken));
    }

    /** Idempotent: unknown or already revoked tokens are ignored. */
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(rawToken))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_TOKEN);
    }

    public record IssuedRefreshToken(UUID id, String value, Instant expiresAt) {
    }
}
