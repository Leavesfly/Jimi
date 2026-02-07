package io.leavesfly.jimi.work.ui;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.work.config.WorkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 主窗口
 * <p>
 * Jimi Work 的主界面，包含对话区域、输入框和工具栏
 * </p>
 *
 * @author Jimi2 Team
 */
public class MainWindow {
    
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
    
    /** 主舞台 */
    private final Stage stage;
    
    /** 工作目录 */
    private final Path workDir;
    
    /** LLM 实例 */
    private final LLM llm;
    
    /** 配置 */
    private final WorkConfig config;
    
    /** 消息总线 */
    private final Wire wire;
    
    /** 对话上下文 */
    private Context context;
    
    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    
    /** 执行引擎 */
    private Engine engine;
    
    /** 当前 Agent */
    private Agent agent;
    
    /** 对话区域 */
    private VBox chatArea;
    
    /** 滚动面板 */
    private ScrollPane scrollPane;
    
    /** 输入框 */
    private TextArea inputArea;
    
    /** 发送按钮 */
    private Button sendButton;
    
    /** 当前助手消息文本流 */
    private TextFlow currentAssistantFlow;
    
    /**
     * 构造函数
     *
     * @param stage   主舞台
     * @param workDir 工作目录
     * @param llm     LLM 实例
     * @param config  配置
     */
    public MainWindow(Stage stage, Path workDir, LLM llm, WorkConfig config) {
        this.stage = stage;
        this.workDir = workDir;
        this.llm = llm;
        this.config = config;
        this.wire = new DefaultWire();
        this.context = new DefaultContext();
        
        ObjectMapper objectMapper = new ObjectMapper();
        this.toolRegistry = new DefaultToolRegistry(objectMapper);
        
        initAgent();
        initEngine();
        initUI();
        subscribeWire();
    }
    
    /**
     * 初始化 Agent
     */
    @SuppressWarnings("unchecked")
    private void initAgent() {
        // 通过 SPI 加载工具
        AgentSpec agentSpec = AgentSpec.builder()
                .name("jimi")
                .description("Jimi 桌面助手")
                .version("2.0.0")
                .build();
        
        Runtime toolRuntime = Runtime.builder().workDir(workDir).build();
        List<Tool> tools = new ArrayList<>();
        for (ToolProvider provider : ServiceLoader.load(ToolProvider.class)) {
            if (provider.supports(agentSpec, toolRuntime)) {
                for (Tool<?> t : provider.createTools(agentSpec, toolRuntime)) {
                    tools.add((Tool) t);
                }
            }
        }
        
        // 注册工具到 Registry
        for (Tool tool : tools) {
            toolRegistry.register(tool);
        }
        
        // 构建工具描述
        StringBuilder toolDesc = new StringBuilder();
        for (Tool tool : tools) {
            toolDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        
        this.agent = Agent.builder()
                .name("jimi")
                .description("Jimi 桌面助手")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个强大的 AI 编程助手。\n"
                        + "你有以下工具可用：\n" + toolDesc
                        + "请始终使用清晰、专业的语言，并在执行危险操作前确认。")
                .tools(tools)
                .maxSteps(100)
                .build();
        
        log.info("初始化 Agent，已加载 {} 个工具", tools.size());
    }
    
    /**
     * 初始化引擎
     */
    private void initEngine() {
        // 创建运行时（注入外部 LLM）
        Runtime runtime = Runtime.builder()
                .workDir(workDir)
                .llm(llm)
                .yoloMode(config.isYoloMode())
                .maxContextTokens(config.getMaxContextTokens())
                .build();
        
        this.engine = DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
    }
    
    /**
     * 初始化 UI
     */
    private void initUI() {
        // 主布局
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        
        // 顶部标题栏
        HBox header = createHeader();
        root.setTop(header);
        
        // 中间对话区域
        chatArea = new VBox(10);
        chatArea.setPadding(new Insets(10));
        
        scrollPane = new ScrollPane(chatArea);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        root.setCenter(scrollPane);
        
        // 底部输入区域
        HBox inputBox = createInputArea();
        root.setBottom(inputBox);
        
        // 创建场景
        Scene scene = new Scene(root, 800, 600);
        
        // 配置舞台
        stage.setTitle("Jimi Work - AI 编程助手 (" + llm.getProvider() + "/" + llm.getModel() + ")");
        stage.setScene(scene);
        stage.setMinWidth(600);
        stage.setMinHeight(400);
    }
    
