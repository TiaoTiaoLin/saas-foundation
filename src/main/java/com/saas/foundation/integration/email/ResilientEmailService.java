package com.saas.foundation.integration.email;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resilient façade over {@link EmailProviderClient}:
 * <ul>
 *   <li>Exponential backoff retry on transient provider failures</li>
 *   <li>Circuit breaker to shed load when the provider is unhealthy</li>
 *   <li>Fallback that records a degraded result instead of failing the caller hard</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientEmailService {

    public static final String RESILIENCE_NAME = "emailProvider";

    private final EmailProviderClient emailProviderClient;

    @CircuitBreaker(name = RESILIENCE_NAME, fallbackMethod = "sendFallback")
    @Retry(name = RESILIENCE_NAME)
    public EmailSendResult sendWelcomeEmail(String tenantId, String recipientEmail) {
        EmailMessage message = new EmailMessage(
                recipientEmail,
                "Welcome to SaaS Foundation",
                "Your tenant " + tenantId + " has been registered."
        );
        return emailProviderClient.send(message);
    }

    @CircuitBreaker(name = RESILIENCE_NAME, fallbackMethod = "sendInviteFallback")
    @Retry(name = RESILIENCE_NAME)
    public EmailSendResult sendMembershipInvite(String tenantId, String recipientEmail, String role) {
        EmailMessage message = new EmailMessage(
                recipientEmail,
                "You were added to a tenant",
                "You were added to tenant " + tenantId + " as " + role + "."
        );
        return emailProviderClient.send(message);
    }

    @SuppressWarnings("unused")
    private EmailSendResult sendFallback(String tenantId, String recipientEmail, Throwable cause) {
        log.error("Email fallback engaged tenantId={} to={} cause={}",
                tenantId, recipientEmail, cause.toString());
        return EmailSendResult.fallback("queued-for-retry:" + cause.getClass().getSimpleName());
    }

    @SuppressWarnings("unused")
    private EmailSendResult sendInviteFallback(String tenantId, String recipientEmail, String role, Throwable cause) {
        log.error("Invite email fallback engaged tenantId={} to={} role={} cause={}",
                tenantId, recipientEmail, role, cause.toString());
        return EmailSendResult.fallback("queued-for-retry:" + cause.getClass().getSimpleName());
    }
}
