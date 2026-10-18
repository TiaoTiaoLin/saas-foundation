package com.saas.foundation.domain.billing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** A Stripe event that has already updated the organization. */
@Entity
@Table(name = "billing_events")
@Getter
public class BillingEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Required by JPA. */
    public BillingEvent() {
    }

    public BillingEvent(String eventId, String eventType, Instant receivedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }
}
