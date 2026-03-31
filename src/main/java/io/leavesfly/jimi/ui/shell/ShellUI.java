package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.command.CommandRegistry;
import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.ui.notification.NotificationService;
import io.leavesfly.jimi.ui.shell.handler.InteractionHandler;
import io.leavesfly.jimi.ui.shell.handler.WireMessageHandler;
import io.leavesfly.jimi.ui.shell.input.AgentCommandProcessor;
import io.leavesfly.jimi.ui.shell.input.InputProcessor;
import io.leavesfly.jimi.ui.shell.input.MetaCommandProcessor;
import io.leavesfly.jimi.ui.shell.input.ShellShortcutProcessor;
import io.leavesfly.jimi.ui.shell.jline.JimiCompleter;
import io.leavesfly.jimi.ui.shell.jline.JimiHighlighter;
import io.leavesfly.jimi.ui.shell.jline.JimiParser;
import io.leavesfly.jimi.ui.shell.output.AssistantTextRenderer;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.ui.shell.output.SpinnerManager;
import io.leavesfly.jimi.ui.shell.output.ToolVisualization;
import io.leavesfly.jimi.ui.shell.output.WelcomeRenderer;
import io.leavesfly.jimi.ui.shell.style.PromptBuilder;
import io.leavesfly.jimi.client.EngineClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.context.ApplicationContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell UI - 基于 JLine 的交互式命令行界面
 * 提供富文本显示、命令历史、自动补全等功能
 * <p>
 * 采用插件化架构：
 * - CommandHandler: 元命令处理器
 * - InputProcessor: 输入处理器
 * - CommandRegistry: 命令注册表
 * - WireMessageHandler: Wire消息处理器
 * - WelcomeRenderer: 欢迎信息渲染器
 */
@Slf4j
public class ShellUI implements AutoCloseable {

    private final Terminal terminal;
    private final LineReader lineReader;
    private final EngineClient engineClient;
    private final AtomicBoolean running;
    private final AtomicReference<String> currentStatus;
    private Disposable wireSubscription;

    // Shell UI 配置
    private final ShellUIConfig uiConfig;

    // 主题配置
    private ThemeConfig theme;

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
    private final WireMessageHandler wireMessageHandler;
    private final WelcomeRenderer welcomeRenderer;
    private final ToolVisualization toolVisualization;

    /**
     * 创建 Shell UI
     *
     * @param engineClient       EngineClient 实例
     * @param applicationContext Spring 应用上下文（用于获取 CommandRegistry）
     * @throws IOException 终端初始化失败
     */
    public ShellUI(EngineClient engineClient, ApplicationContext applicationContext) throws IOException {
        this.engineClient = engineClient;
        this.running = new AtomicBoolean(false);
        this.currentStatus = new AtomicReference<>("ready");

        // 初始化阶段：直接从EngineClient获取配置（无需消息交互）
        this.uiConfig = engineClient.getShellUIConfig();

        // 初始化主题
        this.theme = engineClient.getThemeConfig();

        // 初始化 Terminal
        this.terminal = TerminalBuilder.builder().system(true).encoding("UTF-8").build();

        // 从 Spring 容器获取 CommandRegistry（已自动注册所有命令）
        this.commandRegistry = applicationContext.getBean(CommandRegistry.class);
        log.info("Loaded CommandRegistry with {} commands from Spring context", commandRegistry.size());

        // 获取通知服务
        this.notificationService = applicationContext.getBean(NotificationService.class);

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
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.AUTO_MENU, true)
                .option(LineReader.Option.AUTO_MENU_LIST, true)
                .option(LineReader.Option.INSERT_TAB, false)
                // 其他有用的补全选项
                .option(LineReader.Option.COMPLETE_IN_WORD, true)
                .option(LineReader.Option.CASE_INSENSITIVE, true)
                .build();

        // 初始化输出格式化器
        this.outputFormatter = new OutputFormatter(terminal, theme);

        // 初始化委托组件
        this.renderer = new AssistantTextRenderer(terminal, theme);
        this.spinnerManager = new SpinnerManager(terminal, uiConfig);
        this.promptBuilder = new PromptBuilder(currentStatus, uiConfig, theme, engineClient);
        this.toolVisualization = new ToolVisualization();
        this.welcomeRenderer = new WelcomeRenderer(terminal, outputFormatter, uiConfig, theme, engineClient);

        // 初始化交互处理器和消息处理器
        InteractionHandler interactionHandler = new InteractionHandler(terminal, outputFormatter, lineReader, renderer);
        this.wireMessageHandler = new WireMessageHandler(
                outputFormatter,
                spinnerManager,
                renderer,
                interactionHandler,
                toolVisualization,
                uiConfig,
                currentStatus
        );

        // 设置回调
        wireMessageHandler.setTokenUsageCallback(msg -> welcomeRenderer.showTokenUsage(msg.getUsage()));
        wireMessageHandler.setShortcutsHintCallback(welcomeRenderer::showShortcutsHint);

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
                .publishOn(Schedulers.boundedElastic())
                .subscribe(wireMessageHandler::handle);
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
            welcomeRenderer.printWelcome();

            // 主循环
            while (running.get()) {
                try {
                    // 读取用户输入
                    String input = readLine();

                    if (input == null) {
                        // EOF (Ctrl-D)
                        outputFormatter.printInfo("Bye!");
                        break;
                    }

                    // 处理输入
                    if (!processInput(input.trim())) {
                        break;
                    }

                } catch (UserInterruptException e) {
                    // Ctrl-C
                    outputFormatter.printInfo("Tip: press Ctrl-D or type 'exit' to quit");
                } catch (EndOfFileException e) {
                    // EOF
                    outputFormatter.printInfo("Bye!");
                    break;
                } catch (Exception e) {
                    log.error("Error in shell UI", e);
                    outputFormatter.printError("Error: " + e.getMessage());
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
        int count = welcomeRenderer.incrementInteractionCount();

        // 首次输入时显示输入提示
        if (count == 1) {
            welcomeRenderer.showShortcutsHint("input");
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
     * 停止 Shell UI
     */
    public void stop() {
        running.set(false);
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
        this.welcomeRenderer.setTheme(this.theme);
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
