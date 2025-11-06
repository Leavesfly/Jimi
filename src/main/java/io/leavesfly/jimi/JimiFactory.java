package io.leavesfly.jimi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentRegistry;
import io.leavesfly.jimi.agent.AgentSpec;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.LLMFactory;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.session.SessionManager;
import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.compaction.Compaction;
import io.leavesfly.jimi.soul.context.Context;

import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.mcp.MCPToolProvider;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.mcp.MCPToolLoader;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Jimi 应用工厂（Spring Service）
 * 负责组装所有核心组件，创建完整的 Jimi 实例
 */
@Slf4j
@Service
public class JimiFactory {

    @Autowired
    private JimiConfig config;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private ToolRegistryFactory toolRegistryFactory;
    @Autowired
    private LLMFactory llmFactory;
    @Autowired
    private MCPToolLoader mcpToolLoader;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private Compaction compaction;
    @Autowired
    private List<ToolProvider> toolProviders;  // Spring 自动注入所有 ToolProvider


    /**
     * 创建完整的 Jimi Soul 实例
     *
     * @param session        会话对象
     * @param agentSpecPath  Agent 规范文件路径（可选，null 表示使用默认 agent）
     * @param modelName      模型名称（可选，null 表示使用配置文件默认值）
     * @param yolo           是否启用 YOLO 模式（自动批准所有操作）
     * @param mcpConfigFiles MCP 配置文件列表（可选）
     * @return JimiSoul 实例的 Mono
     */
    public Mono<JimiSoul> createSoul(
            Session session,
            Path agentSpecPath,
            String modelName,
            boolean yolo,
            List<Path> mcpConfigFiles
    ) {
        return Mono.defer(() -> {
            try {
                log.debug("Creating Jimi Soul for session: {}", session.getId());

                // 1. 获取或创建 LLM（使用工厂，带缓存）
                LLM llm = llmFactory.getOrCreateLLM(modelName);

                // 2. 创建 Runtime 依赖
                Approval approval = new Approval(yolo);

                BuiltinSystemPromptArgs builtinArgs = createBuiltinArgs(session);

                Runtime runtime = Runtime.builder()
                        .config(config)
                        .llm(llm)
                        .session(session)
                        .approval(approval)
                        .builtinArgs(builtinArgs)
                        .build();

                // 3. 加载 Agent 规范和 Agent 实例
                AgentSpec agentSpec = agentRegistry.loadAgentSpec(agentSpecPath).block();

                // 使用 AgentRegistry 单例加载 Agent（包含系统提示词处理）
                Agent agent = agentSpecPath != null
                        ? agentRegistry.loadAgent(agentSpecPath, runtime).block()
                        : agentRegistry.loadDefaultAgent(runtime).block();
                if (agent == null) {
                    throw new RuntimeException("Failed to load agent");
                }

                // 4. 创建 Context 并恢复历史
                // 注意：可以通过 new Context(file, mapper, true) 启用异步批量Repository以提升性能
                Context context = new Context(session.getHistoryFile(), objectMapper);

                // 5. 创建 ToolRegistry（包含 Task 工具和 MCP 工具）
                ToolRegistry toolRegistry = createToolRegistry(builtinArgs, approval, agentSpec, runtime, mcpConfigFiles);

                // 6. 创建 JimiSoul（注入 Compaction）
                JimiSoul soul = new JimiSoul(agent, runtime, context, toolRegistry, objectMapper, new WireImpl(), compaction);

                // 7. 恢复上下文历史
                return context.restore()
                        .then(Mono.just(soul))
                        .doOnSuccess(s -> log.info("Jimi Soul created successfully"));

            } catch (Exception e) {
                log.error("Failed to create Jimi Soul", e);
                return Mono.error(e);
            }
        });
    }


    /**
     * 创建工具注册表（使用 ToolProvider SPI 机制）
     */
    private ToolRegistry createToolRegistry(
            BuiltinSystemPromptArgs builtinArgs,
            Approval approval,
            AgentSpec agentSpec,
            Runtime runtime,
            List<Path> mcpConfigFiles
    ) {
        // 创建基础工具注册表（使用 Spring 工厂）
        ToolRegistry registry = toolRegistryFactory.createStandardRegistry(
                builtinArgs,
                approval
        );
        
        // 使用 ToolProvider SPI 机制加载工具
        log.debug("Applying {} tool providers", toolProviders.size());
        
        // 对于 MCP 提供者，需要设置配置文件
        toolProviders.stream()
            .filter(p -> p instanceof MCPToolProvider)
            .forEach(p -> ((MCPToolProvider) p).setMcpConfigFiles(mcpConfigFiles));
        
        // 按顺序应用所有工具提供者
        toolProviders.stream()
            .sorted(Comparator.comparingInt(ToolProvider::getOrder))
            .filter(provider -> provider.supports(agentSpec, runtime))
            .forEach(provider -> {
                log.info("Applying tool provider: {} (order={})", 
                        provider.getName(), provider.getOrder());
                List<Tool<?>> tools = provider.createTools(agentSpec, runtime);
                tools.forEach(registry::register);
                log.debug("  Registered {} tools from {}", tools.size(), provider.getName());
            });
        
        log.info("Created tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }

    private BuiltinSystemPromptArgs createBuiltinArgs(Session session) {
        String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Path workDir = session.getWorkDir().toAbsolutePath();

        // 列出工作目录文件列表（非递归）
        StringBuilder lsBuilder = new StringBuilder();
        try {
            java.nio.file.Files.list(workDir).forEach(p -> {
                String type = java.nio.file.Files.isDirectory(p) ? "dir" : "file";
                lsBuilder.append(type).append("  ").append(p.getFileName().toString()).append("\n");
            });
        } catch (Exception e) {
            log.warn("Failed to list work dir: {}", workDir, e);
        }
        String workDirLs = lsBuilder.toString().trim();

        // 从 SessionManager 缓存加载 AGENTS.md（避免重复 I/O）
        String agentsMd = sessionManager.loadAgentsMd(workDir);

        return BuiltinSystemPromptArgs.builder()
                .jimiNow(now)
                .jimiWorkDir(workDir)
                .jimiWorkDirLs(workDirLs)
                .jimiAgentsMd(agentsMd)
                .build();
    }
}
