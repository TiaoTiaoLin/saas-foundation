package com.saas.foundation.domain.user;

import com.saas.foundation.domain.common.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserAccount extends AuditedEntity {

    @Column(nullable = false, length = 320)
    private String email;

    /** Display name. Empty until the person sets one. */
    @Column(nullable = false, length = 80)
    private String name = "";

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Path inside the private image bucket. Empty means no avatar. */
    @Column(name = "avatar_key", length = 160)
    private String avatarKey;

    /** Required by JPA. */
    public UserAccount() {
    }

    public UserAccount(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
