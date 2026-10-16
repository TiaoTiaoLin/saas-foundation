package com.saas.foundation.domain.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MembershipInviteRepository extends JpaRepository<MembershipInvite, UUID> {

    Optional<MembershipInvite> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from MembershipInvite i
            where i.tenantId = :tenantId and i.userId = :userId and i.acceptedAt is null
            """)
    int deletePending(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
