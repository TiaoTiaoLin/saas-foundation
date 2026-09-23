package com.saas.foundation.tenant.rls;

import java.util.Optional;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.saas.foundation.tenant.TenantContext;

@Configuration
public class HibernateTenancyConfig {

    /**
     * Sentinel used only when no HTTP/async tenant is bound (e.g. schema tooling).
     * RLS still blocks real data access without a valid tenant GUC.
     */
    private static final String FALLBACK_TENANT = "00000000-0000-0000-0000-000000000000";

    @Bean
    public CurrentTenantIdentifierResolver<String> currentTenantIdentifierResolver() {
        return new CurrentTenantIdentifierResolver<>() {
            @Override
            public String resolveCurrentTenantIdentifier() {
                return Optional.ofNullable(TenantContext.getTenantId()).orElse(FALLBACK_TENANT);
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return true;
            }
        };
    }

    @Bean
    public HibernatePropertiesCustomizer hibernateTenancyCustomizer(
            CurrentTenantIdentifierResolver<String> resolver) {
        return props -> props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
