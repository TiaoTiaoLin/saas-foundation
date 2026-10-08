package com.saas.foundation.tenant;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantSaved;

import lombok.extern.slf4j.Slf4j;

/**
 * Tenant status from Redis, loaded from Postgres when the key is missing or Redis is down.
 * Entries expire after {@code saas.tenancy.active-cache-ttl}. Saving a tenant drops the key
 * once that transaction commits, so a status change is visible on the next request.
 */
@Slf4j
@Component
public class CachingTenantResolver implements TenantResolver {

    static final String ACTIVE = "true";
    static final String INACTIVE = "false";

    private final StringRedisTemplate redis;
    private final TenantRepository tenantRepository;
    private final TenancyProperties properties;

    public CachingTenantResolver(StringRedisTemplate redis,
                                 TenantRepository tenantRepository,
                                 TenancyProperties properties) {
        this.redis = redis;
        this.tenantRepository = tenantRepository;
        this.properties = properties;
    }

    @Override
    public boolean isActive(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }

        String key = cacheKey(tenantId);
        Boolean cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        boolean active = loadActive(tenantId);
        writeCache(key, active);
        return active;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTenantSaved(TenantSaved event) {
        deleteQuietly(cacheKey(event.tenantId().toString()));
    }

    private boolean loadActive(String tenantId) {
        return tenantRepository.findById(UUID.fromString(tenantId))
                .map(Tenant::isActive)
                .orElse(false);
    }

    private Boolean readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (ACTIVE.equals(raw)) {
                return true;
            }
            if (INACTIVE.equals(raw)) {
                return false;
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("Tenant active cache read failed key={}: {}", key, ex.toString());
            return null;
        }
    }

    private void writeCache(String key, boolean active) {
        try {
            redis.opsForValue().set(key, active ? ACTIVE : INACTIVE, properties.activeCacheTtl());
        } catch (RuntimeException ex) {
            log.warn("Tenant active cache write failed key={}: {}", key, ex.toString());
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Tenant active cache delete failed key={}: {}", key, ex.toString());
        }
    }

    private String cacheKey(String tenantId) {
        return properties.activeCacheKeyPrefix() + tenantId;
    }
}
