package vn.lab.mail;

import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Run with: mvn test-compile exec:java -Dexec.mainClass=vn.lab.mail.ProtocolSmokeTest -Dexec.classpathScope=test */
public final class ProtocolSmokeTest {
    public static void main(String[] args) throws Exception {
        ServerEndpoint endpoint = ServerEndpoint.parse("127.0.0.1:2525");
        if (endpoint.smtpPort() != 2525 || endpoint.pop3Port() != 2526)
            throw new AssertionError("IP:port mapping failed");
        Path file = Files.createTempDirectory("mail-smoke-").resolve("mail.db");
        try (LocalMailServer server = new LocalMailServer(file, 0, 0)) {
            server.start();
            LocalMailService newUser = new LocalMailService("127.0.0.1", server.smtpPort(), server.pop3Port());
            newUser.register("charlie@localhost", "charlie-pass-1234");
            newUser.connect("charlie@localhost", "charlie-pass-1234");
            boolean duplicateRejected = false;
            try { newUser.register("charlie@localhost", "charlie-pass-1234"); }
            catch (IOException expected) { duplicateRejected = true; }
            if (!duplicateRejected) throw new AssertionError("Duplicate account was accepted");
            newUser.close();
            byte[] raw;
            MimeMessage message = MimeUtil.create("alice@localhost", "bob@localhost", "Kiểm tra tiếng Việt", "Xin chào Bob.\nDòng thứ hai.");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { message.writeTo(out); raw = out.toByteArray(); }

            try (Wire smtp = new Wire(server.smtpPort())) {
                smtp.expect("220"); smtp.send("EHLO test"); smtp.expect("250-"); smtp.expect("250-"); smtp.expect("250 ");
                smtp.send("AUTH LOGIN"); smtp.expect("334");
                smtp.send(Base64.getEncoder().encodeToString("alice@localhost".getBytes(StandardCharsets.UTF_8))); smtp.expect("334");
                smtp.send(Base64.getEncoder().encodeToString("wrong".getBytes(StandardCharsets.UTF_8))); smtp.expect("535");
                smtp.send("AUTH LOGIN"); smtp.expect("334");
                smtp.send(Base64.getEncoder().encodeToString("alice@localhost".getBytes(StandardCharsets.UTF_8))); smtp.expect("334");
                smtp.send(Base64.getEncoder().encodeToString("alice-app-1234".getBytes(StandardCharsets.UTF_8))); smtp.expect("235");
                smtp.send("MAIL FROM:<alice@localhost>"); smtp.expect("250");
                smtp.send("RCPT TO:<bob@localhost>"); smtp.expect("250");
                smtp.send("DATA"); smtp.expect("354");
                String[] lines = new String(raw, StandardCharsets.ISO_8859_1).split("\r?\n", -1);
                int length = lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
                for (int i = 0; i < length; i++)
                    smtp.send(lines[i].startsWith(".") ? "." + lines[i] : lines[i]);
                smtp.send("."); smtp.expect("250");
            }

            // Kiểm tra thêm cách đăng nhập AUTH PLAIN mà nhiều mail client sử dụng.
            try (Wire smtp = new Wire(server.smtpPort())) {
                smtp.expect("220");
                smtp.send("EHLO test"); smtp.expect("250-"); smtp.expect("250-"); smtp.expect("250 ");
                String credentials = "\0alice@localhost\0alice-app-1234";
                smtp.send("AUTH PLAIN " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
                smtp.expect("235");
            }

            try (Wire pop = new Wire(server.pop3Port())) {
                pop.expect("+OK"); pop.send("USER bob@localhost"); pop.expect("+OK");
                pop.send("PASS bob-app-1234"); pop.expect("+OK");
                pop.send("STAT"); String count = pop.expect("+OK");
                if (!count.startsWith("+OK 1 ")) throw new AssertionError(count);
                pop.send("RETR 1"); pop.expect("+OK");
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                String line;
                while (!(line = pop.read()).equals(".")) content.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                MailRecord received = MimeUtil.parse(content.toByteArray(), "1");
                if (!received.subject().equals("Kiểm tra tiếng Việt") || !received.body().contains("Dòng thứ hai."))
                    throw new AssertionError("subject=" + received.subject() + " codes=" + received.subject().codePoints().boxed().toList()
                            + " body=" + received.body() + " raw=" + new String(raw, StandardCharsets.ISO_8859_1));
            }
            LocalMailService alice = new LocalMailService("127.0.0.1", server.smtpPort(), server.pop3Port());
            LocalMailService bob = new LocalMailService("127.0.0.1", server.smtpPort(), server.pop3Port());
            alice.connect("alice@localhost", "alice-app-1234");
            bob.connect("bob@localhost", "bob-app-1234");
            alice.send("bob@localhost", "JavaMail client", "End-to-end message");
            if (bob.inbox().size() != 2 || alice.sent().size() != 2 ||
                    !bob.inbox().getFirst().body().contains("End-to-end message"))
                throw new AssertionError("LocalMailService round-trip failed");
            alice.close(); bob.close();
        }
        MailDatabase database = new MailDatabase(file);
        if (database.list("bob@localhost", "INBOX").size() != 2 ||
                database.list("alice@localhost", "SENT").size() != 2 ||
                !database.listAccounts().contains("charlie@localhost"))
            throw new AssertionError("Messages were not persisted");
        long latestId = database.list("bob@localhost", "INBOX").getFirst().id();
        if (!database.readMail(latestId).body().contains("End-to-end message"))
            throw new AssertionError("Server mail detail failed");
        System.out.println("PASS: client registration, duplicate rejection, SMTP/POP3, mail detail, SQLite persistence");
    }

    private static final class Wire implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        final BufferedWriter output;
        Wire(int port) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
        }
        String read() throws IOException { return input.readLine(); }
        String expect(String prefix) throws IOException {
            String value = read();
            if (value == null || !value.startsWith(prefix)) throw new AssertionError("Expected " + prefix + ", got " + value);
            return value;
        }
        void send(String text) throws IOException { output.write(text + "\r\n"); output.flush(); }
        @Override public void close() throws IOException { socket.close(); }
    }
}
