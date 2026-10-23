package com.saas.foundation.billing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.model.Event;

class BillingWebhookProcessorTest {

    private static final String PAYLOAD = "{}";
    private static final String SIGNATURE = "t=1,v1=abc";

    private final BillingService service = mock(BillingService.class);
    private final BillingWebhookProcessor processor = new BillingWebhookProcessor(service);
    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId("evt_1");
        when(service.verify(PAYLOAD, SIGNATURE)).thenReturn(event);
    }

    @Test
    void appliesTheEventOnce() {
        processor.handle(PAYLOAD, SIGNATURE);

        verify(service, times(1)).apply(PAYLOAD, event);
    }

    @Test
    void retriesAfterAnOptimisticLockConflict() {
        doThrow(new OptimisticLockingFailureException("tenants row changed"))
                .doThrow(new OptimisticLockingFailureException("tenants row changed"))
                .doNothing()
                .when(service).apply(PAYLOAD, event);

        processor.handle(PAYLOAD, SIGNATURE);

        verify(service, times(3)).apply(PAYLOAD, event);
    }

    @Test
    void retriesAfterADuplicateEventInsert() {
        doThrow(new DataIntegrityViolationException("billing_events_pkey"))
                .doNothing()
                .when(service).apply(PAYLOAD, event);

        processor.handle(PAYLOAD, SIGNATURE);

        verify(service, times(2)).apply(PAYLOAD, event);
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        doThrow(new OptimisticLockingFailureException("tenants row changed"))
                .when(service).apply(PAYLOAD, event);

        assertThatThrownBy(() -> processor.handle(PAYLOAD, SIGNATURE))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(service, times(BillingWebhookProcessor.MAX_ATTEMPTS)).apply(PAYLOAD, event);
    }

    @Test
    void doesNotRetryOtherFailures() {
        doThrow(new IllegalStateException("boom")).when(service).apply(PAYLOAD, event);

        assertThatThrownBy(() -> processor.handle(PAYLOAD, SIGNATURE))
                .isInstanceOf(IllegalStateException.class);

        verify(service, times(1)).apply(PAYLOAD, event);
    }

    @Test
    void aBadSignatureIsNeverApplied() {
        when(service.verify(PAYLOAD, "bad"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, BillingService.BAD_SIGNATURE));

        assertThatThrownBy(() -> processor.handle(PAYLOAD, "bad"))
                .isInstanceOf(ResponseStatusException.class);

        verify(service, never()).apply(any(), any());
    }
}
