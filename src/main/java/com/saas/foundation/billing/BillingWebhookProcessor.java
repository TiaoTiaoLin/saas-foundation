package com.saas.foundation.billing;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.stripe.model.Event;

import lombok.extern.slf4j.Slf4j;

/**
 * Verifies a Stripe webhook and applies it, retrying when it loses a write race.
 *
 * <p>Stripe often sends {@code customer.subscription.updated} and {@code checkout.session.completed}
 * within the same second. Both update the same versioned {@code tenants} row, so one transaction
 * can fail on commit with an optimistic-lock conflict. A duplicate delivery of the same event can
 * also collide on the {@code billing_events} primary key. Both are safe to repeat: each attempt
 * runs in a new transaction that reloads the row and re-checks {@code billing_events}.
 *
 * <p>This class is deliberately not transactional. {@link BillingService#apply} is, and it has to
 * be called through its proxy once per attempt.
 */
@Slf4j
@Component
public class BillingWebhookProcessor {

    static final int MAX_ATTEMPTS = 3;

    private final BillingService billingService;

    public BillingWebhookProcessor(BillingService billingService) {
        this.billingService = billingService;
    }

    public void handle(String payload, String signature) {
        Event event = billingService.verify(payload, signature);
        for (int attempt = 1; ; attempt++) {
            try {
                billingService.apply(payload, event);
                return;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Stripe event {} still conflicted after {} attempts", event.getId(), attempt);
                    throw ex;
                }
                log.warn("Stripe event {} conflicted on attempt {} ({}), retrying",
                        event.getId(), attempt, ex.getClass().getSimpleName());
            }
        }
    }
}
