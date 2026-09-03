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

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Phase D8: emails a sales order's invoice once payment-service has computed it. Same
 * choreography as {@link InventoryEventListener} -- a pure subscriber with no idea what
 * the order actually contains beyond what the event carries, and the same known,
 * out-of-scope duplicate-notification risk documented there.
 *
 * <p>Builds a real, presentable invoice here rather than a one-line total: an itemized
 * table, the weight surcharge broken out, and a grand total, both as HTML (for
 * {@code EmailNotificationSender}) and as plain text (for the log line and as the
 * email's plain-text fallback). Everything it needs is already on the event -- Phase
 * D8/D9's own invoice-generation call is where this data was computed in the first
 * place, so this listener only ever formats, never recomputes.
 */
@Component
public class InvoiceEventListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventListener.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US)
                    .withZone(java.time.ZoneOffset.UTC);

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
                    plainTextInvoice(event),
                    event.recipientEmail(),
                    htmlInvoice(event)));
        } finally {
            MDC.remove("correlationId");
        }
    }

    private String plainTextInvoice(InvoiceGeneratedEvent event) {

        StringBuilder body = new StringBuilder();
        body.append("Invoice ").append(event.invoiceId())
                .append(" for order ").append(event.orderId()).append("\n\n");

        for (InvoiceGeneratedEvent.Line line : event.items()) {
            body.append(String.format("  %-20s  x%-4d  %8s  %10s%n",
                    line.skuNumber(), line.quantity(), money(line.unitPrice()), money(line.lineTotal())));
        }

        body.append("\nSubtotal:        ").append(money(event.lineTotal()));
        body.append("\nShipping (").append(event.carrierCode()).append("): ").append(money(event.weightSurcharge()));
        body.append("\nTotal:           ").append(money(event.totalAmount()));

        return body.toString();
    }

    /**
     * Inline-styled HTML, deliberately -- most email clients strip or ignore a
     * {@code <style>} block, so every rule here lives on the element it applies to.
     */
    private String htmlInvoice(InvoiceGeneratedEvent event) {

        StringBuilder rows = new StringBuilder();
        for (InvoiceGeneratedEvent.Line line : event.items()) {
            rows.append("""
                    <tr>
                      <td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;">%s</td>
                      <td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;text-align:center;">%d</td>
                      <td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;text-align:right;">%s</td>
                      <td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;text-align:right;">%s</td>
                    </tr>
                    """.formatted(line.skuNumber(), line.quantity(), money(line.unitPrice()), money(line.lineTotal())));
        }

        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:0 auto;color:#1f2937;">
                  <div style="background:#111827;color:#ffffff;padding:20px 24px;border-radius:8px 8px 0 0;">
                    <h1 style="margin:0;font-size:20px;">Impulse</h1>
                    <p style="margin:4px 0 0;font-size:13px;color:#9ca3af;">Invoice %s</p>
                  </div>
                  <div style="border:1px solid #e5e7eb;border-top:none;padding:24px;border-radius:0 0 8px 8px;">
                    <p style="margin:0 0 4px;font-size:13px;color:#6b7280;">Order</p>
                    <p style="margin:0 0 16px;font-size:14px;">%s</p>
                    <p style="margin:0 0 20px;font-size:12px;color:#9ca3af;">%s</p>

                    <table style="width:100%%;border-collapse:collapse;font-size:14px;">
                      <thead>
                        <tr style="background:#f9fafb;">
                          <th style="padding:8px 12px;text-align:left;color:#6b7280;font-size:12px;">Sku</th>
                          <th style="padding:8px 12px;text-align:center;color:#6b7280;font-size:12px;">Qty</th>
                          <th style="padding:8px 12px;text-align:right;color:#6b7280;font-size:12px;">Unit price</th>
                          <th style="padding:8px 12px;text-align:right;color:#6b7280;font-size:12px;">Line total</th>
                        </tr>
                      </thead>
                      <tbody>
                        %s
                      </tbody>
                    </table>

                    <div style="margin-top:16px;padding-top:12px;">
                      <div style="display:flex;justify-content:space-between;font-size:14px;padding:4px 0;">
                        <span style="color:#6b7280;">Subtotal</span><span>%s</span>
                      </div>
                      <div style="display:flex;justify-content:space-between;font-size:14px;padding:4px 0;">
                        <span style="color:#6b7280;">Shipping (%s)</span><span>%s</span>
                      </div>
                      <div style="display:flex;justify-content:space-between;font-size:17px;font-weight:700;padding:10px 0 0;border-top:2px solid #111827;margin-top:8px;">
                        <span>Total</span><span>%s</span>
                      </div>
                    </div>
                  </div>
                  <p style="text-align:center;color:#9ca3af;font-size:11px;margin-top:16px;">
                    Impulse -- a portfolio demo of a modernized supply-chain platform. This is not a real charge.
                  </p>
                </div>
                """.formatted(
                event.invoiceId(),
                event.orderId(),
                DATE_FORMAT.format(event.occurredAt()),
                rows,
                money(event.lineTotal()),
                event.carrierCode(),
                money(event.weightSurcharge()),
                money(event.totalAmount()));
    }

    private String money(BigDecimal amount) {
        return "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
