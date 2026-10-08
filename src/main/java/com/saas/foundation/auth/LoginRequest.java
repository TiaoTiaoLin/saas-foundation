package com.saas.foundation.auth;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param tenantId required only when the user belongs to more than one tenant
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        UUID tenantId
) {
}
