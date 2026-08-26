package com.demo.notification_service.notification;

/**
 * How a notification actually leaves the system.
 *
 * <p>An interface with one logging implementation, rather than logging inline in the Kafka
 * listener. Two reasons: the listener's tests can then assert on <em>what was sent</em>
 * instead of scraping log output, and swapping in a real email or SMS provider later is a
 * new implementation rather than surgery on the consumer.
 */
public interface NotificationSender {

    void send(Notification notification);
}
