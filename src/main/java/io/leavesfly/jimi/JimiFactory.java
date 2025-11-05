package io.leavesfly.jimi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentRegistry;
import io.leavesfly.jimi.agent.ResolvedAgentSpec;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.config.LLMModelConfig;
import io.leavesfly.jimi.config.LLMProviderConfig;
import io.leavesfly.jimi.exception.ConfigException;
import io.leavesfly.jimi.llm.ChatProvider;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.provider.KimiChatProvider;
import io.leavesfly.jimi.llm.provider.OpenAICompatibleChatProvider;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.Context;

import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.task.Task;
import io.leavesfly.jimi.tool.mcp.MCPToolLoader;
import io.leavesfly.jimi.tool.mcp.MCPTool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Jimi 应用工厂
 * 负责组装所有核心组件，创建完整的 Jimi 实例
 */
@Slf4j
public class JimiFactory {

    private final JimiConfig config;
    private final ObjectMapper objectMapper;

    public JimiFactory(JimiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

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

                // 1. 创建 LLM
                LLM llm = createLLM(modelName);

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
                ResolvedAgentSpec resolvedAgentSpec = loadAgentSpec(agentSpecPath, runtime);

                // 使用 AgentRegistry 单例加载 Agent（包含系统提示词处理）
                AgentRegistry agentRegistry = AgentRegistry.getInstance();
                Agent agent = agentSpecPath != null 
                        ? agentRegistry.loadAgent(agentSpecPath, runtime).block()
                        : agentRegistry.loadDefaultAgent(runtime).block();
                if (agent == null) {
                    throw new RuntimeException("Failed to load agent");
                }

                // 4. 创建 Context 并恢复历史
                Context context = new Context(session.getHistoryFile(), objectMapper);

                // 5. 创建 ToolRegistry（包含 Task 工具和 MCP 工具）
                ToolRegistry toolRegistry = createToolRegistry(builtinArgs, approval, resolvedAgentSpec, runtime, mcpConfigFiles);

                // 6. 创建 JimiSoul
                JimiSoul soul = new JimiSoul(agent, runtime, context, toolRegistry, objectMapper);

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
     * 创建 LLM 实例
     */
    private LLM createLLM(String modelName) {
        // 确定使用的模型
        String effectiveModelName = modelName != null ? modelName : config.getDefaultModel();

        if (effectiveModelName == null || effectiveModelName.isEmpty()) {
            log.warn("No model specified, LLM will not be initialized");
            return null;
        }

        LLMModelConfig modelConfig = config.getModels().get(effectiveModelName);
        if (modelConfig == null) {
            throw new ConfigException("Model not found in config: " + effectiveModelName);
        }

        LLMProviderConfig providerConfig = config.getProviders().get(modelConfig.getProvider());
        if (providerConfig == null) {
            throw new ConfigException("Provider not found in config: " + modelConfig.getProvider());
        }

        // 检查环境变量覆盖
        String apiKey = Optional.ofNullable(System.getenv("KIMI_API_KEY"))
                .orElse(providerConfig.getApiKey());
        String baseUrl = Optional.ofNullable(System.getenv("KIMI_BASE_URL"))
                .orElse(providerConfig.getBaseUrl());
        String model = Optional.ofNullable(System.getenv("KIMI_MODEL_NAME"))
                .orElse(modelConfig.getModel());

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("No API key configured, LLM will not be initialized");
            return null;
        }

        // 创建带环境变量覆盖的 provider config
        LLMProviderConfig effectiveProviderConfig = LLMProviderConfig.builder()
                .type(providerConfig.getType())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .customHeaders(providerConfig.getCustomHeaders())
                .build();

        // 创建 ChatProvider
        ChatProvider chatProvider;
        switch (providerConfig.getType()) {
            case KIMI:
                chatProvider = new KimiChatProvider(model, effectiveProviderConfig, objectMapper);
                break;

            case DEEPSEEK:
                chatProvider = new OpenAICompatibleChatProvider(
                        model, effectiveProviderConfig, objectMapper, "DeepSeek");
                break;

            case QWEN:
                chatProvider = new OpenAICompatibleChatProvider(
                        model, effectiveProviderConfig, objectMapper, "Qwen");
                break;

            case OLLAMA:
                chatProvider = new OpenAICompatibleChatProvider(
                        model, effectiveProviderConfig, objectMapper, "Ollama");
                break;

            case OPENAI:
                chatProvider = new OpenAICompatibleChatProvider(
                        model, effectiveProviderConfig, objectMapper, "OpenAI");
                break;

            default:
                throw new ConfigException("Unsupported provider type: " + providerConfig.getType());
        }

        log.info("Created LLM: provider={}, model={}", providerConfig.getType(), model);

        return LLM.builder()
                .chatProvider(chatProvider)
                .maxContextSize(modelConfig.getMaxContextSize())
                .build();
    }

