package vn.lab.mail;

/** Một địa chỉ SMTP; POP3 luôn dùng cổng kế tiếp. Ví dụ 127.0.0.1:2525 -> POP3 2526. */
final class ServerEndpoint {
    private final String ip;
    private final int smtpPort;

    private ServerEndpoint(String ip, int smtpPort) {
        this.ip = ip;
        this.smtpPort = smtpPort;
    }

    static ServerEndpoint parse(String text) {
        String[] parts = text.trim().split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Nhập địa chỉ theo dạng IP:port, ví dụ 127.0.0.1:2525");
        }

        String ip = parts[0].equalsIgnoreCase("localhost") ? "127.0.0.1" : parts[0];
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("IP phải là địa chỉ IPv4");
        for (String octet : octets) {
            try {
                int number = Integer.parseInt(octet);
                if (number < 0 || number > 255) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("IP không hợp lệ: " + ip);
            }
        }

        int port;
        try { port = Integer.parseInt(parts[1]); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Port phải là số"); }
        if (port < 1 || port > 65534) {
            throw new IllegalArgumentException("SMTP port phải từ 1 đến 65534 (POP3 dùng port + 1)");
        }
        return new ServerEndpoint(ip, port);
    }

    String ip() { return ip; }
    int smtpPort() { return smtpPort; }
    int pop3Port() { return smtpPort + 1; }
    @Override public String toString() { return ip + ":" + smtpPort; }
}
