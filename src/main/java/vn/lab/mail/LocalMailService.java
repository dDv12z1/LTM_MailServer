package vn.lab.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Client cho server localhost: gửi qua SMTP, đọc qua POP3. */
final class LocalMailService implements AutoCloseable {
    private final String host;
    private final int smtpPort, pop3Port;
    private String address;
    private String password;

    LocalMailService(ServerEndpoint endpoint) {
        this(endpoint.ip(), endpoint.smtpPort(), endpoint.pop3Port());
    }
    LocalMailService(String host, int smtpPort, int pop3Port) {
        this.host = host; this.smtpPort = smtpPort; this.pop3Port = pop3Port;
    }

    public void connect(String address, String appPassword) throws Exception {
        this.address = LocalAddress.normalize(address);
        if (appPassword.isBlank()) throw new IllegalArgumentException("Nhập app password.");
        this.password = appPassword;
        // Lệnh USER/PASS trong PopConnection kiểm tra mật khẩu ngay khi đăng nhập.
        try (PopConnection ignored = pop()) { }
        catch (Exception e) { close(); throw e; }
    }

    /** Đăng ký qua cổng SMTP của server; app password chỉ gửi trong phiên kết nối này. */
    public void register(String address, String appPassword) throws Exception {
        String localAddress = LocalAddress.normalize(address);
        if (appPassword == null || appPassword.length() < 8) {
            throw new IllegalArgumentException("App password cần ít nhất 8 ký tự.");
        }
        try (Socket socket = new Socket(host, smtpPort)) {
            socket.setSoTimeout(10_000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.ISO_8859_1));
            String greeting = input.readLine();
            if (greeting == null || !greeting.startsWith("220")) {
                throw new IOException("Server SMTP không phản hồi.");
            }
            String encodedAddress = Base64.getEncoder().encodeToString(localAddress.getBytes(StandardCharsets.UTF_8));
            String encodedPassword = Base64.getEncoder().encodeToString(appPassword.getBytes(StandardCharsets.UTF_8));
            output.write("X-REGISTER " + encodedAddress + " " + encodedPassword + "\r\n");
            output.flush();
            String response = input.readLine();
            if (response == null || !response.startsWith("250")) {
                throw new IOException(response == null ? "Server không phản hồi."
                        : "Không thể tạo tài khoản. Email có thể đã tồn tại hoặc dữ liệu không hợp lệ.");
            }
        }
    }

    public List<MailRecord> inbox() throws Exception { return load("INBOX"); }
    public List<MailRecord> sent() throws Exception { return load("SENT"); }

    private List<MailRecord> load(String folder) throws Exception {
        List<MailRecord> result = new ArrayList<>();
        try (PopConnection pop = pop()) {
            pop.command("X-FOLDER " + folder);
            String stat = pop.command("STAT");
            int count = Integer.parseInt(stat.split(" ")[1]);
            for (int i = 1; i <= count; i++) {
                byte[] rawMail = pop.retrieve(i);
                result.add(MimeUtil.parse(rawMail, folder + "-" + i));
            }
        }
        return result;
    }

    public void send(String to, String subject, String body) throws Exception {
        String recipient = LocalAddress.normalize(to);
        Properties settings = new Properties();
        settings.put("mail.smtp.host", host);
        settings.put("mail.smtp.port", String.valueOf(smtpPort));
        settings.put("mail.smtp.auth", "true");
        settings.put("mail.smtp.connectiontimeout", "5000");
        settings.put("mail.smtp.timeout", "5000");
        Session session = Session.getInstance(settings);
        Message message = MimeUtil.create(address, recipient, subject, body);
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, smtpPort, address, password);
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    private PopConnection pop() throws Exception { return new PopConnection(host, pop3Port, address, password); }
    @Override public void close() { address = null; password = null; }

    private static final class PopConnection implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        PopConnection(String host, int port, String address, String password) throws Exception {
            socket = new Socket(host, port);
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
            try {
                expect(read());
                command("USER " + address);
                command("PASS " + password);
            } catch (Exception error) {
                socket.close();
                throw error;
            }
        }
        String read() throws IOException { return in.readLine(); }
        String command(String text) throws IOException {
            out.write(text); out.write("\r\n"); out.flush();
            String response = read(); expect(response); return response;
        }
        byte[] retrieve(int number) throws IOException {
            command("RETR " + number);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            String line;
            while ((line = read()) != null && !line.equals(".")) {
                if (line.startsWith("..")) line = line.substring(1);
                content.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            if (line == null) throw new EOFException("Server closed during RETR");
            return content.toByteArray();
        }
        private void expect(String response) throws IOException {
            if (response == null || !response.startsWith("+OK"))
                throw new IOException(response == null ? "Server không phản hồi" : response);
        }
        @Override public void close() throws IOException {
            try { out.write("QUIT\r\n"); out.flush(); } finally { socket.close(); }
        }
    }
}
