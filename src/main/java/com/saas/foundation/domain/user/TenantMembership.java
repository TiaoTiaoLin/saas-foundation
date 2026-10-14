package com.saas.foundation.domain.user;

import java.util.UUID;

import com.saas.foundation.domain.common.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "tenant_memberships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tenant_memberships_tenant_user",
                columnNames = {"tenant_id", "user_id"}
        )
)
@Getter
@Setter
public class TenantMembership extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MembershipRole role;

    /** Required by JPA. */
    public TenantMembership() {
    }

    public TenantMembership(UUID tenantId, UUID userId, MembershipRole role) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
    }
}
