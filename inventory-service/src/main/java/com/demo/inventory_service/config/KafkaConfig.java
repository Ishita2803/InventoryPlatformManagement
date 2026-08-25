package com.demo.inventory_service.config;

import com.demo.inventory_service.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    /**
     * Resolves the payload to the type each {@code @KafkaListener} method declares, which is
     * what allows the wire format to stay plain JSON with no Java type headers. See
     * order-service's equivalent for the full reasoning.
     */
    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        return new StringJsonMessageConverter();
    }

    /** This service owns the two result topics, so it declares them. */
    @Bean
    public NewTopic inventoryReservedTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_RESERVED)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic inventoryFailedTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_FAILED)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * The object-valued template, used by {@code OrderPlacedListener} to publish result
     * events as JSON.
     *
     * <p>Declared explicitly because Boot's auto-configured template is
     * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} — a raw-type condition that
     * ignores generics. Declaring {@link #stringKafkaTemplate} below would otherwise switch
     * Boot's off and break every injection point expecting this one.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * A template that writes values as raw strings, for republishing records to a
     * dead-letter topic. Those payloads are already serialized, so passing them through the
     * JsonSerializer would double-encode them.
     *
     * <p>Derived from the auto-configured {@link ProducerFactory}'s configuration so it
     * inherits bootstrap servers, acks and timeouts rather than silently reverting to
     * client defaults.
     */
    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<?, ?> producerFactory) {

        Map<String, Object> props = new HashMap<>(producerFactory.getConfigurationProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
