package io.leavesfly.jimi.tool.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentRegistry;
import io.leavesfly.jimi.agent.AgentSpec;

import io.leavesfly.jimi.agent.SubagentSpec;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.soul.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.soul.approval.ApprovalRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Task 工具 - 子 Agent 任务委托
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
public class Task extends AbstractTool<Task.Params> {

    /**
     * 最大重试次数（用于响应过短时的继续提示）
     */
    private static final int MAX_CONTINUE_ATTEMPTS = 1;

    /**
     * 响应过短时的继续提示词
     */
    private static final String CONTINUE_PROMPT = """
            Your previous response was too brief. Please provide a more comprehensive summary that includes:
                        
            1. Specific technical details and implementations
            2. Complete code examples if relevant
            3. Detailed findings and analysis
            4. All important information that should be aware of by the caller
            """.strip();

    /**
     * 最小响应长度（字符数）
     */
    private static final int MIN_RESPONSE_LENGTH = 200;

    private Runtime runtime;
    private Session session;
    private AgentSpec agentSpec;
    private String taskDescription;
    private final ObjectMapper objectMapper;
    private final AgentRegistry agentRegistry;
    private final ToolRegistryFactory toolRegistryFactory;
    private final Map<String, Agent> subagents;
    private Map<String, SubagentSpec> subagentSpecs;

    /**
     * 标记 subagent 是否已加载（懒加载模式）
     */
    private volatile boolean subagentsLoaded = false;

    /**
     * Task 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        /**
         * 任务描述（3-5 个单词的简短描述）
         */
        @JsonProperty("description")
        private String description;

        /**
         * 子 Agent 名称
         */
        @JsonProperty("subagent_name")
        private String subagentName;

