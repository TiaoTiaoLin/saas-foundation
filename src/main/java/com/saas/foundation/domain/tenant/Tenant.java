package com.saas.foundation.domain.tenant;

import java.time.Instant;

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

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", length = 255)
    private String stripeSubscriptionId;

    /** NONE until a Pro checkout completes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 32)
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    /** FREE for every organization. PRO is granted by an active Pro subscription. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Plan plan = Plan.FREE;

    /** Stripe {@code event.created} of the newest billing event applied to this organization. */
    @Column(name = "stripe_event_created_at")
    private Instant stripeEventCreatedAt;

    /** Required by JPA. */
    public Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    /** Products are included with the free plan while the organization is active. */
    public boolean allowsProductAccess() {
        return isActive();
    }

    /** The audit log is included with an active Pro subscription. */
    public boolean allowsAuditAccess() {
        return isActive() && plan == Plan.PRO && subscriptionStatus == SubscriptionStatus.ACTIVE;
    }

    /** An open Pro subscription should be managed, not replaced with a second checkout. */
    public boolean hasOpenProSubscription() {
        return plan == Plan.PRO
                && (subscriptionStatus == SubscriptionStatus.ACTIVE
                || subscriptionStatus == SubscriptionStatus.PAST_DUE);
    }
}
