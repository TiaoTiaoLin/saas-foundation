package com.saas.foundation.domain.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Row-locked so two concurrent refreshes with the same token cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.userId = :userId and t.tenantId = :tenantId and t.revokedAt is null
            """)
    int revokeAllActive(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId, @Param("now") Instant now);
}
