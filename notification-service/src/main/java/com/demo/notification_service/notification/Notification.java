package com.demo.notification_service.notification;

/**
 * A message to be delivered to a customer.
 *
 * <p>Note what it does <em>not</em> contain: any order total, line items or customer record.
 * This service is told what happened, not given access to look it up. If a notification ever
 * needs the order total, the right fix is to add it to the event — not to give this service
 * a connection to order-service's database.
 */
public record Notification(Kind kind, String orderId, String body) {

    public enum Kind {
        ORDER_CONFIRMED,
        ORDER_FAILED
    }
}
