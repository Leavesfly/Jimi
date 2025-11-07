package io.leavesfly.jimi.tool.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentRegistry;
import io.leavesfly.jimi.agent.AgentSpec;

import io.leavesfly.jimi.agent.SubagentSpec;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.soul.JimiSoul;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.soul.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.tool.WireAware;
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
import reactor.core.Disposable;

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
public class Task extends AbstractTool<Task.Params> implements WireAware {

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
    private int minResponseLength = 200;

    private Runtime runtime;
    private Session session;
    private AgentSpec agentSpec;
    private String taskDescription;
    private Wire parentWire;  // 主 Agent 的 Wire，用于转发子Agent的审批请求
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
         * 任务描述
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
    public void setRuntimeParams(AgentSpec agentSpec, Runtime runtime) {
        setRuntimeParams(agentSpec, runtime, null);
    }
    
    /**
     * 设置 Wire（实现 WireAware 接口）
     * 用于转发子Agent的审批请求到主 Agent
     * 
     * @param wire 主 Agent 的 Wire 消息总线
     */
    @Override
    public void setWire(Wire wire) {
        this.parentWire = wire;
    }
    
    /**
     * 设置运行时参数并初始化工具（包括主 Wire）
     * 使用懒加载模式，不在 Setter 中执行 I/O 操作
     * 
     * @param agentSpec Agent 规范
     * @param runtime 运行时上下文
     * @param parentWire 主 Agent 的 Wire（可选）
     */
    public void setRuntimeParams(AgentSpec agentSpec, Runtime runtime, Wire parentWire) {
        this.agentSpec = agentSpec;
        this.runtime = runtime;
        this.session = runtime.getSession();
        this.subagentSpecs = agentSpec.getSubagents();
        this.parentWire = parentWire;

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
        sb.append("生成一个子代理（subagent）来执行特定任务。");
        sb.append("子代理将在全新的上下文中生成，不包含任何您的历史记录。\n\n");

        sb.append("**上下文隔离**\n\n");
        sb.append("上下文隔离是使用子代理的主要优势之一。");
        sb.append("通过将任务委托给子代理，您可以保持主上下文的简洁，");
        sb.append("并专注于用户请求的主要目标。\n\n");

        sb.append("**可用的子代理：**\n\n");
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
        log.info("Task tool called: {} -> {}", 
                 params != null ? params.getDescription() : null, 
                 params != null ? params.getSubagentName() : null);

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
                // 1. 子历史文件
                Path subHistoryFile = getSubagentHistoryFile();

                // 2. 子上下文
                Context subContext = createSubContext(subHistoryFile);

                // 3. 子工具注册表
                ToolRegistry subToolRegistry = createSubToolRegistry();

                // 4. 子 JimiSoul
                JimiSoul subSoul = createSubSoul(agent, subContext, subToolRegistry);

                // 5. 事件桥接（仅审批请求），返回订阅以便释放
                Disposable subscription = bridgeWireEvents(subSoul.getWire());

                // 6. 运行并后处理
                return subSoul.run(prompt)
                        .then(Mono.defer(() -> extractFinalResponse(subContext, subSoul, prompt)))
                        .doFinally(signalType -> {
                            if (subscription != null && !subscription.isDisposed()) {
                                subscription.dispose();
                            }
                        });

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
     * 创建子上下文
     */
    private Context createSubContext(Path subHistoryFile) {
        return new Context(subHistoryFile, objectMapper);
    }

    /**
     * 创建子工具注册表
     */
    private ToolRegistry createSubToolRegistry() {
        return toolRegistryFactory.createStandardRegistry(
                runtime.getBuiltinArgs(),
                runtime.getApproval()
        );
    }

    /**
     * 创建子 JimiSoul
     */
    private JimiSoul createSubSoul(Agent agent, Context subContext, ToolRegistry subToolRegistry) {
        return new JimiSoul(
                agent,
                runtime,
                subContext,
                subToolRegistry,
                objectMapper
        );
    }

    /**
     * 桥接子 Wire 到父 Wire，仅转发审批请求
     * 返回订阅对象以便在运行结束时释放
     */
    private Disposable bridgeWireEvents(Wire subWire) {
        if (parentWire != null) {
            return subWire.asFlux().subscribe(msg -> {
                if (msg instanceof ApprovalRequest) {
                    log.debug("Forwarding approval request from subagent to parent wire");
                    parentWire.send(msg);
                }
            });
        } else {
            log.debug("Parent wire not available, subagent approval requests will not be forwarded");
            return null;
        }
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
        if (response.length() < minResponseLength) {
            log.debug("Subagent response too brief ({}), requesting continuation", response.length());

            return subSoul.run(continuePrompt)
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
