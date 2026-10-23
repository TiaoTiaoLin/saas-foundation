package com.saas.foundation.billing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;
    private final BillingWebhookProcessor webhookProcessor;

    public BillingController(BillingService billingService, BillingWebhookProcessor webhookProcessor) {
        this.billingService = billingService;
        this.webhookProcessor = webhookProcessor;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public BillingResponse current() {
        return billingService.current(currentUserId());
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasRole('OWNER')")
    public BillingLinkResponse checkout() {
        return billingService.checkout(currentUserId());
    }

    @PostMapping("/portal")
    @PreAuthorize("hasRole('OWNER')")
    public BillingLinkResponse portal() {
        return billingService.portal(currentUserId());
    }

    /** Stripe signs this request. There is no access token. */
    @PostMapping("/webhook")
    public void webhook(HttpServletRequest request) throws IOException {
        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String signature = request.getHeader("Stripe-Signature");
        webhookProcessor.handle(payload, signature);
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return UUID.fromString(jwt.getToken().getSubject());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
    }
}
