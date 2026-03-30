package io.leavesfly.jimi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.session.SessionManager;
import io.leavesfly.jimi.mcp.server.SimpleJimiServer;
import io.leavesfly.jimi.ui.DebugLogger;
import io.leavesfly.jimi.ui.shell.ShellUI;
import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.client.WireEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CLI 应用入口
 * 负责命令行参数解析和系统初始化
 */
@Slf4j
@Component
@Command(
        name = "jimi",
        description = "Jimi",
        mixinStandardHelpOptions = true,
        version = "0.1.0"
)
public class CliApplication implements CommandLineRunner, Runnable {

    private final JimiConfig jimiConfig;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final JimiFactory jimiFactory;


    @Value("${jimi.embedded:false}")
    private boolean embeddedMode;

    @Autowired
    public CliApplication(JimiConfig jimiConfig, SessionManager sessionManager,
                          ObjectMapper objectMapper, ApplicationContext applicationContext,
                          JimiFactory jimiFactory) {
        this.jimiConfig = jimiConfig;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.jimiFactory = jimiFactory;

    }

    @Option(names = {"--verbose"}, description = "Print verbose information")
    private boolean verbose;

    @Option(names = {"--debug"}, description = "Log debug information")
    private boolean debug;

    @Option(names = {"-w", "--work-dir"}, description = "Working directory for the agent")
    private Path workDir = Paths.get(System.getProperty("user.dir"));

    @Option(names = {"-C", "--continue"}, description = "Continue the previous session")
    private boolean continueSession;

    @Option(names = {"-m", "--model"}, description = "LLM model to use")
    private String modelName;

    @Option(names = {"-y", "--yolo", "--yes"}, description = "Automatically approve all actions")
    private boolean yolo = false;

    @Option(names = {"--agent-file"}, description = "Custom agent specification file")
    private Path agentFile;

    @Option(names = {"--mcp-config-file"}, description = "MCP configuration file (can be specified multiple times)")
    private List<Path> mcpConfigFiles = new ArrayList<>();

    @Option(names = {"-c", "--command"}, description = "User query to the agent")
    private String command;

    @Option(names = {"--simple-server", "--mcp-server"}, description = "Start as simple server (StdIO mode for IDE integration)")
    private boolean simpleServer = false;

    @Override
    public void run(String... args) throws Exception {
        // 嵌入式模式下跳过 CLI 启动
        if (embeddedMode) {
            log.info("Running in embedded mode, skipping CLI startup");
            return;
        }

        // 解析命令行参数
        CommandLine commandLine = new CommandLine(this);
        int exitCode = commandLine.execute(args);

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Picocli 的 Runnable 接口实现
     * 当命令行参数解析完成后，Picocli 会调用此方法执行主逻辑
     */
    @Override
    public void run() {
        executeMain();
    }

    private void executeMain() {
        try {
            // Simple Server 模式（IDE集成）
            if (simpleServer) {
                log.info("Starting Jimi in Simple Server mode...");
                SimpleJimiServer server = new SimpleJimiServer(jimiFactory);
                server.start();
                return;
            }

            // 启用 debug 模式
            if (debug) {
                DebugLogger.enable();
            }

            // 配置已由 Spring 管理，直接使用注入的 JimiConfig
            if (verbose) {
                System.out.println("Loaded config: " + jimiConfig);
            }

            // 创建或继续会话
            Session session;
            if (continueSession) {
                Optional<Session> existingSession = sessionManager.continueSession(workDir);
                if (existingSession.isPresent()) {
                    session = existingSession.get();
                    System.out.println("✓ Continuing previous session: " + session.getId());
                } else {
                    System.err.println("No previous session found for the working directory");
                    return;
                }
            } else {
                session = sessionManager.createSession(workDir);
                System.out.println("✓ Created new session: " + session.getId());
            }

            System.out.println("✓ Session history file: " + session.getHistoryFile());
            System.out.println("✓ Working directory: " + session.getWorkDir());

            // 使用注入的 JimiFactory 创建 Engine（Builder 模式）
            JimiEngine jimiEngine = jimiFactory.createEngine()
                    .session(session)
                    .agentSpec(agentFile)
                    .model(modelName)
                    .yolo(yolo)
                    .mcpConfigs(mcpConfigFiles)
                    .build()
                    .block();

            if (jimiEngine == null) {
                System.err.println("Failed to create Jimi Engine");
                System.exit(1);
                return;
            }

            // 如果有命令，直接执行
            if (command != null && !command.isEmpty()) {
                System.out.println("\n[INFO] Executing command: " + command);
                jimiEngine.run(command).block();
                System.out.println("\n✓ Command completed");
                return;
            }

            // 初始化阶段：创建WireEngineClient（内部完成所有配置缓存）
            HookRegistry hookRegistry = applicationContext.getBean(HookRegistry.class);
            EngineClient engineClient = new WireEngineClient(jimiEngine, hookRegistry, sessionManager);

            // 初始化阶段：创建ShellUI（注入EngineClient）
            try (ShellUI shellUI = new ShellUI(engineClient, applicationContext)) {
                // 运行阶段：启动主循环
                shellUI.run().block();
            }

        } catch (Exception e) {
            log.error("Error executing Jimi", e);
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
}
