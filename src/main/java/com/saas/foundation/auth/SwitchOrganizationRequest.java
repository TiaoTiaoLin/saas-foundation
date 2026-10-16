package com.saas.foundation.auth;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SwitchOrganizationRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 256) String refreshToken
) {
}
