package com.saas.foundation.support;

import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantStatus;

public final class TestTenants {

    private TestTenants() {
    }

    public static Tenant ensure(TenantRepository repository, String tenantId, TenantStatus status) {
        UUID id = UUID.fromString(tenantId);
        return repository.findById(id).orElseGet(() -> {
            Tenant tenant = new Tenant("Tenant " + tenantId);
            ReflectionTestUtils.setField(tenant, "id", id);
            tenant.setStatus(status);
            if (status == TenantStatus.ACTIVE) {
                tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            }
            return repository.saveAndFlush(tenant);
        });
    }
}
