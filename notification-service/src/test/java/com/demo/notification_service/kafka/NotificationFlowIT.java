package com.demo.notification_service.kafka;

import com.demo.notification_service.events.InventoryFailedEvent;
import com.demo.notification_service.events.InventoryReservedEvent;
import com.demo.notification_service.events.KafkaTopics;
import com.demo.notification_service.notification.Notification;
import com.demo.notification_service.notification.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The notification path end to end, against a real broker.
 *
 * <p>Uses a recording {@link NotificationSender} rather than a Mockito mock so the assertions
 * describe delivered notifications rather than interactions — and so a duplicate delivery
 * would show up as two entries rather than needing a times(2) verification.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaTopics.INVENTORY_RESERVED, KafkaTopics.INVENTORY_FAILED}
)
class NotificationFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TestConfiguration
    static class RecordingSenderConfig {

        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    /** Captures what would have been emailed. */
    static class RecordingNotificationSender implements NotificationSender {

        final List<Notification> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Notification notification) {
            sent.add(notification);
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RecordingNotificationSender sender;

    @BeforeEach
    void setUp() {
        sender.sent.clear();
    }

    @Test
    @DisplayName("an InventoryReserved event on the topic produces a confirmation")
    void reservedEventNotifiesTheCustomer() {

        String orderId = UUID.randomUUID().toString();

        send(KafkaTopics.INVENTORY_RESERVED, orderId, """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-08-26T10:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(notificationsFor(orderId)).hasSize(1));

        Notification sent = notificationsFor(orderId).getFirst();
        assertThat(sent.kind()).isEqualTo(Notification.Kind.ORDER_CONFIRMED);
    }

    @Test
    @DisplayName("an InventoryFailed event passes the reason through to the customer")
    void failedEventNotifiesWithReason() {

        String orderId = UUID.randomUUID().toString();

        send(KafkaTopics.INVENTORY_FAILED, orderId, """
                {"eventId":"%s","orderId":"%s","reason":"Insufficient inventory. Available=0, requested=9",
                 "occurredAt":"2026-08-26T10:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(notificationsFor(orderId)).hasSize(1));

        Notification sent = notificationsFor(orderId).getFirst();
        assertThat(sent.kind()).isEqualTo(Notification.Kind.ORDER_FAILED);
        assertThat(sent.body()).contains("Insufficient inventory");
    }

    @Test
    @DisplayName("a redelivered event sends a SECOND notification — this service does not dedupe")
    void duplicateDeliveryProducesDuplicateNotification() {

        String orderId = UUID.randomUUID().toString();
        String payload = """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-08-26T10:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId);

        send(KafkaTopics.INVENTORY_RESERVED, orderId, payload);
        send(KafkaTopics.INVENTORY_RESERVED, orderId, payload);

        // Asserting the LIMITATION, not a feature. order- and inventory-service dedupe with
        // a processed_event table; this service has no database, so it cannot. Pinning the
        // behaviour means nobody later assumes it is safe from duplicates.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(notificationsFor(orderId)).hasSize(2));
    }

    private void send(String topic, String key, String payload) {
        kafkaTemplate.send(topic, key, payload);
    }

    private List<Notification> notificationsFor(String orderId) {
        return sender.sent.stream()
                .filter(n -> orderId.equals(n.orderId()))
                .toList();
    }
}
