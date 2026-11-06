package vn.lab.mail;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;

/** Chỉ server dùng lớp này. Client lấy thư qua POP3, không đọc SQLite trực tiếp. */
final class MailDatabase {
    record StoredMail(long id, byte[] content) {}
    record MailOverview(long id, String owner, String folder, String subject, Instant time) {
        @Override public String toString() {
            return "#" + id + "  " + owner + " / " + folder + "\n" + subject + "  •  " + time;
        }
    }
    private final String jdbcUrl;

    MailDatabase(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        // accounts: tài khoản + mật khẩu đã hash; mail: mỗi bản ghi thuộc một hộp thư.
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS accounts " +
                    "(address TEXT PRIMARY KEY, salt BLOB NOT NULL, password_hash BLOB NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail " +
                    "(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, " +
                    "folder TEXT NOT NULL, content BLOB NOT NULL, created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mail_owner_folder ON mail(owner, folder, id)");
        }
        // Chỉ thêm tài khoản mẫu ở lần chạy đầu tiên.
        if (accountCount() == 0) {
            addAccount("alice@localhost", "alice-app-1234");
            addAccount("bob@localhost", "bob-app-1234");
            System.out.println("Demo accounts: alice@localhost / alice-app-1234; bob@localhost / bob-app-1234");
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private int accountCount() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return result.getInt(1);
        }
    }

    List<String> listAccounts() throws SQLException {
        List<String> accounts = new ArrayList<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT address FROM accounts ORDER BY address")) {
            while (rows.next()) accounts.add(rows.getString(1));
        }
        return accounts;
    }

    List<MailOverview> recentMail() throws Exception {
        List<MailOverview> messages = new ArrayList<>();
        String sql = "SELECT id,owner,folder,content,created_at FROM mail ORDER BY id DESC LIMIT 50";
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                MimeMessage mail = new MimeMessage(Session.getInstance(new Properties()),
                        new ByteArrayInputStream(rows.getBytes("content")));
                String subject = mail.getSubject();
                messages.add(new MailOverview(rows.getLong("id"), rows.getString("owner"),
                        rows.getString("folder"), subject == null ? "(Không có tiêu đề)" : subject,
                        Instant.ofEpochMilli(rows.getLong("created_at"))));
            }
        }
        return messages;
    }

    MailRecord readMail(long id) throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("SELECT content FROM mail WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Không tìm thấy thư #" + id);
                return MimeUtil.parse(row.getBytes(1), String.valueOf(id));
            }
        }
    }

    void addAccount(String address, String password) throws Exception {
        String normalized = LocalAddress.normalize(address);
        if (password == null || password.length() < 8) throw new IllegalArgumentException("App password must contain at least 8 characters");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(password, salt);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO accounts(address,salt,password_hash) VALUES(?,?,?)")) {
            statement.setString(1, normalized);
            statement.setBytes(2, salt);
            statement.setBytes(3, hash);
            statement.executeUpdate();
        }
    }

    boolean authenticate(String address, String password) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT salt,password_hash FROM accounts WHERE address=?")) {
            statement.setString(1, LocalAddress.normalize(address));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                byte[] savedHash = result.getBytes(2);
                byte[] enteredHash = hash(password, result.getBytes(1));
                return java.security.MessageDigest.isEqual(savedHash, enteredHash);
            }
        } catch (Exception e) { return false; }
    }

    boolean accountExists(String address) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE address=?")) {
            statement.setString(1, LocalAddress.normalize(address));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    void save(String sender, List<String> recipients, byte[] content) throws SQLException {
        // Một thư tạo bản sao ở INBOX của người nhận và SENT của người gửi.
        // Transaction đảm bảo lưu tất cả bản sao hoặc không lưu bản nào.
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO mail(owner,folder,content,created_at) VALUES(?,?,?,?)")) {
            connection.setAutoCommit(false);
            try {
                Set<String> unique = new LinkedHashSet<>(recipients);
                for (String recipient : unique) insert(statement, LocalAddress.normalize(recipient), "INBOX", content);
                insert(statement, LocalAddress.normalize(sender), "SENT", content);
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void insert(PreparedStatement s, String owner, String folder, byte[] content) throws SQLException {
        s.setString(1, owner); s.setString(2, folder); s.setBytes(3, content);
        s.setLong(4, System.currentTimeMillis()); s.addBatch();
    }

    List<StoredMail> list(String owner, String folder) throws SQLException {
        List<StoredMail> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id,content FROM mail WHERE owner=? AND folder=? ORDER BY id DESC LIMIT 200")) {
            statement.setString(1, LocalAddress.normalize(owner));
            statement.setString(2, folder);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new StoredMail(rows.getLong(1), rows.getBytes(2)));
            }
        }
        return result;
    }

    private static byte[] hash(String password, byte[] salt) throws Exception {
        // Không lưu app password dạng chữ thường. Dùng salt riêng cho từng tài khoản.
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
