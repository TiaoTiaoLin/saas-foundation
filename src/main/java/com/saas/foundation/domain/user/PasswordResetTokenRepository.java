package com.saas.foundation.domain.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PasswordResetToken t
            set t.usedAt = :now
            where t.userId = :userId and t.usedAt is null
            """)
    int invalidateActive(@Param("userId") UUID userId, @Param("now") Instant now);
}
