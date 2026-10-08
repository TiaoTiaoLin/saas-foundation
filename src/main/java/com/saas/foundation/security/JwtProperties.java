package com.saas.foundation.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Settings for the access tokens this application issues and validates.
 *
 * @param secret HMAC-SHA256 signing key; must be at least 32 bytes (256 bits)
 */
@Validated
@ConfigurationProperties(prefix = "saas.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String secret,
        @NotNull Duration accessTokenTtl
) {
}
