package com.saas.foundation.tenant.registration;

import java.util.UUID;

public record RegisterTenantResponse(
        String tenantId,
        String organizationName,
        String adminEmail,
        boolean welcomeEmailAccepted,
        String welcomeEmailDetail
) {
}
