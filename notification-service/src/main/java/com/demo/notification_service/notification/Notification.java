package com.demo.notification_service.notification;

/**
 * A message to be delivered to a customer.
 *
 * <p>Note what it does <em>not</em> contain: any order total, line items or customer record.
 * This service is told what happened, not given access to look it up. If a notification ever
 * needs the order total, the right fix is to add it to the event — not to give this service
 * a connection to order-service's database.
 *
 * <p>{@code recipientEmail} is the one exception, and it's nullable on purpose: only
 * order-service's {@code InvoiceGeneratedEvent} carries a real address (resolved from
 * customer-service at invoice time), so an {@code ORDER_CONFIRMED}/{@code ORDER_FAILED}
 * notification is still log-only. Null also covers a demo/legacy {@code customerId} with
 * no real onboarded customer record behind it -- {@link EmailNotificationSender} treats
 * "no address" as "nothing to email," not an error.
 *
 * <p>{@code htmlBody} is likewise nullable and only ever set for {@code INVOICE_GENERATED}
 * -- a real, presentable invoice (itemized lines, weight surcharge, total) that
 * {@link InvoiceEventListener} builds from the event's own data. {@code body} stays the
 * plain-text summary every notification has always had, used for the log line and as the
 * email's plain-text fallback.
 */
public record Notification(Kind kind, String orderId, String body, String recipientEmail, String htmlBody) {

    public enum Kind {
        ORDER_CONFIRMED,
        ORDER_FAILED,
        INVOICE_GENERATED
    }
}
