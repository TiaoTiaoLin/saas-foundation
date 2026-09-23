package com.saas.foundation.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

/**
 * Custom SaaS metrics:
 * <ul>
 *   <li>{@code tenant.active.requests} — in-flight HTTP requests per tenant</li>
 *   <li>{@code api.latency} — request latency with client-side p50/p95/p99</li>
 * </ul>
 */
@Component
public class TenantMetrics {

    public static final String METRIC_ACTIVE_REQUESTS = "tenant.active.requests";
    public static final String METRIC_API_LATENCY = "api.latency";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> activeByTenant = new ConcurrentHashMap<>();

    @Getter
    private final Timer apiLatencyTimer;

    private final AtomicLong totalActive = new AtomicLong();

    public TenantMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.apiLatencyTimer = Timer.builder(METRIC_API_LATENCY)
                .description("API request latency (tenant-scoped HTTP requests)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder("tenant.active.requests.total", totalActive, AtomicLong::get)
                .description("Total in-flight tenant-scoped HTTP requests")
                .register(meterRegistry);
    }

    public void requestStarted(String tenantId) {
        AtomicInteger counter = activeByTenant.computeIfAbsent(tenantId, this::registerTenantGauge);
        counter.incrementAndGet();
        totalActive.incrementAndGet();
    }

    public void requestFinished(String tenantId) {
        AtomicInteger counter = activeByTenant.get(tenantId);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
        totalActive.updateAndGet(v -> Math.max(0, v - 1));
    }

    private AtomicInteger registerTenantGauge(String tenantId) {
        AtomicInteger counter = new AtomicInteger();
        Gauge.builder(METRIC_ACTIVE_REQUESTS, counter, AtomicInteger::get)
                .description("In-flight HTTP requests for a tenant")
                .tag("tenant_id", tenantId)
                .register(meterRegistry);
        return counter;
    }
}
