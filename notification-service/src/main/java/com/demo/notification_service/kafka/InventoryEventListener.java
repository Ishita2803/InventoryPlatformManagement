package com.demo.notification_service.kafka;

import com.demo.notification_service.events.InventoryFailedEvent;
import com.demo.notification_service.events.InventoryReservedEvent;
import com.demo.notification_service.events.KafkaTopics;
import com.demo.notification_service.notification.Notification;
import com.demo.notification_service.notification.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Turns inventory outcomes into customer notifications.
 *
 * <p>This service is a pure subscriber. It never calls order-service, never queries a
 * database, and has no idea what an order costs or who placed it beyond what the event
 * carries. That is the point of choreography: adding a notification did not require changing
 * either of the services that produce these events.
 *
 * <p><strong>Duplicate notifications are possible, and are not prevented here.</strong>
 * Kafka is at-least-once, so a redelivered event sends a second email. order-service and
 * inventory-service each guard against duplicates with a {@code processed_event} table, but
 * this service deliberately has no database — see {@code KafkaConfig} — so it has nowhere to
 * record what it has already sent. An in-memory set would be worse than nothing: it would
 * look like deduplication while being per-instance and lost on every restart.
 *
 * <p>The real fix belongs at the boundary, not here: real email providers accept an
 * idempotency key and collapse duplicates themselves. Failing that, this service would need
 * its own small store. Both are out of scope while the "provider" is a log line.
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final NotificationSender notificationSender;

    public InventoryEventListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED, groupId = "notification-service")
    public void onInventoryReserved(
            InventoryReservedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received InventoryReserved eventId={} orderId={}",
                    event.eventId(), event.orderId());

            notificationSender.send(new Notification(
                    Notification.Kind.ORDER_CONFIRMED,
                    event.orderId(),
                    "Good news — we have reserved the stock for your order "
                            + event.orderId() + " and it is being prepared."));
        } finally {
            MDC.remove("correlationId");
        }
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED, groupId = "notification-service")
    public void onInventoryFailed(
            InventoryFailedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received InventoryFailed eventId={} orderId={} reason={}",
                    event.eventId(), event.orderId(), event.reason());

            notificationSender.send(new Notification(
                    Notification.Kind.ORDER_FAILED,
                    event.orderId(),
                    "We were unable to fulfil your order " + event.orderId()
                            + ". Reason: " + event.reason()
                            + ". You have not been charged."));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
