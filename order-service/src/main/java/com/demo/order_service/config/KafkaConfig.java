package com.demo.order_service.config;

import com.demo.order_service.events.KafkaTopics;
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

    /** Settlement topics: this service publishes both, so it declares both. */
    @Bean
    public NewTopic orderConfirmedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CONFIRMED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CANCELLED).partitions(1).replicas(1).build();
    }

    /**
     * The object-valued template, for publishing domain events as JSON.
     *
     * <p><strong>This has to be declared explicitly, even though Boot would normally provide
     * it.</strong> Boot's auto-configuration is {@code @ConditionalOnMissingBean(KafkaTemplate.class)},
     * and that condition is on the <em>raw type</em> — generics are not considered. So
     * declaring {@link #stringKafkaTemplate} below silently switches Boot's template off
     * entirely, and every injection point wanting {@code KafkaTemplate<String, Object>}
     * fails to resolve. Declaring both here makes the pair explicit instead of leaving one
     * to appear or vanish depending on what else is defined.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * A template that writes values as raw strings.
     *
     * <p>Needed because two things this service publishes are <em>already</em> serialized
     * JSON: outbox payloads, and records being republished to a dead-letter topic. Sending
     * those through the JsonSerializer double-encodes them — the broker receives a quoted,
     * escaped JSON *string* rather than a JSON object, and the consumer cannot deserialize
     * it. That is a real bug, not a cosmetic one, and it was caught only because an
     * integration test asserted on the payload rather than merely on delivery.
     *
     * <p>Built from the auto-configured {@link ProducerFactory}'s own configuration, so it
     * inherits bootstrap servers, acks and — importantly — the timeout settings. Building it
     * from scratch left {@code max.block.ms} at its 60-second default, which turned a
     * broker-down test into a four-minute one.
     */
    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<?, ?> producerFactory) {

        Map<String, Object> props = new HashMap<>(producerFactory.getConfigurationProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
