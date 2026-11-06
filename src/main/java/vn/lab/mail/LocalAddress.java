package vn.lab.mail;

import java.util.Locale;

/** Kiểm tra địa chỉ thư nội bộ. Không truy cập database. */
final class LocalAddress {
    private LocalAddress() { }

    static String normalize(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9._%+\\-]+@localhost")) {
            throw new IllegalArgumentException("Chỉ hỗ trợ địa chỉ @localhost");
        }
        return value;
    }
}
