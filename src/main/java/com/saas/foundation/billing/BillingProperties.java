package com.saas.foundation.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "saas.billing")
public record BillingProperties(
        String secretKey,
        String webhookSecret,
        String proPriceId,
        @NotBlank String appBaseUrl
) {

    public boolean configured() {
        return present(secretKey) && present(webhookSecret) && present(proPriceId);
    }

    public String successUrl() {
        return base() + "/billing?checkout=success";
    }

    public String cancelUrl() {
        return base() + "/billing?checkout=cancel";
    }

    public String returnUrl() {
        return base() + "/billing";
    }

    private String base() {
        return appBaseUrl.replaceAll("/+$", "");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
