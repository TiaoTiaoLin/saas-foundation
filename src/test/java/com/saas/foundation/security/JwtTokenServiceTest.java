package com.saas.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.saas.foundation.security.JwtTokenService.IssuedToken;

class JwtTokenServiceTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "saas-foundation-test", "unit-test-jwt-secret-at-least-32-bytes-long", Duration.ofMinutes(15));

    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtTokenService tokenService = tokenServiceFor(PROPERTIES);
    private final JwtDecoder decoder = jwtConfig.jwtDecoder(PROPERTIES);

    @Test
    void issuedTokenCarriesUserTenantAndRoles() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        IssuedToken token = tokenService.issueAccessToken(userId, tenantId, List.of("ADMIN"));
        Jwt jwt = decoder.decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(JwtTokenService.TENANT_ID_CLAIM)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM)).containsExactly("ADMIN");
        assertThat(jwt.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(PROPERTIES.issuer());
        assertThat(jwt.getExpiresAt()).isEqualTo(token.expiresAt());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtProperties otherSecret = new JwtProperties(
                PROPERTIES.issuer(), "another-secret-that-is-also-32-bytes-long", PROPERTIES.accessTokenTtl());
        IssuedToken forged = tokenServiceFor(otherSecret)
                .issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertThatThrownBy(() -> decoder.decode(forged.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties otherIssuer = new JwtProperties(
                "someone-else", PROPERTIES.secret(), PROPERTIES.accessTokenTtl());
        IssuedToken token = tokenServiceFor(otherIssuer)
                .issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtException.class);
    }

    private JwtTokenService tokenServiceFor(JwtProperties properties) {
        return new JwtTokenService(jwtConfig.jwtEncoder(properties), properties);
    }
}
