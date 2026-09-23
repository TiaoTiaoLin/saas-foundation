package com.saas.foundation.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "saas.kafka")
public record KafkaTopicsProperties(
        @NotBlank String tenantRegisteredTopic
) {
}
