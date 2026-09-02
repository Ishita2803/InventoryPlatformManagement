package com.demo.notification_service.kafka;

import com.demo.notification_service.events.InventoryFailedEvent;
import com.demo.notification_service.events.InventoryReservedEvent;
import com.demo.notification_service.notification.Notification;
import com.demo.notification_service.notification.NotificationSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InventoryEventListenerTest {

    private static final String ORDER_ID = "55555555-5555-5555-5555-555555555555";

    @Mock
    private NotificationSender notificationSender;

    @InjectMocks
    private InventoryEventListener listener;

    @Test
    @DisplayName("a reserved order produces a confirmation notification naming the order")
    void reservedProducesConfirmation() {

        listener.onInventoryReserved(new InventoryReservedEvent(
                UUID.randomUUID().toString(), ORDER_ID, Instant.now()), null);

        Notification sent = capture();
        assertThat(sent.kind()).isEqualTo(Notification.Kind.ORDER_CONFIRMED);
        assertThat(sent.orderId()).isEqualTo(ORDER_ID);
        assertThat(sent.body()).contains(ORDER_ID);
    }

    @Test
    @DisplayName("a failed order explains why, and says the customer was not charged")
    void failedProducesFailureNoticeWithReason() {

        listener.onInventoryFailed(new InventoryFailedEvent(
                UUID.randomUUID().toString(), ORDER_ID,
                "Insufficient inventory. Available=0, requested=2", Instant.now()), null);

        Notification sent = capture();
        assertThat(sent.kind()).isEqualTo(Notification.Kind.ORDER_FAILED);
        assertThat(sent.orderId()).isEqualTo(ORDER_ID);

        // The reason from the event reaches the customer rather than a generic apology.
        assertThat(sent.body()).contains("Insufficient inventory");
        assertThat(sent.body()).contains("not been charged");
    }

    private Notification capture() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationSender).send(captor.capture());
        return captor.getValue();
    }
}
