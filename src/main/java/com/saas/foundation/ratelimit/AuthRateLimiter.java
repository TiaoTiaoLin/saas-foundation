package com.saas.foundation.ratelimit;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.saas.foundation.ratelimit.AuthRateLimitProperties.Limit;
import com.saas.foundation.security.Hashing;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed limits for signup, login and refresh, keyed by client IP and (for login)
 * by email. Emails are hashed so Redis keys do not contain personal data.
 *
 * <p>The client IP is {@code request.getRemoteAddr()}. Behind a reverse proxy, enable
 * {@code server.forward-headers-strategy} so it reflects the real client, not the proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private final ProxyManager<String> proxyManager;
    private final AuthRateLimitProperties properties;
    private final Map<Limit, BucketConfiguration> configurations = new ConcurrentHashMap<>();

    public void checkLogin(String clientIp, String email) {
        check("login:ip:" + clientIp, properties.loginPerIp());
        check("login:email:" + Hashing.sha256Hex(email.trim().toLowerCase(Locale.ROOT)), properties.loginPerEmail());
    }

    public void checkSignup(String clientIp) {
        check("signup:ip:" + clientIp, properties.signupPerIp());
    }

    public void checkRefresh(String clientIp) {
        check("refresh:ip:" + clientIp, properties.refreshPerIp());
    }

    private void check(String key, Limit limit) {
        if (!properties.enabled()) {
            return;
        }
        BucketConfiguration configuration = configurations.computeIfAbsent(limit, AuthRateLimiter::toConfiguration);
        ConsumptionProbe probe = proxyManager.builder()
                .build(properties.keyPrefix() + key, () -> configuration)
                .tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return;
        }
        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        log.warn("Auth rate limit exceeded key={} retryAfterSeconds={}", key, retryAfterSeconds);
        throw RateLimitExceededException.withoutTenant(retryAfterSeconds);
    }

    private static BucketConfiguration toConfiguration(Limit limit) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.window())
                        .build())
                .build();
    }
}
