package com.saas.foundation.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "saas.security.refresh-token")
public record RefreshTokenProperties(
        @NotNull Duration ttl
) {
}
