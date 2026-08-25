package com.demo.inventory_service.config;

import com.demo.inventory_service.events.KafkaTopics;
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
 * What happens to a message this service cannot handle.
 *
 * <p>Without this, Spring's default is ten quick attempts and then a log line — the message
 * is dropped and nobody finds out. With it, a message that keeps failing is moved to
 * {@code <topic>.DLT}, where it can be inspected, fixed and replayed.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    /** Suffix used by the recoverer below. Kept as a constant so the topic bean agrees. */
    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {

        // Same partition number in the DLT as in the source topic, so the DLT must have at
        // least as many partitions — hence the explicit NewTopic below rather than trusting
        // broker auto-creation defaults.
        // The string template, so the dead-lettered payload is the original text rather
        // than a JSON-encoded copy of it.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        // Three attempts with backoff. Bounded because the failures worth retrying are
        // transient (a database blip, a lock clash) and those resolve in seconds; anything
        // still failing after that will fail forever, and retrying it just blocks the
        // partition behind it.
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

        // A payload that will not parse will not parse the second time either. Retrying it
        // wastes six seconds per poison message and delays every good message queued behind
        // it, so these go straight to the DLT.
        handler.addNotRetryableExceptions(
                ConversionException.class,
                MessageConversionException.class);

        return handler;
    }

    /** This service consumes order.placed, so this is the DLT it can produce to. */
    @Bean
    public NewTopic orderPlacedDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_PLACED + DLT_SUFFIX)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
