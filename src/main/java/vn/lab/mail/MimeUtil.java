package vn.lab.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

final class MimeUtil {
    private MimeUtil() {}

    static MimeMessage create(String from, String to, String subject, String body) throws Exception {
        return create(Session.getInstance(new Properties()), from, to, subject, body);
    }

    static MimeMessage create(Session session, String from, String to, String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        message.saveChanges();
        return message;
    }

    static MailRecord parse(byte[] data, String id) throws Exception {
        return parse(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data)), id);
    }

    static MailRecord parse(Message message, String id) throws Exception {
        Address[] from = message.getFrom();
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        Date date = message.getSentDate();
        return new MailRecord(id, addresses(from), addresses(to), message.getSubject(),
                plainText(message), date == null ? Instant.now() : date.toInstant());
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "";
        StringBuilder result = new StringBuilder();
        for (Address address : addresses) {
            if (!result.isEmpty()) result.append(", ");
            result.append(address);
        }
        return result.toString();
    }

    private static String plainText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) return String.valueOf(part.getContent());
        if (part.isMimeType("text/html")) return String.valueOf(part.getContent())
                .replaceAll("(?is)<(script|style).*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            String fallback = "";
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) continue;
                String text = plainText(child);
                if (child.isMimeType("text/plain") && !text.isBlank()) return text;
                if (fallback.isBlank()) fallback = text;
            }
            return fallback;
        }
        return "";
    }
}
