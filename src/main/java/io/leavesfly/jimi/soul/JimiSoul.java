package io.leavesfly.jimi.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.exception.LLMNotSetException;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.soul.compaction.Compaction;
import io.leavesfly.jimi.soul.compaction.SimpleCompaction;

import io.leavesfly.jimi.soul.context.Context;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.WireAware;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JimiSoul - Soul 的核心实现
 * 
 * 职责（重构后）：
 * - 作为 Soul 接口的实现
 * - 协调各组件（AgentExecutor、Context等）
 * - 提供统一的对外API
 * - 管理组件生命周期
 * 
 * 设计改进（v2.0）：
 * - 委托执行：将主循环逻辑委托给 AgentExecutor
 * - 轻量协调：仅负责组件装配和协调
 * - 单一职责：专注于提供 Soul 接口实现
 */
@Slf4j
public class JimiSoul implements Soul {

    private static final int RESERVED_TOKENS = 50_000;

    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Compaction compaction;
    private final AgentExecutor executor;

    /**
     * 简化构造函数（保留兼容性）
     * @deprecated 推荐使用完整构造函数，通过 JimiFactory 创建并注入 Compaction
     */
    @Deprecated
    public JimiSoul(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this(agent, runtime, context, toolRegistry, objectMapper, 
            new WireImpl(), new SimpleCompaction());
    }
    
    /**
     * 完整构造函数（支持依赖注入）
     * 允许自定义 Wire 和 Compaction 实现
     */
    public JimiSoul(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Wire wire,
            Compaction compaction
    ) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.wire = wire;
        this.compaction = compaction;
        
        // 创建执行器
        this.executor = new AgentExecutor(agent, runtime, context, wire, toolRegistry, compaction);
        
        // 设置 Approval 事件转发
        runtime.getApproval().asFlux().subscribe(wire::send);
        
        // 为所有实现 WireAware 接口的工具注入 Wire
        toolRegistry.getAllTools().forEach(tool -> {
            if (tool instanceof WireAware) {
                ((WireAware) tool).setWire(wire);
            }
        });
    }

    // Getter methods for Shell UI and other components
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
            int available = Math.max(0, maxContextSize - RESERVED_TOKENS - used);
            double usagePercent = maxContextSize > 0 ? (used * 100.0 / maxContextSize) : 0.0;
            status.put("maxContextSize", maxContextSize);
            status.put("reservedTokens", RESERVED_TOKENS);
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
            // 检查 LLM 是否设置
            if (runtime.getLlm() == null) {
                return Mono.error(new LLMNotSetException());
            }

            // 委托给 AgentExecutor 执行
            return executor.execute(userInput);
        });
    }

    /**
     * 获取 Wire 消息总线（供 UI 使用）
     */
    public Wire getWire() {
        return wire;
    }
}
