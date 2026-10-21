package com.saas.foundation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.foundation.audit.AuditRecorder;
import com.saas.foundation.domain.audit.AuditAction;
import com.saas.foundation.domain.audit.AuditTargetType;
import com.saas.foundation.domain.billing.BillingEvent;
import com.saas.foundation.domain.billing.BillingEventRepository;
import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.tenant.TenantContext;
import com.stripe.model.Event;

class BillingServiceTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    private final BillingGateway gateway = mock(BillingGateway.class);
    private final BillingEventRepository events = mock(BillingEventRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);

    private BillingService service;
    private Tenant tenant;
    private UUID ownerId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        service = serviceWith(new BillingProperties(
                "sk_test_123", WEBHOOK_SECRET, "price_123", "http://localhost:5173"));
        tenant = new Tenant("Acme");
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        ownerId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        TenantContext.setTenantId(tenant.getId().toString());
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), ownerId))
                .thenReturn(Optional.of(new TenantMembership(tenant.getId(), ownerId, MembershipRole.OWNER)));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), memberId))
                .thenReturn(Optional.of(new TenantMembership(tenant.getId(), memberId, MembershipRole.MEMBER)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anOwnerCanStartCheckout() {
        UserAccount owner = new UserAccount("owner@acme.test", "hash");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        when(users.findById(ownerId)).thenReturn(Optional.of(owner));
        when(gateway.createCheckoutSession(any())).thenReturn("https://checkout.stripe.test/c/pay");

        BillingLinkResponse response = service.checkout(ownerId);

        assertThat(response.url()).isEqualTo("https://checkout.stripe.test/c/pay");
        verify(gateway).createCheckoutSession(any());
    }

    @Test
    void aMemberCannotStartCheckout() {
        assertThatThrownBy(() -> service.checkout(memberId))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo(MembershipService.OWNER_REQUIRED);
                });
        verify(gateway, never()).createCheckoutSession(any());
    }

    @Test
    void anActiveSubscriptionIsNotReplaced() {
        tenant.setPlan(com.saas.foundation.domain.tenant.Plan.PRO);
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        tenant.setStripeSubscriptionId("sub_live");

        assertThatThrownBy(() -> service.checkout(ownerId))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(BillingService.ALREADY_SUBSCRIBED));
        verify(gateway, never()).createCheckoutSession(any());
    }

    @Test
    void checkoutReportsThatBillingIsNotConfigured() {
        BillingService unconfigured = serviceWith(new BillingProperties("", "", "", "http://localhost:5173"));

        assertThatThrownBy(() -> unconfigured.checkout(ownerId))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).isEqualTo(BillingService.NOT_CONFIGURED);
                });
    }

    @Test
    void aCanceledSubscriptionClosesProductAccessAndARetryDoesNothing() throws Exception {
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        String payload = subscriptionPayload(tenant.getId(), "canceled");
        Event event = service.verify(payload, signature(payload));

        service.apply(payload, event);

        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.FREE);
        assertThat(tenant.allowsProductAccess()).isTrue();
        assertThat(tenant.allowsAuditAccess()).isFalse();
        assertThat(tenant.getStripeCustomerId()).isEqualTo("cus_123");
        verify(events).save(any(BillingEvent.class));
        verify(audit).record(
                eq(AuditAction.BILLING_SUBSCRIPTION_CHANGED),
                eq(tenant.getId()),
                isNull(),
                eq(AuditTargetType.SUBSCRIPTION),
                eq(tenant.getId()),
                eq(Map.of("from", "ACTIVE", "to", "CANCELED", "planFrom", "FREE", "planTo", "FREE")));

        clearInvocations(audit);
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        when(events.existsById("evt_1")).thenReturn(true);
        service.apply(payload, event);

        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verifyNoInteractions(audit);
    }

    @Test
    void theProPriceOpensTheAuditLogUntilTheSubscriptionEnds() throws Exception {
        String activated = subscriptionPayload("evt_pro", tenant.getId(), "active", "price_123");
        service.apply(activated, service.verify(activated, signature(activated)));

        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.PRO);
        assertThat(tenant.allowsAuditAccess()).isTrue();
        assertThat(tenant.allowsProductAccess()).isTrue();

        String canceled = subscriptionPayload("evt_pro_end", tenant.getId(), "canceled", "price_123");
        service.apply(canceled, service.verify(canceled, signature(canceled)));

        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.FREE);
        assertThat(tenant.allowsAuditAccess()).isFalse();
        assertThat(tenant.allowsProductAccess()).isTrue();
    }

    @Test
    void anOlderSubscriptionEventDoesNotReplaceACompletedCheckout() throws Exception {
        String checkout = checkoutPayload("evt_checkout", tenant.getId(), 1_710_000_001L);
        service.apply(checkout, service.verify(checkout, signature(checkout)));

        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.PRO);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(tenant.getStripeEventCreatedAt()).isEqualTo(Instant.ofEpochSecond(1_710_000_001L));

        clearInvocations(audit);
        String older = subscriptionPayload("evt_old", tenant.getId(), "incomplete", "price_123", 1_710_000_000L);
        service.apply(older, service.verify(older, signature(older)));

        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.PRO);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(tenant.getStripeEventCreatedAt()).isEqualTo(Instant.ofEpochSecond(1_710_000_001L));
        verifyNoInteractions(audit);
        verify(events, times(2)).save(any(BillingEvent.class));
    }

    @Test
    void anIncompleteEventAtTheSameSecondDoesNotReplacePro() throws Exception {
        String activated = subscriptionPayload("evt_active", tenant.getId(), "active", "price_123", 1_710_000_000L);
        service.apply(activated, service.verify(activated, signature(activated)));

        clearInvocations(audit);
        String incomplete = subscriptionPayload("evt_incomplete", tenant.getId(), "incomplete", "price_123", 1_710_000_000L);
        service.apply(incomplete, service.verify(incomplete, signature(incomplete)));

        assertThat(tenant.getPlan()).isEqualTo(com.saas.foundation.domain.tenant.Plan.PRO);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verifyNoInteractions(audit);
    }

    @Test
    void aBadSignatureIsRejected() {
        assertThatThrownBy(() -> service.verify("{}", "t=1,v1=deadbeef"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(BillingService.BAD_SIGNATURE));
    }

    private BillingService serviceWith(BillingProperties properties) {
        return new BillingService(
                properties, gateway, events, tenants, memberships, users, new ObjectMapper(), audit);
    }

    private static String subscriptionPayload(UUID tenantId, String status) {
        return subscriptionPayload("evt_1", tenantId, status, null);
    }

    private static String subscriptionPayload(String eventId, UUID tenantId, String status, String priceId) {
        return subscriptionPayload(eventId, tenantId, status, priceId, 1_710_000_000L);
    }

    private static String subscriptionPayload(String eventId, UUID tenantId, String status, String priceId, long created) {
        String items = priceId == null
                ? ""
                : ",\"items\":{\"data\":[{\"price\":{\"id\":\"" + priceId + "\"}}]}";
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "created": %d,
                  "livemode": false,
                  "pending_webhooks": 1,
                  "type": "customer.subscription.updated",
                  "request": {"id": null, "idempotency_key": null},
                  "data": {
                    "object": {
                      "id": "sub_123",
                      "object": "subscription",
                      "customer": "cus_123",
                      "status": "%s",
                      "metadata": {"tenant_id": "%s"}%s
                    }
                  }
                }
                """.formatted(eventId, created, status, tenantId, items);
    }

    private static String checkoutPayload(String eventId, UUID tenantId, long created) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "created": %d,
                  "livemode": false,
                  "pending_webhooks": 1,
                  "type": "checkout.session.completed",
                  "request": {"id": null, "idempotency_key": null},
                  "data": {
                    "object": {
                      "id": "cs_123",
                      "object": "checkout.session",
                      "status": "complete",
                      "customer": "cus_123",
                      "subscription": "sub_123",
                      "client_reference_id": "%s",
                      "metadata": {"tenant_id": "%s"}
                    }
                  }
                }
                """.formatted(eventId, created, tenantId, tenantId);
    }

    private static String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signed = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signed);
    }
}
