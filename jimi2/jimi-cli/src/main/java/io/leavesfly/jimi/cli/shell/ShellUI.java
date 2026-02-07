package io.leavesfly.jimi.cli.shell;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.message.ContentPart;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.cli.config.CliConfig;
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
                .dumb(false)
                .build();
        
        lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, workDir.resolve(".jimi/history").toString())
                .build();
    }
    
    /**
     * 初始化引擎
     */
    private void initEngine() {
        // 注册 Agent 的工具
        if (agent.getTools() != null) {
            agent.getTools().forEach(toolRegistry::register);
        }
        
        // 创建运行时（注入 LLM）
        Runtime runtime = Runtime.builder()
                .workDir(workDir)
                .llm(llm)
                .yoloMode(cliConfig.isYoloMode())
                .maxContextTokens(cliConfig.getMaxContextTokens())
                .build();
        
        // 创建引擎
        engine = DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
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
                
                // 处理命令
                if (input.startsWith(COMMAND_PREFIX)) {
                    handleCommand(input);
                } else {
                    // 处理普通对话
                    handleChat(input);
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
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        switch (command) {
            case "exit":
            case "quit":
            case "q":
                running.set(false);
                println("再见！");
                break;
                
            case "help":
            case "h":
                printHelp();
                break;
                
            case "clear":
            case "cls":
                clearScreen();
                break;
                
            case "reset":
                resetContext();
                println("上下文已重置");
                break;
                
            case "history":
                showHistory();
                break;
                
            case "agent":
                showAgentInfo();
                break;
                
            case "tools":
                showTools();
                break;
                
            default:
                printColored("未知命令: " + command + "，输入 /help 查看帮助", AttributedStyle.RED);
                break;
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
            }
            
            println("");
            
        } catch (Exception e) {
            log.error("执行异常", e);
            printColored("\n错误: " + e.getMessage(), AttributedStyle.RED);
        } finally {
            executing.set(false);
        }
    }
    
    /**
     * 显示帮助信息
     */
    private void printHelp() {
        println("");
        printStyled("可用命令:", AttributedStyle.BOLD);
        println("  /help, /h       显示此帮助信息");
        println("  /exit, /quit    退出程序");
        println("  /clear, /cls    清屏");
        println("  /reset          重置对话上下文");
        println("  /history        显示对话历史");
        println("  /agent          显示当前 Agent 信息");
        println("  /tools          显示可用工具");
        println("");
        printStyled("提示:", AttributedStyle.BOLD);
        println("  - 直接输入文本开始对话");
        println("  - Ctrl+C 中断当前操作");
        println("  - Ctrl+D 退出程序");
        println("");
    }
    
    /**
     * 清屏
     */
    private void clearScreen() {
        terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
        terminal.flush();
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
     * 显示历史
     */
    private void showHistory() {
        println("");
        printStyled("对话历史:", AttributedStyle.BOLD);
        int i = 1;
        for (Message msg : context.getHistory()) {
            String roleStr = msg.getRole() == Role.USER ? "用户" : "助手";
            String content = msg.getContent();
            if (content != null && content.length() > 50) {
                content = content.substring(0, 50) + "...";
            }
            println(String.format("  %d. [%s] %s", i++, roleStr, content != null ? content : "(无内容)"));
        }
        println("");
    }
    
    /**
     * 显示 Agent 信息
     */
    private void showAgentInfo() {
        println("");
        printStyled("Agent 信息:", AttributedStyle.BOLD);
        println("  名称: " + agent.getName());
        println("  描述: " + (agent.getDescription() != null ? agent.getDescription() : "无"));
        println("  版本: " + (agent.getVersion() != null ? agent.getVersion() : "1.0.0"));
        println("");
    }
    
    /**
     * 显示可用工具
     */
    private void showTools() {
        println("");
        printStyled("可用工具:", AttributedStyle.BOLD);
        if (agent.getTools() != null && !agent.getTools().isEmpty()) {
            for (var tool : agent.getTools()) {
                println("  - " + tool.getName() + ": " + tool.getDescription());
            }
        } else {
            println("  无注册工具");
        }
        println("");
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
