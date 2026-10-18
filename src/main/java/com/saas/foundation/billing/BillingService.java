package com.saas.foundation.billing;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.foundation.domain.billing.BillingEvent;
import com.saas.foundation.domain.billing.BillingEventRepository;
import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.tenant.TenantContext;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BillingService {

    static final String NOT_CONFIGURED = "Billing is not configured";
    static final String ALREADY_SUBSCRIBED = "Manage the current subscription instead of starting a new one";
    static final String SUBSCRIBE_FIRST = "Subscribe before managing billing";
    static final String BAD_SIGNATURE = "Invalid Stripe signature";

    private final BillingProperties properties;
    private final BillingGateway billingGateway;
    private final BillingEventRepository billingEventRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final UserAccountRepository userAccountRepository;
    private final ObjectMapper objectMapper;

    public BillingService(BillingProperties properties,
                          BillingGateway billingGateway,
                          BillingEventRepository billingEventRepository,
                          TenantRepository tenantRepository,
                          TenantMembershipRepository tenantMembershipRepository,
                          UserAccountRepository userAccountRepository,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.billingGateway = billingGateway;
        this.billingEventRepository = billingEventRepository;
        this.tenantRepository = tenantRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.userAccountRepository = userAccountRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BillingResponse current(UUID userId) {
        Tenant tenant = requireTenant(currentTenantId());
        boolean owner = isOwner(tenant.getId(), userId);
        return new BillingResponse(
                statusName(tenant),
                tenant.allowsProductAccess(),
                properties.configured(),
                owner && properties.configured() && canStartCheckout(tenant),
                owner && tenant.getStripeCustomerId() != null && !tenant.getStripeCustomerId().isBlank());
    }

    @Transactional(readOnly = true)
    public BillingLinkResponse checkout(UUID userId) {
        requireConfigured();
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, userId);
        Tenant tenant = requireTenant(tenantId);
        if (!canStartCheckout(tenant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_SUBSCRIBED);
        }
        UserAccount user = userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
        String url = billingGateway.createCheckoutSession(new CheckoutCommand(
                properties.priceId(),
                tenant.getStripeCustomerId(),
                user.getEmail(),
                tenantId.toString(),
                properties.successUrl(),
                properties.cancelUrl()));
        return new BillingLinkResponse(url);
    }

    @Transactional(readOnly = true)
    public BillingLinkResponse portal(UUID userId) {
        requireConfigured();
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, userId);
        Tenant tenant = requireTenant(tenantId);
        if (tenant.getStripeCustomerId() == null || tenant.getStripeCustomerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SUBSCRIBE_FIRST);
        }
        return new BillingLinkResponse(
                billingGateway.createPortalSession(tenant.getStripeCustomerId(), properties.returnUrl()));
    }

    public Event verify(String payload, String signature) {
        requireConfigured();
        try {
            return Webhook.constructEvent(payload, signature, properties.webhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_SIGNATURE);
        }
    }

    @Transactional
    public void apply(String payload, Event event) {
        if (billingEventRepository.existsById(event.getId())) {
            return;
        }
        JsonNode object = objectNode(payload).path("data").path("object");
        switch (event.getType()) {
            case "checkout.session.completed" -> applyCheckout(object);
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
                    applySubscription(object);
            default -> log.info("Ignoring Stripe event type={}", event.getType());
        }
        billingEventRepository.save(new BillingEvent(event.getId(), event.getType(), Instant.now()));
    }

    private void applyCheckout(JsonNode object) {
        Tenant tenant = findTenant(object);
        if (tenant == null) {
            return;
        }
        String customerId = textId(object.path("customer"));
        String subscriptionId = textId(object.path("subscription"));
        if (customerId != null) {
            tenant.setStripeCustomerId(customerId);
        }
        if (subscriptionId != null) {
            tenant.setStripeSubscriptionId(subscriptionId);
        }
        if ("complete".equals(object.path("status").asText())) {
            tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        }
        log.info("Checkout completed tenantId={} subscriptionStatus={}", tenant.getId(), tenant.getSubscriptionStatus());
    }

    private void applySubscription(JsonNode object) {
        Tenant tenant = findTenant(object);
        if (tenant == null) {
            return;
        }
        String customerId = textId(object.path("customer"));
        if (customerId != null) {
            tenant.setStripeCustomerId(customerId);
        }
        if (object.path("id").isTextual()) {
            tenant.setStripeSubscriptionId(object.path("id").asText());
        }
        tenant.setSubscriptionStatus(mapStatus(object.path("status").asText("")));
        log.info("Subscription updated tenantId={} subscriptionStatus={}", tenant.getId(), tenant.getSubscriptionStatus());
    }

    private Tenant findTenant(JsonNode object) {
        String tenantId = object.path("metadata").path("tenant_id").asText("");
        if (tenantId.isBlank()) {
            tenantId = object.path("client_reference_id").asText("");
        }
        if (!tenantId.isBlank()) {
            String referencedTenantId = tenantId;
            try {
                return tenantRepository.findById(UUID.fromString(referencedTenantId))
                        .orElseGet(() -> missing(referencedTenantId));
            } catch (IllegalArgumentException ex) {
                log.warn("Stripe event has an invalid tenant id");
                return null;
            }
        }
        String customerId = textId(object.path("customer"));
        if (customerId == null) {
            log.warn("Stripe event has no tenant id or customer");
            return null;
        }
        return tenantRepository.findByStripeCustomerId(customerId).orElseGet(() -> {
            log.warn("No organization for Stripe customer");
            return null;
        });
    }

    private static Tenant missing(String tenantId) {
        log.warn("No organization for Stripe tenant id={}", tenantId);
        return null;
    }

    static SubscriptionStatus mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active", "trialing" -> SubscriptionStatus.ACTIVE;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled", "unpaid", "incomplete_expired" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.INCOMPLETE;
        };
    }

    private JsonNode objectNode(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_SIGNATURE);
        }
    }

    private static boolean canStartCheckout(Tenant tenant) {
        SubscriptionStatus status = tenant.getSubscriptionStatus();
        return status == null
                || status == SubscriptionStatus.NONE
                || status == SubscriptionStatus.CANCELED
                || status == SubscriptionStatus.INCOMPLETE;
    }

    private static String statusName(Tenant tenant) {
        SubscriptionStatus status = tenant.getSubscriptionStatus();
        return status == null ? SubscriptionStatus.NONE.name() : status.name();
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
    }

    private void requireOwner(UUID tenantId, UUID userId) {
        if (!isOwner(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MembershipService.OWNER_REQUIRED);
        }
    }

    private boolean isOwner(UUID tenantId, UUID userId) {
        return tenantMembershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .filter(membership -> membership.getRole() == MembershipRole.OWNER)
                .isPresent();
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    }

    private static UUID currentTenantId() {
        return UUID.fromString(TenantContext.requireTenantId());
    }

    private static String textId(JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            return node.asText();
        }
        if (node.isObject() && node.path("id").isTextual()) {
            return node.path("id").asText();
        }
        return null;
    }
}
