package com.saas.foundation.messaging;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.messaging.event.TenantRegisteredEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "saas.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantRegisteredEventListener {

    private final ResilientEmailService resilientEmailService;
    private final AtomicLong processedCount = new AtomicLong();

    public TenantRegisteredEventListener(ResilientEmailService resilientEmailService) {
        this.resilientEmailService = resilientEmailService;
    }

    @KafkaListener(
            topics = "${saas.kafka.tenant-registered-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "tenantRegisteredKafkaListenerContainerFactory"
    )
    public void onTenantRegistered(@Payload TenantRegisteredEvent event) {
        log.info("Consumed TenantRegisteredEvent eventId={} tenantId={} org={}",
                event.eventId(), event.tenantId(), event.organizationName());

        resilientEmailService.sendWelcomeEmail(event.tenantId(), event.adminEmail());
        processedCount.incrementAndGet();
    }

    public long processedCount() {
        return processedCount.get();
    }
}
