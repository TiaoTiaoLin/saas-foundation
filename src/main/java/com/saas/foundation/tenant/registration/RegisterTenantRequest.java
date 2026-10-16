package com.saas.foundation.tenant.registration;

import com.saas.foundation.auth.UserName;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterTenantRequest(
        @NotBlank @Size(max = 128) String organizationName,
        @NotBlank @Size(max = UserName.MAX_LENGTH) String adminName,
        @NotBlank @Email @Size(max = 320) String adminEmail,
        @NotBlank @Size(min = 12, max = 72) String adminPassword
) {
}
