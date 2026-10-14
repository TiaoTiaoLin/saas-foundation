package com.saas.foundation.tenant.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.saas.foundation.observability.TenantMetricsInterceptor;
import com.saas.foundation.ratelimit.TenantRateLimitInterceptor;

@Configuration
public class TenantWebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final TenantMetricsInterceptor tenantMetricsInterceptor;
    private final TenantRateLimitInterceptor tenantRateLimitInterceptor;

    public TenantWebMvcConfig(TenantInterceptor tenantInterceptor,
                              TenantMetricsInterceptor tenantMetricsInterceptor,
                              TenantRateLimitInterceptor tenantRateLimitInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
        this.tenantMetricsInterceptor = tenantMetricsInterceptor;
        this.tenantRateLimitInterceptor = tenantRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .order(0)
                .addPathPatterns("/**");
        registry.addInterceptor(tenantMetricsInterceptor)
                .order(50)
                .addPathPatterns("/**");
        registry.addInterceptor(tenantRateLimitInterceptor)
                .order(100)
                .addPathPatterns("/**");
    }
}
