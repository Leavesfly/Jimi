package io.leavesfly.jimi.work.ui;

import io.leavesfly.jimi.work.model.ApprovalInfo;
import io.leavesfly.jimi.work.model.SessionMetadata;
import io.leavesfly.jimi.work.model.WorkSession;
import io.leavesfly.jimi.work.service.WorkService;
import io.leavesfly.jimi.work.ui.component.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 主窗口 - OpenWork 风格多面板布局
 * 集成侧边栏、对话、时间线、Skills 管理和审批
 */
public class MainWindow {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final Stage stage;
    private final WorkService service;

    // === UI 组件 ===
    /** 侧边栏 */
    private VBox sidebar;
    private ListView<WorkSession> sessionList;
    private Label workspaceLabel;
    private ComboBox<String> agentSelector;

    /** 主内容区 */
    private StackPane contentPane;
    private ChatPane chatPane;
    private TimelinePane timelinePane;
    private SkillManagerPane skillManagerPane;

    /** 状态栏 */
    private Label statusLabel;

    /** 当前状态 */
    private Path currentWorkspace;
    private WorkSession currentSession;

    public MainWindow(Stage stage, WorkService service) {
        this.stage = stage;
        this.service = service;
        initUI();
    }

    private void initUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("main-view");

        // === 顶部工具栏 ===
        HBox toolbar = createToolbar();
        root.setTop(toolbar);

        // === 左侧侧边栏 ===
        sidebar = createSidebar();
        root.setLeft(sidebar);

        // === 主内容区 ===
        chatPane = new ChatPane(service);
        timelinePane = new TimelinePane();
        skillManagerPane = new SkillManagerPane();

        // 审批回调
        chatPane.setApprovalCallback(info -> Platform.runLater(() -> {
            ApprovalDialog.show(info).ifPresent(response ->
                    service.handleApproval(info.getToolCallId(), response));
        }));

        // Todo 更新回调
        chatPane.setTodoUpdateCallback(todoList ->
                Platform.runLater(() -> timelinePane.updateTodoList(todoList)));

        contentPane = new StackPane();
        contentPane.getChildren().add(createWelcomePane());
        root.setCenter(contentPane);

        // === 底部状态栏 ===
        HBox statusBar = createStatusBar();
        root.setBottom(statusBar);

        // 创建场景
        Scene scene = new Scene(root, 1200, 800);

        // 加载样式
        try {
            String css = Objects.requireNonNull(
                    getClass().getResource("/css/jwork.css")).toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            log.debug("CSS 未找到，使用默认样式");
        }

