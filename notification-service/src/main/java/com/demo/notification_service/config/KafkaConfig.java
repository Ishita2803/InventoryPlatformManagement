package com.demo.notification_service.config;

import com.demo.notification_service.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring for a consumer-only service.
 *
 * <p>Smaller than the equivalents in order- and inventory-service, and the differences are
 * deliberate rather than accidental:
 *
 * <ul>
 *   <li><strong>No topic declarations for the two topics it reads.</strong> inventory-service
 *       owns and creates those. A consumer that also declares its source topics quietly
 *       becomes a second owner of the schema, and the two definitions drift.</li>
 *   <li><strong>No object-valued {@code KafkaTemplate}.</strong> This service never publishes
 *       a domain event, so it does not need one. The single {@code KafkaTemplate<String,
 *       String>} below therefore switches off Boot's auto-configured template — which is
 *       fine <em>here</em>, precisely because nothing injects the object-valued one. In the
 *       other two services it broke everything.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    public static final String DLT_SUFFIX = ".DLT";

    /**
     * Resolves each payload to the type the listener method declares, so the wire format
     * stays plain JSON with no Java type header naming a class this service cannot load.
     */
    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        return new StringJsonMessageConverter();
    }

    /**
     * Values are plain strings because the only thing this service ever produces is a
     * dead-lettered record, whose payload is already serialized JSON. Routing it through a
     * JsonSerializer would re-encode it into a quoted, escaped string.
     */
    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(
            org.springframework.kafka.core.ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(200L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000L);
        backOff.setMaxAttempts(3);
        backOff.setJitter(100L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                ConversionException.class,
                MessageConversionException.class);

        return handler;
    }

    /**
     * The dead-letter topics, declared here as well as in order-service.
     *
     * <p>Both services consume these topics under different group ids, so both can
     * dead-letter to them. Declaring them in both means this service starts correctly even
     * if order-service has never run. Which service quarantined a given record is
     * distinguishable from the {@code kafka_dlt-original-consumer-group} header Spring adds.
     */
    @Bean
    public NewTopic inventoryReservedDltTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_RESERVED + DLT_SUFFIX)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic inventoryFailedDltTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_FAILED + DLT_SUFFIX)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
