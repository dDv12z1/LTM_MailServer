# MailBox — JavaFX Mail Client & Server

Bài lab dùng Java 21, JavaFX, SMTP, POP3 và SQLite. Client nhập `IP:SMTP port` để kết nối server; POP3 tự dùng cổng kế tiếp. Ví dụ `127.0.0.1:2525` sẽ gửi thư qua cổng `2525` và đọc thư qua `2526`.

## Chạy bằng file .bat

1. Bấm đúp `run-server.bat` để mở giao diện quản lý server.
2. Trong cửa sổ server, **tự nhập IP** và SMTP port, rồi bấm **Bắt đầu server**.
3. Bấm đúp `run-client.bat` để mở client. Có thể chạy file này nhiều lần cho nhiều người.
4. Trong client, nhập `IP:SMTP port`, email và app password. Người dùng mới bấm **Tạo tài khoản** để đăng ký trên server và tự đăng nhập.

Server dùng sidebar xanh đậm như client, với các trang **Kết nối**, **Tài khoản**, **Thư đã lưu** và **Hoạt động**. Chọn một thư trong trang **Thư đã lưu** để xem nội dung.

Có thể chạy từ Command Prompt bằng `run-server.bat` và `run-client.bat`. Máy cần JDK 21 và Maven; các file `.bat` tự tìm bản đi kèm IntelliJ IDEA 2024.3.7 nếu chúng chưa có trong PATH/JAVA_HOME. Lần chạy đầu Maven cần Internet để tải thư viện.

## Nhập IP

- **Cùng một máy:** server nhập `127.0.0.1`, client nhập `127.0.0.1:2525`.
- **Hai máy trong cùng mạng LAN:** server nhập IP LAN của máy server, ví dụ `192.168.1.10`; client ở máy kia nhập `192.168.1.10:2525`.
- Server có thể nhập `0.0.0.0` để lắng nghe mọi card mạng. Client phải nhập **IP thực của máy server**, không nhập `0.0.0.0`.

Nếu Windows Firewall hỏi quyền khi dùng LAN, cần cho phép hai cổng SMTP và POP3 trong mạng đang dùng. Server lab hiện truyền app password qua SMTP/POP3 chưa mã hóa, nên chỉ dùng trên máy cá nhân hoặc mạng lab tin cậy; không mở các cổng này ra Internet.

## Tài khoản và dữ liệu

Database `data/mail.db` tự tạo hai tài khoản mẫu ở lần chạy đầu:

| Email | App password |
| --- | --- |
| `alice@localhost` | `alice-app-1234` |
| `bob@localhost` | `bob-app-1234` |

Mở hai client, đăng nhập Alice và Bob, gửi thư từ Alice đến `bob@localhost`, rồi bấm **Làm mới** ở Bob. Server hiển thị thư đã lưu và số kết nối đang hoạt động. Mỗi client cũng có thể bấm **Tạo tài khoản**, tự đặt email `@localhost` và app password ít nhất 8 ký tự. Tài khoản mới xuất hiện trong trang **Tài khoản** của server.

App password bắt buộc khi client đăng nhập. Client không ghi mật khẩu ra file; server lưu hash PBKDF2 cùng salt. Thư đến và thư đã gửi được lưu trên SQLite của server, nên vẫn còn sau khi khởi động lại.

## Code

- `MailApp.java`: giao diện client.
- `ServerApp.java`: giao diện quản lý server.
- `LocalMailService.java`: client SMTP/POP3.
- `LocalMailServer.java`: xử lý các lệnh SMTP/POP3.
- `MailDatabase.java`: tài khoản và thư trong SQLite.
- `ServerEndpoint.java`: kiểm tra địa chỉ `IP:port` và tính POP3 port.

Xem [hướng dẫn đọc code](docs/LEARNING_GUIDE.md) để theo dõi luồng gửi/nhận. Kiểm tra nhanh: chạy `ProtocolSmokeTest.main` trong IntelliJ; test dùng cổng localhost ngẫu nhiên.
