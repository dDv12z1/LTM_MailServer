package vn.lab.mail;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Giao diện: đăng nhập -> hộp thư -> soạn/gửi/nhận thư. */
public final class MailApp extends Application {
    // Gọi mạng ở luồng riêng để cửa sổ JavaFX không bị đứng khi server phản hồi chậm.
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-worker"); t.setDaemon(true); return t;
    });
    private Stage stage;
    private LocalMailService service;
    private String account;
    private String folder = "INBOX";
    private Label status, listTitle;
    private ListView<MailRecord> mailList;
    private Label detailSubject, detailMeta;
    private TextArea detailBody;
    private Button inboxButton, sentButton;

    @Override public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("MailBox • JavaFX Mail Client");
        stage.setMinWidth(850); stage.setMinHeight(560);
        showLogin(); stage.show();
    }

    private void showLogin() {
        Label mark = new Label("M"); mark.getStyleClass().add("login-mark");
        Label title = new Label("MailBox"); title.getStyleClass().add("login-title");
        Label sub = new Label("Kết nối mail server qua địa chỉ IP và port"); sub.getStyleClass().add("muted");
        TextField serverAddress = new TextField("127.0.0.1:2525");
        serverAddress.setPromptText("127.0.0.1:2525");
        TextField email = new TextField(); email.setPromptText("alice@localhost");
        PasswordField password = new PasswordField(); password.setPromptText("App password");
        Label hint = new Label("Dùng tài khoản mẫu hoặc tạo tài khoản cá nhân trên server.");
        hint.getStyleClass().add("hint"); hint.setWrapText(true);
        Button login = new Button("Đăng nhập"); login.getStyleClass().add("primary"); login.setMaxWidth(Double.MAX_VALUE);
        Button register = new Button("Tạo tài khoản");
        register.getStyleClass().add("secondary-button");
        register.setMaxWidth(Double.MAX_VALUE);
        Label feedback = new Label(); feedback.getStyleClass().add("error"); feedback.setWrapText(true);
        login.setOnAction(event -> login(serverAddress, email, password, login, feedback));
        register.setOnAction(event -> showRegistration(serverAddress, email, feedback));
        VBox card = new VBox(12, mark, title, sub, new Label("Server (IP:SMTP port)"), serverAddress,
                new Label("Email"), email, new Label("App password"), password, hint, login, register, feedback);
        card.getStyleClass().add("login-card"); card.setMaxWidth(420); card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane root = new StackPane(card); root.getStyleClass().add("login-bg");
        root.setPadding(new Insets(28)); setScene(root, 940, 620);
        Platform.runLater(email::requestFocus);
    }

    private void login(TextField serverAddress, TextField email, PasswordField password,
                       Button loginButton, Label feedback) {
        String address = email.getText().trim();
        String appPassword = password.getText();
        password.clear();
        ServerEndpoint endpoint;
        try { endpoint = ServerEndpoint.parse(serverAddress.getText()); }
        catch (IllegalArgumentException error) { feedback.setText(error.getMessage()); return; }

        if (address.isBlank() || appPassword.isBlank()) {
            feedback.setText("Vui lòng nhập email và app password.");
            return;
        }

        loginButton.setDisable(true);
        feedback.setText("Đang kết nối...");
        LocalMailService newService = new LocalMailService(endpoint);

        runInBackground(() -> {
            newService.connect(address, appPassword);
            return newService;
        }, connectedService -> {
            openMailbox(connectedService, address);
        }, error -> {
            loginButton.setDisable(false);
            feedback.setText(errorMessage(error));
        });
    }

    private void showRegistration(TextField serverAddress, TextField loginEmail, Label loginFeedback) {
        ServerEndpoint endpoint;
        try { endpoint = ServerEndpoint.parse(serverAddress.getText()); }
        catch (IllegalArgumentException error) { loginFeedback.setText(error.getMessage()); return; }

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Tạo tài khoản cá nhân");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField address = new TextField();
        address.setPromptText("tenban@localhost");
        PasswordField password = new PasswordField();
        password.setPromptText("Ít nhất 8 ký tự");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Nhập lại app password");
        Label feedback = new Label();
        feedback.getStyleClass().add("error");
        feedback.setWrapText(true);
        Button create = new Button("Tạo và đăng nhập");
        create.getStyleClass().add("primary");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(event -> {
            String newAddress = address.getText().trim();
            String newPassword = password.getText();
            String repeatedPassword = confirm.getText();
            if (!newPassword.equals(repeatedPassword)) {
                feedback.setText("Hai lần nhập app password không khớp.");
                return;
            }
            create.setDisable(true);
            feedback.setText("Đang tạo tài khoản...");
            LocalMailService newService = new LocalMailService(endpoint);
            runInBackground(() -> {
                newService.register(newAddress, newPassword);
                newService.connect(newAddress, newPassword);
                return newService;
            }, connectedService -> {
                dialog.close();
                loginEmail.setText(newAddress);
                openMailbox(connectedService, newAddress);
            }, error -> {
                create.setDisable(false);
                feedback.setText(errorMessage(error));
            });
        });

        VBox form = new VBox(10, new Label("Server: " + endpoint), new Label("Email"), address,
                new Label("App password"), password, new Label("Nhập lại app password"), confirm,
                feedback, create);
        form.setPadding(new Insets(18));
        form.setPrefWidth(390);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/mail.css").toExternalForm());
        dialog.show();
    }

    private void openMailbox(LocalMailService connectedService, String address) {
        service = connectedService;
        account = address;
        showMailbox();
    }

    private void showMailbox() {
        Label logo = new Label("MailBox"); logo.getStyleClass().add("brand");
        Label user = new Label(account); user.getStyleClass().add("sidebar-user"); user.setWrapText(true);
        inboxButton = sidebarButton("Hộp thư đến", () -> switchFolder("INBOX"));
        sentButton = sidebarButton("Đã gửi", () -> switchFolder("SENT"));
        Button compose = new Button("＋  Soạn thư"); compose.getStyleClass().add("compose-button");
        compose.setMaxWidth(Double.MAX_VALUE); compose.setOnAction(e -> showCompose());
        Button refresh = sidebarButton("↻  Làm mới", this::refresh);
        Button logout = sidebarButton("Đăng xuất", () -> { service.close(); service = null; showLogin(); });
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox sidebar = new VBox(14, logo, user, compose, inboxButton, sentButton, refresh, spacer, logout);
        sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(215); sidebar.setPadding(new Insets(28, 16, 20, 16));

        listTitle = new Label("Hộp thư đến"); listTitle.getStyleClass().add("section-title");
        status = new Label("Đang tải..."); status.getStyleClass().add("muted");
        VBox listHeader = new VBox(3, listTitle, status); listHeader.setPadding(new Insets(23, 20, 18, 20));
        mailList = new ListView<>(); mailList.getStyleClass().add("mail-list");
        mailList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(MailRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText((item.subject() == null || item.subject().isBlank() ? "(Không có tiêu đề)" : item.subject())
                        + "\n" + item.from());
            }
        });
        VBox listPane = new VBox(listHeader, mailList); listPane.getStyleClass().add("list-pane");
        listPane.setPrefWidth(310); VBox.setVgrow(mailList, Priority.ALWAYS);

        detailSubject = new Label("Chọn một thư để xem"); detailSubject.getStyleClass().add("detail-title");
        detailSubject.setWrapText(true);
        detailMeta = new Label("Nội dung thư sẽ hiển thị tại đây."); detailMeta.getStyleClass().add("muted");
        detailMeta.setWrapText(true);
        detailBody = new TextArea(); detailBody.setEditable(false); detailBody.setWrapText(true);
        detailBody.getStyleClass().add("detail-body"); VBox.setVgrow(detailBody, Priority.ALWAYS);
        VBox detail = new VBox(12, detailSubject, detailMeta, new Separator(), detailBody);
        detail.getStyleClass().add("detail-pane"); detail.setPadding(new Insets(26));
        mailList.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item == null) { detailSubject.setText("Chọn một thư để xem"); detailMeta.setText(""); detailBody.clear(); }
            else {
                detailSubject.setText(item.subject() == null || item.subject().isBlank() ? "(Không có tiêu đề)" : item.subject());
                detailMeta.setText("Từ: " + item.from() + "\nĐến: " + item.to() + "\n" + item.date());
                detailBody.setText(item.body());
            }
        });
        HBox content = new HBox(listPane, detail); HBox.setHgrow(detail, Priority.ALWAYS);
        BorderPane root = new BorderPane(); root.setLeft(sidebar); root.setCenter(content);
        setScene(root, 1050, 670); switchFolder("INBOX");
    }

    private Button sidebarButton(String text, Runnable action) {
        Button button = new Button(text); button.getStyleClass().add("sidebar-button");
        button.setMaxWidth(Double.MAX_VALUE); button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(e -> action.run()); return button;
    }

    private void switchFolder(String selected) {
        folder = selected;
        inboxButton.getStyleClass().remove("selected"); sentButton.getStyleClass().remove("selected");
        (selected.equals("INBOX") ? inboxButton : sentButton).getStyleClass().add("selected");
        listTitle.setText(selected.equals("INBOX") ? "Hộp thư đến" : "Đã gửi");
        refresh();
    }

    private void refresh() {
        if (service == null || mailList == null) return;
        String requested = folder;
        LocalMailService currentService = service;
        status.setText("Đang tải...");
        runInBackground(() -> requested.equals("INBOX") ? currentService.inbox() : currentService.sent(), items -> {
            if (service != currentService || !folder.equals(requested)) return;
            mailList.getItems().setAll(items); status.setText(items.size() + " thư");
        }, error -> {
            if (service == currentService) status.setText("Lỗi: " + errorMessage(error));
        });
    }

    private void showCompose() {
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Soạn thư mới"); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextField to = new TextField(); to.setPromptText("bob@localhost");
        TextField subject = new TextField(); subject.setPromptText("Tiêu đề");
        TextArea body = new TextArea(); body.setPromptText("Nội dung thư..."); body.setPrefRowCount(10); body.setWrapText(true);
        Label feedback = new Label(); feedback.getStyleClass().add("error");
        Button send = new Button("Gửi thư"); send.getStyleClass().add("primary");
        send.setOnAction(e -> sendMail(dialog, to, subject, body, send, feedback));
        VBox form = new VBox(9, new Label("Đến"), to, new Label("Tiêu đề"), subject,
                new Label("Nội dung"), body, feedback, send);
        form.setPadding(new Insets(18)); form.setPrefWidth(520);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/mail.css").toExternalForm());
        dialog.show();
    }

    private void sendMail(Dialog<Void> dialog, TextField to, TextField subject,
                          TextArea body, Button sendButton, Label feedback) {
        String recipient = to.getText().trim();
        String title = subject.getText().trim();
        String content = body.getText();
        if (recipient.isBlank() || title.isBlank() || content.isBlank()) {
            feedback.setText("Nhập người nhận, tiêu đề và nội dung.");
            return;
        }

        sendButton.setDisable(true);
        feedback.setText("Đang gửi...");
        LocalMailService currentService = service;
        runInBackground(() -> {
            currentService.send(recipient, title, content);
            return null;
        }, ignored -> {
            dialog.close();
            switchFolder("SENT");
        }, error -> {
            sendButton.setDisable(false);
            feedback.setText(errorMessage(error));
        });
    }

    // work chạy ở luồng nền; ok/fail chạy lại trên luồng JavaFX để sửa giao diện.
    private <T> void runInBackground(Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) {
        background.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> ok.accept(result));
            } catch (Exception error) {
                Platform.runLater(() -> fail.accept(error));
            }
        });
    }

    private static String errorMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    private void setScene(javafx.scene.Parent root, int width, int height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("/mail.css").toExternalForm());
        stage.setScene(scene);
    }

    @Override public void stop() {
        if (service != null) service.close();
        background.shutdownNow();
    }
    public static void main(String[] args) { launch(args); }
}
