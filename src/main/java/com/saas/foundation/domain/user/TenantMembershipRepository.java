package com.saas.foundation.domain.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    List<TenantMembership> findByUserId(UUID userId);

    List<TenantMembership> findByTenantId(UUID tenantId);

    Optional<TenantMembership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    /** Locks every membership in the tenant so the last owner cannot be removed twice at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TenantMembership m where m.tenantId = :tenantId")
    List<TenantMembership> findByTenantIdForUpdate(@Param("tenantId") UUID tenantId);
}
