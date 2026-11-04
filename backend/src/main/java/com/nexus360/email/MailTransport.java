package com.nexus360.email;

import com.nexus360.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP delivery for the outbox. Configured from {@code MAIL_*} (never from the database or the API) and deliberately not
 * exposed as a {@code JavaMailSender} bean, so actuator health does not depend on the SMTP server. Without MAIL_HOST,
 * {@link #configured()} is false and nothing is ever sent.
 */
@Component
public class MailTransport {

    private final AppProperties.Mail config;
    private final JavaMailSenderImpl sender;

    public MailTransport(AppProperties properties) {
        this.config = properties.mail();
        this.sender = config != null && config.configured() ? build(config) : null;
    }

    public boolean configured() {
        return sender != null;
    }

    public String fromAddress() {
        return config == null ? null : config.fromAddress();
    }

    /** Sends one message; throws {@link MailException} when the SMTP server refuses or cannot be reached. */
    public void send(EmailMessage message) {
        if (sender == null) {
            throw new IllegalStateException("SMTP is not configured");
        }
        MimeMessage mime = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(config.fromAddress(), "SA", StandardCharsets.UTF_8.name()));
            helper.setTo(message.getToAddress());
            helper.setSubject(message.getSubject());
            helper.setText(message.getTextBody(), message.getHtmlBody());
            if (message.getAttachmentData() != null && message.getAttachmentName() != null) {
                helper.addAttachment(message.getAttachmentName(), new ByteArrayResource(message.getAttachmentData()),
                        message.getAttachmentContentType() == null ? "application/octet-stream" : message.getAttachmentContentType());
            }
            mime.setHeader("Auto-Submitted", "auto-generated");
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Could not build the message", e);
        }
        sender.send(mime);
    }

    private static JavaMailSenderImpl build(AppProperties.Mail c) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(c.host().trim());
        s.setPort(c.port());
        if (c.username() != null && !c.username().isBlank()) {
            s.setUsername(c.username());
            s.setPassword(c.password());
        }
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = s.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(c.username() != null && !c.username().isBlank()));
        p.put("mail.smtp.starttls.enable", String.valueOf(c.starttls()));
        p.put("mail.smtp.connectiontimeout", String.valueOf(c.timeoutMs()));
        p.put("mail.smtp.timeout", String.valueOf(c.timeoutMs()));
        p.put("mail.smtp.writetimeout", String.valueOf(c.timeoutMs()));
        return s;
    }
}
