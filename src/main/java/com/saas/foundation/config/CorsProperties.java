package com.saas.foundation.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Browser origins allowed to call the API. The frontend runs on its own origin,
 * so requests from that origin are rejected unless they are listed here.
 */
@Validated
@ConfigurationProperties(prefix = "saas.cors")
public record CorsProperties(
        @NotNull List<String> allowedOrigins
) {
}
