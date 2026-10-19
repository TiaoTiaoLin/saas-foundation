package com.saas.foundation.tenant;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantSaved;

import lombok.extern.slf4j.Slf4j;

/**
 * Tenant status and product access, cached in Redis and loaded from Postgres when a key is missing.
 * Product routes are open while the organization is active. The audit log is not cached here.
 * Entries expire after {@code saas.tenancy.active-cache-ttl}. Saving a tenant drops both keys
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
        return readFlag(tenantId, cacheKey(tenantId), true);
    }

    @Override
    public boolean allowsProductAccess(String tenantId) {
        return readFlag(tenantId, productCacheKey(tenantId), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTenantSaved(TenantSaved event) {
        String tenantId = event.tenantId().toString();
        deleteQuietly(cacheKey(tenantId));
        deleteQuietly(productCacheKey(tenantId));
    }

    private boolean readFlag(String tenantId, String key, boolean organizationActive) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        Boolean cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        Access access = loadAccess(tenantId);
        writeCache(cacheKey(tenantId), access.active());
        writeCache(productCacheKey(tenantId), access.products());
        return organizationActive ? access.active() : access.products();
    }

    private Access loadAccess(String tenantId) {
        return tenantRepository.findById(UUID.fromString(tenantId))
                .map(tenant -> new Access(tenant.isActive(), tenant.allowsProductAccess()))
                .orElse(new Access(false, false));
    }

    private record Access(boolean active, boolean products) {
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

    private String productCacheKey(String tenantId) {
        return properties.activeCacheKeyPrefix() + "products:" + tenantId;
    }
}
