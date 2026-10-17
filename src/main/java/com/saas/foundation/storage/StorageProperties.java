package com.saas.foundation.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "saas.storage")
public record StorageProperties(
        @NotNull Duration signedUrlTtl,
        String url,
        String bucket,
        String serviceRoleKey,
        String localDirectory,
        String signingSecret
) {
}
