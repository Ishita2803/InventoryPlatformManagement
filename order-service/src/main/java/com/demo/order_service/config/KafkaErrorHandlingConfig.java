package com.demo.order_service.config;

import com.demo.order_service.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a message this service cannot handle. See inventory-service's equivalent
 * for the reasoning; the two are deliberately duplicated rather than shared, for the same
 * reason the event classes are.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {

        // The string template, so the dead-lettered payload is the original text rather
        // than a JSON-encoded copy of it.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        // Spring Framework 7 folded ExponentialBackOffWithMaxRetries into ExponentialBackOff,
        // which now carries setMaxAttempts and built-in jitter.
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(200L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000L);
        backOff.setMaxAttempts(3);
        // Jitter so that many consumers failing on the same downstream outage do not all
        // retry in lockstep and hammer it back down as it recovers.
        backOff.setJitter(100L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        handler.addNotRetryableExceptions(
                ConversionException.class,
                MessageConversionException.class);

        return handler;
    }

    /** This service consumes the two inventory result topics, so it owns both their DLTs. */
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
