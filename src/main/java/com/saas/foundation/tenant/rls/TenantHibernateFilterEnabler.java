package com.saas.foundation.tenant.rls;

import org.hibernate.Session;
import org.springframework.stereotype.Component;

import com.saas.foundation.tenant.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Enables Hibernate's {@code tenantFilter} as defense-in-depth alongside PostgreSQL RLS.
 * Call from services that perform reads via the Hibernate Session API, or rely on
 * {@link TenantEntityListener} / repository base class integration.
 */
@Slf4j
@Component
public class TenantHibernateFilterEnabler {

    public static final String FILTER_NAME = "tenantFilter";
    public static final String PARAM_NAME = "tenantId";

    @PersistenceContext
    private EntityManager entityManager;

    public void enableForCurrentTenant() {
        String tenantId = TenantContext.requireTenantId();
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(FILTER_NAME)
                .setParameter(PARAM_NAME, java.util.UUID.fromString(tenantId));
        log.trace("Enabled Hibernate tenantFilter for tenantId={}", tenantId);
    }
}