    /**
     * 创建顶部标题栏
     *
     * @return 标题栏
     */
    private HBox createHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));
        
        Label title = new Label("Jimi Work");
        title.setFont(Font.font(18));
        title.setStyle("-fx-font-weight: bold;");
        
        Label agentLabel = new Label(" - " + agent.getName() 
                + " [" + llm.getProvider() + "/" + llm.getModel() + "]");
        agentLabel.setStyle("-fx-text-fill: gray;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button clearBtn = new Button("清空对话");
        clearBtn.setOnAction(e -> clearChat());
        
        header.getChildren().addAll(title, agentLabel, spacer, clearBtn);
        return header;
    }
    
    /**
     * 创建输入区域
     *
     * @return 输入区域
     */
    private HBox createInputArea() {
        HBox inputBox = new HBox(10);
        inputBox.setAlignment(Pos.CENTER);
        inputBox.setPadding(new Insets(10, 0, 0, 0));
        
        inputArea = new TextArea();
        inputArea.setPromptText("输入消息...");
        inputArea.setPrefRowCount(3);
        inputArea.setWrapText(true);
        HBox.setHgrow(inputArea, Priority.ALWAYS);
        
        // 快捷键：Ctrl+Enter 发送
        inputArea.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode().getName().equals("Enter")) {
                sendMessage();
            }
        });
        
        sendButton = new Button("发送");
        sendButton.setPrefWidth(80);
        sendButton.setOnAction(e -> sendMessage());
        
        inputBox.getChildren().addAll(inputArea, sendButton);
        return inputBox;
    }
    
    /**
     * 订阅消息总线
     */
    private void subscribeWire() {
        wire.asFlux().subscribe(this::handleWireMessage);
    }
    
    /**
     * 处理消息总线消息
     *
     * @param message 消息
     */
    private void handleWireMessage(WireMessage message) {
        Platform.runLater(() -> {
            if (message instanceof ContentPartMessage) {
                ContentPartMessage cpm = (ContentPartMessage) message;
                var part = cpm.getContentPart();
                if (part instanceof io.leavesfly.jimi.adk.api.message.TextPart) {
                    String text = ((io.leavesfly.jimi.adk.api.message.TextPart) part).getText();
                    appendToAssistantMessage(text);
                }
            } else if (message instanceof StepBegin) {
                // 开始新的助手消息
                if (currentAssistantFlow == null) {
                    currentAssistantFlow = createAssistantMessageFlow();
                    addMessageToChat("助手", currentAssistantFlow, false);
                }
            } else if (message instanceof StepEnd) {
                // 步骤结束
            } else if (message instanceof ToolCallMessage) {
                ToolCallMessage tcm = (ToolCallMessage) message;
                String toolName = tcm.getToolCall().getFunction().getName();
                appendToAssistantMessage("\n[调用工具: " + toolName + "]\n");
            }
        });
    }
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            return;
        }
        
        // 清空输入框
        inputArea.clear();
        
        // 显示用户消息
        addUserMessage(input);
        
        // 准备接收助手消息
        currentAssistantFlow = null;
        
        // 禁用发送按钮
        sendButton.setDisable(true);
        
        // 异步执行
        new Thread(() -> {
            try {
                ExecutionResult result = engine.run(input).block();
                
                Platform.runLater(() -> {
                    if (result != null && !result.isSuccess()) {
                        addSystemMessage("执行失败: " + result.getError());
                    }
                    sendButton.setDisable(false);
                    currentAssistantFlow = null;
                });
                
            } catch (Exception e) {
                log.error("执行异常", e);
                Platform.runLater(() -> {
                    addSystemMessage("错误: " + e.getMessage());
                    sendButton.setDisable(false);
                    currentAssistantFlow = null;
                });
            }
        }).start();
    }
    
    /**
     * 添加用户消息
     *
     * @param text 消息文本
     */
    private void addUserMessage(String text) {
        TextFlow flow = new TextFlow();
        Text content = new Text(text);
        content.setStyle("-fx-fill: #333;");
        flow.getChildren().add(content);
        addMessageToChat("用户", flow, true);
    }
    
    /**
     * 添加系统消息
     *
     * @param text 消息文本
     */
    private void addSystemMessage(String text) {
        TextFlow flow = new TextFlow();
        Text content = new Text(text);
        content.setStyle("-fx-fill: #c00;");
        flow.getChildren().add(content);
        addMessageToChat("系统", flow, false);
    }
    
    /**
     * 创建助手消息流
     *
     * @return 文本流
     */
    private TextFlow createAssistantMessageFlow() {
        TextFlow flow = new TextFlow();
        flow.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 5;");
        return flow;
    }
    
    /**
     * 追加文本到助手消息
     *
     * @param text 文本
     */
    private void appendToAssistantMessage(String text) {
        if (currentAssistantFlow != null && text != null) {
            Text textNode = new Text(text);
            currentAssistantFlow.getChildren().add(textNode);
            scrollToBottom();
        }
    }
    
    /**
     * 添加消息到对话区域
     *
     * @param role   角色名
     * @param flow   内容流
     * @param isUser 是否为用户消息
     */
    private void addMessageToChat(String role, TextFlow flow, boolean isUser) {
        VBox messageBox = new VBox(5);
        messageBox.setMaxWidth(Double.MAX_VALUE);
        messageBox.setPadding(new Insets(5));
        
        Label roleLabel = new Label(role + ":");
        roleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (isUser ? "#0066cc" : "#009933") + ";");
        
        messageBox.getChildren().addAll(roleLabel, flow);
        messageBox.setStyle("-fx-background-color: " + (isUser ? "#e6f2ff" : "#f0fff0") + "; -fx-background-radius: 5;");
        
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
     * 清空对话
     */
    private void clearChat() {
        chatArea.getChildren().clear();
        this.context = new DefaultContext();
        // 重新初始化引擎（保留工具和 LLM）
        Runtime runtime = Runtime.builder()
                .workDir(workDir)
                .llm(llm)
                .yoloMode(config.isYoloMode())
                .maxContextTokens(config.getMaxContextTokens())
                .build();
        this.engine = DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
        currentAssistantFlow = null;
    }
    
    /**
     * 显示窗口
     */
    public void show() {
        stage.show();
    }
}
