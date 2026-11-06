package vn.lab.mail;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Optional;

/** Giao diện server dùng cùng sidebar và bố cục danh sách/chi tiết với MailApp. */
public final class ServerApp extends Application {
    private MailDatabase database;
    private LocalMailServer server;
    private BorderPane root;
    private TextField bindIp;
    private TextField smtpPort;
    private Button startButton, stopButton;
    private Button connectionNav, accountsNav, mailNav, activityNav;
    private Label status, sidebarStatus, connections;
    private ListView<String> accounts;
    private ListView<MailDatabase.MailOverview> messages;
    private TextArea events, mailBody;
    private Label accountDetail, mailSubject, mailDetails;
    private Node connectionPage, accountsPage, mailPage, activityPage;
    private Timeline counterTimer;

    @Override public void start(Stage stage) throws Exception {
        database = new MailDatabase(Path.of("data/mail.db"));
        stage.setTitle("MailBox • Server");
        stage.setMinWidth(850);
        stage.setMinHeight(580);

        buildConnectionPage();
        buildAccountsPage();
        buildMailPage();
        buildActivityPage();

        root = new BorderPane();
        root.setLeft(buildSidebar());
        root.getStyleClass().add("server-root");
        showPage(connectionPage, connectionNav);

        Scene scene = new Scene(root, 1050, 670);
        scene.getStylesheets().add(getClass().getResource("/mail.css").toExternalForm());
        stage.setScene(scene);
        refreshData();

        counterTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            int count = server == null ? 0 : server.activeConnections();
            connections.setText("Kết nối hiện tại: " + count);
        }));
        counterTimer.setCycleCount(Timeline.INDEFINITE);
        counterTimer.play();
        stage.show();
    }

    private VBox buildSidebar() {
        Label brand = new Label("MailBox");
        brand.getStyleClass().add("brand");
        Label role = new Label("SERVER MANAGER");
        role.getStyleClass().add("sidebar-user");
        sidebarStatus = new Label("● Chưa chạy");
        sidebarStatus.getStyleClass().add("sidebar-user");
        connections = new Label("Kết nối hiện tại: 0");
        connections.getStyleClass().add("sidebar-user");

        connectionNav = navButton("Kết nối", connectionPage);
        accountsNav = navButton("Tài khoản", accountsPage);
        mailNav = navButton("Thư đã lưu", mailPage);
        activityNav = navButton("Hoạt động", activityPage);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox sidebar = new VBox(14, brand, role, sidebarStatus, connectionNav,
                accountsNav, mailNav, activityNav, spacer, connections);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(215);
        sidebar.setPadding(new Insets(28, 16, 20, 16));
        return sidebar;
    }

    private Button navButton(String title, Node page) {
        Button button = new Button(title);
        button.getStyleClass().add("sidebar-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> showPage(page, button));
        return button;
    }

    private void showPage(Node page, Button selected) {
        root.setCenter(page);
        for (Button button : new Button[]{connectionNav, accountsNav, mailNav, activityNav}) {
            button.getStyleClass().remove("selected");
        }
        selected.getStyleClass().add("selected");
        if (page == accountsPage || page == mailPage) refreshData();
    }

    private void buildConnectionPage() {
        bindIp = new TextField("127.0.0.1");
        bindIp.setPromptText("Ví dụ: 192.168.1.10");
        bindIp.setMaxWidth(Double.MAX_VALUE);
        smtpPort = new TextField("2525");
        smtpPort.setPrefWidth(160);

        startButton = new Button("Bắt đầu server");
        startButton.getStyleClass().addAll("primary", "server-action");
        startButton.setOnAction(event -> startServer());
        stopButton = new Button("Dừng server");
        stopButton.getStyleClass().addAll("stop-button", "server-action");
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stopServer());
        status = new Label("Chưa chạy");
        status.getStyleClass().add("server-status");

        VBox ipBox = labeled("IP lắng nghe (nhập trực tiếp)", bindIp);
        VBox portBox = labeled("SMTP port", smtpPort);
        HBox fields = new HBox(14, ipBox, portBox);
        HBox.setHgrow(ipBox, Priority.ALWAYS);
        HBox buttons = new HBox(12, startButton, stopButton);
        Label hint = new Label("POP3 tự dùng port + 1. Client nhập IP:SMTP port.");
        hint.getStyleClass().add("muted");
        VBox settings = new VBox(16, fields, hint, buttons, status);
        settings.getStyleClass().add("server-card");

        Label guide = new Label("Cùng một máy: chọn 127.0.0.1, client nhập 127.0.0.1:2525.\n"
                + "Qua LAN: nhập IP mạng của máy server; client nhập IP đó cùng SMTP port.");
        guide.setWrapText(true);
        VBox guideCard = new VBox(10, sectionTitle("Địa chỉ cho client"), guide);
        guideCard.getStyleClass().add("server-card");

        VBox page = new VBox(18, pageTitle("Kết nối server"), settings, guideCard);
        page.getStyleClass().add("server-page");
        connectionPage = page;
    }

    private void buildAccountsPage() {
        accounts = new ListView<>();
        accounts.getStyleClass().add("mail-list");
        VBox.setVgrow(accounts, Priority.ALWAYS);
        Button add = new Button("+ Thêm tài khoản");
        add.getStyleClass().add("primary");
        add.setOnAction(event -> showAddAccount());
        VBox listPane = new VBox(12, sectionTitle("Tài khoản"), accounts, add);
        listPane.getStyleClass().add("list-pane");
        listPane.setPadding(new Insets(22));
        listPane.setPrefWidth(330);

        accountDetail = new Label("Chọn một tài khoản để xem.");
        accountDetail.getStyleClass().add("detail-title");
        accountDetail.setWrapText(true);
        Label note = new Label("Địa chỉ thư nội bộ có dạng tên@localhost.\n"
                + "App password được lưu dưới dạng hash trong SQLite.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        accounts.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            accountDetail.setText(selected == null ? "Chọn một tài khoản để xem." : selected);
        });
        VBox detail = new VBox(16, accountDetail, new Separator(), note);
        detail.getStyleClass().add("detail-pane");
        detail.setPadding(new Insets(26));
        HBox.setHgrow(detail, Priority.ALWAYS);
        accountsPage = new HBox(listPane, detail);
    }

    private void buildMailPage() {
        messages = new ListView<>();
        messages.getStyleClass().add("mail-list");
        VBox.setVgrow(messages, Priority.ALWAYS);
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(event -> refreshData());
        VBox listPane = new VBox(12, sectionTitle("Thư đã lưu"), messages, refresh);
        listPane.getStyleClass().add("list-pane");
        listPane.setPadding(new Insets(22));
        listPane.setPrefWidth(350);

        mailSubject = new Label("Chọn một thư để xem");
        mailSubject.getStyleClass().add("detail-title");
        mailSubject.setWrapText(true);
        mailDetails = new Label("");
        mailDetails.getStyleClass().add("muted");
        mailDetails.setWrapText(true);
        mailBody = new TextArea();
        mailBody.setEditable(false);
        mailBody.setWrapText(true);
        mailBody.getStyleClass().add("detail-body");
        VBox.setVgrow(mailBody, Priority.ALWAYS);
        VBox detail = new VBox(12, mailSubject, mailDetails, new Separator(), mailBody);
        detail.getStyleClass().add("detail-pane");
        detail.setPadding(new Insets(26));
        HBox.setHgrow(detail, Priority.ALWAYS);
        messages.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> showMail(selected));
        mailPage = new HBox(listPane, detail);
    }

    private void buildActivityPage() {
        events = new TextArea();
        events.setEditable(false);
        events.setWrapText(true);
        VBox.setVgrow(events, Priority.ALWAYS);
        VBox page = new VBox(14, pageTitle("Hoạt động"),
                new Label("Các kết nối và thư mới sẽ xuất hiện tại đây."), events);
        page.getStyleClass().add("server-page");
        activityPage = page;
    }

    private VBox labeled(String title, Control control) {
        return new VBox(6, new Label(title), control);
    }

    private Label pageTitle(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("detail-title");
        return label;
    }

    private Label sectionTitle(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        return label;
    }

    private void startServer() {
        try {
            ServerEndpoint endpoint = ServerEndpoint.parse(bindIp.getText() + ":" + smtpPort.getText());
            server = new LocalMailServer(Path.of("data/mail.db"), endpoint.ip(),
                    endpoint.smtpPort(), endpoint.pop3Port());
            server.setEventListener(message -> Platform.runLater(() -> {
                events.appendText(message + "\n");
                if (message.startsWith("Stored mail:") || message.startsWith("Account created:")) refreshData();
            }));
            server.start();
            startButton.setDisable(true);
            stopButton.setDisable(false);
            bindIp.setDisable(true);
            smtpPort.setDisable(true);
            status.setText("Đang chạy: " + endpoint + " · POP3 " + endpoint.pop3Port());
            sidebarStatus.setText("● Đang chạy");
        } catch (Exception error) {
            server = null;
            showError("Không thể khởi động server", error);
        }
    }

    private void stopServer() {
        if (server != null) server.close();
        server = null;
        startButton.setDisable(false);
        stopButton.setDisable(true);
        bindIp.setDisable(false);
        smtpPort.setDisable(false);
        status.setText("Đã dừng");
        sidebarStatus.setText("● Đã dừng");
    }

    private void refreshData() {
        if (accounts == null || messages == null) return;
        try {
            accounts.getItems().setAll(database.listAccounts());
            messages.getItems().setAll(database.recentMail());
        } catch (Exception error) {
            showError("Không thể đọc database", error);
        }
    }

    private void showMail(MailDatabase.MailOverview selected) {
        if (selected == null) {
            mailSubject.setText("Chọn một thư để xem");
            mailDetails.setText("");
            mailBody.clear();
            return;
        }
        try {
            MailRecord mail = database.readMail(selected.id());
            mailSubject.setText(mail.subject());
            mailDetails.setText("Hộp thư: " + selected.owner() + " / " + selected.folder()
                    + "\nTừ: " + mail.from() + "\nĐến: " + mail.to() + "\n" + selected.time());
            mailBody.setText(mail.body());
        } catch (Exception error) {
            showError("Không thể đọc thư", error);
        }
    }

    private void showAddAccount() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Thêm tài khoản");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField address = new TextField();
        address.setPromptText("charlie@localhost");
        PasswordField password = new PasswordField();
        password.setPromptText("Ít nhất 8 ký tự");
        VBox form = new VBox(10, new Label("Email"), address, new Label("App password"), password);
        form.setPadding(new Insets(18));
        form.setPrefWidth(340);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/mail.css").toExternalForm());
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                database.addAccount(address.getText(), password.getText());
                events.appendText("Đã thêm tài khoản: " + address.getText().trim() + "\n");
                refreshData();
            } catch (Exception error) {
                showError("Không thể thêm tài khoản", error);
            }
        }
    }

    private void showError(String title, Exception error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(error.getMessage());
        alert.showAndWait();
    }

    @Override public void stop() {
        if (counterTimer != null) counterTimer.stop();
        if (server != null) server.close();
    }

    public static void main(String[] args) { launch(args); }
}
