package com.demo.notification_service.notification;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

/**
 * Sends a real email over SMTP, via Spring's {@link JavaMailSender} -- Gmail SMTP by
 * default, but nothing here is Gmail-specific; any SMTP host works through
 * {@code spring.mail.*} configuration.
 *
 * <p>A no-op, not an error, when {@link Notification#recipientEmail()} is null: not every
 * {@code Notification.Kind} has a resolved address yet (only {@code INVOICE_GENERATED}
 * does, as of Phase D8+), and a demo/legacy {@code customerId} may have no real
 * onboarded customer record behind it at all. Failing loudly here would turn "we don't
 * know this customer's email" into a 500 for a request that already succeeded.
 *
 * <p>Sends HTML (with the plain-text {@link Notification#body()} as the alternative
 * part every mail client falls back to) when {@link Notification#htmlBody()} is present;
 * plain text only otherwise. Only {@code INVOICE_GENERATED} builds an HTML body today.
 *
 * <p>Deliberately <em>not</em> a {@link NotificationSender} itself -- see
 * {@link LoggingNotificationSender}'s class doc for why.
 */
@Component
public class EmailNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationSender(
            JavaMailSender mailSender,
            @Value("${notification.from-email:${spring.mail.username:}}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void send(Notification notification) {

        if (notification.recipientEmail() == null || notification.recipientEmail().isBlank()) {
            log.info("No recipient email on file for order {} ({}) -- skipping real email",
                    notification.orderId(), notification.kind());
            return;
        }

        try {
            if (notification.htmlBody() != null) {
                sendHtml(notification);
            } else {
                sendPlainText(notification);
            }
        } catch (MailException | jakarta.mail.MessagingException | UnsupportedEncodingException failure) {
            // Mirrors PaymentClient.generateInvoice's own fails-open reasoning: the order
            // and its invoice already exist, so an SMTP hiccup is logged, not thrown back
            // at a Kafka listener that would otherwise retry forever or dead-letter a
            // perfectly valid event.
            log.error("Could not email {} notification for order {} to {}: {}",
                    notification.kind(), notification.orderId(), notification.recipientEmail(),
                    failure.toString());
            return;
        }

        log.info("Emailed {} notification for order {} to {}",
                notification.kind(), notification.orderId(), notification.recipientEmail());
    }

    private void sendPlainText(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(notification.recipientEmail());
        message.setSubject(subjectFor(notification.kind()));
        message.setText(notification.body());
        mailSender.send(message);
    }

    private void sendHtml(Notification notification)
            throws jakarta.mail.MessagingException, UnsupportedEncodingException {

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        // true = multipart, so the plain-text part travels alongside the HTML one for
        // clients that can't or won't render HTML.
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(fromAddress, "Impulse");
        helper.setTo(notification.recipientEmail());
        helper.setSubject(subjectFor(notification.kind()));
        helper.setText(notification.body(), notification.htmlBody());
        mailSender.send(mimeMessage);
    }

    private String subjectFor(Notification.Kind kind) {
        return switch (kind) {
            case INVOICE_GENERATED -> "Your Impulse invoice is ready";
            case ORDER_CONFIRMED -> "Your Impulse order is confirmed";
            case ORDER_FAILED -> "Your Impulse order could not be fulfilled";
        };
    }
}
