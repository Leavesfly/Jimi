package io.leavesfly.jimi.cli.shell;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.message.ContentPart;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.engine.RuntimeConfig;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;
import io.leavesfly.jimi.cli.command.CommandRegistry;
import io.leavesfly.jimi.cli.command.commands.*;
import io.leavesfly.jimi.cli.command.SimpleCommandOutput;
import io.leavesfly.jimi.cli.config.CliConfig;
import io.leavesfly.jimi.cli.notification.NotificationService;
import io.leavesfly.jimi.cli.notification.NotificationType;
import io.leavesfly.jimi.cli.shell.input.AgentCommandProcessor;
import io.leavesfly.jimi.cli.shell.input.InputProcessor;
import io.leavesfly.jimi.cli.shell.input.MetaCommandProcessor;
import io.leavesfly.jimi.cli.shell.input.ShellShortcutProcessor;
import io.leavesfly.jimi.cli.shell.output.OutputFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交互式 Shell 界面
 * <p>
 * 基于 JLine3 实现的命令行交互界面，提供：
 * 1. 多行输入支持
 * 2. 历史记录
 * 3. 流式输出显示
 * 4. 颜色高亮
 * </p>
 *
 * @author Jimi2 Team
 */
public class ShellUI {
    
    private static final Logger log = LoggerFactory.getLogger(ShellUI.class);
    
    /** 当前运行的 Agent */
    private final Agent agent;
    
    /** 工作目录 */
    private final Path workDir;
    
    /** LLM 实例 */
    private final LLM llm;
    
    /** CLI 配置 */
    private final CliConfig cliConfig;
    
    /** 执行引擎 */
    private Engine engine;
    
    /** 消息总线 */
    private final Wire wire;
    
    /** 对话上下文 */
    private Context context;
    
    /** 工具注册表 */
    private ToolRegistry toolRegistry;
    
    /** 命令注册表 */
    private CommandRegistry commandRegistry;
    
    /** Runtime 上下文 */
    private Runtime runtime;
    
    /** JLine 终端 */
    private Terminal terminal;
    
    /** 行读取器 */
    private LineReader lineReader;
    
    /** 是否正在运行 */
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    /** 是否正在执行任务 */
    private final AtomicBoolean executing = new AtomicBoolean(false);
    
    /** 对象映射器 */
    private final ObjectMapper objectMapper;
    
    /** 命令前缀 */
    private static final String COMMAND_PREFIX = "/";
    
    /** 输出格式化器 */
    private OutputFormatter outputFormatter;
    
    /** 通知服务 */
    private NotificationService notificationService;
    
    /** 输入处理器链 */
    private List<InputProcessor> inputProcessors;
    
    /**
     * 构造函数
     *
     * @param agent     要运行的 Agent
     * @param workDir   工作目录
     * @param llm       LLM 实例
     * @param cliConfig CLI 配置
     */
    public ShellUI(Agent agent, Path workDir, LLM llm, CliConfig cliConfig) {
        this.agent = agent;
        this.workDir = workDir;
        this.llm = llm;
        this.cliConfig = cliConfig;
        this.wire = new DefaultWire();
        this.context = new DefaultContext();
        this.objectMapper = new ObjectMapper();
        this.toolRegistry = new DefaultToolRegistry(objectMapper);
    }
    
