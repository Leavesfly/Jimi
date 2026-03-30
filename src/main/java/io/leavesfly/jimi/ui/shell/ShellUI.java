package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookType;
import io.leavesfly.jimi.core.interaction.approval.ApprovalRequest;
import io.leavesfly.jimi.core.interaction.HumanInputRequest;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.command.CommandRegistry;
import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.ui.notification.NotificationService;
import io.leavesfly.jimi.ui.shell.input.AgentCommandProcessor;
import io.leavesfly.jimi.ui.shell.input.InputProcessor;
import io.leavesfly.jimi.ui.shell.input.MetaCommandProcessor;
import io.leavesfly.jimi.ui.shell.input.ShellShortcutProcessor;
import io.leavesfly.jimi.ui.shell.output.AssistantTextRenderer;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.ui.ToolVisualization;
import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.*;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.context.ApplicationContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell UI - 基于 JLine 的交互式命令行界面
 * 提供富文本显示、命令历史、自动补全等功能
 * <p>
 * 采用插件化架构：
 * - CommandHandler: 元命令处理器
 * - InputProcessor: 输入处理器
 * - CommandRegistry: 命令注册表
 */
@Slf4j
public class ShellUI implements AutoCloseable {

    private final Terminal terminal;
    private final LineReader lineReader;
    private final EngineClient engineClient;
    private final ToolVisualization toolVisualization;
    private final AtomicBoolean running;
    private final AtomicReference<String> currentStatus;
    private final Map<String, String> activeTools; // ConcurrentHashMap, accessed from Wire subscriber thread
    private Disposable wireSubscription;

    // Shell UI 配置
    private final ShellUIConfig uiConfig;

    // 主题配置
    private ThemeConfig theme;

    // 快捷提示计数器
    private final AtomicInteger interactionCount;
    private final AtomicBoolean welcomeHintShown;
    private final AtomicBoolean inputHintShown;
    private final AtomicBoolean thinkingHintShown;

    // 插件化组件
    private final OutputFormatter outputFormatter;
    private final CommandRegistry commandRegistry;
    private final List<InputProcessor> inputProcessors;

    // 通知服务
    private final NotificationService notificationService;

    // 委托组件
    private final SpinnerManager spinnerManager;
    private final PromptBuilder promptBuilder;
    private final AssistantTextRenderer renderer;
    private final InteractionHandler interactionHandler;
    private final AsyncSubagentEventDisplay asyncDisplay;

    /**
     * 创建 Shell UI
     *
     * @param engineClient       EngineClient 实例
     * @param applicationContext Spring 应用上下文（用于获取 CommandRegistry）
     * @throws IOException 终端初始化失败
     */
    public ShellUI(EngineClient engineClient, ApplicationContext applicationContext) throws IOException {
        this.engineClient = engineClient;
        this.toolVisualization = new ToolVisualization();
        this.running = new AtomicBoolean(false);
        this.currentStatus = new AtomicReference<>("ready");
        this.activeTools = new ConcurrentHashMap<>();
        
        // 初始化阶段：直接从EngineClient获取配置（无需消息交互）
        this.uiConfig = engineClient.getShellUIConfig();

        // 初始化主题
        this.theme = engineClient.getThemeConfig();

        // 初始化快捷提示计数器
        this.interactionCount = new AtomicInteger(0);
        this.welcomeHintShown = new AtomicBoolean(false);
        this.inputHintShown = new AtomicBoolean(false);
        this.thinkingHintShown = new AtomicBoolean(false);

        // 初始化 Terminal
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .encoding("UTF-8")
                .build();

        // 从 Spring 容器获取 CommandRegistry（已自动注册所有命令）
        this.commandRegistry = applicationContext.getBean(CommandRegistry.class);
        log.info("Loaded CommandRegistry with {} commands from Spring context", commandRegistry.size());

        // 获取通知服务
        this.notificationService = applicationContext.getBean(io.leavesfly.jimi.ui.notification.NotificationService.class);

        // 初始化阶段：从EngineClient获取工作目录
        Path workingDir = engineClient.getWorkDir();

        // 初始化 LineReader（使用增强的 JimiCompleter）
        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("Jimi")
                .completer(new JimiCompleter(commandRegistry, workingDir))
                .highlighter(new JimiHighlighter())
                .parser(new JimiParser())
                // 禁用事件扩展（!字符）
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                // 启用自动补全功能
                .option(LineReader.Option.AUTO_LIST, true)           // 自动显示补全列表
                .option(LineReader.Option.AUTO_MENU, true)           // 启用自动菜单
                .option(LineReader.Option.AUTO_MENU_LIST, true)      // 自动显示菜单列表
                .option(LineReader.Option.INSERT_TAB, false)         // 行首按Tab触发补全而非Tab字符
                // 其他有用的补全选项
                .option(LineReader.Option.COMPLETE_IN_WORD, true)    // 允许在单词中间补全
                .option(LineReader.Option.CASE_INSENSITIVE, true)    // 不区分大小写匹配
                .build();

        // 初始化输出格式化器
        this.outputFormatter = new OutputFormatter(terminal, theme);

        // 初始化委托组件
        this.renderer = new AssistantTextRenderer(terminal, theme);
        this.spinnerManager = new SpinnerManager(terminal, uiConfig);
        this.promptBuilder = new PromptBuilder(currentStatus, uiConfig, theme, engineClient);
        this.interactionHandler = new InteractionHandler(terminal, outputFormatter, lineReader, renderer);
        this.asyncDisplay = new AsyncSubagentEventDisplay(terminal, outputFormatter, notificationService, uiConfig, renderer);

        // 初始化输入处理器
        this.inputProcessors = new ArrayList<>();
        registerInputProcessors();

        // 初始化阶段：订阅 Wire 消息
        subscribeWire();
    }

