package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.engine.hook.HookContext;
import io.leavesfly.jimi.core.engine.hook.HookRegistry;
import io.leavesfly.jimi.core.engine.hook.HookType;
import io.leavesfly.jimi.core.interaction.approval.ApprovalRequest;
import io.leavesfly.jimi.core.interaction.approval.ApprovalResponse;
import io.leavesfly.jimi.core.interaction.HumanInputRequest;
import io.leavesfly.jimi.core.interaction.HumanInputResponse;
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
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.ui.ToolVisualization;
import io.leavesfly.jimi.wire.Wire;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
    private final JimiEngine soul;
    private final ToolVisualization toolVisualization;
    private final AtomicBoolean running;
    private final AtomicReference<String> currentStatus;
    private final Map<String, String> activeTools;
    private final AtomicBoolean assistantOutputStarted;
    private final AtomicInteger currentLineLength; // 当前行的字符计数
    private final AtomicBoolean isInReasoningMode; // 当前是否在推理模式
    private Disposable wireSubscription;

    // 审批请求队列
    private final BlockingQueue<ApprovalRequest> approvalQueue;

    // Shell UI 配置
    private final ShellUIConfig uiConfig;
    
    // 主题配置
    private ThemeConfig theme;
    
    // 旋转动画相关
    private Thread spinnerThread;
    private final AtomicBoolean showSpinner;
    private final AtomicReference<String> spinnerMessage;
    
    // Token 统计
    private final AtomicInteger currentStepTokens; // 当前步骤的Token消耗
    private int lastTotalTokens; // 上次记录的总Token数
    
    // 快捷提示计数器
    private final AtomicInteger interactionCount; // 交互次数计数器
    private final AtomicBoolean welcomeHintShown; // 是否已显示欢迎提示
    private final AtomicBoolean inputHintShown; // 是否已显示输入提示
    private final AtomicBoolean thinkingHintShown; // 是否已显示思考提示

    // 插件化组件
    private final OutputFormatter outputFormatter;
    private final CommandRegistry commandRegistry;
    private final List<InputProcessor> inputProcessors;
    
    // 通知服务
    private final NotificationService notificationService;
    
    // Hook 注册表
    private final HookRegistry hookRegistry;

    /**
     * 创建 Shell UI
     *
     * @param soul               JimiEngine 实例
     * @param applicationContext Spring 应用上下文（用于获取 CommandRegistry）
     * @throws IOException 终端初始化失败
     */
    public ShellUI(JimiEngine soul, ApplicationContext applicationContext) throws IOException {
        this.soul = soul;
        this.toolVisualization = new ToolVisualization();
        this.running = new AtomicBoolean(false);
        this.currentStatus = new AtomicReference<>("ready");
        this.activeTools = new HashMap<>();
        this.assistantOutputStarted = new AtomicBoolean(false);
        this.currentLineLength = new AtomicInteger(0);
        this.isInReasoningMode = new AtomicBoolean(false);
        this.approvalQueue = new LinkedBlockingQueue<>();
        
        // 获取 UI 配置
        this.uiConfig = soul.getRuntime().getConfig().getShellUI();
        
        // 初始化主题
        String themeName = uiConfig.getThemeName();
        if (themeName != null && !themeName.isEmpty()) {
            this.theme = ThemeConfig.getPresetTheme(themeName);
        } else {
            this.theme = uiConfig.getTheme();
        }
        if (this.theme == null) {
            this.theme = ThemeConfig.defaultTheme();
        }
        
        // 初始化旋转动画相关
        this.showSpinner = new AtomicBoolean(false);
        this.spinnerMessage = new AtomicReference<>("");
        
        // 初始化 Token 统计
        this.currentStepTokens = new AtomicInteger(0);
        this.lastTotalTokens = 0;
        
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
        
        // 获取 Hook 注册表
        this.hookRegistry = applicationContext.getBean(HookRegistry.class);

        // 获取工作目录
        Path workingDir = soul.getRuntime().getSession().getWorkDir();

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

        // 初始化输入处理器
        this.inputProcessors = new ArrayList<>();
        registerInputProcessors();

        // 订阅 Wire 消息
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
        Wire wire = soul.getWire();
        wireSubscription = wire.asFlux()
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(this::handleWireMessage);
    }

    /**
     * 处理 Wire 消息
     */
    private void handleWireMessage(WireMessage message) {
        try {
            if (message instanceof StepBegin stepBegin) {
                // 重置当前步骤的Token计数
                currentStepTokens.set(0);
                
                // 显示主Agent和subAgent的步骤，但用不同的格式区分
                if (stepBegin.isSubagent()) {
                    // subAgent的步骤 - 显示缩进和Agent名称
                    String agentName = stepBegin.getAgentName() != null ? stepBegin.getAgentName() : "subagent";
                    printStatus("  🤖 [" + agentName + "] Step " + stepBegin.getStepNumber() + " - Thinking...");
                } else {
                    // 主 Agent 的步骤
                    currentStatus.set("thinking (step " + stepBegin.getStepNumber() + ")");
                    String statusMsg = "🧠 Step " + stepBegin.getStepNumber() + " - Thinking...";
                    printStatus(statusMsg);
                    
                    // 启动旋转动画（如果配置了）
                    if (uiConfig.isShowSpinner()) {
                        startSpinner("正在思考...");
                    }
                    
                    // 首次思考时显示提示
                    if (stepBegin.getStepNumber() == 1) {
                        showShortcutsHint("thinking");
                    }
                    
                    // 重置输出标志和行长度
                    assistantOutputStarted.set(false);
                    currentLineLength.set(0);
                    isInReasoningMode.set(false); // 重置推理模式
                }

            } else if (message instanceof StepInterrupted) {
                currentStatus.set("interrupted");
                activeTools.clear();
                // 如果有输出，添加换行
                if (assistantOutputStarted.getAndSet(false)) {
                    terminal.writer().println();
                    terminal.flush();
                }
                printError("⚠️  Step interrupted");
                
                // 显示错误提示
                showShortcutsHint("error");

            } else if (message instanceof CompactionBegin) {
                currentStatus.set("compacting");
                printStatus("🗜️  Compacting context...");

            } else if (message instanceof CompactionEnd) {
                currentStatus.set("ready");
                printSuccess("✅ Context compacted");

            } else if (message instanceof StatusUpdate statusUpdate) {
                Map<String, Object> statusMap = statusUpdate.getStatus();
                String status = statusMap.getOrDefault("status", "unknown").toString();
                currentStatus.set(status);

            } else if (message instanceof ContentPartMessage contentMsg) {
                // 停止旋转动画（LLM开始输出内容）
                if (uiConfig.isShowSpinner()) {
                    stopSpinner();
                }
                
                // 打印 LLM 输出的内容部分
                ContentPart part = contentMsg.getContentPart();
                if (part instanceof TextPart textPart) {
                    // 根据内容类型使用不同的显示样式
                    boolean isReasoning = contentMsg.getContentType() == ContentPartMessage.ContentType.REASONING;
                    printAssistantText(textPart.getText(), isReasoning);
                }

            } else if (message instanceof ToolCallMessage toolCallMsg) {
                // 停止旋转动画
                if (uiConfig.isShowSpinner()) {
                    stopSpinner();
                }
                
                // 工具调用开始 - 如果有输出，先添加换行
                if (assistantOutputStarted.getAndSet(false)) {
                    terminal.writer().println();
                    terminal.flush();
                }
                
                ToolCall toolCall = toolCallMsg.getToolCall();
                String toolName = toolCall.getFunction().getName();
                activeTools.put(toolCall.getId(), toolName);

                // 根据配置的显示模式显示工具调用
                String displayMode = uiConfig.getToolDisplayMode();
                if ("minimal".equals(displayMode)) {
                    // 最小模式：只显示工具名
                    printStatus("🔧 " + toolName);
                } else if ("compact".equals(displayMode)) {
                    // 紧凑模式：显示工具名 + 截断的参数
                    String args = toolCall.getFunction().getArguments();
                    int truncateLen = uiConfig.getToolArgsTruncateLength();
                    if (args != null && args.length() > truncateLen) {
                        args = args.substring(0, truncateLen) + "...";
                    }
                    printStatus("🔧 " + toolName + " | " + (args != null ? args : ""));
                } else {
                    // 完整模式：使用工具可视化
                    toolVisualization.onToolCallStart(toolCall);
                }

            } else if (message instanceof ToolResultMessage toolResultMsg) {
                // 工具执行结果
                String toolCallId = toolResultMsg.getToolCallId();
                ToolResult result = toolResultMsg.getToolResult();

                // 根据显示模式显示结果
                String displayMode = uiConfig.getToolDisplayMode();
                if ("minimal".equals(displayMode)) {
                    // 最小模式：只显示完成状态
                    if (result.isOk()) {
                        printSuccess("✅ " + activeTools.get(toolCallId));
                    } else {
                        printError("❌ " + activeTools.get(toolCallId));
                    }
                } else if ("compact".equals(displayMode)) {
                    // 紧凑模式：显示结果摘要
                    String resultPreview = result.isOk() ? "✅ 成功" : "❌ 失败: " + result.getMessage();
                    printInfo("  → " + resultPreview);
                } else {
                    // 完整模式：使用工具可视化
                    toolVisualization.onToolCallComplete(toolCallId, result);
                }

                activeTools.remove(toolCallId);
            } else if (message instanceof TokenUsageMessage tokenUsageMsg) {
                // 显示Token消耗统计
                showTokenUsage(tokenUsageMsg.getUsage());
            } else if (message instanceof ApprovalRequest approvalRequest) {
                // 处理审批请求
                log.info("[ShellUI] Received ApprovalRequest: action={}, description={}", 
                        approvalRequest.getAction(), approvalRequest.getDescription());
                handleApprovalRequest(approvalRequest);
            } else if (message instanceof HumanInputRequest humanInputRequest) {
                // 处理人工交互请求
                log.info("[ShellUI] Received HumanInputRequest: type={}, question={}",
                        humanInputRequest.getInputType(), truncateForLog(humanInputRequest.getQuestion()));
                handleHumanInputRequest(humanInputRequest);
            } else if (message instanceof AsyncSubagentStarted asyncStarted) {
                // 异步子代理启动
                handleAsyncSubagentStarted(asyncStarted);
            } else if (message instanceof AsyncSubagentProgress asyncProgress) {
                // 异步子代理进度
                handleAsyncSubagentProgress(asyncProgress);
            } else if (message instanceof AsyncSubagentCompleted asyncCompleted) {
                // 异步子代理完成
                handleAsyncSubagentCompleted(asyncCompleted);
            } else if (message instanceof AsyncSubagentTrigger asyncTrigger) {
                // 异步子代理触发（Watch 模式）
                handleAsyncSubagentTrigger(asyncTrigger);
            }
        } catch (Exception e) {
            log.error("Error handling wire message", e);
        }
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
            Path workDir = soul.getRuntime().getWorkDir();
            String agentName = soul.getAgent() != null ? soul.getAgent().getName() : null;

            HookContext hookContext = HookContext.builder()
                    .hookType(hookType)
                    .workDir(workDir)
                    .agentName(agentName)
                    .build();

            hookRegistry.trigger(hookType, hookContext)
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
            String prompt = buildPrompt();
            return lineReader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw e;
        } catch (EndOfFileException e) {
            return null;
        }
    }

    /**
     * 构建提示符
     */
    private String buildPrompt() {
        String promptStyle = uiConfig.getPromptStyle();
        
        switch (promptStyle) {
            case "simple":
                return buildSimplePrompt();
            case "rich":
                return buildRichPrompt();
            default:
                return buildNormalPrompt();
        }
    }
    
    /**
     * 构建简洁提示符（只有图标和名称）
     */
    private String buildSimplePrompt() {
        String status = currentStatus.get();
        AttributedStyle style = getStyleForStatus(status);
        String icon = getIconForStatus(status);
        
        String promptText = icon + " jimi> ";
        return new AttributedString(promptText, style).toAnsi();
    }
    
    /**
     * 构建标准提示符（图标、名称和状态）
     */
    private String buildNormalPrompt() {
        String status = currentStatus.get();
        AttributedStyle style = getStyleForStatus(status);
        String icon = getIconForStatus(status);
        
        StringBuilder promptText = new StringBuilder();
        promptText.append(icon).append(" jimi");
        
        // 添加状态提示
        if (status.startsWith("thinking")) {
//            promptText.append("[🧠]");
        } else if (status.equals("compacting")) {
            promptText.append("[🗂️]");
        }
        
        promptText.append("> ");
        return new AttributedString(promptText.toString(), style).toAnsi();
    }
    
    /**
     * 构建丰富提示符（图标、名称、状态、上下文统计）
     */
    private String buildRichPrompt() {
        String status = currentStatus.get();
        AttributedStyle style = getStyleForStatus(status);
        String icon = getIconForStatus(status);
        
        StringBuilder promptText = new StringBuilder();
        
        // 调试日志
        log.debug("Building rich prompt - showTime: {}, showStats: {}", 
            uiConfig.isShowTimeInPrompt(), uiConfig.isShowContextStats());
        
        // 时间（如果启用）
        if (uiConfig.isShowTimeInPrompt()) {
            String time = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            );
            promptText.append("[").append(time).append("] ");
            log.debug("Added time to prompt: {}", time);
        }
        
        // 图标和名称
        promptText.append(icon).append(" jimi");
        
        // 状态标签
        if (status.startsWith("thinking")) {
            promptText.append("[🧠]");
        } else if (status.equals("compacting")) {
            promptText.append("[🗂️]");
        } else if (status.equals("interrupted")) {
            promptText.append("[⚠️]");
        } else if (status.equals("error")) {
            promptText.append("[❌]");
        }
        
        // 上下文统计（如果启用）
        if (uiConfig.isShowContextStats()) {
            try {
                int messageCount = soul.getContext().getHistory().size();
                int tokenCount = soul.getContext().getTokenCount();
                
                log.debug("Context stats - messages: {}, tokens: {}", messageCount, tokenCount);
                
                promptText.append(" [");
                promptText.append("💬").append(messageCount);
                
                if (tokenCount > 0) {
                    promptText.append(" 💡");
                    // Token数格式化（K为单位）
                    if (tokenCount >= 1000) {
                        promptText.append(String.format("%.1fK", tokenCount / 1000.0));
                    } else {
                        promptText.append(tokenCount);
                    }
                }
                
                promptText.append("]");
            } catch (Exception e) {
                // 忽略错误，不显示统计
                log.warn("Failed to get context stats", e);
            }
        }
        
        promptText.append("> ");
        String result = new AttributedString(promptText.toString(), style).toAnsi();
        log.debug("Final prompt text: {}", promptText.toString());
        return result;
    }
    
    /**
     * 根据状态获取图标
     */
    private String getIconForStatus(String status) {
        if (status.startsWith("thinking")) {
            return "🧠";
        }
        
        switch (status) {
            case "compacting":
                return "🗂️";
            case "interrupted":
                return "⚠️";
            case "error":
                return "❌";
            case "ready":
            default:
                return "✨";
        }
    }
    
    /**
     * 根据状态获取样式
     */
    private AttributedStyle getStyleForStatus(String status) {
        if (status.startsWith("thinking")) {
            AttributedStyle style = ColorMapper.createStyle(theme.getThinkingColor());
            return theme.isBoldPrompt() ? style.bold() : style;
        }
        
        switch (status) {
            case "compacting":
                return ColorMapper.createStyle(theme.getStatusColor());
            case "interrupted":
            case "error":
                return ColorMapper.createStyle(theme.getErrorColor());
            case "ready":
            default:
                AttributedStyle readyStyle = ColorMapper.createStyle(theme.getPromptColor());
                return theme.isBoldPrompt() ? readyStyle.bold() : readyStyle;
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
                .soul(soul)
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
     * 打印助手文本输出（流式，带智能换行）
     * 
     * @param text 要打印的文本
     * @param isReasoning 是否为推理内容（思考过程）
     */
    private void printAssistantText(String text, boolean isReasoning) {
        if (text == null || text.isEmpty()) {
            return;
        }
        
        // 防止输出字符串 "null"
        if ("null".equals(text)) {
            log.warn("Received 'null' string as content, ignoring");
            return;
        }
        
        // 标记输出已开始
        if (!assistantOutputStarted.getAndSet(true)) {
            // 第一次输出，添加提示
            terminal.writer().println();
            terminal.flush();
            currentLineLength.set(0);
        }
        
        // 检查是否需要切换模式
        boolean wasInReasoningMode = isInReasoningMode.get();
        if (isReasoning != wasInReasoningMode) {
            // 模式切换，添加标记
            if (currentLineLength.get() > 0) {
                terminal.writer().println();
                currentLineLength.set(0);
            }
            
            if (isReasoning) {
                // 切换到推理模式
                AttributedStyle labelStyle = AttributedStyle.DEFAULT
                        .foreground(AttributedStyle.CYAN)
                        .italic();
                terminal.writer().println(new AttributedString("💡 [思考过程]", labelStyle).toAnsi());
            } else {
                // 切换到正式内容
                terminal.writer().println(); // 空行分隔
                AttributedStyle labelStyle = AttributedStyle.DEFAULT
                        .foreground(AttributedStyle.GREEN)
                        .bold();
                terminal.writer().println(new AttributedString("✅ [正式回答]", labelStyle).toAnsi());
            }
            terminal.flush();
            currentLineLength.set(0);
            isInReasoningMode.set(isReasoning);
        }

        // 获取终端宽度，默认80，减去一些边距
        int terminalWidth = terminal.getWidth();
        int maxLineWidth = terminalWidth > 20 ? terminalWidth - 4 : 76;
        
        // 根据是否为推理内容设置不同的样式
        AttributedStyle style;
        if (isReasoning) {
            // 推理内容：使用主题推理颜色
            style = ColorMapper.createStyle(theme.getReasoningColor());
            if (theme.isItalicReasoning()) {
                style = style.italic();
            }
        } else {
            // 正式内容：使用主题助手颜色
            style = ColorMapper.createStyle(theme.getAssistantColor());
        }
        
        // 逐字符处理，实现智能换行
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            
            // 处理换行符
            if (ch == '\n') {
                terminal.writer().println();
                currentLineLength.set(0);
                continue;
            }
            
            // 检查是否需要自动换行
            int charWidth = isChineseChar(ch) ? 2 : 1; // 中文字符占2个宽度
            if (currentLineLength.get() + charWidth > maxLineWidth) {
                // 如果不是在空格处，尝试找到合适的断点
                if (ch != ' ' && i > 0 && text.charAt(i - 1) != ' ') {
                    // 在中文字符或标点符号后可以直接换行
                    if (isChineseChar(ch) || isChinesePunctuation(ch)) {
                        terminal.writer().println();
                        currentLineLength.set(0);
                    } else {
                        // 英文单词中间，先换行再输出
                        terminal.writer().println();
                        currentLineLength.set(0);
                    }
                } else {
                    terminal.writer().println();
                    currentLineLength.set(0);
                    // 跳过行首空格
                    if (ch == ' ') {
                        continue;
                    }
                }
            }
            
            // 输出字符
            terminal.writer().print(new AttributedString(String.valueOf(ch), style).toAnsi());
            currentLineLength.addAndGet(charWidth);
        }
        
        terminal.flush();
    }
    
    /**
     * 判断是否为中文字符
     */
    private boolean isChineseChar(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FA5;
    }
    
    /**
     * 判断是否为中文标点符号
     */
    private boolean isChinesePunctuation(char ch) {
        return (ch >= 0x3000 && ch <= 0x303F) || // CJK符号和标点
               (ch >= 0xFF00 && ch <= 0xFFEF);   // 全角ASCII、全角标点
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
     * 处理审批请求（在 Wire 订阅线程中调用）
     * 直接在当前线程处理，不再使用队列
     */
    private void handleApprovalRequest(ApprovalRequest request) {
        try {
            log.info("[ShellUI] Processing approval request for action: {}", request.getAction());
            
            // 如果有助手输出，先换行
            if (assistantOutputStarted.getAndSet(false)) {
                terminal.writer().println();
                terminal.flush();
            }

            // 打印审批请求
            terminal.writer().println();
            terminal.flush();
            outputFormatter.printStatus("\u26a0\ufe0f  需要审批:");
            outputFormatter.printInfo("  操作类型: " + request.getAction());
            outputFormatter.printInfo("  操作描述: " + request.getDescription());
            terminal.writer().println();
            terminal.flush();

            // 读取用户输入 - 直接在当前线程读取
            String prompt = new AttributedString("\u2753 是否批准？[y/n/a] (y=批准, n=拒绝, a=本次会话全部批准): ",
                    AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                    .toAnsi();

            String response = lineReader.readLine(prompt).trim().toLowerCase();

            // 解析响应
            ApprovalResponse approvalResponse;
            switch (response) {
                case "y":
                case "yes":
                    approvalResponse = ApprovalResponse.APPROVE;
                    outputFormatter.printSuccess("\u2705 已批准");
                    break;
                case "a":
                case "all":
                    approvalResponse = ApprovalResponse.APPROVE_FOR_SESSION;
                    outputFormatter.printSuccess("\u2705 已批准（本次会话全部同类操作）");
                    break;
                case "n":
                case "no":
                default:
                    approvalResponse = ApprovalResponse.REJECT;
                    outputFormatter.printError("\u274c 已拒绝");
                    break;
            }

            terminal.writer().println();
            terminal.flush();

            // 发送响应
            request.resolve(approvalResponse);
            
            log.info("[ShellUI] Approval request resolved: {}", approvalResponse);

        } catch (UserInterruptException e) {
            // 用户按 Ctrl-C，默认拒绝
            log.info("Approval interrupted by user");
            outputFormatter.printError("\u274c 审批已取消");
            request.resolve(ApprovalResponse.REJECT);
        } catch (Exception e) {
            log.error("Error handling approval request", e);
            // 发生错误时默认拒绝
            request.resolve(ApprovalResponse.REJECT);
        }
    }

    /**
     * 在主线程中处理审批请求
     * 显示审批提示并等待用户输入
     */
    private void handleApprovalRequestInMainThread(ApprovalRequest request) {
        try {
            // 如果有助手输出，先换行
            if (assistantOutputStarted.getAndSet(false)) {
                terminal.writer().println();
                terminal.flush();
            }

            // 打印审批请求
            outputFormatter.println("");
            outputFormatter.printStatus("\u26a0\ufe0f  需要审批:");
            outputFormatter.printInfo("  操作类型: " + request.getAction());
            outputFormatter.printInfo("  操作描述: " + request.getDescription());
            outputFormatter.println("");

            // 读取用户输入
            String prompt = new AttributedString("\u2753 是否批准？[y/n/a] (y=批准, n=拒绝, a=本次会话全部批准): ",
                    AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                    .toAnsi();

            String response = lineReader.readLine(prompt).trim().toLowerCase();

            // 解析响应
            ApprovalResponse approvalResponse;
            switch (response) {
                case "y":
                case "yes":
                    approvalResponse = ApprovalResponse.APPROVE;
                    outputFormatter.printSuccess("\u2705 已批准");
                    break;
                case "a":
                case "all":
                    approvalResponse = ApprovalResponse.APPROVE_FOR_SESSION;
                    outputFormatter.printSuccess("\u2705 已批准（本次会话全部同类操作）");
                    break;
                case "n":
                case "no":
                default:
                    approvalResponse = ApprovalResponse.REJECT;
                    outputFormatter.printError("\u274c 已拒绝");
                    break;
            }

            outputFormatter.println("");

            // 发送响应
            request.resolve(approvalResponse);

        } catch (UserInterruptException e) {
            // 用户按 Ctrl-C，默认拒绝
            log.info("Approval interrupted by user");
            outputFormatter.printError("\u274c 审批已取消");
            request.resolve(ApprovalResponse.REJECT);
        } catch (Exception e) {
            log.error("Error handling approval request", e);
            // 发生错误时默认拒绝
            request.resolve(ApprovalResponse.REJECT);
        }
    }

    /**
     * 处理人工交互请求
     * 显示问题并等待用户输入
     */
    private void handleHumanInputRequest(HumanInputRequest request) {
        try {
            // 如果有助手输出，先换行
            if (assistantOutputStarted.getAndSet(false)) {
                terminal.writer().println();
                terminal.flush();
            }

            // 打印问题
            terminal.writer().println();
            outputFormatter.printStatus("\ud83e\udd14 Agent 需要您的反馈:");
            outputFormatter.printInfo(request.getQuestion());
            terminal.writer().println();
            terminal.flush();

            HumanInputResponse response;

            switch (request.getInputType()) {
                case CONFIRM -> {
                    // 确认型：满意/需要修改/拒绝
                    String prompt = new AttributedString(
                            "\u2753 请选择 [y=满意继续 / m=需要修改 / n=拒绝]: ",
                            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                            .toAnsi();
                    String input = lineReader.readLine(prompt).trim().toLowerCase();

                    response = switch (input) {
                        case "y", "yes", "满意" -> {
                            outputFormatter.printSuccess("\u2705 已确认");
                            yield HumanInputResponse.approved();
                        }
                        case "m", "modify", "修改" -> {
                            String modificationPrompt = new AttributedString(
                                    "\ud83d\udcdd 请输入修改意见: ",
                                    AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                                    .toAnsi();
                            String modification = lineReader.readLine(modificationPrompt);
                            outputFormatter.printInfo("\ud83d\udcac 已记录修改意见");
                            yield HumanInputResponse.needsModification(modification);
                        }
                        default -> {
                            outputFormatter.printError("\u274c 已拒绝");
                            yield HumanInputResponse.rejected();
                        }
                    };
                }
                case FREE_INPUT -> {
                    // 自由输入型
                    String defaultHint = request.getDefaultValue() != null
                            ? " (默认: " + request.getDefaultValue() + ")"
                            : "";
                    String prompt = new AttributedString(
                            "\ud83d\udcdd 请输入" + defaultHint + ": ",
                            AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                            .toAnsi();
                    String input = lineReader.readLine(prompt).trim();
                    if (input.isEmpty() && request.getDefaultValue() != null) {
                        input = request.getDefaultValue();
                    }
                    outputFormatter.printInfo("\u2705 已记录输入");
                    response = HumanInputResponse.inputProvided(input);
                }
                case CHOICE -> {
                    // 选择型
                    List<String> choices = request.getChoices();
                    if (choices != null && !choices.isEmpty()) {
                        outputFormatter.printInfo("请从以下选项中选择:");
                        for (int i = 0; i < choices.size(); i++) {
                            outputFormatter.printInfo("  " + (i + 1) + ". " + choices.get(i));
                        }
                        String prompt = new AttributedString(
                                "\ud83d\udc49 请输入选项序号: ",
                                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                                .toAnsi();
                        String input = lineReader.readLine(prompt).trim();
                        try {
                            int index = Integer.parseInt(input) - 1;
                            if (index >= 0 && index < choices.size()) {
                                String selected = choices.get(index);
                                outputFormatter.printSuccess("\u2705 已选择: " + selected);
                                response = HumanInputResponse.inputProvided(selected);
                            } else {
                                outputFormatter.printError("\u274c 无效的选项序号");
                                response = HumanInputResponse.rejected();
                            }
                        } catch (NumberFormatException e) {
                            outputFormatter.printError("\u274c 请输入有效的序号");
                            response = HumanInputResponse.rejected();
                        }
                    } else {
                        outputFormatter.printError("\u274c 没有可用的选项");
                        response = HumanInputResponse.rejected();
                    }
                }
                default -> {
                    outputFormatter.printError("\u274c 未知的输入类型");
                    response = HumanInputResponse.rejected();
                }
            }

            terminal.writer().println();
            terminal.flush();

            // 发送响应
            request.resolve(response);

            log.info("[ShellUI] Human input request resolved: {}", response.getStatus());

        } catch (UserInterruptException e) {
            // 用户按 Ctrl-C，默认拒绝
            log.info("Human input interrupted by user");
            outputFormatter.printError("\u274c 交互已取消");
            request.resolve(HumanInputResponse.rejected());
        } catch (Exception e) {
            log.error("Error handling human input request", e);
            request.resolve(HumanInputResponse.rejected());
        }
    }

    /**
     * 截断日志输出
     */
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
    
    /**
     * 根据步骤状态字符串返回对应的图标
     */
    private String getStatusIcon(String status) {
        return switch (status) {
            case "PENDING" -> "⏳";
            case "IN_PROGRESS", "EXECUTING" -> "🔄";
            case "DONE", "COMPLETED" -> "✅";
            case "SKIPPED" -> "⏭️";
            case "FAILED" -> "❌";
            default -> "🟠";
        };
    }
    
    /**
     * 启动旋转动画
     */
    private void startSpinner(String message) {
        if (spinnerThread != null && spinnerThread.isAlive()) {
            // 已经有动画在运行
            return;
        }
        
        showSpinner.set(true);
        spinnerMessage.set(message);
        
        spinnerThread = new Thread(() -> {
            String[] frames = getSpinnerFrames();
            int i = 0;
            
            try {
                // 先输出一条空行
                terminal.writer().println();
                
                while (showSpinner.get()) {
                    // 清除当前行并显示动画
                    terminal.writer().print("\r" + frames[i % frames.length] + " " + spinnerMessage.get() + "   ");
                    terminal.flush();
                    
                    i++;
                    Thread.sleep(uiConfig.getSpinnerIntervalMs());
                }
                
                // 清除旋转动画行
                terminal.writer().print("\r" + " ".repeat(50) + "\r");
                terminal.flush();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }
    
    /**
     * 停止旋转动画
     */
    private void stopSpinner() {
        showSpinner.set(false);
        if (spinnerThread != null) {
            try {
                spinnerThread.join(500); // 等待最多500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinnerThread = null;
        }
    }
    
    /**
     * 获取旋转动画帧
     */
    private String[] getSpinnerFrames() {
        return switch (uiConfig.getSpinnerType()) {
            case "arrows" -> new String[]{"←", "↖", "↑", "↗", "→", "↘", "↓", "↙"};
            case "circles" -> new String[]{"◐", "◓", "◑", "◒"};
            default -> new String[]{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}; // dots
        };
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
        currentStepTokens.set(stepTokens);
        
        // 构建显示消息
        StringBuilder msg = new StringBuilder();
        msg.append("\n📊 Token: ");
        msg.append("本次 ").append(usage.getPromptTokens()).append("+").append(usage.getCompletionTokens());
        msg.append(" = ").append(stepTokens);
        
        // 如果有上下文Token总数，显示累计
        try {
            int totalTokens = soul.getContext().getTokenCount();
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
        String frequency = uiConfig.getShortcutsHintFrequency();
        
        switch (frequency) {
            case "first_time":
                // 仅首次显示
                if (!shouldShowFirstTimeHint(hintType)) {
                    return;
                }
                break;
            case "periodic":
                // 定期显示
                int count = interactionCount.get();
                int interval = uiConfig.getShortcutsHintInterval();
                if (count % interval != 0) {
                    return;
                }
                break;
            case "always":
            default:
                // 总是显示
                break;
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
        switch (hintType) {
            case "welcome":
                return welcomeHintShown.compareAndSet(false, true);
            case "input":
                return inputHintShown.compareAndSet(false, true);
            case "thinking":
                return thinkingHintShown.compareAndSet(false, true);
            default:
                return true; // 其他类型总是显示
        }
    }
    
    /**
     * 根据类型获取提示内容
     */
    private String getHintForType(String hintType) {
        switch (hintType) {
//            case "welcome":
//                return "💡 快捷键: /help (帮助) | /status (状态) | /history (历史) | Ctrl+C (中断) | Ctrl+D (退出)";
//
//            case "input":
//                return "💡 提示: 输入 /help 查看所有命令 | Tab 键自动补全 | ↑↓ 箭头浏览历史";
//
//            case "thinking":
//                return "💡 提示: 按 Ctrl+C 可中断当前操作";
            
            case "error":
                return "💡 提示: /reset 清空上下文 | /status 查看状态 | /history 查看历史";
            
            case "approval":
                return "💡 快捷键: y (批准) | n (拒绝) | a (全部批准)";
            
            default:
                return null;
        }
    }

    // ==================== 异步子代理消息处理 ====================

    /**
     * 处理异步子代理启动消息
     */
    private void handleAsyncSubagentStarted(AsyncSubagentStarted message) {
        // 如果有助手输出，先换行
        if (assistantOutputStarted.getAndSet(false)) {
            terminal.writer().println();
            terminal.flush();
        }
        
        terminal.writer().println();
        outputFormatter.printStatus(String.format(
                "🚀 异步子代理已启动: [%s] %s",
                message.getSubagentId(),
                message.getSubagentName()
        ));
        outputFormatter.printInfo(String.format(
                "   模式: %s | 使用 /async status %s 查看状态",
                message.getMode(),
                message.getSubagentId()
        ));
        terminal.writer().println();
        terminal.flush();
    }

    /**
     * 处理异步子代理进度消息
     */
    private void handleAsyncSubagentProgress(AsyncSubagentProgress message) {
        // 进度消息通常较频繁，使用简洁格式
        log.debug("Async subagent {} progress: {} (step {})",
                message.getSubagentId(),
                message.getProgressInfo(),
                message.getStepNumber());
        
        // 只在 debug 模式或特定配置下显示进度
        // 避免过多刷新干扰用户
    }

    /**
     * 处理异步子代理完成消息
     */
    private void handleAsyncSubagentCompleted(AsyncSubagentCompleted message) {
        // 如果有助手输出，先换行
        if (assistantOutputStarted.getAndSet(false)) {
            terminal.writer().println();
            terminal.flush();
        }
        
        terminal.writer().println();
        
        // 格式化运行时长
        String durationStr = formatDuration(message.getDuration());
        
        if (message.isSuccess()) {
            outputFormatter.printSuccess(String.format(
                    "✅ 异步子代理完成: [%s] (%s)",
                    message.getSubagentId(),
                    durationStr
            ));
            // 显示结果摘要（截断过长内容）
            String result = message.getResult();
            if (result != null && !result.isEmpty()) {
                String preview = result.length() > 100 
                        ? result.substring(0, 100) + "..." 
                        : result;
                outputFormatter.printInfo("   结果: " + preview.replace("\n", " "));
            }
        } else {
            outputFormatter.printError(String.format(
                    "❌ 异步子代理失败: [%s] (%s)",
                    message.getSubagentId(),
                    durationStr
            ));
            if (message.getResult() != null) {
                outputFormatter.printError("   错误: " + message.getResult());
            }
        }
        
        outputFormatter.printInfo(String.format(
                "   使用 /async result %s 查看完整结果",
                message.getSubagentId()
        ));
        terminal.writer().println();
        terminal.flush();
        
        // 发送桌面通知
        notificationService.notifyAsyncComplete(
                message.getSubagentId(),
                message.getResult(),
                message.isSuccess(),
                uiConfig
        );
        
        // 播放终端提示音（如果启用）
        if (uiConfig.isEnableNotificationSound()) {
            try {
                terminal.writer().write('\007'); // Bell
                terminal.flush();
            } catch (Exception e) {
                // 忽略提示音错误
            }
        }
    }

    /**
     * 处理异步子代理触发消息（Watch 模式）
     */
    private void handleAsyncSubagentTrigger(AsyncSubagentTrigger message) {
        // 如果有助手输出，先换行
        if (assistantOutputStarted.getAndSet(false)) {
            terminal.writer().println();
            terminal.flush();
        }
        
        terminal.writer().println();
        
        // 用釒铃图标表示触发警报
        outputFormatter.printWarning(String.format(
                "🔔 监控触发: [%s]",
                message.getSubagentId()
        ));
        
        // 显示匹配模式
        outputFormatter.printInfo(String.format(
                "   匹配模式: %s",
                message.getMatchedPattern()
        ));
        
        // 显示匹配内容（截断过长内容）
        String content = message.getMatchedContent();
        if (content != null && !content.isEmpty()) {
            String preview = content.length() > 150 
                    ? content.substring(0, 150) + "..." 
                    : content;
            outputFormatter.printInfo("   触发内容: " + preview.replace("\n", " "));
        }
        
        // 显示触发时间
        if (message.getTriggerTime() != null) {
            String timeStr = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(message.getTriggerTime());
            outputFormatter.printInfo("   触发时间: " + timeStr);
        }
        
        terminal.writer().println();
        terminal.flush();
        
        // 发送桌面通知
        notificationService.notifyWatchTrigger(
                message.getSubagentId(),
                message.getMatchedPattern(),
                message.getMatchedContent(),
                uiConfig
        );
        
        // 播放系统提示音（如果启用）
        if (uiConfig.isEnableNotificationSound()) {
            try {
                terminal.writer().write('\007'); // Bell
                terminal.flush();
            } catch (Exception e) {
                // 忽略提示音错误
            }
        }
    }

    /**
     * 格式化时长
     */
    private String formatDuration(java.time.Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return String.format("%dm%ds", seconds / 60, seconds % 60);
        } else {
            return String.format("%dh%dm%ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
    }

    /**
     * 更新主题（运行时切换）
     */
    public void updateTheme() {
        // 从配置中重新加载主题
        String themeName = uiConfig.getThemeName();
        if (themeName != null && !themeName.isEmpty()) {
            this.theme = ThemeConfig.getPresetTheme(themeName);
        } else {
            this.theme = uiConfig.getTheme();
        }
        if (this.theme == null) {
            this.theme = ThemeConfig.defaultTheme();
        }
        
        // 更新OutputFormatter的主题
        this.outputFormatter.setTheme(this.theme);
    }

    @Override
    public void close() throws Exception {
        if (wireSubscription != null) {
            wireSubscription.dispose();
        }
        if (terminal != null) {
            terminal.close();
        }
    }
}
