package com.saas.foundation.domain.common;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.saas.foundation.tenant.TenantContext;
import com.saas.foundation.tenant.rls.TenantHibernateFilterEnabler;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Base type for all tenant-scoped aggregates.
 * {@code tenant_id} is stamped from {@link TenantContext} and enforced by PostgreSQL RLS
 * plus the Hibernate {@code tenantFilter}.
 */
@Getter
@Setter
@MappedSuperclass
@FilterDef(
        name = TenantHibernateFilterEnabler.FILTER_NAME,
        parameters = @ParamDef(name = TenantHibernateFilterEnabler.PARAM_NAME, type = UUID.class)
)
@Filter(
        name = TenantHibernateFilterEnabler.FILTER_NAME,
        condition = "tenant_id = :" + TenantHibernateFilterEnabler.PARAM_NAME
)
@EntityListeners(AuditingEntityListener.class)
public abstract class TenantScopedEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Hibernate 6 {@code @TenantId} participates in multi-tenancy DISCRIMINATOR strategy
     * when configured; we also set it explicitly from TenantContext for clarity.
     */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (tenantId == null) {
            tenantId = UUID.fromString(TenantContext.requireTenantId());
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        // Prevent cross-tenant mutation via detached entities
        UUID active = UUID.fromString(TenantContext.requireTenantId());
        if (!active.equals(tenantId)) {
            throw new IllegalStateException("Attempted cross-tenant entity mutation");
        }
    }
}
