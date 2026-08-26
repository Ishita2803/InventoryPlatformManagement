package com.demo.notification_service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mock provider: writes the notification to the log instead of sending it.
 *
 * <p>Deliberately the only implementation. Wiring a real email provider would add an
 * external dependency, credentials to manage and a rate limit to respect, and would
 * demonstrate nothing about the distributed-systems problems this project is about.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("""
                
                ---------- MOCK EMAIL ----------
                To:      customer of order {}
                Subject: {}
                
                {}
                --------------------------------""",
                notification.orderId(), notification.kind(), notification.body());
    }
}
