package vn.lab.mail;

import java.time.Instant;

public record MailRecord(String id, String from, String to, String subject, String body, Instant date) {
    @Override public String toString() {
        return (subject == null || subject.isBlank() ? "(Không có tiêu đề)" : subject)
                + "\n" + from + "  •  " + date;
    }
}