    /**
     * 运行交互式 Shell
     */
    public void run() {
        try {
            // 初始化终端
            initTerminal();
            
            // 初始化引擎
            initEngine();
            
            // 显示欢迎信息
            printWelcome();
            
            // 订阅消息总线，实时显示输出
            subscribeWire();
            
            // 主循环
            mainLoop();
            
        } catch (Exception e) {
            log.error("Shell 运行异常", e);
            System.err.println("运行错误: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    /**
     * 初始化终端
     */
    private void initTerminal() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .jansi(true)  // 启用 JANSI 跨平台支持
                .build();
        
        // 初始化输出格式化器和通知服务
        outputFormatter = new OutputFormatter(terminal);
        notificationService = new NotificationService();
        
        lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new JimiCompleter(commandRegistry != null ? commandRegistry : new CommandRegistry(), workDir))
                .highlighter(new JimiHighlighter())
                .parser(new JimiParser())
                .variable(LineReader.HISTORY_FILE, workDir.resolve(".jimi/history").toString())
                .build();
    }
    
    /**
     * 初始化引擎
     */
    private void initEngine() {
        // 通过 SPI 加载工具
        AgentSpec agentSpec = AgentSpec.builder()
                .name(agent.getName())
                .build();
        
        java.util.List<Tool<?>> allTools = new java.util.ArrayList<>();
        for (ToolProvider provider : java.util.ServiceLoader.load(ToolProvider.class)) {
            RuntimeConfig tempConfig = RuntimeConfig.builder().workDir(workDir).build();
            Runtime tempRuntime = Runtime.builder().config(tempConfig).build();
            if (provider.supports(agentSpec, tempRuntime)) {
                allTools.addAll(provider.createTools(agentSpec, tempRuntime));
            }
        }
        
        // 注册工具
        allTools.forEach(toolRegistry::register);
        
        // 注册 Agent 自带的工具
        if (agent.getTools() != null) {
            agent.getTools().forEach(toolRegistry::register);
        }
        
        log.info("已加载 {} 个工具", allTools.size());
        
        // 创建 Runtime（使用新风格 API）
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .workDir(workDir)
                .yoloMode(cliConfig.isYoloMode())
                .maxContextTokens(cliConfig.getMaxContextTokens())
                .build();
        
        runtime = Runtime.builder()
                .llm(llm)
                .config(runtimeConfig)
                .build();
        
        // 创建引擎
        engine = DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
        
        // 初始化命令注册表
        initCommands(allTools);
    }
    
    /**
     * 初始化命令系统
     */
    private void initCommands(java.util.List<Tool<?>> tools) {
        commandRegistry = new CommandRegistry();
        
        java.util.Map<String, String> config = new java.util.HashMap<>();
        config.put("provider", llm.getProvider());
        config.put("model", llm.getModel());
        config.put("work_dir", workDir.toString());
        
        // 注册所有内置命令
        commandRegistry.register(new HelpCommand(commandRegistry));
        commandRegistry.register(new VersionCommand("2.0.0"));
        commandRegistry.register(new ToolsCommand(tools));
        commandRegistry.register(new ClearCommand());
        commandRegistry.register(new StatusCommand(engine, tools));
        commandRegistry.register(new ResetCommand(engine));
        commandRegistry.register(new ConfigCommand(config));
        commandRegistry.register(new HistoryCommand(engine));
        commandRegistry.register(new InitCommand(engine));
        commandRegistry.register(new ThemeCommand());
        commandRegistry.register(new CompactCommand(engine));
        
        log.info("已注册 {} 个命令", commandRegistry.size());
        
        // 初始化输入处理器链（按优先级排序）
        initInputProcessors();
        
        // 用完整的 CommandRegistry 重建 LineReader 的补全器
        if (terminal != null) {
            lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new JimiCompleter(commandRegistry, workDir))
                    .highlighter(new JimiHighlighter())
                    .parser(new JimiParser())
                    .variable(LineReader.HISTORY_FILE, workDir.resolve(".jimi/history").toString())
                    .build();
        }
    }
    
    /**
     * 初始化输入处理器链
     */
    private void initInputProcessors() {
        inputProcessors = new ArrayList<>();
        inputProcessors.add(new MetaCommandProcessor(commandRegistry));
        inputProcessors.add(new ShellShortcutProcessor());
        inputProcessors.add(new AgentCommandProcessor());
        // 按优先级排序（数值越小优先级越高）
        inputProcessors.sort(Comparator.comparingInt(InputProcessor::getPriority));
    }
    
    /**
     * 显示欢迎信息
     */
    private void printWelcome() {
        String agentName = agent.getName();
        String agentDesc = agent.getDescription();
        
        println("");
        printColored("╔══════════════════════════════════════════╗", AttributedStyle.CYAN);
        printColored("║          Jimi CLI v2.0.0                 ║", AttributedStyle.CYAN);
        printColored("╚══════════════════════════════════════════╝", AttributedStyle.CYAN);
        println("");
        printColored("当前 Agent: " + agentName, AttributedStyle.GREEN);
        if (agentDesc != null && !agentDesc.isEmpty()) {
            println("描述: " + agentDesc);
        }
        printColored("LLM: " + llm.getProvider() + "/" + llm.getModel(), AttributedStyle.GREEN);
        println("");
        printColored("输入 /help 查看可用命令，输入 /exit 退出", AttributedStyle.YELLOW);
        println("");
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
        if (message instanceof ContentPartMessage) {
            // 流式内容输出
            ContentPartMessage cpm = (ContentPartMessage) message;
            ContentPart part = cpm.getContentPart();
            if (part instanceof TextPart) {
                print(((TextPart) part).getText());
            }
        } else if (message instanceof StepBegin) {
            // 步骤开始
            StepBegin sb = (StepBegin) message;
            printColored("\n[Step " + sb.getStepNumber() + "] ", AttributedStyle.BLUE);
        } else if (message instanceof StepEnd) {
            // 步骤结束
            println("");
        } else if (message instanceof ToolCallMessage) {
            // 工具调用
            ToolCallMessage tcm = (ToolCallMessage) message;
            String toolName = tcm.getToolCall().getFunction().getName();
            printColored("\n> 调用工具: " + toolName, AttributedStyle.MAGENTA);
        } else if (message instanceof ToolResultMessage) {
            // 工具结果
            ToolResultMessage trm = (ToolResultMessage) message;
            if (trm.getToolResult().isOk()) {
                printColored(" ✓", AttributedStyle.GREEN);
            } else {
                printColored(" ✗ " + trm.getToolResult().getError(), AttributedStyle.RED);
            }
        }
    }
    