    /**
     * 注册所有输入处理器
     */
    private void registerInputProcessors() {
        inputProcessors.add(new MetaCommandProcessor(commandRegistry));
        inputProcessors.add(new ShellShortcutProcessor());
        inputProcessors.add(new AgentCommandProcessor());

        // 按优先级排序
        inputProcessors.sort(Comparator.comparingInt(InputProcessor::getPriority));

        log.info("Registered {} input processors", inputProcessors.size());
    }

    /**
     * 订阅 Wire 消息总线
     * 使用 boundedElastic 调度器处理可能阻塞的消息（如审批请求）
     */
    private void subscribeWire() {
        wireSubscription = engineClient.subscribe()
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(this::handleWireMessage);
    }

    /**
     * 处理 Wire 消息，根据消息类型分发到对应的处理方法
     */
    private void handleWireMessage(WireMessage message) {
        try {
            if (message instanceof StepBegin stepBegin) {
                handleStepBegin(stepBegin);
            } else if (message instanceof StepInterrupted) {
                handleStepInterrupted();
            } else if (message instanceof CompactionBegin) {
                handleCompactionBegin();
            } else if (message instanceof CompactionEnd) {
                handleCompactionEnd();
            } else if (message instanceof StatusUpdate statusUpdate) {
                handleStatusUpdate(statusUpdate);
            } else if (message instanceof ContentPartMessage contentMsg) {
                handleContentPart(contentMsg);
            } else if (message instanceof ToolCallMessage toolCallMsg) {
                handleToolCall(toolCallMsg);
            } else if (message instanceof ToolResultMessage toolResultMsg) {
                handleToolResult(toolResultMsg);
            } else if (message instanceof TokenUsageMessage tokenUsageMsg) {
                showTokenUsage(tokenUsageMsg.getUsage());
            } else if (message instanceof ApprovalRequest approvalRequest) {
                log.info("[ShellUI] Received ApprovalRequest: action={}, description={}",
                        approvalRequest.getAction(), approvalRequest.getDescription());
                interactionHandler.handleApprovalRequest(approvalRequest);
            } else if (message instanceof HumanInputRequest humanInputRequest) {
                log.info("[ShellUI] Received HumanInputRequest: type={}, question={}",
                        humanInputRequest.getInputType(), truncateForLog(humanInputRequest.getQuestion()));
                interactionHandler.handleHumanInputRequest(humanInputRequest);
            } else if (message instanceof AsyncSubagentStarted asyncStarted) {
                asyncDisplay.handleStarted(asyncStarted);
            } else if (message instanceof AsyncSubagentProgress asyncProgress) {
                asyncDisplay.handleProgress(asyncProgress);
            } else if (message instanceof AsyncSubagentCompleted asyncCompleted) {
                asyncDisplay.handleCompleted(asyncCompleted);
            } else if (message instanceof AsyncSubagentTrigger asyncTrigger) {
                asyncDisplay.handleTrigger(asyncTrigger);
            } else {
                log.debug("Unhandled wire message type: {}", message.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Error handling wire message: {}", message.getClass().getSimpleName(), e);
        }
    }

    private void handleStepBegin(StepBegin stepBegin) {
        if (stepBegin.isSubagent()) {
            String agentName = stepBegin.getAgentName() != null ? stepBegin.getAgentName() : "subagent";
            printStatus("  🤖 [" + agentName + "] Step " + stepBegin.getStepNumber() + " - Thinking...");
        } else {
            currentStatus.set("thinking (step " + stepBegin.getStepNumber() + ")");
            printStatus("🧠 Step " + stepBegin.getStepNumber() + " - Thinking...");

            if (uiConfig.isShowSpinner()) {
                spinnerManager.start("正在思考...");
            }
            if (stepBegin.getStepNumber() == 1) {
                showShortcutsHint("thinking");
            }

            renderer.resetForNewStep();
        }
    }

    private void handleStepInterrupted() {
        currentStatus.set("interrupted");
        activeTools.clear();
        renderer.flushLineIfNeeded();
        printError("⚠️  Step interrupted");
        showShortcutsHint("error");
    }

    private void handleCompactionBegin() {
        currentStatus.set("compacting");
        printStatus("🗜️  Compacting context...");
    }

    private void handleCompactionEnd() {
        currentStatus.set("ready");
        printSuccess("✅ Context compacted");
    }

    private void handleStatusUpdate(StatusUpdate statusUpdate) {
        Map<String, Object> statusMap = statusUpdate.getStatus();
        String status = statusMap.getOrDefault("status", "unknown").toString();
        currentStatus.set(status);
    }

    private void handleContentPart(ContentPartMessage contentMsg) {
        if (uiConfig.isShowSpinner()) {
            spinnerManager.stop();
        }

        ContentPart part = contentMsg.getContentPart();
        if (part instanceof TextPart textPart) {
            boolean isReasoning = contentMsg.getContentType() == ContentPartMessage.ContentType.REASONING;
            renderer.print(textPart.getText(), isReasoning);
        }
    }

    private void handleToolCall(ToolCallMessage toolCallMsg) {
        if (uiConfig.isShowSpinner()) {
            spinnerManager.stop();
        }

        renderer.flushLineIfNeeded();

        ToolCall toolCall = toolCallMsg.getToolCall();
        String toolName = toolCall.getFunction().getName();
        activeTools.put(toolCall.getId(), toolName);

        String displayMode = uiConfig.getToolDisplayMode();
        switch (displayMode) {
            case "minimal" -> printStatus("🔧 " + toolName);
            case "compact" -> {
                String args = toolCall.getFunction().getArguments();
                int truncateLen = uiConfig.getToolArgsTruncateLength();
                if (args != null && args.length() > truncateLen) {
                    args = args.substring(0, truncateLen) + "...";
                }
                printStatus("🔧 " + toolName + " | " + (args != null ? args : ""));
            }
            default -> toolVisualization.onToolCallStart(toolCall);
        }
    }

    private void handleToolResult(ToolResultMessage toolResultMsg) {
        String toolCallId = toolResultMsg.getToolCallId();
        ToolResult result = toolResultMsg.getToolResult();

        String displayMode = uiConfig.getToolDisplayMode();
        switch (displayMode) {
            case "minimal" -> {
                if (result.isOk()) {
                    printSuccess("✅ " + activeTools.get(toolCallId));
                } else {
                    printError("❌ " + activeTools.get(toolCallId));
                }
            }
            case "compact" -> {
                String resultPreview = result.isOk() ? "✅ 成功" : "❌ 失败: " + result.getMessage();
                printInfo("  → " + resultPreview);
            }
            default -> toolVisualization.onToolCallComplete(toolCallId, result);
        }

        activeTools.remove(toolCallId);
    }

    /**
     * 运行 Shell UI
     *
     * @return 是否成功运行
     */
    public Mono<Boolean> run() {
        return Mono.defer(() -> {
            running.set(true);

            // 触发 ON_SESSION_START hook
            triggerSessionHook(HookType.ON_SESSION_START);

            // 打印欢迎信息
            printWelcome();

            // 主循环
            while (running.get()) {
                try {
                    // 读取用户输入
                    String input = readLine();

                    if (input == null) {
                        // EOF (Ctrl-D)
                        printInfo("Bye!");
                        break;
                    }

                    // 处理输入
                    if (!processInput(input.trim())) {
                        break;
                    }

                } catch (UserInterruptException e) {
                    // Ctrl-C
                    printInfo("Tip: press Ctrl-D or type 'exit' to quit");
                } catch (EndOfFileException e) {
                    // EOF
                    printInfo("Bye!");
                    break;
                } catch (Exception e) {
                    log.error("Error in shell UI", e);
                    printError("Error: " + e.getMessage());
                }
            }

            // 触发 ON_SESSION_END hook
            triggerSessionHook(HookType.ON_SESSION_END);

            return Mono.just(true);
        });
    }

    /**
     * 触发会话生命周期 Hook
     */
    private void triggerSessionHook(HookType hookType) {
        try {
            Path workDir = engineClient.getWorkDir();
            String agentName = engineClient.getAgentName();

            HookContext hookContext = HookContext.builder()
                    .hookType(hookType)
                    .workDir(workDir)
                    .agentName(agentName)
                    .build();

            engineClient.triggerHook(hookType, hookContext)
                    .doOnError(e -> log.warn("Session hook trigger failed for type {}: {}", hookType, e.getMessage()))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .block();
        } catch (Exception e) {
            log.warn("Failed to trigger session hook {}: {}", hookType, e.getMessage());
        }
    }

    /**
     * 读取一行输入
     */
    private String readLine() {
        try {
            return lineReader.readLine(promptBuilder.build());
        } catch (UserInterruptException e) {
            throw e;
        } catch (EndOfFileException e) {
            return null;
        }
    }

    /**
     * 处理用户输入
     *
     * @return 是否继续运行
     */
    private boolean processInput(String input) {
        if (input.isEmpty()) {
            return true;
        }
        
        // 增加交互计数
        interactionCount.incrementAndGet();
        
        // 首次输入时显示输入提示
        if (interactionCount.get() == 1) {
            showShortcutsHint("input");
        }

        // 检查退出命令
        if (input.equals("exit") || input.equals("quit")) {
            outputFormatter.printInfo("Bye!");
            return false;
        }

        // 构建上下文
        ShellContext context = ShellContext.builder()
                .engineClient(engineClient)
                .terminal(terminal)
                .lineReader(lineReader)
                .rawInput(input)
                .outputFormatter(outputFormatter)
                .build();

        // 按优先级查找匹配的输入处理器
        for (InputProcessor processor : inputProcessors) {
            if (processor.canProcess(input)) {
                try {
                    return processor.process(input, context);
                } catch (Exception e) {
                    log.error("Error processing input with {}", processor.getClass().getSimpleName(), e);
                    outputFormatter.printError("处理输入失败: " + e.getMessage());
                    return true;
                }
            }
        }

        // 如果没有处理器匹配，打印错误
        outputFormatter.printError("无法处理输入: " + input);
        return true;
    }

    /**
     * 打印状态信息（黄色）
     */
    private void printStatus(String text) {
        outputFormatter.printStatus(text);
    }

    /**
     * 打印成功信息（绿色）
     */
    private void printSuccess(String text) {
        outputFormatter.printSuccess(text);
    }

    /**
     * 打印错误信息（红色）
     */
    private void printError(String text) {
        outputFormatter.printError(text);
    }

    /**
     * 打印欢迎信息
     */
    private void printWelcome() {
        outputFormatter.println("");
        printBanner();
        outputFormatter.println("");
        outputFormatter.printSuccess("Welcome to Jimi ");
        outputFormatter.printInfo("Type /help for available commands, or just start chatting!");
        outputFormatter.println("");
        
        // 显示欢迎快捷提示
        showShortcutsHint("welcome");
    }

    /**
     * 打印 Banner
     */
    private void printBanner() {
        // 获取版本信息
        String version = getVersionInfo();
        String javaVersion = System.getProperty("java.version");
        // 提取主版本号 (如 "17" from "17.0.1")
        String javaMajorVersion = javaVersion.split("\\.")[0];
        
        String banner = String.format("""
                
                ╭──────────────────────────────────────────────╮
                │                                              │
                │        🤖  J I M I  %-24s │
                │                                              │
                │        Your AI Coding Companion              │
                │        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━      │
                │                                              │
                │  🔧 Code  💬 Chat  🧠 Think  ⚡ Fast         │
                │                                              │
                │  Java %s | Type /help to start              │
                ╰──────────────────────────────────────────────╯
                
                """, version, javaMajorVersion);

        AttributedStyle style = ColorMapper.createBoldStyle(theme.getBannerColor());

        terminal.writer().println(new AttributedString(banner, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 获取版本信息
     */
    private String getVersionInfo() {
        // 尝试从 Manifest 读取版本号
        Package pkg = this.getClass().getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        
        // 如果无法从 Manifest 获取，返回默认版本
        if (version == null || version.isEmpty()) {
            version = "v0.1.0"; // 从 pom.xml 读取的默认版本
        } else {
            version = "v" + version;
        }
        
        return version;
    }

    /**
     * 打印信息（蓝色）
     */
    private void printInfo(String text) {
        outputFormatter.printInfo(text);
    }

    /**
     * 停止 Shell UI
     */
    public void stop() {
        running.set(false);
    }

    /**
     * 显示Token消耗统计（在每个步骤结束时调用）
     */
    private void showTokenUsage(ChatCompletionResult.Usage usage) {
        if (!uiConfig.isShowTokenUsage() || usage == null) {
            return;
        }
        
        // 记录当前步骤的Token
        int stepTokens = usage.getTotalTokens();
        
        // 构建显示消息
        StringBuilder msg = new StringBuilder();
        msg.append("\n📊 Token: ");
        msg.append("本次 ").append(usage.getPromptTokens()).append("+").append(usage.getCompletionTokens());
        msg.append(" = ").append(stepTokens);
        
        // 如果有上下文Token总数，显示累计
        try {
            int totalTokens = engineClient.getTokenCount();
            if (totalTokens > 0) {
                msg.append(" | 总计 ").append(totalTokens);
            }
        } catch (Exception e) {
            // 忽略错误
        }
        
        // 使用主题Token颜色显示
        AttributedStyle style = ColorMapper.createStyle(theme.getTokenColor());
        terminal.writer().println(new AttributedString(msg.toString(), style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 显示快捷提示
     * @param hintType 提示类型：welcome, input, thinking, error
     */
    private void showShortcutsHint(String hintType) {
        if (!uiConfig.isShowShortcutsHint()) {
            return;
        }
        
        // 根据频率配置决定是否显示
        switch (uiConfig.getShortcutsHintFrequency()) {
            case "first_time" -> {
                if (!shouldShowFirstTimeHint(hintType)) {
                    return;
                }
            }
            case "periodic" -> {
                int count = interactionCount.get();
                int interval = uiConfig.getShortcutsHintInterval();
                if (count % interval != 0) {
                    return;
                }
            }
            default -> { /* always: do nothing, show hint */ }
        }
        
        // 显示对应类型的提示
        String hint = getHintForType(hintType);
        if (hint != null && !hint.isEmpty()) {
            terminal.writer().println();
            AttributedStyle style = ColorMapper.createStyle(theme.getHintColor());
            if (theme.isItalicReasoning()) {
                style = style.italic();
            }
            terminal.writer().println(new AttributedString(hint, style).toAnsi());
            terminal.flush();
        }
    }
    
    /**
     * 判断是否应该显示首次提示
     */
    private boolean shouldShowFirstTimeHint(String hintType) {
        return switch (hintType) {
            case "welcome" -> welcomeHintShown.compareAndSet(false, true);
            case "input" -> inputHintShown.compareAndSet(false, true);
            case "thinking" -> thinkingHintShown.compareAndSet(false, true);
            default -> true;
        };
    }

    /**
     * 根据类型获取提示内容
     */
    private String getHintForType(String hintType) {
        return switch (hintType) {
            case "error" -> "💡 提示: /reset 清空上下文 | /status 查看状态 | /history 查看历史";
            case "approval" -> "💡 快捷键: y (批准) | n (拒绝) | a (全部批准)";
            default -> null;
        };
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncateForLog(String text) {
        if (text == null) return null;
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    /**
     * 从配置中解析当前主题
     */
    private ThemeConfig resolveTheme() {
        String themeName = uiConfig.getThemeName();
        ThemeConfig resolved = (themeName != null && !themeName.isEmpty())
                ? ThemeConfig.getPresetTheme(themeName)
                : uiConfig.getTheme();
        return resolved != null ? resolved : ThemeConfig.defaultTheme();
    }

    /**
     * 更新主题（运行时切换）
     */
    public void updateTheme() {
        this.theme = resolveTheme();
        this.outputFormatter.setTheme(this.theme);
        this.renderer.setTheme(this.theme);
        this.promptBuilder.setTheme(this.theme);
    }

    @Override
    public void close() throws Exception {
        spinnerManager.stop();
        if (wireSubscription != null) {
            wireSubscription.dispose();
        }
        if (terminal != null) {
            terminal.close();
        }
    }
}
