package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Task 工具 - 子 Agent 任务委托
 *
 * 核心优势：
 * 1. 上下文隔离：子 Agent 拥有独立的上下文，不会污染主 Agent
 * 2. 专业化分工：不同的子 Agent 可以专注于不同领域
 *
 * 使用场景：
 * - 修复编译错误（避免详细的调试过程污染主上下文）
 * - 搜索特定技术信息（只返回相关结果）
 * - 独立模块的开发/重构/测试
 */
@Slf4j
public class TaskTool extends AbstractTool<TaskTool.Params> {

    /**
     * 最小响应长度（字符数）
     */
    private static final int MIN_RESPONSE_LENGTH = 10;

    /**
     * 响应过短时的继续提示词
     */
    private static final String CONTINUE_PROMPT = """
            你之前的回答过于简短。请提供更全面的总结，包括：
            
            1. 具体的技术细节和实现方式
            2. 相关的完整代码示例
            3. 详细的发现和分析结果
            4. 所有调用者需要注意的重要信息
            """.strip();

    private final Runtime runtime;
    private final Wire wire;
    private final List<ToolProvider> toolProviders;
    private final Function<SubEngineConfig, Mono<ExecutionResult>> engineFactory;

    /**
     * Task 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonProperty("description")
        @JsonPropertyDescription("任务的简短描述，用于说明该子任务的目的")
        private String description;

        @JsonProperty("prompt")
        @JsonPropertyDescription("发送给子 Agent 的完整任务提示词，必须包含足够的上下文信息以便子 Agent 能够独立完成任务")
        private String prompt;
    }

    /**
     * 子引擎配置（传递给 engineFactory）
     */
    @Data
    @Builder
    public static class SubEngineConfig {
        private Agent agent;
        private Runtime runtime;
        private Context context;
        private ToolRegistry toolRegistry;
        private Wire wire;
        private String input;
    }

    /**
     * 构造 TaskTool
     *
     * @param runtime       运行时环境
     * @param wire          消息总线
     * @param toolProviders 工具提供者列表
     * @param engineFactory 子引擎工厂函数（由调用方注入，用于创建并执行子 Engine）
     * @param subagents     可用的子 Agent 列表及描述
     */
    public TaskTool(Runtime runtime, Wire wire,
                    List<ToolProvider> toolProviders,
                    Function<SubEngineConfig, Mono<ExecutionResult>> engineFactory,
                    Map<String, String> subagents) {
        super("Task", buildDescription(subagents), Params.class);
        this.runtime = runtime;
        this.wire = wire;
        this.toolProviders = toolProviders;
        this.engineFactory = engineFactory;
    }

    /**
     * 简化构造（无子 Agent 描述时使用通用描述）
     */
    public TaskTool(Runtime runtime, Wire wire,
                    List<ToolProvider> toolProviders,
                    Function<SubEngineConfig, Mono<ExecutionResult>> engineFactory) {
        super("Task",
              "生成一个子代理（subagent）来执行特定任务。子代理将在全新的上下文中生成，不包含任何您的历史记录。" +
              "通过将任务委托给子代理，您可以保持主上下文的简洁。",
              Params.class);
        this.runtime = runtime;
        this.wire = wire;
        this.toolProviders = toolProviders;
        this.engineFactory = engineFactory;
    }

    private static String buildDescription(Map<String, String> subagents) {
        StringBuilder sb = new StringBuilder();
        sb.append("生成一个子代理（subagent）来执行特定任务。");
        sb.append("子代理将在全新的上下文中生成，不包含任何您的历史记录。\n\n");
        sb.append("**上下文隔离**\n\n");
        sb.append("上下文隔离是使用子代理的主要优势之一。");
        sb.append("通过将任务委托给子代理，您可以保持主上下文的简洁，");
        sb.append("并专注于用户请求的主要目标。\n\n");

        if (subagents != null && !subagents.isEmpty()) {
            sb.append("**可用的子代理：**\n\n");
            for (Map.Entry<String, String> entry : subagents.entrySet()) {
                sb.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        log.info("Task tool called: {}", params != null ? params.getDescription() : null);

        if (params == null) {
            return Mono.just(ToolResult.error("Invalid parameters: params is null"));
        }
        if (params.getPrompt() == null || params.getPrompt().isBlank()) {
            return Mono.just(ToolResult.error("Prompt cannot be empty"));
        }

        if (engineFactory == null) {
            return Mono.just(ToolResult.error("Task tool not properly configured: no engine factory"));
        }

        // 创建子 Agent（使用主 Agent 的系统提示词和模型）
        Agent subAgent = Agent.builder()
                .name("task-subagent")
                .systemPrompt(runtime.getLlm() != null ? "You are a helpful assistant that completes tasks." : null)
                .build();

        SubEngineConfig config = SubEngineConfig.builder()
                .agent(subAgent)
                .runtime(runtime)
                .wire(wire)
                .input(params.getPrompt())
                .build();

        return engineFactory.apply(config)
                .map(result -> {
                    if (result.isSuccess()) {
                        String response = result.getResponse();
                        if (response != null && !response.isBlank()) {
                            return ToolResult.success(response);
                        }
                        return ToolResult.success("Subagent task completed but produced no output.");
                    } else {
                        return ToolResult.error("Subagent task failed: " + result.getError());
                    }
                })
                .onErrorResume(e -> {
                    log.error("Failed to run subagent task", e);
                    return Mono.just(ToolResult.error("Failed to run subagent: " + e.getMessage()));
                });
    }
}