    /**
     * 主循环
     */
    private void mainLoop() {
        while (running.get()) {
            try {
                // 读取用户输入
                String input = readInput();
                if (input == null) {
                    continue;
                }
                
                input = input.trim();
                if (input.isEmpty()) {
                    continue;
                }
                
                // 通过输入处理器链处理
                boolean shouldContinue = processInput(input);
                if (!shouldContinue) {
                    running.set(false);
                }
                
            } catch (UserInterruptException e) {
                // Ctrl+C
                if (executing.get()) {
                    println("\n已中断当前操作");
                    engine.interrupt();
                    executing.set(false);
                } else {
                    printColored("\n输入 /exit 退出程序", AttributedStyle.YELLOW);
                }
            } catch (EndOfFileException e) {
                // Ctrl+D
                running.set(false);
            }
        }
    }
    
    /**
     * 通过输入处理器链处理输入
     */
    private boolean processInput(String input) {
        // 构建 ShellContext
        ShellContext shellContext = ShellContext.builder()
                .engine(engine)
                .runtime(runtime)
                .toolRegistry(toolRegistry)
                .commandRegistry(commandRegistry)
                .terminal(terminal)
                .lineReader(lineReader)
                .rawInput(input)
                .outputFormatter(outputFormatter)
                .build();
        
        // 遍历输入处理器链，找到第一个能处理的
        for (InputProcessor processor : inputProcessors) {
            if (processor.canProcess(input)) {
                try {
                    executing.set(true);
                    return processor.process(input, shellContext);
                } catch (Exception e) {
                    log.error("输入处理异常", e);
                    printColored("处理异常: " + e.getMessage(), AttributedStyle.RED);
                    return true;
                } finally {
                    executing.set(false);
                }
            }
        }
        
        // 没有处理器能处理，回退到默认对话处理
        handleChat(input);
        return true;
    }
    
    /**
     * 读取用户输入
     *
     * @return 用户输入
     */
    private String readInput() {
        String prompt = getPrompt();
        return lineReader.readLine(prompt);
    }
    
    /**
     * 获取提示符
     *
     * @return 提示符字符串
     */
    private String getPrompt() {
        return new AttributedString(
                ">>> ",
                AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
        ).toAnsi(terminal);
    }
    
    /**
     * 处理命令
     *
     * @param input 命令输入
     */
    private void handleCommand(String input) {
        String[] parts = input.substring(1).split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        
        // 特殊命令：exit/quit
        if ("exit".equals(commandName) || "quit".equals(commandName) || "q".equals(commandName)) {
            running.set(false);
            println("再见！");
            return;
        }
        
        // 查找命令
        Command command = commandRegistry.find(commandName);
        if (command == null) {
            printColored("未知命令: " + commandName + "，输入 /help 查看帮助", AttributedStyle.RED);
            return;
        }
        
        // 创建命令上下文
        CommandContext commandContext = CommandContext.builder()
                .runtime(runtime)
                .commandName(commandName)
                .args(args)
                .rawInput(input)
                .output(new SimpleCommandOutput())
                .build();
        
        // 执行命令
        try {
            command.execute(commandContext);
        } catch (Exception e) {
            log.error("命令执行失败: {}", commandName, e);
            printColored("命令执行失败: " + e.getMessage(), AttributedStyle.RED);
        }
    }
    
    /**
     * 处理对话
     *
     * @param input 用户输入
     */
    private void handleChat(String input) {
        executing.set(true);
        
        try {
            // 执行 Agent
            ExecutionResult result = engine.run(input).block();
            
            // 处理结果
            if (result != null && !result.isSuccess()) {
                printColored("\n执行失败: " + result.getError(), AttributedStyle.RED);
                notificationService.notify("执行失败", result.getError(), NotificationType.ERROR);
            } else {
                notificationService.notify("执行完成", input, NotificationType.SUCCESS);
            }
            
            println("");
            
        } catch (Exception e) {
            log.error("执行异常", e);
            printColored("\n错误: " + e.getMessage(), AttributedStyle.RED);
            notificationService.notify("执行异常", e.getMessage(), NotificationType.ERROR);
        } finally {
            executing.set(false);
        }
    }
    
    /**
     * 重置上下文
     */
    private void resetContext() {
        this.context = new DefaultContext();
        // 重新初始化引擎
        initEngine();
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        try {
            if (terminal != null) {
                terminal.close();
            }
        } catch (IOException e) {
            log.error("关闭终端失败", e);
        }
    }
    
    /**
     * 打印文本
     *
     * @param text 文本
     */
    private void print(String text) {
        terminal.writer().print(text);
        terminal.flush();
    }
    
    /**
     * 打印一行
     *
     * @param text 文本
     */
    private void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }
    
    /**
     * 打印彩色文本
     *
     * @param text  文本
     * @param color 颜色常量
     */
    private void printColored(String text, int color) {
        AttributedString as = new AttributedString(text, AttributedStyle.DEFAULT.foreground(color));
        terminal.writer().println(as.toAnsi(terminal));
        terminal.flush();
    }
    
    /**
     * 打印带样式的文本（用于 BOLD 等样式）
     *
     * @param text  文本
     * @param style 样式
     */
    private void printStyled(String text, AttributedStyle style) {
        AttributedString as = new AttributedString(text, style);
        terminal.writer().println(as.toAnsi(terminal));
        terminal.flush();
    }
}
