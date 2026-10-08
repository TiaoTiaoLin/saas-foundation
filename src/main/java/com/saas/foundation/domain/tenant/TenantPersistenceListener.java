package com.saas.foundation.domain.tenant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;

/**
 * Notifies listeners that a tenant row changed. The callback runs at flush, still inside
 * the surrounding transaction, so listeners can wait until commit before touching the cache.
 */
@Component
public class TenantPersistenceListener {

    private final ApplicationEventPublisher events;

    public TenantPersistenceListener(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostPersist
    @PostUpdate
    public void onChange(Tenant tenant) {
        events.publishEvent(new TenantSaved(tenant.getId()));
    }
}
