package com.saas.foundation.tenant;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "saas.tenancy")
public record TenancyProperties(
        @NotBlank String headerName,
        @DefaultValue("tenant:active:") @NotBlank String activeCacheKeyPrefix,
        @DefaultValue("1m") @NotNull Duration activeCacheTtl,
        @NotNull List<String> excludedPathPatterns
) {
}