    /**
     * 加载 Agent 规范
     */
    private ResolvedAgentSpec loadAgentSpec(Path agentSpecPath, Runtime runtime) {
        try {
            // 使用 AgentRegistry 单例加载
            AgentRegistry agentRegistry = AgentRegistry.getInstance();
            ResolvedAgentSpec resolved = agentSpecPath != null
                    ? agentRegistry.loadAgentSpec(agentSpecPath).block()
                    : agentRegistry.loadDefaultAgentSpec().block();

            if (resolved == null) {
                throw new RuntimeException("Failed to load agent spec");
            }

            log.debug("Agent spec loaded: {}", resolved.getName());
            return resolved;

        } catch (Exception e) {
            log.error("Failed to load agent spec", e);
            throw new RuntimeException("Failed to load agent spec", e);
        }
    }

    /**
     * 创建工具注册表（包含 Task 工具和 MCP 工具）
     */
    private ToolRegistry createToolRegistry(
            BuiltinSystemPromptArgs builtinArgs,
            Approval approval,
            ResolvedAgentSpec resolvedAgentSpec,
            Runtime runtime,
            List<Path> mcpConfigFiles
    ) {
        // 创建基础工具注册表
        ToolRegistry registry = ToolRegistry.createStandardRegistry(
                builtinArgs,
                approval,
                objectMapper
        );

        // 如果 Agent 有子 Agent 规范，注册 Task 工具
        if (resolvedAgentSpec.getSubagents() != null && !resolvedAgentSpec.getSubagents().isEmpty()) {
            Task taskTool = new Task(resolvedAgentSpec, runtime, objectMapper);
            registry.register(taskTool);
            log.info("Registered Task tool with {} subagents", resolvedAgentSpec.getSubagents().size());
        }

        // 加载 MCP 工具
        if (mcpConfigFiles != null && !mcpConfigFiles.isEmpty()) {
            MCPToolLoader mcpLoader = new MCPToolLoader(objectMapper);

            for (Path configFile : mcpConfigFiles) {
                try {
                    List<MCPTool> mcpTools = mcpLoader.loadFromFile(configFile, registry);
                    log.info("Loaded {} MCP tools from {}", mcpTools.size(), configFile);
                } catch (Exception e) {
                    log.error("Failed to load MCP config: {}", configFile, e);
                    // 继续加载其他配置文件
                }
            }
        }

        log.debug("Created tool registry with {} tools", registry.getToolNames().size());
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

        // 加载 AGENTS.md（如存在）
        String agentsMd = "";
        java.nio.file.Path agentsPath = workDir.resolve("AGENTS.md");
        java.nio.file.Path agentsPathLower = workDir.resolve("agents.md");
        try {
            if (java.nio.file.Files.isRegularFile(agentsPath)) {
                agentsMd = java.nio.file.Files.readString(agentsPath).trim();
            } else if (java.nio.file.Files.isRegularFile(agentsPathLower)) {
                agentsMd = java.nio.file.Files.readString(agentsPathLower).trim();
            }
        } catch (Exception e) {
            log.warn("Failed to read AGENTS.md from work dir: {}", workDir, e);
        }

        return BuiltinSystemPromptArgs.builder()
                .kimiNow(now)
                .kimiWorkDir(workDir)
                .kimiWorkDirLs(workDirLs)
                .kimiAgentsMd(agentsMd)
                .build();
    }
}
