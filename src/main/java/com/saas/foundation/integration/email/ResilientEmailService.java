package com.saas.foundation.integration.email;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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
public class ResilientEmailService {

    public static final String RESILIENCE_NAME = "emailProvider";

    private final EmailProviderClient emailProviderClient;

    public ResilientEmailService(EmailProviderClient emailProviderClient) {
        this.emailProviderClient = emailProviderClient;
    }

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
    public EmailSendResult sendMembershipInvite(String recipientEmail, String organizationName,
                                                String role, String acceptUrl) {
        EmailMessage message = new EmailMessage(
                recipientEmail,
                "Invitation to " + organizationName,
                """
                You were invited to join %s as %s.

                Open this link to accept. It expires soon.

                %s

                If you do not want to join, you can ignore this email.
                """.formatted(organizationName, role, acceptUrl)
        );
        return emailProviderClient.send(message);
    }

    @CircuitBreaker(name = RESILIENCE_NAME, fallbackMethod = "sendResetFallback")
    @Retry(name = RESILIENCE_NAME)
    public EmailSendResult sendPasswordReset(String recipientEmail, String resetUrl) {
        EmailMessage message = new EmailMessage(
                recipientEmail,
                "Reset your password",
                """
                Use this link to choose a new password. It expires soon.

                %s

                If you did not ask for this, you can ignore this email.
                """.formatted(resetUrl)
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
    private EmailSendResult sendInviteFallback(String recipientEmail, String organizationName, String role,
                                               String acceptUrl, Throwable cause) {
        log.error("Invite email fallback engaged organization={} to={} role={} cause={}",
                organizationName, recipientEmail, role, cause.toString());
        return EmailSendResult.fallback("queued-for-retry:" + cause.getClass().getSimpleName());
    }

    @SuppressWarnings("unused")
    private EmailSendResult sendResetFallback(String recipientEmail, String resetUrl, Throwable cause) {
        log.error("Password reset email fallback engaged to={} cause={}", recipientEmail, cause.toString());
        return EmailSendResult.fallback("queued-for-retry:" + cause.getClass().getSimpleName());
    }
}
