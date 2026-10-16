package com.saas.foundation.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param appBaseUrl public origin of the web app, with no trailing slash, used in the invite link
 */
@Validated
@ConfigurationProperties(prefix = "saas.security.membership-invite")
public record MembershipInviteProperties(
        @NotNull Duration ttl,
        @NotBlank String appBaseUrl
) {
}
