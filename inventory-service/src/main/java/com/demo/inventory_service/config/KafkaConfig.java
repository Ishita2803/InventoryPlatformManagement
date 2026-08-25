package com.demo.inventory_service.config;

import com.demo.inventory_service.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

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
}
