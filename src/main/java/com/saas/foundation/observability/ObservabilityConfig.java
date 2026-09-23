package com.saas.foundation.observability;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterFilter commonTagsMeterFilter() {
        return MeterFilter.commonTags(List.of(Tag.of("service", "saas-foundation")));
    }

    /**
     * Enables {@code @Timed} on service methods when needed.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
