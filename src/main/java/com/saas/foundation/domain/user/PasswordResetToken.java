package com.saas.foundation.domain.user;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * A single-use password reset. Only the SHA-256 hash of the token is stored.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
public class PasswordResetToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    public PasswordResetToken() {
    }

    public PasswordResetToken(UUID userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        if (usedAt == null) {
            usedAt = now;
        }
    }
}
