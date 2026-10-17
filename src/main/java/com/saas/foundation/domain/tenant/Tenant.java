package com.saas.foundation.domain.tenant;

import com.saas.foundation.domain.common.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@EntityListeners(TenantPersistenceListener.class)
@Getter
@Setter
public class Tenant extends AuditedEntity {

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TenantStatus status = TenantStatus.ACTIVE;

    /** Path inside the private image bucket. Empty means no logo. */
    @Column(name = "logo_key", length = 160)
    private String logoKey;

    /** Required by JPA. */
    public Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
