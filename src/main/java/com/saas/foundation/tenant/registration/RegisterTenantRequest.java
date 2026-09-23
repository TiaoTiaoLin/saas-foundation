package com.saas.foundation.tenant.registration;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterTenantRequest(
        @NotBlank @Size(max = 128) String organizationName,
        @NotBlank @Email String adminEmail
) {
}
