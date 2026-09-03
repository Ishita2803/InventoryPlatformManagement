package com.demo.notification_service.notification;

import org.springframework.stereotype.Component;

/**
 * The <em>only</em> {@link NotificationSender}, and every Kafka listener injects it.
 * {@link LoggingNotificationSender} and {@link EmailNotificationSender} deliberately do
 * not implement {@link NotificationSender} themselves -- if they did, an autowire point
 * typed {@code NotificationSender} would have three candidates in production, and a test
 * overriding the bean with {@code @Primary} (see {@code NotificationFlowIT}) would then
 * have to out-rank two production {@code @Primary}-less-but-still-matching beans as well
 * as this one. One implementation, no {@code @Primary} needed here at all, and a test's
 * single {@code @Primary} override is unambiguous.
 *
 * <p>{@link LoggingNotificationSender} always runs -- it's the one thing every existing
 * test and every prior demo have relied on being able to see in the pod logs.
 * {@link EmailNotificationSender} runs alongside it, and is itself a no-op when there's no
 * recipient address to send to.
 */
@Component
public class CompositeNotificationSender implements NotificationSender {

    private final LoggingNotificationSender loggingNotificationSender;
    private final EmailNotificationSender emailNotificationSender;

    public CompositeNotificationSender(
            LoggingNotificationSender loggingNotificationSender,
            EmailNotificationSender emailNotificationSender) {
        this.loggingNotificationSender = loggingNotificationSender;
        this.emailNotificationSender = emailNotificationSender;
    }

    @Override
    public void send(Notification notification) {
        loggingNotificationSender.send(notification);
        emailNotificationSender.send(notification);
    }
}
