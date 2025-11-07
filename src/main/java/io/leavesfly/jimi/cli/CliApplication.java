package io.leavesfly.jimi.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.JimiFactory;
import io.leavesfly.jimi.config.ConfigLoader;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.session.SessionManager;
import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.ui.shell.ShellUI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 使用 Picocli 实现命令行参数解析
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

    private final ConfigLoader configLoader;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final JimiFactory jimiFactory;

    @Autowired
    public CliApplication(ConfigLoader configLoader, SessionManager sessionManager,
                          ObjectMapper objectMapper, ApplicationContext applicationContext,
                          JimiFactory jimiFactory) {
        this.configLoader = configLoader;
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
    private boolean yolo = true;

    @Option(names = {"--agent-file"}, description = "Custom agent specification file")
    private Path agentFile;

    @Option(names = {"--mcp-config-file"}, description = "MCP configuration file (can be specified multiple times)")
    private List<Path> mcpConfigFiles = new ArrayList<>();

    @Option(names = {"-c", "--command"}, description = "User query to the agent")
    private String command;

    @Override
    public void run(String... args) throws Exception {
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
            // 配置已由 Spring 管理，但这里仍然需要加载以获取最新配置（如果需要）
            // 或者直接使用注入的 JimiConfig
            JimiConfig config = configLoader.loadConfig(null);

            if (verbose) {
                System.out.println("Loaded config: " + config);
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

            // 使用注入的 JimiFactory 创建 Soul
            JimiSoul soul = jimiFactory.createSoul(session, agentFile, modelName, yolo, mcpConfigFiles).block();

            if (soul == null) {
                System.err.println("Failed to create Jimi Soul");
                System.exit(1);
                return;
            }

            // 如果有命令，直接执行
            if (command != null && !command.isEmpty()) {
                System.out.println("\n[INFO] Executing command: " + command);
                soul.run(command).block();
                System.out.println("\n✓ Command completed");
                return;
            }

            // 否则启动 Shell UI
            try (ShellUI shellUI = new ShellUI(soul, applicationContext)) {
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