        /**
         * 任务提示词（需要包含完整的背景信息）
         */
        @JsonProperty("prompt")
        private String prompt;
    }

    @Autowired
    public Task(ObjectMapper objectMapper, AgentRegistry agentRegistry, ToolRegistryFactory toolRegistryFactory) {
        super("Task", "Task tool (description will be set when initialized)", Params.class);

        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
        this.toolRegistryFactory = toolRegistryFactory;
        this.subagents = new HashMap<>();
    }

    /**
     * 设置运行时参数并初始化工具
     * 使用懒加载模式，不在 Setter 中执行 I/O 操作
     */
    public void setRuntimeParams(AgentSpec agentSpec, Runtime runtime) {
        this.agentSpec = agentSpec;
        this.runtime = runtime;
        this.session = runtime.getSession();
        this.subagentSpecs = agentSpec.getSubagents();

        // 更新工具描述
        this.taskDescription = loadDescription(agentSpec);

        // 不在这里加载 subagents，改为懒加载
        // loadSubagents();
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
        sb.append("Spawn a subagent to perform a specific task. ");
        sb.append("Subagent will be spawned with a fresh context without any history of yours.\n\n");

        sb.append("**Context Isolation**\n\n");
        sb.append("Context isolation is one of the key benefits of using subagents. ");
        sb.append("By delegating tasks to subagents, you can keep your main context clean ");
        sb.append("and focused on the main goal requested by the user.\n\n");

        sb.append("**Available Subagents:**\n\n");
        for (Map.Entry<String, SubagentSpec> entry : agentSpec.getSubagents().entrySet()) {
            sb.append("- `").append(entry.getKey()).append("`: ")
                    .append(entry.getValue().getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 懒加载所有子 Agent（首次调用时执行）
     * 使用双重检查锁定确保线程安全
     */
    private void ensureSubagentsLoaded() {
        if (!subagentsLoaded) {
            synchronized (this) {
                if (!subagentsLoaded) {
                    loadSubagents();
                    subagentsLoaded = true;
                }
            }
        }
    }

    /**
     * 加载所有子 Agent（内部方法）
     */
    private void loadSubagents() {
        for (Map.Entry<String, SubagentSpec> entry : subagentSpecs.entrySet()) {
            String name = entry.getKey();
            SubagentSpec spec = entry.getValue();

            try {
                log.debug("Loading subagent: {}", name);

                // 使用注入的 AgentRegistry 加载子 Agent
                Agent agent = agentRegistry.loadSubagent(spec, runtime).block();

                if (agent != null) {
                    subagents.put(name, agent);
                    log.info("Loaded subagent: {} -> {}", name, agent.getName());
                }
            } catch (Exception e) {
                log.error("Failed to load subagent: {}", name, e);
            }
        }
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("Task tool called: {} -> {}", params.getDescription(), params.getSubagentName());

        // 懒加载 subagents（首次调用时）
        ensureSubagentsLoaded();

        // 检查子 Agent 是否存在
        if (!subagents.containsKey(params.getSubagentName())) {
            return Mono.just(ToolResult.error(
                    "Subagent not found: " + params.getSubagentName(),
                    "Subagent not found"
            ));
        }

        Agent subagent = subagents.get(params.getSubagentName());

        return runSubagent(subagent, params.getPrompt())
                .onErrorResume(e -> {
                    log.error("Failed to run subagent", e);
                    return Mono.just(ToolResult.error(
                            "Failed to run subagent: " + e.getMessage(),
                            "Failed to run subagent"
                    ));
                });
    }

    /**
     * 运行子 Agent
     */
    private Mono<ToolResult> runSubagent(Agent agent, String prompt) {
        return Mono.defer(() -> {
            try {
                // 1. 创建子 Agent 历史文件
                Path subHistoryFile = getSubagentHistoryFile();

                // 2. 创建独立的上下文
                Context subContext = new Context(subHistoryFile, objectMapper);

                // 3. 创建子 Agent 的工具注册表（使用 ToolRegistryFactory）
                ToolRegistry subToolRegistry = toolRegistryFactory.createStandardRegistry(
                        runtime.getBuiltinArgs(),
                        runtime.getApproval()
                );

                // 4. 创建子 JimiSoul
                JimiSoul subSoul = new JimiSoul(
                        agent,
                        runtime,
                        subContext,
                        subToolRegistry,
                        objectMapper
                );

                // 5. 订阅子 Agent 的 Wire 消息，转发审批请求到主 Agent
                Wire subWire = subSoul.getWire();
                subWire.asFlux().subscribe(msg -> {
                    // 只转发审批请求到主 Wire
                    if (msg instanceof ApprovalRequest) {
                        // TODO: 获取主 Wire 并转发
                        log.debug("Approval request from subagent: {}", msg);
                    }
                });

                // 6. 运行子 Agent
                return subSoul.run(prompt)
                        .then(Mono.defer(() -> {
                            // 7. 提取子 Agent 的最终响应
                            return extractFinalResponse(subContext, subSoul, prompt);
                        }));

            } catch (Exception e) {
                log.error("Error running subagent", e);
                return Mono.just(ToolResult.error(
                        e.getMessage(),
                        "Failed to run subagent"
                ));
            }
        });
    }

    /**
     * 提取子 Agent 的最终响应
     */
    private Mono<ToolResult> extractFinalResponse(Context subContext, JimiSoul subSoul, String originalPrompt) {
        List<Message> history = subContext.getHistory();

        // 检查上下文是否有效
        if (history.isEmpty()) {
            return Mono.just(ToolResult.error(
                    "The subagent seemed not to run properly. Maybe you have to do the task yourself.",
                    "Failed to run subagent"
            ));
        }

        // 获取最后一条消息
        Message lastMessage = history.get(history.size() - 1);

        // 检查是否是助手响应
        if (lastMessage.getRole() != MessageRole.ASSISTANT) {
            return Mono.just(ToolResult.error(
                    "The subagent seemed not to run properly. Maybe you have to do the task yourself.",
                    "Failed to run subagent"
            ));
        }

        // 提取文本内容
        String response = extractText(lastMessage);

        // 如果响应过短，尝试继续
        if (response.length() < MIN_RESPONSE_LENGTH) {
            log.debug("Subagent response too brief ({}), requesting continuation", response.length());

            return subSoul.run(CONTINUE_PROMPT)
                    .then(Mono.defer(() -> {
                        List<Message> updatedHistory = subContext.getHistory();
                        if (!updatedHistory.isEmpty()) {
                            Message continueMsg = updatedHistory.get(updatedHistory.size() - 1);
                            if (continueMsg.getRole() == MessageRole.ASSISTANT) {
                                String extendedResponse = extractText(continueMsg);
                                return Mono.just(ToolResult.ok(extendedResponse, "Subagent task completed"));
                            }
                        }
                        // 如果继续失败，返回原始响应
                        return Mono.just(ToolResult.ok(response, "Subagent task completed"));
                    }));
        }

        return Mono.just(ToolResult.ok(response, "Subagent task completed"));
    }

    /**
     * 从消息中提取文本内容
     */
    private String extractText(Message message) {
        return message.getContentParts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .filter(text -> text != null && !text.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 生成子 Agent 历史文件路径
     */
    private Path getSubagentHistoryFile() throws IOException {
        Path mainHistoryFile = session.getHistoryFile();
        String baseName = mainHistoryFile.getFileName().toString();
        String nameWithoutExt = baseName.substring(0, baseName.lastIndexOf('.'));
        String ext = baseName.substring(baseName.lastIndexOf('.'));

        Path parent = mainHistoryFile.getParent();

        // 查找下一个可用的文件名
        for (int i = 1; i < 1000; i++) {
            Path candidate = parent.resolve(nameWithoutExt + "_sub_" + i + ext);
            if (!Files.exists(candidate)) {
                // 创建文件
                Files.createFile(candidate);
                log.debug("Created subagent history file: {}", candidate);
                return candidate;
            }
        }

        throw new IOException("Unable to create subagent history file");
    }
}
