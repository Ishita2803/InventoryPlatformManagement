package com.demo.notification_service.notification;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Session;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-no-op-not-an-error rule, and the plain-text vs. HTML branch that actually
 * decides whether {@code customer.html}'s "check your email" story is real.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationSender sender;

    @BeforeEach
    void setUp() {
        sender = new EmailNotificationSender(mailSender, "impulse@example.com");
    }

    @Test
    @DisplayName("no recipient email: skipped silently, nothing sent, no exception")
    void noRecipientIsANoOp() {

        Notification notification = new Notification(
                Notification.Kind.ORDER_CONFIRMED, "order-1", "body", null, null);

        assertThatNoException().isThrownBy(() -> sender.send(notification));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("no htmlBody: sends a plain SimpleMailMessage")
    void plainTextWhenNoHtmlBody() {

        Notification notification = new Notification(
                Notification.Kind.ORDER_CONFIRMED, "order-1", "Your order is confirmed.",
                "customer@example.com", null);

        sender.send(notification);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThatNoException().isThrownBy(() -> {
            org.assertj.core.api.Assertions.assertThat(sent.getTo()).containsExactly("customer@example.com");
            org.assertj.core.api.Assertions.assertThat(sent.getSubject()).isEqualTo("Your Impulse order is confirmed");
            org.assertj.core.api.Assertions.assertThat(sent.getText()).isEqualTo("Your order is confirmed.");
        });
    }

    @Test
    @DisplayName("an htmlBody present: sends a MimeMessage, not the plain SimpleMailMessage path")
    void htmlWhenHtmlBodyPresent() throws Exception {

        Session session = Session.getInstance(new Properties());
        MimeMessage mimeMessage = new MimeMessage(session);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        Notification notification = new Notification(
                Notification.Kind.INVOICE_GENERATED, "order-1", "Invoice ready.",
                "customer@example.com", "<p>Invoice ready.</p>");

        sender.send(notification);

        verify(mailSender, times(1)).send(mimeMessage);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("an SMTP failure is logged, not thrown -- the invoice already exists")
    void smtpFailureDoesNotPropagate() {

        doThrow(new MailSendException("SMTP is down")).when(mailSender).send(any(SimpleMailMessage.class));

        Notification notification = new Notification(
                Notification.Kind.ORDER_CONFIRMED, "order-1", "body", "customer@example.com", null);

        assertThatNoException().isThrownBy(() -> sender.send(notification));
    }
}
