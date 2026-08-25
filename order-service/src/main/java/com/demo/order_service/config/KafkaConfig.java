package com.demo.order_service.config;

import com.demo.order_service.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

@Configuration
public class KafkaConfig {

    /**
     * Converts the raw JSON string into whatever type each {@code @KafkaListener} method
     * declares as its parameter.
     *
     * <p>This is what lets the consumer work without Java type headers on the wire. Since
     * event classes are duplicated per service, the producer's class name is not a type this
     * service can load, so the target type has to come from the listener signature instead.
     */
    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        return new StringJsonMessageConverter();
    }

    /**
     * Declared so the topic exists with a known shape rather than being auto-created with
     * broker defaults the first time something publishes to it.
     *
     * <p>One partition is a deliberate simplification: ordering is only guaranteed within a
     * partition, and events are keyed by {@code orderId}, so a single partition trivially
     * preserves per-order ordering. Scaling out means more partitions, and the keying is
     * what keeps ordering correct when that happens.
     */
    @Bean
    public NewTopic orderPlacedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_PLACED)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
