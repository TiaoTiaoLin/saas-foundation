package com.saas.foundation.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.saas.foundation.tenant.exception.MissingTenantException;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setsAndClearsTenant() {
        TenantContext.setTenantId("11111111-1111-1111-1111-111111111111");
        assertThat(TenantContext.isPresent()).isTrue();
        assertThat(TenantContext.requireTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");

        TenantContext.clear();
        assertThat(TenantContext.isPresent()).isFalse();
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(MissingTenantException.class);
    }

    @Test
    void rejectsBlankTenant() {
        assertThatThrownBy(() -> TenantContext.setTenantId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
