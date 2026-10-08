package com.saas.foundation.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Limits for unauthenticated auth endpoints, which the per-tenant limiter cannot cover.
 */
@Validated
@ConfigurationProperties(prefix = "saas.auth-rate-limit")
public record AuthRateLimitProperties(
        boolean enabled,
        @NotBlank String keyPrefix,
        @NotNull @Valid Limit loginPerIp,
        @NotNull @Valid Limit loginPerEmail,
        @NotNull @Valid Limit signupPerIp,
        @NotNull @Valid Limit refreshPerIp
) {

    public record Limit(@Min(1) long capacity, @NotNull Duration window) {
    }
}
