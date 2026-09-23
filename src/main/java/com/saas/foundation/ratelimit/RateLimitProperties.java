package com.saas.foundation.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "saas.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) long capacity,
        @NotNull Duration window,
        @NotBlank String keyPrefix,
        @NotNull List<String> excludedPathPatterns
) {
}
