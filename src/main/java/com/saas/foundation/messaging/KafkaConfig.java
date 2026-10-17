package com.saas.foundation.messaging;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import com.saas.foundation.messaging.event.TenantRegisteredEvent;

@Configuration
@ConditionalOnProperty(prefix = "saas.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    @Bean
    public NewTopic tenantRegisteredTopic(KafkaTopicsProperties topics) {
        return TopicBuilder.name(topics.tenantRegisteredTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public KafkaAdmin kafkaAdmin(KafkaProperties kafkaProperties) {
        normalizePemTruststore(kafkaProperties);
        return new KafkaAdmin(kafkaProperties.buildAdminProperties(null));
    }

    @Bean
    public ProducerFactory<String, TenantRegisteredEvent> tenantRegisteredProducerFactory(
            KafkaProperties kafkaProperties) {
        normalizePemTruststore(kafkaProperties);
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, TenantRegisteredEvent> tenantRegisteredKafkaTemplate(
            ProducerFactory<String, TenantRegisteredEvent> tenantRegisteredProducerFactory) {
        return new KafkaTemplate<>(tenantRegisteredProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, TenantRegisteredEvent> tenantRegisteredConsumerFactory(
            KafkaProperties kafkaProperties) {
        normalizePemTruststore(kafkaProperties);
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TenantRegisteredEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.saas.foundation.messaging.event");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TenantRegisteredEvent>
    tenantRegisteredKafkaListenerContainerFactory(
            ConsumerFactory<String, TenantRegisteredEvent> tenantRegisteredConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TenantRegisteredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tenantRegisteredConsumerFactory);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * Render env values often store the PEM as one line with literal "\n".
     * Kafka's PEM parser needs real line breaks.
     */
    private static void normalizePemTruststore(KafkaProperties kafkaProperties) {
        String certificates = kafkaProperties.getProperties().get("ssl.truststore.certificates");
        if (certificates == null || certificates.isBlank()) {
            return;
        }
        String pem = certificates.replace("\\r\\n", "\n").replace("\\n", "\n").trim();
        kafkaProperties.getProperties().put("ssl.truststore.certificates", pem);
    }
}
