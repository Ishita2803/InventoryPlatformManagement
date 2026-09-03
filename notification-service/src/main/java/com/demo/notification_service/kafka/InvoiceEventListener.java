package com.demo.notification_service.kafka;

import com.demo.notification_service.events.InvoiceGeneratedEvent;
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
 * Phase D8: emails a sales order's invoice once payment-service has computed it. Same
 * choreography as {@link InventoryEventListener} -- a pure subscriber with no idea what
 * the order actually contains beyond what the event carries, and the same known,
 * out-of-scope duplicate-notification risk documented there.
 */
@Component
public class InvoiceEventListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventListener.class);

    private final NotificationSender notificationSender;

    public InvoiceEventListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @KafkaListener(topics = KafkaTopics.INVOICE_GENERATED, groupId = "notification-service")
    public void onInvoiceGenerated(
            InvoiceGeneratedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received InvoiceGenerated eventId={} orderId={} invoiceId={} totalAmount={}",
                    event.eventId(), event.orderId(), event.invoiceId(), event.totalAmount());

            notificationSender.send(new Notification(
                    Notification.Kind.INVOICE_GENERATED,
                    event.orderId(),
                    "Your invoice " + event.invoiceId() + " for order " + event.orderId()
                            + " is ready: " + event.totalAmount() + "."));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
