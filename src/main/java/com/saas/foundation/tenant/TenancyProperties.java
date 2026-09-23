package com.saas.foundation.tenant;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "saas.tenancy")
public record TenancyProperties(
        @NotBlank String headerName,
        @NotNull List<String> excludedPathPatterns
) {
}
