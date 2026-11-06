package vn.lab.mail;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Server SMTP/POP3 tối giản cho bài lab. */
public final class LocalMailServer implements AutoCloseable {
    private final MailDatabase database;
    private final ServerSocket smtp;
    private final ServerSocket pop3;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private Consumer<String> eventListener = System.out::println;

    public LocalMailServer(Path databaseFile, int smtpPort, int pop3Port) throws Exception {
        this(databaseFile, "127.0.0.1", smtpPort, pop3Port);
    }

    public LocalMailServer(Path databaseFile, String bindIp, int smtpPort, int pop3Port) throws Exception {
        database = new MailDatabase(databaseFile);
        smtp = new ServerSocket(); pop3 = new ServerSocket();
        try {
            InetAddress address = InetAddress.getByName(bindIp);
            if (!(address instanceof Inet4Address)) throw new IllegalArgumentException("Chỉ hỗ trợ IPv4");
            smtp.bind(new InetSocketAddress(address, smtpPort));
            pop3.bind(new InetSocketAddress(address, pop3Port));
        } catch (Exception e) { close(); throw e; }
    }

    public void start() {
        workers.submit(() -> accept(smtp, "SMTP", this::smtpSession));
        workers.submit(() -> accept(pop3, "POP3", this::pop3Session));
        log("Mail server ready: SMTP " + smtp.getLocalPort() + ", POP3 " + pop3.getLocalPort());
    }

    public void setEventListener(Consumer<String> listener) { eventListener = listener; }
    public int activeConnections() { return activeConnections.get(); }
    int smtpPort() { return smtp.getLocalPort(); }
    int pop3Port() { return pop3.getLocalPort(); }

    private void log(String message) { eventListener.accept(message); }

