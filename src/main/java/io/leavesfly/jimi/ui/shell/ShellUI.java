package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.command.CommandRegistry;
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
    private final JimiSoul soul;
    private final ToolVisualization toolVisualization;
    private final AtomicBoolean running;
    private final AtomicReference<String> currentStatus;
    private final Map<String, String> activeTools;
    private final AtomicBoolean assistantOutputStarted;
    private final AtomicInteger currentLineLength; // 当前行的字符计数
    private Disposable wireSubscription;

    // 插件化组件
    private final OutputFormatter outputFormatter;
    private final CommandRegistry commandRegistry;
    private final List<InputProcessor> inputProcessors;

    /**
     * 创建 Shell UI
     *
     * @param soul               JimiSoul 实例
     * @param applicationContext Spring 应用上下文（用于获取 CommandRegistry）
     * @throws IOException 终端初始化失败
     */
    public ShellUI(JimiSoul soul, ApplicationContext applicationContext) throws IOException {
        this.soul = soul;
        this.toolVisualization = new ToolVisualization();
        this.running = new AtomicBoolean(false);
        this.currentStatus = new AtomicReference<>("ready");
        this.activeTools = new HashMap<>();
        this.assistantOutputStarted = new AtomicBoolean(false);
        this.currentLineLength = new AtomicInteger(0);

        // 初始化 Terminal
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .encoding("UTF-8")
                .build();

        // 从 Spring 容器获取 CommandRegistry（已自动注册所有命令）
        this.commandRegistry = applicationContext.getBean(CommandRegistry.class);
        log.info("Loaded CommandRegistry with {} commands from Spring context", commandRegistry.size());

        // 获取工作目录
        Path workingDir = soul.getRuntime().getSession().getWorkDir();

        // 初始化 LineReader（使用增强的 JimiCompleter）
        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("Jimi")
                .completer(new JimiCompleter(commandRegistry, workingDir))
                .highlighter(new JimiHighlighter())
                .parser(new JimiParser())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();

        // 初始化输出格式化器
        this.outputFormatter = new OutputFormatter(terminal);

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
     */
    private void subscribeWire() {
        Wire wire = soul.getWire();
        wireSubscription = wire.asFlux()
                .subscribe(this::handleWireMessage);
    }

    /**
     * 处理 Wire 消息
     */
    private void handleWireMessage(WireMessage message) {
        try {
            if (message instanceof StepBegin stepBegin) {
                currentStatus.set("thinking (step " + stepBegin.getStepNumber() + ")");
                printStatus("🤔 Step " + stepBegin.getStepNumber() + " - Thinking...");
                // 重置输出标志和行长度
                assistantOutputStarted.set(false);
                currentLineLength.set(0);

            } else if (message instanceof StepInterrupted) {
                currentStatus.set("interrupted");
                activeTools.clear();
                // 如果有输出，添加换行
                if (assistantOutputStarted.getAndSet(false)) {
                    terminal.writer().println();
                    terminal.flush();
                }
                printError("⚠️  Step interrupted");

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
                // 打印 LLM 输出的内容部分
                ContentPart part = contentMsg.getContentPart();
                if (part instanceof TextPart textPart) {
//                    log.debug("Received content part: [{}]", textPart.getText());
                    printAssistantText(textPart.getText());
                }

            } else if (message instanceof ToolCallMessage toolCallMsg) {
                // 工具调用开始 - 如果有输出，先添加换行
                if (assistantOutputStarted.getAndSet(false)) {
                    terminal.writer().println();
                    terminal.flush();
                }
                
                ToolCall toolCall = toolCallMsg.getToolCall();
                String toolName = toolCall.getFunction().getName();
                activeTools.put(toolCall.getId(), toolName);

                // 使用工具可视化
                toolVisualization.onToolCallStart(toolCall);

            } else if (message instanceof ToolResultMessage toolResultMsg) {
                // 工具执行结果
                String toolCallId = toolResultMsg.getToolCallId();
                ToolResult result = toolResultMsg.getToolResult();

                // 使用工具可视化
                toolVisualization.onToolCallComplete(toolCallId, result);

                activeTools.remove(toolCallId);
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

            return Mono.just(true);
        });
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
        String status = currentStatus.get();
        AttributedStyle style;
        String icon;

        switch (status) {
            case "thinking":
            case "compacting":
                style = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
                icon = "⏳";
                break;
            case "interrupted":
            case "error":
                style = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
                icon = "❌";
                break;
            default:
                style = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
                icon = "✨";
        }

        String promptText = icon + " jimi> ";
        return new AttributedString(promptText, style).toAnsi();
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
     */
    private void printAssistantText(String text) {
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

        // 获取终端宽度，默认80，减去一些边距
        int terminalWidth = terminal.getWidth();
        int maxLineWidth = terminalWidth > 20 ? terminalWidth - 4 : 76;
        
        AttributedStyle style = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);
        
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
        outputFormatter.printSuccess("Welcome to Jimi - Java Implementation of Moonshot Intelligence");
        outputFormatter.printInfo("Type /help for available commands, or just start chatting!");
        outputFormatter.println("");
    }

    /**
     * 打印 Banner
     */
    private void printBanner() {
        String banner = """
                ╔═══════════════════════════════════════╗
                ║         _  _           _              ║
                ║        | |(_)         (_)             ║
                ║        | | _  _ __ ___  _             ║
                ║     _  | || || '_ ` _ \\| |            ║
                ║    | |_| || || | | | | | |            ║
                ║     \\___/ |_||_| |_| |_|_|            ║
                ║                                       ║
                ╚═══════════════════════════════════════╝
                """;

        AttributedStyle style = AttributedStyle.DEFAULT
                .foreground(AttributedStyle.CYAN)
                .bold();

        terminal.writer().println(new AttributedString(banner, style).toAnsi());
        terminal.flush();
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
