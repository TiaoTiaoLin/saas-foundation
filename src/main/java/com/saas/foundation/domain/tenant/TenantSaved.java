package com.saas.foundation.domain.tenant;

import java.util.UUID;

/**
 * Published when a tenant row is inserted or updated, while that transaction is still open.
 */
public record TenantSaved(UUID tenantId) {
}
