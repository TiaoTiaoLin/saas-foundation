package com.saas.foundation.integration.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Resend credentials. {@code apiKey} is {@code RESEND_API_KEY}; {@code from} is {@code RESEND_FROM}
 * and must be an address on a domain verified in Resend, for example {@code Acme <hello@example.com>}.
 */
@Validated
@ConfigurationProperties(prefix = "saas.integrations.email")
public record EmailProperties(
        @NotBlank String apiKey,
        @NotBlank String from
) {
}
