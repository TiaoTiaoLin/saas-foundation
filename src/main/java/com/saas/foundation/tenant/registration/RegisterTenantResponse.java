package com.saas.foundation.tenant.registration;

public record RegisterTenantResponse(
        String tenantId,
        String organizationName,
        String adminEmail,
        boolean welcomeEmailAccepted,
        String welcomeEmailDetail
) {
}
