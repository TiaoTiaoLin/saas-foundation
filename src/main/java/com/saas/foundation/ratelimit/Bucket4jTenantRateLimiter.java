package com.saas.foundation.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed Bucket4j limiter: greedy refill approximates a sliding window
 * of {@code capacity} requests per {@code window} (default 100/min per tenant).
 */
@Slf4j
@Component
public class Bucket4jTenantRateLimiter implements TenantRateLimiter {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration bucketConfiguration;
    private final RateLimitProperties properties;

    public Bucket4jTenantRateLimiter(ProxyManager<String> proxyManager,
                                     BucketConfiguration bucketConfiguration,
                                     RateLimitProperties properties) {
        this.proxyManager = proxyManager;
        this.bucketConfiguration = bucketConfiguration;
        this.properties = properties;
    }

    @Override
    public void checkOrThrow(String tenantId) {
        if (!properties.enabled()) {
            return;
        }

        String key = properties.keyPrefix() + tenantId;
        Supplier<BucketConfiguration> configSupplier = () -> bucketConfiguration;
        BucketProxy bucket = proxyManager.builder().build(key, configSupplier);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            log.trace("Rate limit OK tenantId={} remaining={}", tenantId, probe.getRemainingTokens());
            return;
        }

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        log.warn("Rate limit exceeded tenantId={} retryAfterSeconds={}", tenantId, retryAfterSeconds);
        throw new RateLimitExceededException(tenantId, retryAfterSeconds);
    }
}
