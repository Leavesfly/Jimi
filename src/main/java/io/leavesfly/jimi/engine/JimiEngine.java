package io.leavesfly.jimi.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.exception.LLMNotSetException;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.engine.compaction.Compaction;
import io.leavesfly.jimi.engine.compaction.SimpleCompaction;

import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.retrieval.RetrievalPipeline;
import io.leavesfly.jimi.skill.SkillMatcher;
import io.leavesfly.jimi.skill.SkillProvider;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.wire.WireAware;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JimiEngine - Engine 的核心实现
 * 
 * 职责（重构后）：
 * - 作为 Engine 接口的实现
 * - 协调各组件（AgentExecutor、Context等）
 * - 提供统一的对外API
 * - 管理组件生命周期
 * 
 * 设计改进（v2.0）：
 * - 委托执行：将主循环逻辑委托给 AgentExecutor
 * - 轻量协调：仅负责组件装配和协调
 * - 单一职责：专注于提供 Engine 接口实现
 */
@Slf4j
public class JimiEngine implements Engine {

    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Compaction compaction;
    private final AgentExecutor executor;
    private final boolean isSubagent;  // 标记是否为子Agent
    private final SkillMatcher skillMatcher;  // Skill 匹配器（可选）
    private final SkillProvider skillProvider; // Skill 提供者（可选）
    private final RetrievalPipeline retrievalPipeline; // 检索增强管线（可选）

    /**
     * 简化构造函数（保留兼容性）
     * @deprecated 推荐使用完整构造函数，通过 JimiFactory 创建并注入 Compaction
     */
    @Deprecated
    public JimiEngine(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this(agent, runtime, context, toolRegistry, objectMapper, 
            new WireImpl(), new SimpleCompaction(), false, null, null);
    }
    
    /**
     * 完整构造函数（支持依赖注入）
     * 允许自定义 Wire 和 Compaction 实现
     */
    public JimiEngine(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Wire wire,
            Compaction compaction
    ) {
        this(agent, runtime, context, toolRegistry, objectMapper, wire, compaction, false, null, null);
    }
    
    /**
     * 最完整构造函数（支持子Agent标记和Skill组件）
     * 用于创建子Agent的JimiSoul实例
     */
    public JimiEngine(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Wire wire,
            Compaction compaction,
            boolean isSubagent,
            SkillMatcher skillMatcher,
            SkillProvider skillProvider
    ) {
        this(agent, runtime, context, toolRegistry, objectMapper, wire, compaction, 
                isSubagent, skillMatcher, skillProvider, null);
    }

    /**
     * 最完整构造函数（支持检索增强）
     */
    public JimiEngine(
            Agent agent,
            Runtime runtime,
            Context context,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Wire wire,
            Compaction compaction,
            boolean isSubagent,
            SkillMatcher skillMatcher,
            SkillProvider skillProvider,
            RetrievalPipeline retrievalPipeline
    ) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.wire = wire;
        this.compaction = compaction;
        this.isSubagent = isSubagent;
        this.skillMatcher = skillMatcher;
        this.skillProvider = skillProvider;
        this.retrievalPipeline = retrievalPipeline;
        
        // 创建执行器（传入isSubagent标记、Skill组件和检索管线）
        this.executor = new AgentExecutor(agent, runtime, context, wire, toolRegistry, compaction, 
                isSubagent, skillMatcher, skillProvider, retrievalPipeline);
        
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
