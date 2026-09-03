package com.demo.notification_service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes every notification to the log, regardless of whether it was also emailed --
 * the one thing every existing test and every prior demo has relied on being able to
 * see in the pod logs.
 *
 * <p>Deliberately <em>not</em> a {@link NotificationSender} itself: only
 * {@link CompositeNotificationSender} implements that interface, so there is exactly one
 * bean production code (and a test's {@code @Primary} override) ever has to reason
 * about, rather than three candidates an autowire point could resolve to.
 */
@Component
public class LoggingNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    public void send(Notification notification) {
        log.info("""

                ---------- MOCK EMAIL ----------
                To:      {}
                Subject: {}

                {}
                --------------------------------""",
                notification.recipientEmail() != null
                        ? notification.recipientEmail()
                        : "customer of order " + notification.orderId(),
                notification.kind(), notification.body());
    }
}
