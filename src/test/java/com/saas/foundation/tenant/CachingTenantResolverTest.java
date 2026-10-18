package com.saas.foundation.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantSaved;
import com.saas.foundation.domain.tenant.TenantStatus;

@ExtendWith(MockitoExtension.class)
class CachingTenantResolverTest {

    private static final Duration TTL = Duration.ofMinutes(1);
    private static final String PREFIX = "tenant:active:";

    @Mock
    private TenantRepository tenants;

    private MapBackedRedis redis;
    private CachingTenantResolver resolver;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        redis = new MapBackedRedis();
        resolver = new CachingTenantResolver(redis, tenants, new TenancyProperties(
                "X-Tenant-ID", PREFIX, TTL, List.of()));
        tenantId = UUID.randomUUID();
    }

    @Test
    void cacheHitSkipsTheDatabase() {
        redis.values.put(PREFIX + tenantId, "true");

        assertThat(resolver.isActive(tenantId.toString())).isTrue();

        verify(tenants, never()).findById(any());
    }

    @Test
    void cacheMissLoadsTheTenantAndStoresTheFlag() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));

        assertThat(resolver.isActive(tenantId.toString())).isTrue();

        assertThat(redis.values).containsEntry(PREFIX + tenantId, "true");
        assertThat(redis.ttl).isEqualTo(TTL);
    }

    @Test
    void missingTenantIsCachedAsInactive() {
        when(tenants.findById(tenantId)).thenReturn(Optional.empty());

        assertThat(resolver.isActive(tenantId.toString())).isFalse();

        assertThat(redis.values).containsEntry(PREFIX + tenantId, "false");
        assertThat(redis.ttl).isEqualTo(TTL);
    }

    @Test
    void redisOutageFallsBackToTheDatabase() {
        redis.failure = new RedisConnectionFailureException("down");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.SUSPENDED)));

        assertThat(resolver.isActive(tenantId.toString())).isFalse();
    }

    @Test
    void noSubscriptionBlocksProductAccess() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));

        assertThat(resolver.isActive(tenantId.toString())).isTrue();
        assertThat(resolver.allowsProductAccess(tenantId.toString())).isFalse();
        assertThat(redis.values).containsEntry(PREFIX + "products:" + tenantId, "false");
    }

    @Test
    void anActiveSubscriptionOpensProductAccess() {
        Tenant subscribed = tenant(TenantStatus.ACTIVE);
        subscribed.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(subscribed));

        assertThat(resolver.allowsProductAccess(tenantId.toString())).isTrue();
        assertThat(redis.values).containsEntry(PREFIX + "products:" + tenantId, "true");
    }

    @Test
    void aCanceledSubscriptionBlocksProductAccess() {
        Tenant canceled = tenant(TenantStatus.ACTIVE);
        canceled.setSubscriptionStatus(SubscriptionStatus.CANCELED);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(canceled));

        assertThat(resolver.isActive(tenantId.toString())).isTrue();
        assertThat(resolver.allowsProductAccess(tenantId.toString())).isFalse();
        assertThat(redis.values).containsEntry(PREFIX + "products:" + tenantId, "false");
    }

    @Test
    void savingATenantDeletesTheCachedFlag() {
        redis.values.put(PREFIX + tenantId, "true");
        redis.values.put(PREFIX + "products:" + tenantId, "true");

        resolver.onTenantSaved(new TenantSaved(tenantId));

        assertThat(redis.values).doesNotContainKey(PREFIX + tenantId);
        assertThat(redis.values).doesNotContainKey(PREFIX + "products:" + tenantId);
    }

    private Tenant tenant(TenantStatus status) {
        Tenant tenant = new Tenant("Acme");
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        tenant.setStatus(status);
        return tenant;
    }

    private static final class MapBackedRedis extends StringRedisTemplate {

        private final Map<String, String> values = new HashMap<>();
        private Duration ttl;
        private RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            if (failure != null) {
                throw failure;
            }
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[] {ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> values.get(String.valueOf(args[0]));
                        case "set" -> {
                            values.put((String) args[0], (String) args[1]);
                            if (args.length == 3 && args[2] instanceof Duration duration) {
                                ttl = duration;
                            }
                            yield null;
                        }
                        default -> defaultReturn(method.getReturnType());
                    });
        }

        @Override
        public Boolean delete(String key) {
            if (failure != null) {
                throw failure;
            }
            return values.remove(key) != null;
        }

        private static Object defaultReturn(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == long.class || returnType == int.class) {
                return 0;
            }
            return null;
        }
    }
}
