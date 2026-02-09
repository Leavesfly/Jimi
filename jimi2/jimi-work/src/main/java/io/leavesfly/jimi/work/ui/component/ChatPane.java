package io.leavesfly.jimi.work.ui.component;

import io.leavesfly.jimi.work.model.StreamChunk;
import io.leavesfly.jimi.work.model.WorkSession;
import io.leavesfly.jimi.work.model.ApprovalInfo;
import io.leavesfly.jimi.work.model.TodoInfo;
import io.leavesfly.jimi.work.service.WorkService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 聊天面板 - 流式对话 UI
 */
public class ChatPane extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(ChatPane.class);

    private final WorkService service;

    /** 对话区域 */
    private VBox chatArea;
    private ScrollPane scrollPane;
    /** 输入区域 */
    private TextArea inputArea;
    private Button sendButton;
    private Button cancelButton;

    /** 当前会话 */
    private WorkSession currentSession;
    /** 当前助手消息流 */
    private TextFlow currentAssistantFlow;

    /** 审批回调 */
    private Consumer<ApprovalInfo> approvalCallback;
    /** Todo 更新回调 */
    private Consumer<TodoInfo.TodoList> todoUpdateCallback;

    public ChatPane(WorkService service) {
        this.service = service;
        getStyleClass().add("chat-pane");
        initUI();
    }

    private void initUI() {
        // 对话区域
        chatArea = new VBox(8);
        chatArea.setPadding(new Insets(10));
        chatArea.getStyleClass().add("chat-area");

        scrollPane = new ScrollPane(chatArea);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("chat-scroll");
        setCenter(scrollPane);

        // 输入区域
        inputArea = new TextArea();
        inputArea.setPromptText("输入消息... (Ctrl+Enter 发送)");
        inputArea.setPrefRowCount(3);
        inputArea.setWrapText(true);
        inputArea.getStyleClass().add("chat-input");

        inputArea.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode().getName().equals("Enter")) {
                sendMessage();
            }
        });

        sendButton = new Button("发送");
        sendButton.getStyleClass().add("send-button");
        sendButton.setPrefWidth(80);
        sendButton.setOnAction(e -> sendMessage());

        cancelButton = new Button("取消");
        cancelButton.getStyleClass().add("cancel-button");
        cancelButton.setPrefWidth(80);
        cancelButton.setVisible(false);
        cancelButton.setOnAction(e -> cancelExecution());

        VBox buttons = new VBox(5, sendButton, cancelButton);
        buttons.setAlignment(Pos.CENTER);

        HBox inputBox = new HBox(10, inputArea, buttons);
        inputBox.setPadding(new Insets(10));
        inputBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(inputArea, Priority.ALWAYS);
        inputBox.getStyleClass().add("chat-input-box");

        setBottom(inputBox);
    }

    /**
     * 设置当前会话
     */
    public void setSession(WorkSession session) {
        this.currentSession = session;
        chatArea.getChildren().clear();
        currentAssistantFlow = null;

        if (session != null) {
            addSystemMessage("会话已连接: " + session.getDisplayName());
        }
    }

    /**
     * 设置审批回调
     */
    public void setApprovalCallback(Consumer<ApprovalInfo> callback) {
        this.approvalCallback = callback;
    }

    /**
     * 设置 Todo 更新回调
     */
    public void setTodoUpdateCallback(Consumer<TodoInfo.TodoList> callback) {
        this.todoUpdateCallback = callback;
    }

    /**
     * 发送消息
     */
    private void sendMessage() {
        if (currentSession == null) {
            addSystemMessage("请先选择或创建一个会话");
            return;
        }

        String input = inputArea.getText().trim();
        if (input.isEmpty()) return;

        inputArea.clear();
        addUserMessage(input);
        currentAssistantFlow = null;

        // 切换按钮状态
        sendButton.setDisable(true);
        cancelButton.setVisible(true);

        // 执行任务
        service.execute(currentSession.getId(), input)
                .subscribe(
                        chunk -> Platform.runLater(() -> handleChunk(chunk)),
                        error -> Platform.runLater(() -> {
                            addSystemMessage("错误: " + error.getMessage());
                            resetInputState();
                        }),
                        () -> Platform.runLater(this::resetInputState)
                );
    }

    /**
     * 取消执行
     */
    private void cancelExecution() {
        if (currentSession != null) {
            service.cancelTask(currentSession.getId());
            addSystemMessage("任务已取消");
            resetInputState();
        }
    }

    /**
     * 处理流式输出块
     */
    private void handleChunk(StreamChunk chunk) {
        switch (chunk.getType()) {
            case TEXT -> appendToAssistant(chunk.getContent());
            case REASONING -> appendReasoningToAssistant(chunk.getContent());
            case TOOL_CALL -> addToolCallMessage(chunk.getToolName());
            case TOOL_RESULT -> addToolResultMessage(chunk.getToolName(), chunk.getContent());
            case STEP_BEGIN -> {
                currentAssistantFlow = null; // 新步骤开始新的消息块
            }
            case STEP_END -> { /* no-op */ }
            case APPROVAL -> {
                if (approvalCallback != null) {
                    approvalCallback.accept(chunk.getApproval());
                }
            }
            case TODO_UPDATE -> {
                if (todoUpdateCallback != null) {
                    todoUpdateCallback.accept(chunk.getTodoList());
                }
            }
            case ERROR -> addSystemMessage("错误: " + chunk.getContent());
            case DONE -> { /* no-op, handled by complete */ }
        }
    }

    /**
     * 追加文本到助手消息
     */
    private void appendToAssistant(String text) {
        if (text == null || text.isEmpty()) return;
        if (currentAssistantFlow == null) {
            currentAssistantFlow = new TextFlow();
            currentAssistantFlow.getStyleClass().add("assistant-text-flow");
            addMessageBox("助手", currentAssistantFlow, false);
        }
        Text textNode = new Text(text);
        textNode.getStyleClass().add("assistant-text");
        currentAssistantFlow.getChildren().add(textNode);
        scrollToBottom();
    }

    /**
     * 追加推理内容到助手消息
     */
    private void appendReasoningToAssistant(String text) {
        if (text == null || text.isEmpty()) return;
        if (currentAssistantFlow == null) {
            currentAssistantFlow = new TextFlow();
            currentAssistantFlow.getStyleClass().add("assistant-text-flow");
            addMessageBox("助手", currentAssistantFlow, false);
        }
        Text textNode = new Text(text);
        textNode.getStyleClass().addAll("reasoning-text");
        textNode.setStyle("-fx-fill: #888; -fx-font-style: italic;");
        currentAssistantFlow.getChildren().add(textNode);
        scrollToBottom();
    }

    /**
     * 添加用户消息
     */
    private void addUserMessage(String text) {
        TextFlow flow = new TextFlow();
        Text content = new Text(text);
        content.getStyleClass().add("user-text");
        flow.getChildren().add(content);
        addMessageBox("用户", flow, true);
    }

    /**
     * 添加系统消息
     */
    private void addSystemMessage(String text) {
        TextFlow flow = new TextFlow();
        Text content = new Text(text);
        content.setStyle("-fx-fill: #c00;");
        flow.getChildren().add(content);
        addMessageBox("系统", flow, false);
    }

    /**
     * 添加工具调用消息
     */
    private void addToolCallMessage(String toolName) {
        Label label = new Label("🔧 调用工具: " + toolName);
        label.getStyleClass().add("tool-call-label");
        label.setStyle("-fx-text-fill: #5c6bc0; -fx-font-weight: bold; -fx-padding: 4 8;");
        chatArea.getChildren().add(label);
        scrollToBottom();
    }

    /**
     * 添加工具结果消息
     */
    private void addToolResultMessage(String toolName, String content) {
        if (content != null && content.length() > 200) {
            content = content.substring(0, 200) + "...";
        }
        Label label = new Label("✅ " + toolName + " 完成");
        label.getStyleClass().add("tool-result-label");
        label.setStyle("-fx-text-fill: #4caf50; -fx-padding: 4 8;");
        chatArea.getChildren().add(label);
        scrollToBottom();
    }

    /**
     * 添加消息框到对话区域
     */
    private void addMessageBox(String role, TextFlow flow, boolean isUser) {
        VBox messageBox = new VBox(4);
        messageBox.setMaxWidth(Double.MAX_VALUE);
        messageBox.setPadding(new Insets(8));
        messageBox.getStyleClass().add(isUser ? "user-message" : "assistant-message");

        Label roleLabel = new Label(role);
        roleLabel.getStyleClass().add("role-label");
        roleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " +
                (isUser ? "#1976d2" : "#388e3c") + ";");

        messageBox.getChildren().addAll(roleLabel, flow);
        chatArea.getChildren().add(messageBox);
        scrollToBottom();
    }

    /**
     * 滚动到底部
     */
    private void scrollToBottom() {
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    /**
     * 重置输入状态
     */
    private void resetInputState() {
        sendButton.setDisable(false);
        cancelButton.setVisible(false);
        currentAssistantFlow = null;
    }
}
