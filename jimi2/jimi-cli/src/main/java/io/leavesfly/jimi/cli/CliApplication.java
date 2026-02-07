package io.leavesfly.jimi.cli;

import io.leavesfly.jimi.cli.config.CliConfig;
import io.leavesfly.jimi.cli.shell.ShellUI;
import io.leavesfly.jimi.cli.agent.AgentLoader;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Jimi CLI 应用程序主入口
 * <p>
 * 命令行界面入口，负责：
 * 1. 解析命令行参数
 * 2. 加载 Agent 配置
 * 3. 初始化交互式 Shell
 * </p>
 *
 * @author Jimi2 Team
 */
public class CliApplication {
    
    private static final Logger log = LoggerFactory.getLogger(CliApplication.class);
    
    /** 默认 Agent 规范目录 */
    private static final String DEFAULT_AGENTS_DIR = ".jimi/agents";
    
    /** 默认工作目录 */
    private final Path workDir;
    
    /** Agent 加载器 */
    private final AgentLoader agentLoader;
    
    /** CLI 配置 */
    private final CliConfig cliConfig;
    
    /** LLM 工厂 */
    private final LLMFactory llmFactory;
    
    /** 交互式 Shell */
    private ShellUI shellUI;
    
    /**
     * 构造函数
     *
     * @param workDir 工作目录
     */
    public CliApplication(Path workDir) {
        this.workDir = workDir;
        this.cliConfig = CliConfig.load(workDir);
        this.llmFactory = new LLMFactory();
        this.agentLoader = new AgentLoader(workDir.resolve(DEFAULT_AGENTS_DIR), workDir);
    }
    
    /**
     * 启动应用
     *
     * @param agentName 要启动的 Agent 名称，为空时使用默认
     */
    public void start(String agentName) {
        log.info("启动 Jimi CLI，工作目录: {}", workDir);
        
        try {
            // 1. 创建 LLM 实例
            LLMConfig llmConfig = cliConfig.toLLMConfig();
            if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isEmpty()) {
                System.err.println("错误: 未配置 API Key。");
                System.err.println("请通过以下方式之一配置：");
                System.err.println("  1. 设置环境变量: export OPENAI_API_KEY=sk-xxx");
                System.err.println("  2. 编辑配置文件: ~/.jimi/config.yaml");
                return;
            }
            
            LLM llm = llmFactory.create(llmConfig);
            log.info("LLM 已初始化: provider={}, model={}", llmConfig.getProvider(), llmConfig.getModel());
            
            // 2. 加载所有可用的 Agent
            List<Agent> agents = agentLoader.loadAll();
            log.info("已加载 {} 个 Agent", agents.size());
            
            // 3. 选择要运行的 Agent
            Agent selectedAgent = selectAgent(agents, agentName);
            if (selectedAgent == null) {
                log.error("未找到可用的 Agent");
                System.err.println("错误: 未找到可用的 Agent。请确保配置了 Agent 规范文件。");
                return;
            }
            
            log.info("启动 Agent: {}", selectedAgent.getName());
            
            // 4. 初始化并启动 Shell
            shellUI = new ShellUI(selectedAgent, workDir, llm, cliConfig);
            shellUI.run();
            
        } catch (Exception e) {
            log.error("启动失败", e);
            System.err.println("启动失败: " + e.getMessage());
        }
    }
    
    /**
     * 选择要运行的 Agent
     *
     * @param agents 可用的 Agent 列表
     * @param agentName 指定的 Agent 名称
     * @return 选中的 Agent
     */
    private Agent selectAgent(List<Agent> agents, String agentName) {
        if (agents.isEmpty()) {
            return null;
        }
        
        // 如果指定了名称，查找对应的 Agent
        if (agentName != null && !agentName.isEmpty()) {
            return agents.stream()
                    .filter(a -> a.getName().equalsIgnoreCase(agentName))
                    .findFirst()
                    .orElse(null);
        }
        
        // 否则返回第一个（默认）
        return agents.get(0);
    }
    
    /**
     * 主入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 解析命令行参数
        CliOptions options = parseArgs(args);
        
        // 确定工作目录
        Path workDir = options.workDir != null 
                ? Paths.get(options.workDir) 
                : Paths.get(System.getProperty("user.dir"));
        
        // 创建并启动应用
        CliApplication app = new CliApplication(workDir);
        app.start(options.agentName);
    }
    
    /**
     * 解析命令行参数
     *
     * @param args 命令行参数
     * @return 解析结果
     */
    private static CliOptions parseArgs(String[] args) {
        CliOptions options = new CliOptions();
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-d":
                case "--dir":
                    if (i + 1 < args.length) {
                        options.workDir = args[++i];
                    }
                    break;
                case "-a":
                case "--agent":
                    if (i + 1 < args.length) {
                        options.agentName = args[++i];
                    }
                    break;
                case "-h":
                case "--help":
                    printHelp();
                    System.exit(0);
                    break;
                case "-v":
                case "--version":
                    printVersion();
                    System.exit(0);
                    break;
            }
        }
        
        return options;
    }
    
    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("Jimi CLI - 智能 Agent 命令行工具");
        System.out.println();
        System.out.println("用法: jimi [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  -d, --dir <目录>     指定工作目录");
        System.out.println("  -a, --agent <名称>   指定要启动的 Agent");
        System.out.println("  -h, --help           显示此帮助信息");
        System.out.println("  -v, --version        显示版本信息");
    }
    
    /**
     * 打印版本信息
     */
    private static void printVersion() {
        System.out.println("Jimi CLI v2.0.0");
    }
    
    /**
     * 命令行选项
     */
    private static class CliOptions {
        String workDir;
        String agentName;
    }
}
