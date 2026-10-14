package com.saas.foundation.observability;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.saas.foundation.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
/**
 * Records {@code tenant.active.requests} and {@code api.latency} (p50/p95/p99) for tenant-bound requests.
 */
@Component
public class TenantMetricsInterceptor implements HandlerInterceptor, Ordered {

    static final String TIMER_SAMPLE_ATTR = TenantMetricsInterceptor.class.getName() + ".sample";
    static final String TENANT_ATTR = TenantMetricsInterceptor.class.getName() + ".tenantId";

    private final TenantMetrics tenantMetrics;
    private final MeterRegistry meterRegistry;

    public TenantMetricsInterceptor(TenantMetrics tenantMetrics, MeterRegistry meterRegistry) {
        this.tenantMetrics = tenantMetrics;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!TenantContext.isPresent()) {
            return true;
        }
        String tenantId = TenantContext.requireTenantId();
        request.setAttribute(TENANT_ATTR, tenantId);
        tenantMetrics.requestStarted(tenantId);
        request.setAttribute(TIMER_SAMPLE_ATTR, Timer.start(meterRegistry));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object tenantAttr = request.getAttribute(TENANT_ATTR);
        if (tenantAttr instanceof String tenantId) {
            tenantMetrics.requestFinished(tenantId);
        }

        Object sample = request.getAttribute(TIMER_SAMPLE_ATTR);
        if (sample instanceof Timer.Sample timerSample) {
            timerSample.stop(tenantMetrics.getApiLatencyTimer());
        }
    }

    @Override
    public int getOrder() {
        // After tenant bind (0), before rate limit (100)
        return 50;
    }
}
