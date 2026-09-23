package com.saas.foundation.tenant.rls;

import java.sql.PreparedStatement;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.saas.foundation.tenant.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds the active tenant into PostgreSQL (RLS GUC) and Hibernate (session filter)
 * at the start of every JPA transaction.
 *
 * <p>{@code set_config(..., is_local := true)} scopes the GUC to the current transaction,
 * which is safe with HikariCP pooled connections.
 */
@Slf4j
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public static final String TENANT_GUC = "app.current_tenant";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        applyTenantIsolation();
    }

    private void applyTenantIsolation() {
        if (!TenantContext.isPresent()) {
            log.trace("No tenant context bound; skipping RLS / Hibernate tenant filter");
            return;
        }

        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory());
        if (holder == null || holder.getEntityManager() == null) {
            log.warn("EntityManager not bound after doBegin; cannot apply tenant isolation");
            return;
        }

        EntityManager em = holder.getEntityManager();
        String tenantId = TenantContext.requireTenantId();
        UUID tenantUuid = UUID.fromString(tenantId);
        Session session = em.unwrap(Session.class);

        session.doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
                ps.setString(1, TENANT_GUC);
                ps.setString(2, tenantId);
                ps.execute();
            }
        });

        session.enableFilter(TenantHibernateFilterEnabler.FILTER_NAME)
                .setParameter(TenantHibernateFilterEnabler.PARAM_NAME, tenantUuid);

        log.debug("Applied tenant isolation for tenantId={}", tenantId);
    }
}
