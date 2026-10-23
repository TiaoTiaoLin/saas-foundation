package com.saas.foundation.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * @param tenantRegisteredReplicas replication factor used if the topic has to be created. It cannot
 *                                 exceed the number of brokers, so it defaults to 1 for the single
 *                                 local broker. The prod profile sets it higher.
 */
@Validated
@ConfigurationProperties(prefix = "saas.kafka")
public record KafkaTopicsProperties(
        @NotBlank String tenantRegisteredTopic,
        Integer tenantRegisteredReplicas
) {

    public KafkaTopicsProperties {
        if (tenantRegisteredReplicas == null || tenantRegisteredReplicas < 1) {
            tenantRegisteredReplicas = 1;
        }
    }
}