        stage.setTitle("JWork - Jimi AI Assistant (" +
                service.getLlm().getProvider() + "/" + service.getLlm().getModel() + ")");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
    }

    // ==================== 工具栏 ====================

    private HBox createToolbar() {
        // 左侧: 工作区选择
        Button selectDirBtn = new Button("📂 选择工作区");
        selectDirBtn.setOnAction(e -> selectWorkspace());

        workspaceLabel = new Label("未选择工作区");
        workspaceLabel.setStyle("-fx-text-fill: #888;");

        HBox left = new HBox(10, selectDirBtn, workspaceLabel);
        left.setAlignment(Pos.CENTER_LEFT);

        // 右侧: Agent 选择 + 新建会话
        agentSelector = new ComboBox<>();
        agentSelector.getItems().addAll(service.getAvailableAgents());
        agentSelector.setValue("default");
        agentSelector.setPromptText("选择 Agent");

        Button newSessionBtn = new Button("+ 新建会话");
        newSessionBtn.setStyle("-fx-background-color: #5c6bc0; -fx-text-fill: white;");
        newSessionBtn.setOnAction(e -> createNewSession());

        HBox right = new HBox(10, new Label("Agent:"), agentSelector, newSessionBtn);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox toolbar = new HBox();
        toolbar.setPadding(new Insets(10, 15, 10, 15));
        toolbar.getStyleClass().add("toolbar");
        HBox.setHgrow(left, Priority.ALWAYS);
        toolbar.getChildren().addAll(left, right);

        return toolbar;
    }

    // ==================== 侧边栏 ====================

    private VBox createSidebar() {
        VBox sb = new VBox(10);
        sb.setPrefWidth(220);
        sb.setPadding(new Insets(10));
        sb.getStyleClass().add("sidebar");

        // 会话标题
        Label sessionsTitle = new Label("会话");
        sessionsTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Button historyBtn = new Button("📜 历史");
        historyBtn.setMaxWidth(Double.MAX_VALUE);
        historyBtn.setOnAction(e -> showHistorySessions());

        HBox sessionsHeader = new HBox(10, sessionsTitle, new Region(), historyBtn);
        sessionsHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sessionsHeader.getChildren().get(1), Priority.ALWAYS);

        // 会话列表
        sessionList = new ListView<>();
        sessionList.setCellFactory(lv -> new SessionListCell());
        sessionList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) selectSession(newVal);
                });
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        // 导航按钮
        Separator sep = new Separator();

        Button chatNavBtn = createNavButton("💬 对话", () -> showPane(chatPane));
        Button timelineNavBtn = createNavButton("📋 执行计划", () -> showPane(timelinePane));
        Button skillsNavBtn = createNavButton("🧩 Skills", () -> showPane(skillManagerPane));

        VBox navButtons = new VBox(4, chatNavBtn, timelineNavBtn, skillsNavBtn);

        sb.getChildren().addAll(sessionsHeader, sessionList, sep, navButtons);
        return sb;
    }

    private Button createNavButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.getStyleClass().add("nav-button");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    // ==================== 状态栏 ====================

    private HBox createStatusBar() {
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        HBox bar = new HBox(10, statusLabel);
        bar.setPadding(new Insets(5, 15, 5, 15));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ==================== 欢迎页 ====================

    private VBox createWelcomePane() {
        Label logo = new Label("J");
        logo.setFont(Font.font(60));
        logo.setStyle("-fx-font-weight: bold; -fx-text-fill: #5c6bc0;");

        Label title = new Label("JWork");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");

        Label badge = new Label("AI-POWERED");
        badge.setStyle("-fx-background-color: #5c6bc0; -fx-text-fill: white; " +
                "-fx-padding: 2 8; -fx-background-radius: 10; -fx-font-size: 10px;");

        HBox titleRow = new HBox(15, title, badge);
        titleRow.setAlignment(Pos.CENTER);

        Label subtitle = new Label("Java 程序员的专属 AI 协作台");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        Label instruction = new Label("选择工作区，创建会话，开启智能开发之旅");
        instruction.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");

        VBox welcome = new VBox(20, logo, titleRow, subtitle, new Separator(), instruction);
        welcome.setAlignment(Pos.CENTER);
        welcome.setMaxWidth(500);
        welcome.getStyleClass().add("welcome-pane");
        return welcome;
    }

    // ==================== 操作方法 ====================

    private void selectWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作区");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File selected = chooser.showDialog(stage);
        if (selected != null) {
            currentWorkspace = selected.toPath();
            workspaceLabel.setText(selected.getName());
            setStatus("工作区: " + selected.getAbsolutePath());
            log.info("工作区已选择: {}", currentWorkspace);
        }
    }

    private void createNewSession() {
        if (currentWorkspace == null) {
            showAlert("请先选择工作区");
            return;
        }

        String agentName = agentSelector.getValue();
        WorkSession session = service.createSession(currentWorkspace, agentName);
        sessionList.getItems().add(session);
        sessionList.getSelectionModel().select(session);
        setStatus("会话已创建: " + session.getDisplayName());
    }

    private void selectSession(WorkSession session) {
        currentSession = session;
        chatPane.setSession(session);
        timelinePane.clear();
        showPane(chatPane);
        setStatus("当前会话: " + session.getDisplayName());
    }

    private void showPane(Region pane) {
        contentPane.getChildren().clear();
        contentPane.getChildren().add(pane);
    }

    private void showHistorySessions() {
        List<SessionMetadata> historyList = service.loadSessionMetadataList();
        if (historyList.isEmpty()) {
            showAlert("暂无历史会话");
            return;
        }

        Dialog<SessionMetadata> dialog = new Dialog<>();
        dialog.setTitle("历史会话");
        dialog.setHeaderText("选择要恢复的会话");

        ListView<SessionMetadata> historyView = new ListView<>();
        historyView.getItems().addAll(historyList);
        historyView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SessionMetadata meta, boolean empty) {
                super.updateItem(meta, empty);
                if (empty || meta == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    VBox box = new VBox(4);
                    Label name = new Label(meta.getDisplayName());
                    name.setStyle("-fx-font-weight: bold;");
                    Label time = new Label("创建: " + meta.getCreatedAt());
                    time.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                    box.getChildren().addAll(name, time);
                    setGraphic(box);
                }
            }
        });
        historyView.setPrefWidth(400);
        historyView.setPrefHeight(300);

        dialog.getDialogPane().setContent(historyView);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ?
                historyView.getSelectionModel().getSelectedItem() : null);

        dialog.showAndWait().ifPresent(selected -> {
            try {
                WorkSession restored = service.restoreSession(selected);
                sessionList.getItems().add(restored);
                sessionList.getSelectionModel().select(restored);
                currentWorkspace = restored.getWorkDir();
                workspaceLabel.setText(restored.getWorkDir().getFileName().toString());
                setStatus("会话已恢复: " + restored.getDisplayName());
            } catch (Exception e) {
                log.error("恢复会话失败", e);
                showAlert("恢复会话失败: " + e.getMessage());
            }
        });
    }

    // ==================== 辅助方法 ====================

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * 显示窗口
     */
    public void show() {
        stage.show();
    }

    // ==================== 会话列表单元格 ====================

    private static class SessionListCell extends ListCell<WorkSession> {
        @Override
        protected void updateItem(WorkSession session, boolean empty) {
            super.updateItem(session, empty);
            if (empty || session == null) {
                setText(null);
                setGraphic(null);
            } else {
                VBox box = new VBox(2);
                Label name = new Label(session.getDisplayName());
                name.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                Label status = new Label(session.isRunning() ? "🟢 运行中" : "⚪ 空闲");
                status.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                box.getChildren().addAll(name, status);
                setGraphic(box);
            }
        }
    }
}
