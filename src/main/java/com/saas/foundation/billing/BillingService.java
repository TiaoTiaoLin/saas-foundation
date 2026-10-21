package com.saas.foundation.billing;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.foundation.audit.AuditRecorder;
import com.saas.foundation.domain.audit.AuditAction;
import com.saas.foundation.domain.audit.AuditTargetType;
import com.saas.foundation.domain.billing.BillingEvent;
import com.saas.foundation.domain.billing.BillingEventRepository;
import com.saas.foundation.domain.tenant.Plan;
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
    private final AuditRecorder auditRecorder;

    public BillingService(BillingProperties properties,
                          BillingGateway billingGateway,
                          BillingEventRepository billingEventRepository,
                          TenantRepository tenantRepository,
                          TenantMembershipRepository tenantMembershipRepository,
                          UserAccountRepository userAccountRepository,
                          ObjectMapper objectMapper,
                          AuditRecorder auditRecorder) {
        this.properties = properties;
        this.billingGateway = billingGateway;
        this.billingEventRepository = billingEventRepository;
        this.tenantRepository = tenantRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.userAccountRepository = userAccountRepository;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public BillingResponse current(UUID userId) {
        Tenant tenant = requireTenant(currentTenantId());
        boolean owner = isOwner(tenant.getId(), userId);
        return new BillingResponse(
                planName(tenant),
                statusName(tenant),
                tenant.allowsProductAccess(),
                tenant.allowsAuditAccess(),
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
                properties.proPriceId(),
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
        Instant eventCreated = event.getCreated() == null ? null : Instant.ofEpochSecond(event.getCreated());
        switch (event.getType()) {
            case "checkout.session.completed" -> applyCheckout(object, eventCreated);
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
                    applySubscription(object, eventCreated);
            default -> log.info("Ignoring Stripe event type={}", event.getType());
        }
        billingEventRepository.save(new BillingEvent(event.getId(), event.getType(), Instant.now()));
    }

    private void applyCheckout(JsonNode object, Instant eventCreated) {
        Tenant tenant = findTenant(object);
        if (tenant == null) {
            return;
        }
        if (stale(tenant, eventCreated, null)) {
            log.info("Ignoring older Stripe checkout tenantId={} eventCreated={} applied={}",
                    tenant.getId(), eventCreated, tenant.getStripeEventCreatedAt());
            return;
        }
        SubscriptionStatus beforeStatus = tenant.getSubscriptionStatus();
        Plan beforePlan = planOf(tenant);
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
            tenant.setPlan(Plan.PRO);
        }
        rememberEventTime(tenant, eventCreated);
        recordPlanChange(tenant, beforeStatus, beforePlan);
        log.info("Checkout completed tenantId={} subscriptionStatus={}", tenant.getId(), tenant.getSubscriptionStatus());
    }

    private void applySubscription(JsonNode object, Instant eventCreated) {
        Tenant tenant = findTenant(object);
        if (tenant == null) {
            return;
        }
        SubscriptionStatus incoming = mapStatus(object.path("status").asText(""));
        if (stale(tenant, eventCreated, incoming)) {
            log.info("Ignoring stale Stripe subscription event tenantId={} eventCreated={} applied={}",
                    tenant.getId(), eventCreated, tenant.getStripeEventCreatedAt());
            return;
        }
        SubscriptionStatus beforeStatus = tenant.getSubscriptionStatus();
        Plan beforePlan = planOf(tenant);
        String subscriptionId = object.path("id").isTextual() ? object.path("id").asText() : null;
        String eventPriceId = priceId(object);
        boolean proPrice = !eventPriceId.isEmpty() && eventPriceId.equals(properties.proPriceId());
        boolean trackedPro = subscriptionId != null
                && subscriptionId.equals(tenant.getStripeSubscriptionId())
                && beforePlan == Plan.PRO;
        if (!proPrice && !trackedPro) {
            if (beforePlan == Plan.PRO) {
                return;
            }
            applyStripeIdentity(tenant, object, subscriptionId);
            tenant.setSubscriptionStatus(incoming);
            tenant.setPlan(Plan.FREE);
            rememberEventTime(tenant, eventCreated);
            recordPlanChange(tenant, beforeStatus, beforePlan);
            log.info("Subscription kept the free plan tenantId={} subscriptionStatus={}",
                    tenant.getId(), tenant.getSubscriptionStatus());
            return;
        }
        applyStripeIdentity(tenant, object, subscriptionId);
        tenant.setSubscriptionStatus(incoming);
        tenant.setPlan(incoming == SubscriptionStatus.ACTIVE || incoming == SubscriptionStatus.PAST_DUE
                ? Plan.PRO
                : Plan.FREE);
        rememberEventTime(tenant, eventCreated);
        recordPlanChange(tenant, beforeStatus, beforePlan);
        log.info("Subscription updated tenantId={} plan={} subscriptionStatus={}",
                tenant.getId(), tenant.getPlan(), tenant.getSubscriptionStatus());
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

    /**
     * Stripe {@code event.created} is seconds. An older snapshot must not replace a newer plan.
     * A same-second {@code incomplete} snapshot must not replace an open Pro subscription.
     */
    private static boolean stale(Tenant tenant, Instant eventCreated, SubscriptionStatus incoming) {
        Instant applied = tenant.getStripeEventCreatedAt();
        if (eventCreated == null || applied == null) {
            return false;
        }
        if (eventCreated.isBefore(applied)) {
            return true;
        }
        return incoming == SubscriptionStatus.INCOMPLETE
                && eventCreated.equals(applied)
                && (tenant.getSubscriptionStatus() == SubscriptionStatus.ACTIVE
                || tenant.getSubscriptionStatus() == SubscriptionStatus.PAST_DUE);
    }

    private static void rememberEventTime(Tenant tenant, Instant eventCreated) {
        if (eventCreated == null) {
            return;
        }
        Instant applied = tenant.getStripeEventCreatedAt();
        if (applied == null || eventCreated.isAfter(applied)) {
            tenant.setStripeEventCreatedAt(eventCreated);
        }
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

    private static void applyStripeIdentity(Tenant tenant, JsonNode object, String subscriptionId) {
        String customerId = textId(object.path("customer"));
        if (customerId != null) {
            tenant.setStripeCustomerId(customerId);
        }
        if (subscriptionId != null) {
            tenant.setStripeSubscriptionId(subscriptionId);
        }
    }

    /** Price id from a subscription object. Empty when the event does not carry one. */
    static String priceId(JsonNode subscription) {
        JsonNode item = subscription.path("items").path("data").path(0);
        if (item.path("price").path("id").isTextual()) {
            return item.path("price").path("id").asText();
        }
        if (item.path("plan").path("id").isTextual()) {
            return item.path("plan").path("id").asText();
        }
        if (subscription.path("plan").path("id").isTextual()) {
            return subscription.path("plan").path("id").asText();
        }
        return "";
    }

    private static boolean canStartCheckout(Tenant tenant) {
        return !tenant.hasOpenProSubscription();
    }

    private void recordPlanChange(Tenant tenant, SubscriptionStatus beforeStatus, Plan beforePlan) {
        if (beforeStatus == tenant.getSubscriptionStatus() && beforePlan == tenant.getPlan()) {
            return;
        }
        auditRecorder.record(
                AuditAction.BILLING_SUBSCRIPTION_CHANGED,
                tenant.getId(),
                null,
                AuditTargetType.SUBSCRIPTION,
                tenant.getId(),
                Map.of(
                        "from", statusName(beforeStatus),
                        "to", statusName(tenant.getSubscriptionStatus()),
                        "planFrom", planName(beforePlan),
                        "planTo", planName(tenant.getPlan())));
    }

    private static Plan planOf(Tenant tenant) {
        return tenant.getPlan() == null ? Plan.FREE : tenant.getPlan();
    }

    private static String planName(Tenant tenant) {
        return planName(planOf(tenant));
    }

    private static String planName(Plan plan) {
        return plan == null ? Plan.FREE.name() : plan.name();
    }

    private static String statusName(Tenant tenant) {
        return statusName(tenant.getSubscriptionStatus());
    }

    private static String statusName(SubscriptionStatus status) {
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
