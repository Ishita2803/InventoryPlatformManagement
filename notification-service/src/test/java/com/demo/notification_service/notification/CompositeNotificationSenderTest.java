package com.demo.notification_service.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * Both delegates run for every notification -- logging is never skipped just because
 * email happened, and vice versa.
 */
@ExtendWith(MockitoExtension.class)
class CompositeNotificationSenderTest {

    @Mock
    private LoggingNotificationSender loggingNotificationSender;

    @Mock
    private EmailNotificationSender emailNotificationSender;

    @InjectMocks
    private CompositeNotificationSender compositeNotificationSender;

    @Test
    @DisplayName("sends to both the logger and the email sender")
    void delegatesToBoth() {

        Notification notification = new Notification(
                Notification.Kind.ORDER_CONFIRMED, "order-1", "body", null, null);

        compositeNotificationSender.send(notification);

        verify(loggingNotificationSender).send(notification);
        verify(emailNotificationSender).send(notification);
    }
}
