package com.saas.foundation.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.domain.billing.BillingEventRepository;
import com.saas.foundation.domain.tenant.Plan;
import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantStatus;
import com.saas.foundation.support.PostgresRedisContainers;
import com.saas.foundation.support.TestTenants;

/**
 * Reproduces the October 19 incident: {@code customer.subscription.updated} and
 * {@code checkout.session.completed} arrive together and both write the same versioned tenant row.
 * Neither may be lost, and the organization must end up on Pro.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class BillingWebhookConcurrencyIT {

    private static final String WEBHOOK_SECRET = "whsec_concurrency_test";
    private static final String PRO_PRICE = "price_pro_test";
    private static final int ROUNDS = 15;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.rate-limit.enabled", () -> "false");
        registry.add("saas.auth-rate-limit.enabled", () -> "false");
        registry.add("saas.billing.secret-key", () -> "sk_test_concurrency");
        registry.add("saas.billing.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("saas.billing.pro-price-id", () -> PRO_PRICE);
    }

    @Autowired
    BillingWebhookProcessor processor;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    BillingEventRepository billingEventRepository;

    @Test
    void simultaneousSubscriptionAndCheckoutEventsLeaveTheOrganizationOnPro() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID tenantId = UUID.randomUUID();
                TestTenants.ensure(tenantRepository, tenantId.toString(), TenantStatus.ACTIVE);
                resetBilling(tenantId);

                long created = Instant.now().getEpochSecond();
                String subscriptionId = "evt_sub_" + tenantId;
                String checkoutId = "evt_checkout_" + tenantId;
                // Stripe's real order: the incomplete subscription is one second older than the checkout.
                String subscription = subscriptionPayload(subscriptionId, tenantId, created - 1);
                String checkout = checkoutPayload(checkoutId, tenantId, created);

                CountDownLatch go = new CountDownLatch(1);
                Future<?> first = pool.submit(deliver(go, subscription));
                Future<?> second = pool.submit(deliver(go, checkout));
                go.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);

                Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
                assertThat(tenant.getPlan()).as("plan in round %d", round).isEqualTo(Plan.PRO);
                assertThat(tenant.getSubscriptionStatus()).as("status in round %d", round)
                        .isEqualTo(SubscriptionStatus.ACTIVE);
                assertThat(billingEventRepository.existsById(subscriptionId)).isTrue();
                assertThat(billingEventRepository.existsById(checkoutId)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deliveringTheSameEventTwiceAtOnceAppliesItOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TestTenants.ensure(tenantRepository, tenantId.toString(), TenantStatus.ACTIVE);
        resetBilling(tenantId);
        String eventId = "evt_dup_" + tenantId;
        String checkout = checkoutPayload(eventId, tenantId, Instant.now().getEpochSecond());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<?> first = pool.submit(deliver(go, checkout));
            Future<?> second = pool.submit(deliver(go, checkout));
            go.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(tenant.getPlan()).isEqualTo(Plan.PRO);
        assertThat(billingEventRepository.existsById(eventId)).isTrue();
        assertThat(billingEventRepository.findAllById(List.of(eventId))).hasSize(1);
    }

    private Callable<Void> deliver(CountDownLatch go, String payload) {
        return () -> {
            go.await();
            processor.handle(payload, signature(payload));
            return null;
        };
    }

    private void resetBilling(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setPlan(Plan.FREE);
        tenant.setSubscriptionStatus(SubscriptionStatus.NONE);
        tenantRepository.saveAndFlush(tenant);
    }

    private static String subscriptionPayload(String eventId, UUID tenantId, long created) {
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
                      "id": "sub_%s",
                      "object": "subscription",
                      "customer": "cus_%s",
                      "status": "incomplete",
                      "items": {"data": [{"price": {"id": "%s"}}]},
                      "metadata": {"tenant_id": "%s"}
                    }
                  }
                }
                """.formatted(eventId, created, tenantId, tenantId, PRO_PRICE, tenantId);
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
                      "id": "cs_%s",
                      "object": "checkout.session",
                      "status": "complete",
                      "customer": "cus_%s",
                      "subscription": "sub_%s",
                      "client_reference_id": "%s",
                      "metadata": {"tenant_id": "%s"}
                    }
                  }
                }
                """.formatted(eventId, created, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    private static String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signed = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signed);
    }
}
