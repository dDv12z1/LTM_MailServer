# Hướng dẫn đọc code MailBox

Đọc theo thứ tự sau: `MailApp` → `ServerEndpoint` → `LocalMailService` → `LocalMailServer` → `MailDatabase`. `ServerApp` là giao diện quản lý server.

## Kết nối

1. Trong `ServerApp`, người quản lý nhập IP và SMTP port rồi bấm **Bắt đầu server**.
2. `LocalMailServer` mở hai cổng: SMTP là port đã nhập, POP3 là port + 1.
3. Trong `MailApp`, client nhập `IP:SMTP port`, email và app password.
4. `ServerEndpoint.parse()` kiểm tra địa chỉ. `LocalMailService.connect()` gửi lệnh POP3 `USER`/`PASS` để xác thực.

Ví dụ: server lắng nghe `192.168.1.10:2525`, client gửi thư đến cổng `2525` và đọc thư ở `2526`.

## Đăng ký tài khoản từ client

1. Người dùng nhập IP:port trên màn hình client rồi bấm **Tạo tài khoản**.
2. `MailApp.showRegistration()` kiểm tra hai lần nhập app password.
3. `LocalMailService.register()` gửi lệnh `X-REGISTER` tới SMTP server.
4. `LocalMailServer.registerAccount()` gọi `MailDatabase.addAccount()`; server chỉ lưu hash của mật khẩu.
5. Client đăng nhập bằng tài khoản vừa tạo. Trang **Tài khoản** của server cập nhật danh sách.

## Gửi thư

1. `MailApp.sendMail()` lấy email người nhận, tiêu đề và nội dung từ giao diện.
2. `LocalMailService.send()` tạo MIME và gửi các lệnh SMTP đến server.
3. `LocalMailServer.smtpSession()` xử lý `AUTH`, `MAIL FROM`, `RCPT TO`, `DATA`.
4. `receiveMailData()` kiểm tra người gửi rồi gọi `MailDatabase.save()`.
5. Database ghi một bản vào `INBOX` của người nhận và một bản vào `SENT` của người gửi.

## Nhận thư

1. `MailApp.refresh()` gọi `LocalMailService.inbox()` hoặc `sent()`.
2. Client gửi POP3 `USER`, `PASS`, `STAT`, `RETR`.
3. Server lấy dữ liệu từ SQLite và trả nội dung thư.
4. `MimeUtil.parse()` tách nội dung để giao diện hiển thị.

`X-FOLDER SENT` là lệnh mở rộng riêng của server lab để đọc thư đã gửi.

## Vì sao có luồng nền?

Gửi và nhận qua mạng có thể chờ vài giây. `MailApp.runInBackground()` thực hiện việc mạng ở luồng riêng. `Platform.runLater()` chuyển kết quả về luồng JavaFX để cập nhật giao diện.

## Bước thực hành

1. Chạy server bằng `run-server.bat` và hai client bằng `run-client.bat`.
2. Đặt breakpoint tại `MailApp.sendMail()`, `LocalMailService.send()` và `LocalMailServer.receiveMailData()`.
3. Gửi thư Alice → Bob, quan sát thứ tự các breakpoint và bảng **Thư đã lưu** trên server.
4. Đặt breakpoint tại `LocalMailService.load()` và `MailDatabase.list()`, rồi bấm **Làm mới** ở Bob.
