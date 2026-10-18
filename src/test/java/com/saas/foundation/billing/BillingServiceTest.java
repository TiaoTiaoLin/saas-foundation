package com.saas.foundation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
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
        assertThat(tenant.allowsProductAccess()).isFalse();
        assertThat(tenant.getStripeCustomerId()).isEqualTo("cus_123");
        verify(events).save(any(BillingEvent.class));

        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        when(events.existsById("evt_1")).thenReturn(true);
        service.apply(payload, event);

        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void aBadSignatureIsRejected() {
        assertThatThrownBy(() -> service.verify("{}", "t=1,v1=deadbeef"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(BillingService.BAD_SIGNATURE));
    }

    private BillingService serviceWith(BillingProperties properties) {
        return new BillingService(
                properties, gateway, events, tenants, memberships, users, new ObjectMapper());
    }

    private static String subscriptionPayload(UUID tenantId, String status) {
        return """
                {
                  "id": "evt_1",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "created": 1710000000,
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
                      "metadata": {"tenant_id": "%s"}
                    }
                  }
                }
                """.formatted(status, tenantId);
    }

    private static String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signed = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signed);
    }
}
