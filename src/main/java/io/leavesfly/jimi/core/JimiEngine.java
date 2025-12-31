package io.leavesfly.jimi.core;

import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.compaction.Compaction;

import io.leavesfly.jimi.core.engine.EngineConstants;

import io.leavesfly.jimi.core.engine.context.Context;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.exception.LLMNotSetException;

import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolRegistry;

import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireAware;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JimiEngine - Engine 的核心实现
 * <p>
 * 职责：
 * - 作为 Engine 接口的实现
 * - 协调各组件（AgentExecutor、Context等）
 * - 提供统一的对外API
 * - 管理组件生命周期
 * <p>
 * 设计原则：
 * - Builder 模式：简化构造过程
 * - 委托执行：将主循环逻辑委托给 AgentExecutor
 * - 轻量协调：仅负责组件装配和协调
 */
@Slf4j
public class JimiEngine implements Engine {

    // ==================== 核心依赖（必需） ====================
    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final ToolRegistry toolRegistry;
    private final Wire wire;
    private final Compaction compaction;

    // ==================== 可选配置 ====================
    private final boolean isSubagent;


    // ==================== 内部组件 ====================
    private final AgentExecutor executor;

    /**
     * 私有构造函数，通过 Builder 创建实例
     */
    private JimiEngine(Builder builder) {
        // 必需参数
        this.agent = Objects.requireNonNull(builder.agent, "agent is required");
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime is required");
        this.context = Objects.requireNonNull(builder.context, "context is required");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry is required");

        this.wire = Objects.requireNonNull(builder.wire, "wire is required");
        this.compaction = Objects.requireNonNull(builder.compaction, "compaction is required");

        // 可选配置
        this.isSubagent = builder.isSubagent;


        // 创建执行器（使用 Builder 模式）
        this.executor = AgentExecutor.builder().agent(agent).runtime(runtime).context(context).wire(wire)
                .toolRegistry(toolRegistry).compaction(compaction).isSubagent(isSubagent).build();

        // 设置 Approval 事件转发（仅主 Agent 订阅）
        if (!isSubagent) {
            runtime.getApproval().asFlux().subscribe(wire::send);
        }

        // 为所有实现 WireAware 接口的工具注入 Wire
        toolRegistry.getAllTools().forEach(tool -> {
            if (tool instanceof WireAware) {
                ((WireAware) tool).setWire(wire);
            }
        });
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * JimiEngine Builder
     * <p>
     * 使用示例：
     * <pre>
     * JimiEngine engine = JimiEngine.builder()
     *     .agent(agent)
     *     .runtime(runtime)
     *     .context(context)
     *     .toolRegistry(toolRegistry)
     *     .objectMapper(objectMapper)
     *     .wire(wire)              // 必需
     *     .compaction(compaction)  // 必需
     *     // 可选配置
     *     .skillComponents(SkillComponents.of(matcher, provider))
     *     .memoryComponents(MemoryComponents.of(config, injector, extractor))
     *     .build();
     * </pre>
     */
    public static class Builder {
        // 必需参数
        private Agent agent;
        private Runtime runtime;
        private Context context;
        private ToolRegistry toolRegistry;
        private Wire wire;
        private Compaction compaction;

        // 可选配置
        private boolean isSubagent = false;

        private Builder() {
        }

        // ==================== 必需参数 ====================

        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        public Builder runtime(Runtime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }


        public Builder wire(Wire wire) {
            this.wire = wire;
            return this;
        }

        public Builder compaction(Compaction compaction) {
            this.compaction = compaction;
            return this;
        }

        // ==================== 可选配置 ====================

        public Builder isSubagent(boolean isSubagent) {
            this.isSubagent = isSubagent;
            return this;
        }


        /**
         * 构建 JimiEngine 实例
         */
        public JimiEngine build() {
            return new JimiEngine(this);
        }
    }


    // ==================== Getter 方法 ====================

    public Agent getAgent() {
        return agent;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Context getContext() {
        return context;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public Wire getWire() {
        return wire;
    }

    // ==================== Engine 接口实现 ====================

    @Override
    public String getName() {
        return agent.getName();
    }

    @Override
    public String getModel() {
        LLM llm = runtime.getLlm();
        return llm != null ? llm.getModelName() : "unknown";
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("messageCount", context.getHistory().size());
        status.put("tokenCount", context.getTokenCount());
        status.put("checkpointCount", context.getnCheckpoints());
        LLM llm = runtime.getLlm();
        if (llm != null) {
            int maxContextSize = llm.getMaxContextSize();
            int used = context.getTokenCount();
            int available = Math.max(0, maxContextSize - EngineConstants.RESERVED_TOKENS - used);
            double usagePercent = maxContextSize > 0 ? (used * 100.0 / maxContextSize) : 0.0;
            status.put("maxContextSize", maxContextSize);
            status.put("reservedTokens", EngineConstants.RESERVED_TOKENS);
            status.put("availableTokens", available);
            status.put("contextUsagePercent", Math.round(usagePercent * 100.0) / 100.0);
        }
        return status;
    }

    @Override
    public Mono<Void> run(String userInput) {
        return run(List.of(TextPart.of(userInput)));
    }

    @Override
    public Mono<Void> run(List<ContentPart> userInput) {
        return Mono.defer(() -> {
            if (runtime.getLlm() == null) {
                return Mono.error(new LLMNotSetException());
            }
            return executor.execute(userInput);
        });
    }
}
