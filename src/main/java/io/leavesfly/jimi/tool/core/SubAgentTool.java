package io.leavesfly.jimi.tool.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.agent.SubagentSpec;
import io.leavesfly.jimi.core.compaction.SimpleCompaction;
import io.leavesfly.jimi.core.engine.context.ContextManager;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.knowledge.memory.MemoryRecorder;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.engine.AgentExecutor;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.WireAware;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import io.leavesfly.jimi.wire.message.SubagentCompleted;
import io.leavesfly.jimi.wire.message.SubagentStarting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubAgentTool 工具 - 子 Agent 任务委托
 * <p>
 * 这是 Jimi 的核心特性之一，允许将复杂任务委托给专门的子 Agent 处理。
 * <p>
 * 核心优势：
 * 1. 上下文隔离：子 Agent 拥有独立的上下文，不会污染主 Agent
 * 2. 并行多任务：可以同时启动多个子 Agent 处理独立的子任务
 * 3. 专业化分工：不同的子 Agent 可以专注于不同领域
 * <p>
 * 使用场景：
 * - 修复编译错误（避免详细的调试过程污染主上下文）
 * - 搜索特定技术信息（只返回相关结果）
 * - 分析大型代码库（多个子 Agent 并行探索）
 * - 独立模块的开发/重构/测试
 *
 * @author 山泽
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SubAgentTool extends AbstractTool<SubAgentTool.Params> implements WireAware {

    /**
     * 响应过短时的继续提示词（可配置）
     */
    private String continuePrompt = """
            你之前的回答过于简短。请提供更全面的总结，包括：
                        
            1. 具体的技术细节和实现方式
            2. 相关的完整代码示例
            3. 详细的发现和分析结果
            4. 所有调用者需要注意的重要信息
            """.strip();

    /**
     * 最小响应长度（字符数，可配置）
     */
    private int minResponseLength = 10;

    private JimiRuntime jimiRuntime;
    private Session session;
    private AgentSpec agentSpec;
    private String taskDescription;

    /**
     * 主 Agent 的 Wire 消息总线，统一通过 {@link #setWire(Wire)} 注入
     */
    private Wire parentWire;

    private final ObjectMapper objectMapper;
    private final AgentRegistry agentRegistry;
    private final ToolRegistryFactory toolRegistryFactory;

    // AgentExecutor 依赖组件
    private final MemoryRecorder memoryRecorder;
    private final ContextManager contextManager;

    private final Map<String, Agent> subagents;
    private final Map<String, AgentSpec> subagentAgentSpecs;
    private Map<String, SubagentSpec> subagentSpecs;

    /**
     * 缓存的懒加载 Mono，使用 Mono.cache() 确保只执行一次且线程安全
     */
    private volatile Mono<Void> cachedLoadMono;

    /**
     * SubAgentTool 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        /**
         * 任务描述
         */
        @JsonProperty("description")
        @JsonPropertyDescription("任务的简短描述，用于说明该子任务的目的")
        private String description;

        /**
         * 子 Agent 名称
         */
        @JsonProperty("subagent_name")
        @JsonPropertyDescription("要使用的子 Agent 名称，必须是工具描述中列出的可用子代理之一")
        private String subagentName;

        /**
         * 任务提示词（需要包含完整的背景信息）
         */
        @JsonProperty("prompt")
        @JsonPropertyDescription("发送给子 Agent 的完整任务提示词，必须包含足够的上下文信息以便子 Agent 能够独立完成任务")
        private String prompt;
    }

    @Autowired
    public SubAgentTool(ObjectMapper objectMapper, AgentRegistry agentRegistry, ToolRegistryFactory toolRegistryFactory,
                        MemoryRecorder memoryRecorder,
                        ContextManager contextManager) {
        super("SubAgentTool", "SubAgentTool tool (description will be set when initialized)", Params.class);

        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
        this.toolRegistryFactory = toolRegistryFactory;
        this.memoryRecorder = memoryRecorder;
        this.contextManager = contextManager;
        this.subagents = new HashMap<>();
        this.subagentAgentSpecs = new HashMap<>();
    }

    /**
     * 可选：覆盖最小响应长度
     */
    public void setMinResponseLength(int minResponseLength) {
        if (minResponseLength > 0) {
            this.minResponseLength = minResponseLength;
        }
    }

    /**
     * 可选：覆盖继续提示词
     */
    public void setContinuePrompt(String continuePrompt) {
        if (continuePrompt != null && !continuePrompt.isBlank()) {
            this.continuePrompt = continuePrompt.strip();
        }
    }

    /**
     * 设置运行时参数并初始化工具
     * 使用懒加载模式，不在 Setter 中执行 I/O 操作
     */
    public void setRuntimeParams(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        this.agentSpec = agentSpec;
        this.jimiRuntime = jimiRuntime;
        this.session = jimiRuntime.getSession();
        this.subagentSpecs = agentSpec.getSubagents();

        // 更新工具描述
        this.taskDescription = loadDescription(agentSpec);
    }

    /**
     * 设置 Wire（实现 WireAware 接口）
     * 这是 parentWire 的唯一赋值入口，由 JimiEngine 构造时自动调用
     *
     * @param wire 主 Agent 的 Wire 消息总线
     */
    @Override
    public void setWire(Wire wire) {
        this.parentWire = wire;
    }

    @Override
    public String getDescription() {
        // 如果已初始化运行时参数，返回动态生成的描述
        return taskDescription != null ? taskDescription : super.getDescription();
    }

    /**
     * 加载工具描述（包含子 Agent 列表）
     */
    private static String loadDescription(AgentSpec agentSpec) {
        StringBuilder sb = new StringBuilder();
        sb.append("生成一个子代理（subagent）来执行特定任务。");
        sb.append("子代理将在全新的上下文中生成，不包含任何您的历史记录。\n\n");

        sb.append("**上下文隔离**\n\n");
        sb.append("上下文隔离是使用子代理的主要优势之一。");
        sb.append("通过将任务委托给子代理，您可以保持主上下文的简洁，");
        sb.append("并专注于用户请求的主要目标。\n\n");

        sb.append("**可用的子代理：**\n\n");
        for (Map.Entry<String, SubagentSpec> entry : agentSpec.getSubagents().entrySet()) {
            sb.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue().getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 懒加载所有子 Agent（首次调用时执行）
     * 使用 Mono.cache() 确保只执行一次且线程安全，避免 DCL 在响应式场景下的竞态问题
     */
    private Mono<Void> ensureSubagentsLoaded() {
        if (cachedLoadMono == null) {
            synchronized (this) {
                if (cachedLoadMono == null) {
                    cachedLoadMono = loadSubagents().cache();
                }
            }
        }
        return cachedLoadMono;
    }

    /**
     * 加载所有子 Agent 及其 AgentSpec（内部方法）
     * 同时缓存 Agent 实例和对应的 AgentSpec，以便后续创建子工具注册表时使用正确的 spec
     */
    private Mono<Void> loadSubagents() {
        return Flux.fromIterable(subagentSpecs.entrySet()).flatMap(entry -> {
            String name = entry.getKey();
            SubagentSpec spec = entry.getValue();

            log.debug("Loading subagent: {}", name);

            // 同时加载 AgentSpec 和 Agent
            Mono<AgentSpec> specMono = agentRegistry.loadAgentSpec(spec.getPath());
            Mono<Agent> agentMono = agentRegistry.loadSubagent(spec, jimiRuntime);

            return Mono.zip(specMono, agentMono).doOnSuccess(tuple -> {
                AgentSpec subAgentSpec = tuple.getT1();
                Agent agent = tuple.getT2();
                subagents.put(name, agent);
                subagentAgentSpecs.put(name, subAgentSpec);
                log.info("Loaded subagent: {} -> {}", name, agent.getName());
            }).doOnError(e -> log.error("Failed to load subagent: {}", name, e))
              .onErrorResume(e -> Mono.empty())
              .then();
        }).then();
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("SubAgentTool tool called: {} -> {}", params != null ? params.getDescription() : null, params != null ? params.getSubagentName() : null);

        // 参数校验
        if (params == null) {
            return Mono.just(ToolResult.error("Invalid parameters: params is null", "Invalid parameters"));
        }
        if (params.getSubagentName() == null || params.getSubagentName().isBlank()) {
            return Mono.just(ToolResult.error("Subagent name cannot be empty", "Invalid parameters"));
        }
        if (params.getPrompt() == null || params.getPrompt().isBlank()) {
            return Mono.just(ToolResult.error("Prompt cannot be empty", "Invalid parameters"));
        }

        // 懒加载 subagents（首次调用时，响应式方式）
        String subagentName = params.getSubagentName();
        return ensureSubagentsLoaded().then(Mono.defer(() -> {
            // 检查子 Agent 是否存在
            if (!subagents.containsKey(subagentName)) {
                return Mono.just(ToolResult.error("Subagent not found: " + subagentName, "Subagent not found"));
            }

            Agent subagent = subagents.get(subagentName);
            AgentSpec subAgentSpec = subagentAgentSpecs.get(subagentName);

            return runSubagent(subagent, subAgentSpec, params.getPrompt()).onErrorResume(e -> {
                log.error("Failed to run subagent", e);
                return Mono.just(ToolResult.error("Failed to run subagent: " + e.getMessage(), "Failed to run subagent"));
            });
        }));
    }

    /**
     * 运行子 Agent
     *
     * @param agent        子 Agent 实例
     * @param subAgentSpec 子 Agent 对应的 AgentSpec（用于创建正确的工具注册表）
     * @param prompt       任务提示词
     */
    private Mono<ToolResult> runSubagent(Agent agent, AgentSpec subAgentSpec, String prompt) {
        return Mono.defer(() -> {
            try {
                // 1. 发送 Subagent 启动事件
                if (parentWire != null) {
                    parentWire.send(new SubagentStarting(agent.getName(), prompt));
                }

                // 2. 创建临时历史文件（子 Agent 每次全新上下文，保证上下文隔离）
                Path subHistoryFile = createTempHistoryFile(agent.getName());

                // 3. 子上下文（全新，不加载任何历史）
                Context subContext = new Context(subHistoryFile, objectMapper);

                // 4. 子工具注册表（使用子 Agent 自己的 AgentSpec）
                ToolRegistry subToolRegistry = createSubToolRegistry(subAgentSpec);

                // 5. 子 JimiEngine
                JimiEngine subEngine = createSubEngine(agent, subContext, subToolRegistry);

                // 6. 运行并后处理
                return subEngine.run(prompt)
                        .then(Mono.defer(() -> extractFinalResponse(subContext, subEngine, prompt)))
                        .doOnSuccess(result -> {
                            if (parentWire != null) {
                                parentWire.send(new SubagentCompleted(result.getOutput()));
                            }
                        })
                        .doFinally(signalType -> cleanupTempHistoryFile(subHistoryFile));

            } catch (Exception e) {
                log.error("Error running subagent", e);
                return Mono.just(ToolResult.error(e.getMessage(), "Failed to run subagent"));
            }
        });
    }

    /**
     * 创建子工具注册表
     * 使用子 Agent 自己的 AgentSpec，确保工具集与子 Agent 配置一致
     *
     * @param subAgentSpec 子 Agent 的 AgentSpec
     */
    private ToolRegistry createSubToolRegistry(AgentSpec subAgentSpec) {
        return toolRegistryFactory.create(
                jimiRuntime.getBuiltinArgs(), jimiRuntime.getApproval(), subAgentSpec, jimiRuntime, null);
    }

    /**
     * 创建子 JimiEngine
     */
    private JimiEngine createSubEngine(Agent agent, Context subContext, ToolRegistry subToolRegistry) {
        AgentExecutor executor = AgentExecutor.builder()
                .agent(agent)
                .runtime(jimiRuntime)
                .context(subContext)
                .wire(parentWire != null ? parentWire : new WireImpl())
                .toolRegistry(subToolRegistry)
                .compaction(new SimpleCompaction())
                .memoryRecorder(memoryRecorder)
                .contextManager(contextManager)
                .isSubagent(true)
                .build();
        return JimiEngine.create(executor);
    }

    /**
     * 提取子 Agent 的最终响应
     * <p>
     * 从历史消息中从后往前查找最后一条包含实际文本内容的 ASSISTANT 消息。
     * 跳过纯工具调用（无文本内容）的 ASSISTANT 消息，避免对其误判为"响应过短"
     * 而触发不必要的 continuePrompt 续问。
     * <p>
     * 只有当子 Agent 历史中完全没有 ASSISTANT 消息时才判定为执行失败。
     * 当子 Agent 通过工具调用完成了任务但没有文本总结时，直接返回成功。
     */
    private Mono<ToolResult> extractFinalResponse(Context subContext, JimiEngine subSoul, String originalPrompt) {
        List<Message> history = subContext.getHistory();

        // 检查上下文是否有效
        if (history.isEmpty()) {
            return Mono.just(ToolResult.error("The subagent seemed not to run properly. Maybe you have to do the task yourself.", "Failed to run subagent"));
        }

        // 从后往前查找最后一条包含文本内容的 ASSISTANT 消息
        String response = findLastAssistantTextResponse(history);

        // 没有找到任何带文本的 ASSISTANT 消息
        if (response == null) {
            // 检查是否至少有 ASSISTANT 消息（可能是纯工具调用完成了任务）
            boolean hasAssistantMessage = history.stream()
                    .anyMatch(msg -> msg.getRole() == MessageRole.ASSISTANT);
            if (hasAssistantMessage) {
                // 子 Agent 通过工具调用完成了任务，无需续问，直接返回成功
                log.debug("Subagent completed via tool calls without text summary, returning success");
                return Mono.just(ToolResult.ok("Subagent completed the task via tool calls.", "Subagent task completed"));
            }
            return Mono.just(ToolResult.error("The subagent seemed not to run properly. Maybe you have to do the task yourself.", "Failed to run subagent"));
        }

        // 如果找到了文本响应但过短，且最后一条 ASSISTANT 消息没有工具调用，才尝试续问
        // 如果有工具调用说明子 Agent 已经在执行任务，短文本是正常的中间状态
        if (response.length() < minResponseLength && !hasToolCallsInLastAssistantMessage(history)) {
            log.debug("Subagent response too brief ({} chars), requesting continuation", response.length());
            return requestContinuation(subContext, subSoul, response);
        }

        return Mono.just(ToolResult.ok(response, "Subagent task completed"));
    }

    /**
     * 从历史消息中从后往前查找最后一条包含文本内容的 ASSISTANT 消息
     * <p>
     * 使用 {@link Message#getTextContent()} 而非 {@link Message#getContentParts()}，
     * 因为 content 可能是 String 类型（而非 List&lt;ContentPart&gt;），
     * getTextContent() 能正确处理两种情况。
     *
     * @return 文本内容，如果没有找到则返回 null
     */
    private String findLastAssistantTextResponse(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.getRole() != MessageRole.ASSISTANT) {
                continue;
            }
            String text = message.getTextContent();
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 检查历史中最后一条 ASSISTANT 消息是否包含工具调用
     * 如果包含工具调用，说明子 Agent 正在通过工具执行任务，短文本是正常的
     */
    private boolean hasToolCallsInLastAssistantMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.getRole() == MessageRole.ASSISTANT) {
                return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
            }
        }
        return false;
    }

    /**
     * 请求子 Agent 继续补充响应
     *
     * @param fallbackResponse 如果续问失败时使用的回退响应
     */
    private Mono<ToolResult> requestContinuation(Context subContext, JimiEngine subSoul, String fallbackResponse) {
        return subSoul.run(continuePrompt, true).then(Mono.defer(() -> {
            // 续问后，再次从后往前查找有文本内容的 ASSISTANT 消息
            String extendedResponse = findLastAssistantTextResponse(subContext.getHistory());
            if (extendedResponse != null && !extendedResponse.isEmpty()) {
                return Mono.just(ToolResult.ok(extendedResponse, "Subagent task completed"));
            }
            // 续问也没有产生文本响应，返回回退内容
            if (!fallbackResponse.isEmpty()) {
                return Mono.just(ToolResult.ok(fallbackResponse, "Subagent task completed"));
            }
            return Mono.just(ToolResult.ok("Subagent completed the task via tool calls.", "Subagent task completed"));
        }));
    }

    /**
     * 创建临时历史文件，用于子 Agent 的上下文持久化
     * 每次调用都创建新的临时文件，确保子 Agent 拥有全新的上下文（上下文隔离）
     *
     * @param subagentName 子 Agent 名称（用于文件名前缀，便于调试）
     * @return 临时历史文件路径
     * @throws IOException 如果无法创建文件
     */
    private Path createTempHistoryFile(String subagentName) throws IOException {
        Path mainHistoryFile = session.getHistoryFile();
        Path parent = mainHistoryFile.getParent();

        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        return Files.createTempFile(parent, "sub_" + subagentName + "_", ".jsonl");
    }

    /**
     * 清理子 Agent 的临时历史文件
     */
    private void cleanupTempHistoryFile(Path tempFile) {
        try {
            if (tempFile != null && Files.exists(tempFile)) {
                Files.deleteIfExists(tempFile);
                log.debug("Cleaned up subagent temp history file: {}", tempFile);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup subagent temp history file: {}", tempFile, e);
        }
    }
}
