package com.saas.foundation.messaging;

import java.util.concurrent.CompletableFuture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.saas.foundation.messaging.event.TenantRegisteredEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "saas.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantEventPublisher {

    private final KafkaTemplate<String, TenantRegisteredEvent> tenantRegisteredKafkaTemplate;
    private final KafkaTopicsProperties topics;

    public TenantEventPublisher(KafkaTemplate<String, TenantRegisteredEvent> tenantRegisteredKafkaTemplate,
                                KafkaTopicsProperties topics) {
        this.tenantRegisteredKafkaTemplate = tenantRegisteredKafkaTemplate;
        this.topics = topics;
    }

    /**
     * Publishes asynchronously; callers are not blocked on broker ACK beyond future completion hooks.
     */
    public CompletableFuture<SendResult<String, TenantRegisteredEvent>> publishTenantRegistered(
            TenantRegisteredEvent event) {
        log.info("Publishing TenantRegisteredEvent eventId={} tenantId={} topic={}",
                event.eventId(), event.tenantId(), topics.tenantRegisteredTopic());

        return tenantRegisteredKafkaTemplate
                .send(topics.tenantRegisteredTopic(), event.tenantId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish TenantRegisteredEvent eventId={} tenantId={}",
                                event.eventId(), event.tenantId(), ex);
                    } else if (result != null && result.getRecordMetadata() != null) {
                        log.debug("Published TenantRegisteredEvent eventId={} partition={} offset={}",
                                event.eventId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