    private void accept(ServerSocket listener, String protocol, SessionHandler handler) {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                client.setSoTimeout(30_000);
                activeConnections.incrementAndGet();
                log(protocol + " connected: " + client.getRemoteSocketAddress());
                workers.submit(() -> {
                    try (client) { handler.handle(client); }
                    catch (Exception e) { log(protocol + " error: " + e.getMessage()); }
                    finally { activeConnections.decrementAndGet(); }
                });
            } catch (IOException e) { if (!listener.isClosed()) log(protocol + " error: " + e.getMessage()); }
        }
    }

    private void smtpSession(Socket socket) throws Exception {
        Wire w = new Wire(socket);
        w.line("220 localhost Simple Mail Server");
        // Một kết nối SMTP: đăng nhập -> người gửi -> người nhận -> nội dung.
        String user = null, sender = null;
        List<String> recipients = new ArrayList<>();
        String command;
        while ((command = w.read()) != null) {
            String upper = command.toUpperCase(Locale.ROOT);
            if (upper.startsWith("EHLO ") || upper.startsWith("HELO ")) {
                w.line("250-localhost"); w.line("250-AUTH LOGIN PLAIN"); w.line("250 SIZE 1048576");
            } else if (upper.startsWith("X-REGISTER ")) {
                registerAccount(command, w);
            } else if (upper.startsWith("AUTH PLAIN ")) {
                user = authPlain(command.substring(11), w); 
            } else if (upper.equals("AUTH PLAIN")) {
                w.line("334 "); user = authPlain(w.read(), w);
            } else if (upper.equals("AUTH LOGIN")) {
                w.line("334 VXNlcm5hbWU6"); String name = decoded(w.read());
                w.line("334 UGFzc3dvcmQ6"); String password = decoded(w.read());
                user = authenticate(name, password, w);
            } else if (upper.startsWith("MAIL FROM:")) {
                String candidate = mailbox(command.substring(10));
                if (user == null) w.line("530 Authentication required");
                else if (!user.equalsIgnoreCase(candidate)) w.line("553 Sender must match authenticated account");
                else { sender = user; recipients.clear(); w.line("250 Sender OK"); }
            } else if (upper.startsWith("RCPT TO:")) {
                String candidate = mailbox(command.substring(8));
                if (sender == null) w.line("503 MAIL FROM first");
                else if (!candidate.toLowerCase(Locale.ROOT).endsWith("@localhost") || !database.accountExists(candidate))
                    w.line("550 Unknown local recipient");
                else { recipients.add(candidate); w.line("250 Recipient OK"); }
            } else if (upper.equals("DATA")) {
                if (sender == null || recipients.isEmpty()) { w.line("503 Need sender and recipient"); continue; }
                if (!receiveMailData(w, sender, recipients)) return;
                sender = null; recipients.clear();
            } else if (upper.equals("RSET")) { sender = null; recipients.clear(); w.line("250 Reset"); }
            else if (upper.equals("NOOP")) w.line("250 OK");
            else if (upper.equals("QUIT")) { w.line("221 Bye"); return; }
            else w.line("502 Command not supported");
        }
    }

    /** Lệnh riêng của bài lab: client tự đăng ký tài khoản mới trước khi đăng nhập. */
    private void registerAccount(String command, Wire connection) throws IOException {
        String[] parts = command.split(" ", 3);
        if (parts.length != 3) {
            connection.line("501 Registration requires email and app password");
            return;
        }
        try {
            String address = decoded(parts[1]);
            String password = decoded(parts[2]);
            database.addAccount(address, password);
            log("Account created: " + LocalAddress.normalize(address));
            connection.line("250 Account created");
        } catch (Exception error) {
            connection.line("550 Account exists or registration data is invalid");
        }
    }

    /** DATA kết thúc bằng một dòng chỉ có dấu chấm. Dấu chấm đầu dòng được escape thành hai dấu chấm. */
    private boolean receiveMailData(Wire connection, String sender, List<String> recipients) throws IOException {
        connection.line("354 End data with <CRLF>.<CRLF>");
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        boolean tooLarge = false;
        String line;

        while ((line = connection.read()) != null && !line.equals(".")) {
            if (line.startsWith("..")) line = line.substring(1);
            byte[] bytes = (line + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            if (content.size() + bytes.length > 1_048_576) tooLarge = true;
            if (!tooLarge) content.write(bytes);
        }
        if (line == null) return false;
        if (tooLarge) {
            connection.line("552 Message too large");
            return true;
        }

        try {
            byte[] rawMail = content.toByteArray();
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(rawMail));
            Address[] from = message.getFrom();
            if (from == null || from.length != 1 ||
                    !((InternetAddress) from[0]).getAddress().equalsIgnoreCase(sender)) {
                connection.line("553 From header must match authenticated account");
            } else {
                database.save(sender, recipients, rawMail);
                log("Stored mail: " + sender + " -> " + String.join(", ", recipients));
                connection.line("250 Message stored");
            }
        } catch (Exception error) {
            connection.line("554 Invalid message");
        }
        return true;
    }

    private String authPlain(String encoded, Wire w) throws IOException {
        String value = decoded(encoded);
        int firstZero = value.indexOf('\0');
        int secondZero = value.indexOf('\0', firstZero + 1);
        if (firstZero < 0 || secondZero < 0) {
            w.line("535 Authentication failed");
            return null;
        }
        return authenticate(value.substring(firstZero + 1, secondZero), value.substring(secondZero + 1), w);
    }

    private String authenticate(String name, String password, Wire w) throws IOException {
        if (database.authenticate(name, password)) { w.line("235 Authentication successful"); return LocalAddress.normalize(name); }
        w.line("535 Authentication failed"); return null;
    }

    private void pop3Session(Socket socket) throws Exception {
        Wire w = new Wire(socket);
        w.line("+OK localhost POP3 ready");
        // POP3: đăng nhập, chọn thư mục, rồi lấy danh sách hoặc nội dung thư.
        String username = null, user = null, folder = "INBOX";
        List<MailDatabase.StoredMail> snapshot = List.of();
        String command;
        while ((command = w.read()) != null) {
            String[] parts = command.split(" ", 2);
            String verb = parts[0].toUpperCase(Locale.ROOT);
            String arg = parts.length == 2 ? parts[1].trim() : "";
            if (verb.equals("USER") && user == null) { username = arg; w.line("+OK User accepted"); }
            else if (verb.equals("PASS") && user == null) {
                if (username != null && database.authenticate(username, arg)) {
                    user = LocalAddress.normalize(username); snapshot = database.list(user, folder); w.line("+OK Logged in");
                } else w.line("-ERR Authentication failed");
            } else if (verb.equals("QUIT")) { w.line("+OK Bye"); return; }
            else if (user == null) w.line("-ERR Authenticate first");
            else if (verb.equals("X-FOLDER")) {
                if (!arg.equalsIgnoreCase("INBOX") && !arg.equalsIgnoreCase("SENT")) w.line("-ERR Unknown folder");
                else { folder = arg.toUpperCase(Locale.ROOT); snapshot = database.list(user, folder); w.line("+OK " + folder); }
            } else if (verb.equals("STAT")) {
                int totalBytes = 0;
                for (MailDatabase.StoredMail mail : snapshot) totalBytes += mail.content().length;
                w.line("+OK " + snapshot.size() + " " + totalBytes);
            }
            else if (verb.equals("LIST") || verb.equals("UIDL")) {
                w.line("+OK " + snapshot.size() + " messages");
                for (int i = 0; i < snapshot.size(); i++) w.line((i + 1) + " " + (verb.equals("LIST") ? snapshot.get(i).content().length : snapshot.get(i).id()));
                w.line(".");
            } else if (verb.equals("RETR")) {
                int n;
                try { n = Integer.parseInt(arg); } catch (NumberFormatException e) { n = 0; }
                if (n < 1 || n > snapshot.size()) { w.line("-ERR No such message"); continue; }
                sendPop3Message(w, snapshot.get(n - 1).content());
            } else if (verb.equals("NOOP")) w.line("+OK");
            else w.line("-ERR Command not supported");
        }
    }

    private void sendPop3Message(Wire connection, byte[] content) throws IOException {
        connection.line("+OK " + content.length + " octets");
        String[] lines = new String(content, StandardCharsets.ISO_8859_1).split("\r?\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            connection.line(line.startsWith(".") ? "." + line : line);
        }
        connection.line(".");
    }

    private static String decoded(String encoded) {
        try { return new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
    private static String mailbox(String input) {
        String value = input.trim();
        if (value.startsWith("<") && value.contains(">")) value = value.substring(1, value.indexOf('>'));
        return value.toLowerCase(Locale.ROOT);
    }

    @Override public void close() {
        try { smtp.close(); } catch (IOException ignored) {}
        try { pop3.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        log("Mail server stopped");
    }

    private interface SessionHandler { void handle(Socket socket) throws Exception; }
    private static final class Wire {
        private final BufferedReader in;
        private final BufferedWriter out;
        Wire(Socket socket) throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
        }
        String read() throws IOException { return in.readLine(); }
        void line(String text) throws IOException { out.write(text); out.write("\r\n"); out.flush(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("add-user")) {
            new MailDatabase(Path.of("data/mail.db")).addAccount(args[1], args[2]);
            System.out.println("Account created: " + args[1]); return;
        }
        LocalMailServer server = new LocalMailServer(Path.of("data/mail.db"), 2525, 2526);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
    }
}
